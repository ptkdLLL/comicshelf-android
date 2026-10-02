#pragma once
// 后台、可取消的书库扫描器（VFS 之上，本地与 SMB 通用）。
//
// 面向百万级书库的设计：
//   * 工作队列 + N 个 worker 并行列目录（SMB 上列目录是主要延迟，必须并发）
//   * 每个目录一次 LISTDIR 拿到全部子项（名字/大小/mtime），不再逐文件 stat
//   * 唯一的写库线程 + 批量事务（扫描期间没有 SQL 争用）
//   * 增量可见性靠 scan_token：本次没见到的行在收尾时被索引删除
//   * 子树刷新（start_rel）：只遍历/清理一棵子树，供实时变更增量使用
//
// 为什么不按“目录 mtime 未变就跳过整棵子树”：目录 mtime 只反映其直接子项的
// 增删，深层新增文件不会改上层目录的 mtime，跳过会静默漏书。可靠且完整的
// 增量只有两条路：并行全量遍历，或服务端事件（SMB2 CHANGE_NOTIFY）。
#include "core/database.h"
#include "core/types.h"
#include "util/thread_pool.h"

#include <string>
#include <thread>

namespace cs {

struct ScanOptions {
    bool include_folders = true;
    int workers = 0;        // 0 = 自动
    bool full = true;       // true=完整扫描（全局清理）；false=子树刷新
    std::string start_rel;  // 库根相对路径（'' = 库根），full=false 时生效
};

class Scanner {
public:
    Scanner(Database& writer, ThreadPool& pool, ScanProgress& progress);
    ~Scanner();

    Scanner(const Scanner&) = delete;
    Scanner& operator=(const Scanner&) = delete;

    // Non-blocking: launches the walk on its own thread.
    void start(int64_t lib_id, std::string root_utf8, bool include_folders);
    void start(int64_t lib_id, std::string root_utf8, const ScanOptions& opt);
    void cancel();
    void pause(bool paused);
    void wait();
    bool busy() const;

private:
    void run(int64_t lib_id, std::string root, ScanOptions opt);

    Database& writer_;
    ThreadPool& pool_;
    ScanProgress& progress_;
    std::thread thread_;
};

} // namespace cs
