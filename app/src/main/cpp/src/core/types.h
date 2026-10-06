#pragma once
// Shared value types used across core / ui.
#include <atomic>
#include <cstdint>
#include <string>

namespace cs {

enum class BookKind : int {
    Archive = 0, // .zip / .cbz / .rar / .cbr
    Folder = 1,  // a directory that directly contains image files
};

// A single row of the `books` table. Kept small on purpose: it is copied a lot.
struct Book {
    int64_t id = 0;
    int64_t lib_id = 0;
    std::string path;     // UTF-8 absolute path
    std::string rel_dir;  // directory relative to the library root ('' = root), '/'-separated
    BookKind kind = BookKind::Archive;
    std::string title;
    std::string ext;
    int64_t size = 0;
    int64_t mtime = 0;
    int pages = 0;
    std::string cover_key;
    bool has_cover = false;
    bool pages_indexed = false;
    int64_t added_at = 0;

    // Joined from book_meta (schema v4) so the shelf can draw badges without a
    // per-cell query. Defaults describe a book that has never been opened.
    bool favorite = false;
    int read_state = 0; // 0 unread, 1 reading, 2 finished
    int last_page = 0;

    bool is_folder() const { return kind == BookKind::Folder; }
};

struct LibraryRow {
    int64_t id = 0;
    std::string root; // UTF-8
    std::string name;
    int64_t added_at = 0;
    int64_t last_scan = 0;
    int64_t book_count = 0;
};

// One folder in the library's directory tree (materialized in the `dirs` table).
struct DirRow {
    int64_t id = 0;
    int64_t lib_id = 0;
    std::string parent_rel; // '' for top-level folders
    std::string name;       // last path component
    std::string rel;        // full path relative to the library root
    int64_t book_count = 0; // books directly in this folder
    int64_t total_count = 0;// books in this folder and all subfolders
    int depth = 0;
};

// Per-book user state (schema v4). A missing row means "all defaults".
struct BookMeta {
    bool favorite = false;
    int read_state = 0; // 0 = unread, 1 = reading, 2 = finished
    int last_page = 0;
    int64_t last_read_at = 0;
};

// A saved bookmark (one page of one book), joined with the book title/path so
// the "manage bookmarks" list can show which book each entry belongs to.
struct BookmarkRow {
    int64_t id = 0;
    int64_t book_id = 0;
    std::string book_title;
    std::string book_path;
    int page = 0;
    std::string label;
    int64_t created_at = 0;
};

enum class SortKey : int {
    Added = 0,
    Title,
    Path,
    Size,
    Mtime,
    Pages,
    LastRead = 6,   // v0.5.3：浏览历史/最近阅读（book_meta.last_read_at）
};

// Live progress of a scan. Pointers to this stay valid for the scanner's life.
struct ScanProgress {
    std::atomic<bool> running{false};
    std::atomic<bool> cancel{false};
    std::atomic<bool> paused{false};
    std::atomic<int64_t> lib_id{0};
    std::atomic<int64_t> entries_seen{0};
    std::atomic<int64_t> dirs_seen{0};
    std::atomic<int64_t> added{0};
    std::atomic<int64_t> updated{0};
    std::atomic<int64_t> removed{0};
    std::atomic<int64_t> covers_queued{0};
    // 每次扫描自增：UI 靠它发现“扫描已结束”（哪怕整个扫描比轮询间隔还短）。
    std::atomic<int64_t> serial{0};

    void reset() {
        running = false;
        cancel = false;
        paused = false;
        lib_id = 0;
        entries_seen = 0;
        dirs_seen = 0;
        added = 0;
        updated = 0;
        removed = 0;
        covers_queued = 0;
        // serial 不清零：它是单调递增的“扫描代号”
    }
};

} // namespace cs
