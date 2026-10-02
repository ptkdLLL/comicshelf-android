#include "core/auto_sync.h"

#include "core/library.h"
#include "util/logger.h"
#include "vfs/smb_watch.h"
#include "vfs/vfs.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <map>

namespace cs {

AutoSync::AutoSync(LibraryManager& lib) : lib_(lib) {}

AutoSync::~AutoSync() { stop(); }

int AutoSync::watchers() const {
    std::lock_guard<std::mutex> g(m_);
    return (int)watchers_.size();
}

int AutoSync::alive_count() const {
    std::lock_guard<std::mutex> g(m_);
    int n = 0;
    for (const auto& kv : watchers_)
        if (kv.second->alive()) ++n;
    return n;
}

void AutoSync::stop() {
    {
        std::lock_guard<std::mutex> g(m_);
        stop_ = true;
        for (auto& kv : watchers_) kv.second->stop();
        watchers_.clear();
    }
    cv_.notify_all();
    if (th_.joinable()) th_.join();
    stop_ = false;
}

void AutoSync::start() {
    stop();
    const auto libs = lib_.libraries();

    // 解析好每个 SMB 库（含凭据），再逐个建监视。
    struct Want {
        int64_t id = 0;
        std::string root;
        vfs::SmbConfig cfg;
        std::string sub; // 库根在共享内的相对路径（'/' 开头）
    };
    std::vector<Want> wants;
    for (const auto& l : libs) {
        if (!vfs::is_smb_path(l.root)) continue;
        Want w;
        w.id = l.id;
        w.root = l.root;
        std::string err;
        if (!vfs::smb_path_config(l.root, &w.cfg, &w.sub, &err)) {
            log_warn("autosync: " + l.root + ": " + err);
            continue;
        }
        wants.push_back(std::move(w));
    }
    if (wants.empty()) return;

    th_ = std::thread([this] { worker_loop(); });

    for (const auto& w : wants) {
        auto cb = [this, id = w.id, sub = w.sub](const std::vector<vfs::SmbChange>& evs) {
            std::vector<std::pair<std::string, int>> flat;
            flat.reserve(evs.size());
            for (const auto& e : evs) flat.emplace_back(e.rel, e.action);
            on_changes(id, sub, flat);
        };
        std::string err;
        auto watcher = vfs::make_smb_watcher(w.cfg, w.sub, std::move(cb), &err);
        if (!watcher) {
            log_warn("autosync: 无法监视 " + w.root + ": " + err +
                     "（该服务器可能不支持 CHANGE_NOTIFY，回退为手动/定时全量扫描）");
            continue;
        }
        {
            std::lock_guard<std::mutex> g(m_);
            watchers_[w.cfg.host + "|" + w.cfg.share + "|" + std::to_string(w.id)] = watcher;
        }
        log_info("autosync: 已监视 " + w.root);
    }
    if (watchers() == 0) {
        log_warn("autosync: 没有任何库进入实时同步");
        stop();
    }
}

void AutoSync::on_changes(int64_t lib, const std::string& base,
                          const std::vector<std::pair<std::string, int>>& evs) {
    for (const auto& [share_rel, action] : evs) {
        // 只关心库根之下的变更
        std::string rel = share_rel;
        if (base != "/") {
            if (rel.size() <= base.size() || rel.compare(0, base.size(), base) != 0) {
                if (rel == base) {
                    queue_refresh(lib, "");   // 库根自身变了：整库快刷
                    continue;
                }
                continue;                     // 库外的兄弟目录
            }
            rel = rel.substr(base.size());
        }
        while (!rel.empty() && rel[0] == '/') rel.erase(0, 1);

        // 事件路径是“被改动的项”，刷新它的父目录（文件）或它本身（目录）。
        // 无法可靠区分时统一刷新父目录，代价仍然只是子树级。
        std::string dir = rel;
        auto slash = dir.rfind('/');
        dir = (slash == std::string::npos) ? std::string() : dir.substr(0, slash);

        if (action == 2 /*removed*/) {
            // 可能是文件也可能是目录，两端都处理：父目录刷新 + 该路径子树清理
            queue_refresh(lib, dir);
            queue_remove(lib, rel);
        } else if (action == 4 /*renamed old*/) {
            queue_remove(lib, rel);
            queue_refresh(lib, dir);
        } else {
            queue_refresh(lib, dir);
        }
    }
    cv_.notify_all();
}

void AutoSync::queue_refresh(int64_t lib, const std::string& rel) {
    std::lock_guard<std::mutex> g(m_);
    dirty_[lib].insert(rel);
}

void AutoSync::queue_remove(int64_t lib, const std::string& rel) {
    std::lock_guard<std::mutex> g(m_);
    gone_[lib].insert(rel);
}

// 待办队列的唯一执行者：等扫描器空闲 → 逐个子树刷新。
// 刷新期间来的新事件自然进入下一轮（去重集合）。
void AutoSync::worker_loop() {
    for (;;) {
        std::unordered_map<int64_t, std::unordered_set<std::string>> dirty, gone;
        {
            std::unique_lock<std::mutex> lk(m_);
            cv_.wait(lk, [this] {
                return stop_ || !dirty_.empty() || !gone_.empty();
            });
            if (stop_) return;
            // 去抖：等 1.5s 让同一批变更聚齐
            cv_.wait_for(lk, std::chrono::milliseconds(1500));
            if (stop_) return;
            dirty.swap(dirty_);
            gone.swap(gone_);
        }

        // 扫描器忙就等（刷新与全量扫描不能并发：Scanner::start 会直接丢弃请求）
        int guard = 0;
        while (lib_.progress().running.load() || lib_.progress().paused.load()) {
            if (stop_ || ++guard > 3600) return; // 最多等 1 小时
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }

        for (auto& [lib, rels] : gone) {
            for (const auto& rel : rels) {
                const int64_t n = lib_.remove_subtree(lib, rel);
                if (n > 0) log_info("autosync: 目录已删除 " + rel + "（移除 " +
                                    std::to_string(n) + " 本）");
            }
        }
        for (auto& [lib, rels] : dirty) {
            // 去掉被更上层子树包含的项（refreshing "a/b" 时 "a/b/c" 是多余的）
            std::vector<std::string> list(rels.begin(), rels.end());
            std::sort(list.begin(), list.end());
            std::vector<std::string> top;
            for (const auto& r : list) {
                bool covered = false;
                for (const auto& t : top) {
                    if (r == t || (r.size() > t.size() && r.compare(0, t.size(), t) == 0 &&
                                   (t.empty() || r[t.size()] == '/'))) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) top.push_back(r);
            }
            for (const auto& rel : top) {
                log_info("autosync: 变更 → 刷新 " + (rel.empty() ? "<库根>" : rel));
                lib_.refresh_subtree(lib, rel);
                // 等这次刷新结束再进入下一个，避免请求被 Scanner 丢弃
                int g2 = 0;
                while (lib_.progress().running.load() && !stop_ && ++g2 < 3600)
                    std::this_thread::sleep_for(std::chrono::milliseconds(200));
            }
        }
    }
}

} // namespace cs
