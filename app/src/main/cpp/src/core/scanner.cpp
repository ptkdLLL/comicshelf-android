#include "core/scanner.h"

#include "util/logger.h"
#include "util/path_util.h"
#include "vfs/vfs.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cctype>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

namespace cs {
namespace {

namespace vfs = ::cs::vfs;

constexpr int kAutoWorkers = 6;      // SMB 会话池上限 10，给阅读器留余量
constexpr size_t kBatchRows = 1000;  // 单个写事务的行数上限

bool is_excluded_name(const std::string& name) {
    if (name.empty()) return false;
    // AppleDouble 元数据（"._foo.jpg"）
    if (name.size() >= 2 && name[0] == '.' && name[1] == '_') return true;
    static const char* kJunk[] = {
        "@eadir",     "#recycle",     "@recycle",    ".@__thumb",   "@tmp",
        ".trashes",   ".appledouble", ".snapshot",   "system volume information",
        "$recycle.bin", "lost+found", ".thumbnails", "recycler",
    };
    std::string n;
    n.reserve(name.size());
    for (char c : name) n.push_back((char)std::tolower((unsigned char)c));
    for (const char* j : kJunk)
        if (n == j) return true;
    return false;
}

// ---- 纯字符串路径小工具（SMB 侧路径不是本地文件系统路径）------------------

std::string norm_dir(std::string p) {
    if (p.empty()) return "/";
    if (p[0] != '/') p.insert(p.begin(), '/');
    while (p.size() > 1 && p.back() == '/') p.pop_back();
    return p;
}

std::string join_rel(const std::string& dir, const std::string& name) {
    if (dir.empty() || dir == "/") return "/" + name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

std::string parent_dir(const std::string& p) {
    if (p.size() <= 1) return "/";
    auto slash = p.rfind('/');
    if (slash == std::string::npos || slash == 0) return "/";
    return p.substr(0, slash);
}

std::string base_name(const std::string& p) {
    auto slash = p.rfind('/');
    return slash == std::string::npos ? p : p.substr(slash + 1);
}

// 库根相对路径（'' = 库根），写库的 rel_dir 用它。
std::string lib_rel_of(const std::string& base, const std::string& p) {
    if (base == "/") {
        if (p == "/") return std::string();
        return p[0] == '/' ? p.substr(1) : p;
    }
    if (p.size() <= base.size()) return std::string();
    std::string r = p.substr(base.size());
    while (!r.empty() && r[0] == '/') r.erase(0, 1);
    return r;
}

std::string lower_ext_str(const std::string& name) {
    auto dot = name.rfind('.');
    if (dot == std::string::npos || dot + 1 >= name.size()) return std::string();
    std::string e = name.substr(dot + 1);
    for (auto& c : e) c = (char)std::tolower((unsigned char)c);
    return e;
}

std::string stem_str(const std::string& name) {
    auto dot = name.rfind('.');
    if (dot == std::string::npos || dot == 0) return name;
    return name.substr(0, dot);
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// 扫描期间唯一的写库线程：worker 只往队列里投 Book，写事务在这里成批提交。
class BookWriter {
public:
    BookWriter(Database& db, ScanProgress& prog, int64_t token)
        : db_(db), prog_(prog), token_(token), th_([this] { loop(); }) {}

    ~BookWriter() { finish(); }

    void publish(Book b) {
        std::lock_guard<std::mutex> lk(m_);
        q_.push_back(std::move(b));
        cv_.notify_one();
    }

    void finish() {
        if (done_) return;
        {
            std::lock_guard<std::mutex> lk(m_);
            stop_ = true;
        }
        cv_.notify_all();
        if (th_.joinable()) th_.join();
        done_ = true;
    }

private:
    void loop() {
        std::vector<Book> batch;
        for (;;) {
            {
                std::unique_lock<std::mutex> lk(m_);
                cv_.wait(lk, [this] { return stop_ || !q_.empty(); });
                batch.swap(q_);
                if (batch.empty() && stop_) return;
            }
            if (batch.empty()) continue;
            db_.begin();
            for (size_t i = 0; i < batch.size(); ++i) {
                auto r = db_.upsert_book(batch[i], token_);
                if (r.is_new) ++prog_.added;
                else if (r.changed) ++prog_.updated;
                if ((i + 1) % kBatchRows == 0) {
                    db_.commit();
                    db_.begin();
                }
            }
            db_.commit();
            batch.clear();
        }
    }

    Database& db_;
    ScanProgress& prog_;
    int64_t token_;
    std::mutex m_;
    std::condition_variable cv_;
    std::vector<Book> q_;
    bool stop_ = false;
    bool done_ = false;
    std::thread th_;
};

} // namespace

Scanner::Scanner(Database& writer, ThreadPool& pool, ScanProgress& progress)
    : writer_(writer), pool_(pool), progress_(progress) {}

Scanner::~Scanner() {
    cancel();
    wait();
}

bool Scanner::busy() const { return progress_.running.load(); }

void Scanner::cancel() { progress_.cancel = true; }

void Scanner::pause(bool p) { progress_.paused = p; }

void Scanner::wait() {
    if (thread_.joinable()) thread_.join();
}

void Scanner::start(int64_t lib_id, std::string root_utf8, bool include_folders) {
    ScanOptions opt;
    opt.include_folders = include_folders;
    start(lib_id, std::move(root_utf8), opt);
}

void Scanner::start(int64_t lib_id, std::string root_utf8, const ScanOptions& opt) {
    if (progress_.running.load()) return;
    wait(); // reap the previous run
    progress_.reset();
    progress_.running = true;
    progress_.lib_id = lib_id;
    ++progress_.serial; // 新的扫描代号（UI 靠它发现结束）
    thread_ = std::thread(
        [this, lib_id, root = std::move(root_utf8), opt]() mutable {
            run(lib_id, std::move(root), opt);
        });
}

void Scanner::run(int64_t lib_id, std::string root, ScanOptions opt) {
    const int64_t t0 = now_ms();
    log_info("scan start: " + root +
             (opt.start_rel.empty() ? std::string() : (" @ " + opt.start_rel)) +
             " workers=" + std::to_string(opt.workers ? opt.workers : kAutoWorkers) +
             (opt.full ? " full" : " subtree"));

    // ---- 解析路径到 VFS（smb://... 或本地绝对路径）
    std::string root_rel, err;
    auto vfs = vfs::vfs_for_path(root, &root_rel, &err);
    if (!vfs) {
        log_warn("scan: " + err);
        progress_.running = false;
        return;
    }
    const std::string base = norm_dir(root_rel);                      // 库根（vfs 内）
    const std::string start = norm_dir(join_rel(base, opt.start_rel)); // 遍历起点
    const bool subtree = !opt.full;

    vfs::FileStat st;
    if (!vfs->stat(start, &st) || !st.is_dir) {
        log_warn("scan: 起点不是目录: " + start);
        progress_.running = false;
        return;
    }

    const int64_t token = writer_.next_scan_token();
    const int workers = opt.workers > 0 ? opt.workers : kAutoWorkers;

    // ---- 工作队列 + worker 线程
    std::mutex qm;
    std::condition_variable qcv, done_cv;
    std::deque<std::string> queue;
    queue.push_back(start);
    int inflight = 0;
    std::atomic<bool> stop{false};
    std::atomic<bool> fatal{false};
    std::atomic<int64_t> list_errors{0};
    std::atomic<bool> start_folder_book{false};

    BookWriter bw(writer_, progress_, token);

    auto process = [&](const std::string& dir) {
        std::vector<vfs::DirEntry> entries;
        if (!vfs->list(dir, &entries)) {
            ++list_errors;
            if (dir == start) {
                log_warn("scan: 无法列出起点目录 " + dir);
                fatal = true;
                stop = true;
                qcv.notify_all();
            } else if (list_errors.load() <= 20) {
                log_warn("scan: 列目录失败 " + dir);
            }
            return;
        }
        progress_.entries_seen += (int64_t)entries.size();
        progress_.dirs_seen += 1;

        bool has_image = false;
        std::vector<std::string> subdirs;
        for (const auto& e : entries) {
            if (is_excluded_name(e.name)) continue;
            if (e.st.is_dir) {
                subdirs.push_back(e.name);
                continue;
            }
            if (!e.st.is_file) continue;
            const std::string ext = lower_ext_str(e.name);
            if (paths::is_archive_ext(ext)) {
                Book b;
                b.lib_id = lib_id;
                b.path = vfs->full_path(join_rel(dir, e.name));
                b.rel_dir = lib_rel_of(base, dir);
                b.kind = BookKind::Archive;
                b.title = stem_str(e.name);
                b.ext = ext;
                b.size = (int64_t)e.st.size;
                b.mtime = e.st.mtime;
                bw.publish(std::move(b));
            } else if (paths::is_image_ext(ext)) {
                has_image = true;
            }
        }

        // 直接含图片的目录 = 一本书（与旧扫描器一致：不再下探，其中的归档也不单独入库）
        if (opt.include_folders && has_image && dir != base) {
            int64_t mtime = 0;
            vfs::FileStat dst;
            if (vfs->stat(dir, &dst)) mtime = dst.mtime;
            Book b;
            b.lib_id = lib_id;
            b.path = vfs->full_path(dir);
            b.rel_dir = lib_rel_of(base, parent_dir(dir));
            b.kind = BookKind::Folder;
            b.title = base_name(dir);
            b.size = 0;
            b.mtime = mtime;
            bw.publish(std::move(b));
            if (dir == start) start_folder_book = true;
            return;
        }

        if (!subdirs.empty()) {
            std::lock_guard<std::mutex> lk(qm);
            for (auto& n : subdirs) queue.push_back(join_rel(dir, n));
            qcv.notify_all();
        }
    };

    auto worker = [&]() {
        for (;;) {
            if (stop) return;
            std::string dir;
            {
                std::unique_lock<std::mutex> lk(qm);
                qcv.wait(lk, [&] { return stop.load() || !queue.empty(); });
                if (stop) return;
                dir = std::move(queue.front());
                queue.pop_front();
                ++inflight;
            }
            if (!progress_.cancel.load()) {
                while (progress_.paused.load() && !progress_.cancel.load() && !stop)
                    std::this_thread::sleep_for(std::chrono::milliseconds(50));
                if (!progress_.cancel.load() && !stop) process(dir);
            }
            {
                std::lock_guard<std::mutex> lk(qm);
                --inflight;
                if (queue.empty() && inflight == 0) done_cv.notify_all();
            }
        }
    };

    std::vector<std::thread> pool;
    pool.reserve((size_t)workers);
    for (int i = 0; i < workers; ++i) pool.emplace_back(worker);

    {
        std::unique_lock<std::mutex> lk(qm);
        done_cv.wait(lk, [&] { return (queue.empty() && inflight == 0) || fatal.load(); });
    }
    stop = true;
    qcv.notify_all();
    for (auto& t : pool) t.join();

    bw.finish(); // 写完所有行

    const bool cancelled = progress_.cancel.load();
    if (!cancelled && !fatal.load()) {
        const int64_t removed =
            subtree ? writer_.delete_unseen_in_subtree(lib_id, token, opt.start_rel)
                    : writer_.delete_unseen(lib_id, token);
        progress_.removed = removed;
        // 刷新模式下，起点目录“曾经是 Folder 书、现在已经不是”时补一刀。
        if (subtree && start != base && !start_folder_book.load())
            writer_.delete_book_by_path(lib_id, vfs->full_path(start));
        // 目录树计数：从 books 全量重算（一次 GROUP BY；行数变化时目录计数要跟上）。
        writer_.rebuild_dirs(lib_id);
        writer_.touch_library_scan(lib_id, (int64_t)time(nullptr));
    } else {
        log_warn(cancelled ? "scan cancelled; leftovers kept" : "scan aborted; leftovers kept");
    }

    progress_.running = false;
    log_info("scan done: added=" + std::to_string(progress_.added.load()) +
             " updated=" + std::to_string(progress_.updated.load()) +
             " removed=" + std::to_string(progress_.removed.load()) +
             " dirs=" + std::to_string(progress_.dirs_seen.load()) +
             " errors=" + std::to_string(list_errors.load()) + " in " +
             std::to_string(now_ms() - t0) + "ms");
}

} // namespace cs
