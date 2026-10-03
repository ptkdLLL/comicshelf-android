#pragma once
// Facade used by the UI: libraries + virtualized queries + on-demand covers.
#include "archive/archive.h"
#include "core/database.h"
#include "core/scanner.h"
#include "core/settings.h"
#include "core/types.h"
#include "image/thumbnail_cache.h"
#include "util/thread_pool.h"

#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace cs {

class LibraryManager {
public:
    LibraryManager(Settings& settings, ThreadPool& pool, std::string data_dir_utf8);
    ~LibraryManager();

    LibraryManager(const LibraryManager&) = delete;
    LibraryManager& operator=(const LibraryManager&) = delete;

    bool init(std::string* err = nullptr);

    ThumbnailCache& thumbs() { return thumbs_; }
    // The UI-side database connection (read + translation archive). Guarded
    // internally by a recursive mutex, so worker threads may use it too.
    Database& db() { return db_; }

    // CPU resampling algorithm used when building cover thumbnails. Changing it
    // also changes the cover cache key, so thumbs regenerate automatically.
    void set_thumb_resample(Resample r);
    Resample thumb_resample() const { return thumb_resample_; }
    ScanProgress& progress() { return progress_; }

    // ---- libraries -------------------------------------------------------
    std::vector<LibraryRow> libraries();
    int64_t add_library_root(const std::string& utf8_root, bool start_scan = true);
    void remove_library(int64_t lib_id);
    bool update_library_name(int64_t lib_id, const std::string& name);
    bool library_stats(int64_t lib_id, int64_t& books, int64_t& folders, int& max_depth);

    // ---- scanning --------------------------------------------------------
    void start_scan(int64_t lib_id);
    // 子树刷新：只重扫/清理 rel（库根相对路径，'' = 整库）这棵子树，
    // 供 SMB2 CHANGE_NOTIFY 事件与“刷新此目录”用。
    void refresh_subtree(int64_t lib_id, const std::string& rel);
    // 目录在服务端被删除/改名：直接清掉它（及子树）的所有行。
    int64_t remove_subtree(int64_t lib_id, const std::string& rel);
    void cancel_scan();
    void pause_scan(bool paused);

    // ---- virtualized queries --------------------------------------------
    // `dir_rel` "" + recursive == true means the whole library.
    // `fav_only` / `read_state` (-1 any, 0 unread, 1 reading, 2 read) filter on
    // the per-book user state.
    int64_t count(int64_t lib_id, const std::string& search,
                  const std::string& dir_rel = "", bool recursive = true,
                  bool fav_only = false, int read_state = -1);
    std::vector<Book> page(int64_t lib_id, const std::string& search,
                           SortKey sort, bool desc, int64_t offset, int limit,
                           const std::string& dir_rel = "", bool recursive = true,
                           bool fav_only = false, int read_state = -1);

    // 单本完整文件名(书名 + ".扩展名"; 目录型书籍无扩展名)。
    std::string book_file_name(int64_t id);

    // ---- folder tree -----------------------------------------------------
    std::vector<DirRow> child_dirs(int64_t lib_id, const std::string& parent_rel);
    void rebuild_dirs(int64_t lib_id);

    // ---- covers ----------------------------------------------------------
    // 轮询接口（JNI/UI 用）。每次调用都推进一次状态机：
    //   status: 1 = 生成中（稍后再试）; 2 = 就绪（thumb 非空）;
    //           3 = 暂不可用（内容层永久失败，或瞬态失败冷却中；UI 显示占位并慢速重试）
    // 失败分两类：内容层“永久”（不是图片/包损坏）不再自动重试；
    // 其余（IO/网络/超时）60s 冷却后按需重试；提交时记录 SMB 断连纪元，
    // 期间断过线则任何失败都按可重试处理（断线时读到的“坏数据”不可信）。
    struct CoverPoll {
        int status = 1;
        ThumbPtr thumb;
    };
    CoverPoll cover_poll(const Book& b);
    // 兼容旧调用（桌面壳/预热）：就绪返回缩略图，否则 nullptr（已按需排队生成）。
    ThumbPtr cover(const Book& b);
    bool cover_ready(const Book& b);
    void warm_covers(int64_t lib_id, int max_books);
    // Drops the on-disk + in-memory cover and clears the failed/short-lived
    // state so the next cover() call regenerates it from scratch.
    void forget_cover(const Book& b);
    /** 清空全部封面缓存（磁盘+内存）。 */
    void clear_all_covers();

    // ---- per-book user state (schema v4) ---------------------------------
    BookMeta meta(int64_t book_id);
    void set_favorite(int64_t book_id, bool favorite);
    void set_read_state(int64_t book_id, int read_state);
    void save_progress(int64_t book_id, int page);
    // Removes the book row plus all of its user state; also drops its cover.
    bool delete_book(int64_t book_id);

    // ---- bookmarks / tags ------------------------------------------------
    std::vector<BookmarkRow> bookmarks(int64_t book_id);
    std::vector<BookmarkRow> all_bookmarks();
    bool add_bookmark(int64_t book_id, int page, const std::string& label);
    bool remove_bookmark(int64_t bookmark_id);
    bool has_bookmark(int64_t book_id, int page);
    std::vector<std::string> all_tags();
    std::vector<std::string> book_tags(int64_t book_id);
    void set_book_tags(int64_t book_id, const std::vector<std::string>& tags);
    bool delete_tag(const std::string& name);

    // ---- reading ---------------------------------------------------------
    std::unique_ptr<IArchive> open_book(const Book& b, std::string* err);
    // Counts pages and persists the count. Returns page count or 0.
    int index_pages(const Book& b);
    void request_index_pages(Book b);

private:
    enum class CoverState { None, Inflight, Ready, Failed };
    struct CoverEntry {
        CoverState state = CoverState::None;
        bool permanent = false;  // Failed 且内容层不可恢复 → 不再自动重试
        uint64_t epoch = 0;      // 提交时的 SMB 断连纪元（见 vfs::smb_epoch）
        uint64_t token = 0;      // 提交序号：完成时校验，防止超时重投后旧任务改写新状态
        int64_t stamp_ms = 0;    // 进入当前状态的时间（冷却 / inflight 保险丝）
    };
    struct CoverGenResult {
        bool ok = false;
        bool permanent = false;  // 内容层永久失败（与网络无关）
        std::string reason;
    };
    CoverGenResult generate_cover(const Book& b, ImageRGBA& out) const;
    // 以下 *locked 都要求已持有 state_mtx_。
    CoverState schedule_cover_locked(const Book& b, const std::string& key, int64_t now);
    void pump_warm_locked();
    void enqueue_cover_locked(const Book& b, const std::string& key, bool front);
    void forget_key_locked(const std::string& key);

    Settings& settings_;
    ThreadPool& pool_;
    std::string data_dir_;

    Database db_;      // reader/UI connection
    Database writer_;  // scanner connection (WAL -> safe alongside db_)
    ThumbnailCache thumbs_;
    ScanProgress progress_;
    std::unique_ptr<Scanner> scanner_;

    int thumb_size_ = 400;
    Resample thumb_resample_ = Resample::Box;

    mutable std::mutex state_mtx_;
    std::unordered_map<std::string, CoverEntry> cover_state_;
    std::deque<Book> cover_queue_;                  // 待生成（前=屏上单元格，后=warm 预热）
    std::unordered_set<std::string> cover_queued_;  // 去重：已在队列中的 key
    uint64_t cover_token_ = 0;                      // 提交序号发号器
    int cover_inflight_ = 0;                        // 生成中任务数（与任务完成配对）
    int cover_cap_ = 3;                             // 并发上限（settings: cover_max_inflight）
};

} // namespace cs
