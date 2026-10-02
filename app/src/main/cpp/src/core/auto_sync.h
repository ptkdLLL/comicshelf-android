#pragma once
// SMB 实时同步：把 CHANGE_NOTIFY 事件变成“子树刷新/清理”。
//
//   * 每个 SMB 书库挂一个 watch-tree 订阅（服务端递归推送变更路径）
//   * 事件去抖 + 合并，扫描器忙时排队，空闲后按批执行
//   * 新增/修改 → refresh_subtree（只重扫那一棵子树）
//   * 删除/改名 → remove_subtree（直接清索引行）
//   * 服务端不支持 CHANGE_NOTIFY 时优雅降级（记日志，手动/定时全量扫描仍可用）
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace cs {

class LibraryManager;
namespace vfs {
class SmbWatcher;
}

class AutoSync {
public:
    explicit AutoSync(LibraryManager& lib);
    ~AutoSync();

    AutoSync(const AutoSync&) = delete;
    AutoSync& operator=(const AutoSync&) = delete;

    // (重新)为所有 smb:// 书库建立监视。启动时与“添加 SMB 书库”后调用。
    void start();
    void stop();

    // UI 状态：已建立的监视数 / 仍在工作的监视数
    int watchers() const;
    int alive_count() const;
    bool supported() const { return alive_count() > 0; }

private:
    void on_changes(int64_t lib, const std::string& share_rel_base,
                    const std::vector<std::pair<std::string, int>>& evs);
    void queue_refresh(int64_t lib, const std::string& rel);
    void queue_remove(int64_t lib, const std::string& rel);
    void worker_loop();

    LibraryManager& lib_;
    mutable std::mutex m_;
    std::unordered_map<std::string, std::shared_ptr<vfs::SmbWatcher>> watchers_; // key: host|share
    // 待办：lib → 需要刷新/删除的库内相对路径
    std::unordered_map<int64_t, std::unordered_set<std::string>> dirty_;
    std::unordered_map<int64_t, std::unordered_set<std::string>> gone_;
    bool stop_ = false;
    std::thread th_;
    std::condition_variable cv_;
};

} // namespace cs
