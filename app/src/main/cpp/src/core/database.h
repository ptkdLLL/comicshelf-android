#pragma once
// SQLite-backed metadata store. This is the piece that replaces the
// size-limited Windows index used by other viewers: SQLite grows to terabytes
// and we only ever touch a bounded window of rows.
#include "core/types.h"

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

struct sqlite3;

namespace cs {

// Result of an incremental upsert.
struct UpsertResult {
    int64_t id = 0;
    bool is_new = false;
    bool changed = false;       // size/mtime differ -> pages & cover invalidated
    bool needs_cover = false;   // caller should (re)generate the cover
};

class Database {
public:
    static constexpr int kSchemaVersion = 5;

    Database() = default;
    ~Database();

    Database(const Database&) = delete;
    Database& operator=(const Database&) = delete;

    bool open(const std::string& utf8_path, std::string* err = nullptr);
    void close();
    bool is_open() const { return db_ != nullptr; }

    bool exec(const std::string& sql, std::string* err = nullptr);

    // Applies pending schema upgrades (adds columns/tables, backfills rel_dir,
    // rebuilds the folder tree). Safe to call on every start.
    void migrate();

    // ---- libraries -------------------------------------------------------
    int64_t add_library(const std::string& root_utf8, const std::string& name);
    bool remove_library(int64_t lib_id);
    std::vector<LibraryRow> libraries();
    bool get_library(int64_t lib_id, LibraryRow& out);
    bool touch_library_scan(int64_t lib_id, int64_t when);
    bool update_library_name(int64_t lib_id, const std::string& name);
    // books / folders / max depth for the "library stats" dialog.
    bool library_stats(int64_t lib_id, int64_t& books, int64_t& folders, int& max_depth);

    // ---- books -----------------------------------------------------------
    UpsertResult upsert_book(const Book& b, int64_t scan_token);
    bool get_book(int64_t id, Book& out);

    // Deletes full books (row + user meta + bookmarks + tag links). Returns the
    // number of book rows removed.
    int delete_book(int64_t id);
    int delete_books(const std::vector<int64_t>& ids);

    // ---- per-book user state (schema v4) ---------------------------------
    bool get_meta(int64_t book_id, BookMeta& out);
    bool set_favorite(int64_t book_id, bool favorite);
    bool set_read_state(int64_t book_id, int read_state);
    // Records the last read page and bumps last_read_at.
    bool set_progress(int64_t book_id, int page);

    // ---- bookmarks -------------------------------------------------------
    bool add_bookmark(int64_t book_id, int page, const std::string& label);
    bool remove_bookmark(int64_t bookmark_id);
    bool remove_bookmark_at(int64_t book_id, int page);
    bool has_bookmark(int64_t book_id, int page);
    std::vector<BookmarkRow> bookmarks_of(int64_t book_id);
    std::vector<BookmarkRow> all_bookmarks();

    // ---- tags ------------------------------------------------------------
    bool assign_tag(int64_t book_id, const std::string& name);
    bool unassign_tag(int64_t book_id, const std::string& name);
    bool set_book_tags(int64_t book_id, const std::vector<std::string>& tags);
    std::vector<std::string> tags_of(int64_t book_id);
    std::vector<std::string> all_tags();
    bool delete_tag(const std::string& name);

    // ---- schema v5: translation text archive (TEXT ONLY, never images) ----
    // payload is zlib(JSON) produced by the caller; this layer only stores bytes.
    bool put_text_archive(int64_t book_id, const std::string& payload, int64_t bytes);
    bool get_text_archive(int64_t book_id, std::string& payload,
                          int64_t* bytes = nullptr, int64_t* last_access = nullptr);
    bool touch_text_archive(int64_t book_id);
    bool delete_text_archive(int64_t book_id);
    /** 清空全部翻译档案，返回删除行数（设置页"释放全部翻译与封面"用）。 */
    int64_t clear_text_archive();
    int64_t text_archive_total_bytes();
    // Deletes the least-recently-used books until the total is <= max_bytes.
    // Returns the number of bytes freed.
    int64_t evict_text_archive(int64_t max_bytes);

    // Per-book "translate when opened" flag (sparse: only rows the user opted in).
    bool set_book_translate_enabled(int64_t book_id, bool enabled);
    bool get_book_translate_enabled(int64_t book_id);

    // Glossary (shared, tiny). `rev` changes on every edit so the pipeline hash
    // can invalidate stale archives.
    std::vector<std::pair<std::string, std::string>> glossary();
    bool set_glossary_term(const std::string& src, const std::string& dst);
    bool remove_glossary_term(const std::string& src);
    int64_t glossary_rev();

    // `dir_rel` == "" with recursive == true means "no folder filter".
    // `fav_only` / `read_state` (-1 = any) filter on the bookmark_meta table.
    int64_t count_books(int64_t lib_id, const std::string& search,
                        const std::string& dir_rel = "", bool recursive = true,
                        bool fav_only = false, int read_state = -1);

    std::vector<Book> page_books(int64_t lib_id, const std::string& search,
                                 SortKey sort, bool desc, int64_t offset, int limit,
                                 const std::string& dir_rel = "", bool recursive = true,
                                 bool fav_only = false, int read_state = -1);

    // 单本完整文件名(书名 + ".扩展名"; 目录型书籍无扩展名)。长按菜单"导出书名"用。
    std::string book_file_name(int64_t id);

    bool update_pages(int64_t id, int pages);
    bool update_cover(int64_t id, const std::string& cover_key);
    bool update_pages_and_cover(int64_t id, int pages, const std::string& cover_key);

    // Removes books of `lib_id` that were not touched during the given scan.
    int64_t delete_unseen(int64_t lib_id, int64_t scan_token);

    // ---- subtree ops (incremental refresh of one directory) ----------------
    // All three use the (lib_id, rel_dir) index range, so they stay fast on a
    // million-row table. `rel` is a '/'-separated path relative to the root;
    // '' addresses the whole library.
    // Deletes `rel` itself plus everything below it.
    int64_t delete_books_in_subtree(int64_t lib_id, const std::string& rel);
    // Same but only rows whose scan_token is stale (used after a subtree scan).
    int64_t delete_unseen_in_subtree(int64_t lib_id, int64_t scan_token,
                                     const std::string& rel);
    // One book, addressed by its absolute path (single-file change events).
    int64_t delete_book_by_path(int64_t lib_id, const std::string& path);

    int64_t next_scan_token();
    int64_t total_books();

    // ---- folder tree -----------------------------------------------------
    // Recomputes the whole `dirs` table for a library from `books.rel_dir`.
    void rebuild_dirs(int64_t lib_id);
    // Children of `parent_rel` ('' = top level), ordered by name.
    std::vector<DirRow> child_dirs(int64_t lib_id, const std::string& parent_rel);

    // Transactions (batched writes make million-row scans feasible).
    bool begin();
    bool commit();
    bool rollback();

private:
    bool create_schema(std::string* err);
    bool has_column(const char* table, const char* column);
    bool backfill_rel_dirs(std::string* err);
    // 删除孤立用户态行（books 里已不存在的 book_id）。
    void cleanup_orphan_rows();
    int user_version();
    bool set_user_version(int v);

    sqlite3* db_ = nullptr;
    std::recursive_mutex mtx_;
};

} // namespace cs
