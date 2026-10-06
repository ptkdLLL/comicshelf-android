#include "core/database.h"

#include "util/logger.h"
#include "util/path_util.h"

#include <algorithm>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <set>
#include <sqlite3.h>
#include <unordered_map>

namespace cs {
namespace {

// Escape a user string for use inside a LIKE pattern (ESCAPE '\').
std::string escape_like(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '%' || c == '_' || c == '\\') out.push_back('\\');
        out.push_back(c);
    }
    return out;
}

std::string column_text(sqlite3_stmt* st, int idx) {
    const unsigned char* p = sqlite3_column_text(st, idx);
    if (!p) return {};
    return std::string(reinterpret_cast<const char*>(p));
}

void bind_text(sqlite3_stmt* st, int idx, const std::string& s) {
    sqlite3_bind_text(st, idx, s.c_str(), (int)s.size(), SQLITE_TRANSIENT);
}

const char* sort_column(SortKey k) {
    switch (k) {
        case SortKey::Title: return "title";
        case SortKey::Path: return "path";
        case SortKey::Size: return "size";
        case SortKey::Mtime: return "mtime";
        case SortKey::Pages: return "pages";
        case SortKey::LastRead: return "m.last_read_at";   // P-H2：浏览历史排序（有 LEFT JOIN m）
        case SortKey::Added:
        default: return "id";
    }
}

std::string parent_of(const std::string& rel) {
    auto p = rel.rfind('/');
    return p == std::string::npos ? std::string() : rel.substr(0, p);
}

std::string base_name(const std::string& rel) {
    auto p = rel.rfind('/');
    return p == std::string::npos ? rel : rel.substr(p + 1);
}

int depth_of(const std::string& rel) {
    int d = 0;
    for (char c : rel)
        if (c == '/') ++d;
    return rel.empty() ? 0 : d + 1;
}

// Builds an index-friendly WHERE fragment for the folder/search filter.
// The folder test uses a range scan (`rel_dir >= 'd/' AND rel_dir < 'd0'`)
// instead of LIKE so the (lib_id, rel_dir) index is always used.
struct FilterBuild {
    std::string where;
    std::vector<std::string> text_params; // bound as TEXT, in placeholder order
    int next_index = 2;
};

FilterBuild make_filter(const std::string& dir_rel, bool recursive, const std::string& search,
                        bool fav_only = false, int read_state = -1, int64_t lib_id = 1) {
    FilterBuild f;
    // P-H4：lib_id<=0 ≡ 全部库（浏览历史跨库）。省去 lib 子句时 ?1 缺号——
    // 占位符编号仍自 ?2 起（n=2），bind_filter 对 ?1 的绑定是 SQLITE_RANGE no-op。
    f.where = (lib_id > 0) ? " WHERE lib_id=?1" : " WHERE 1=1";
    int n = 2;
    if (!dir_rel.empty()) {
        if (recursive) {
            f.where += " AND (rel_dir=?" + std::to_string(n) + " OR (rel_dir>=?" +
                       std::to_string(n + 1) + " AND rel_dir<?" + std::to_string(n + 2) + "))";
            f.text_params.push_back(dir_rel);
            f.text_params.push_back(dir_rel + "/");
            f.text_params.push_back(dir_rel + "0"); // '/' is 0x2F, '0' is 0x30
            n += 3;
        } else {
            f.where += " AND rel_dir=?" + std::to_string(n++);
            f.text_params.push_back(dir_rel);
        }
    } else if (!recursive) {
        f.where += " AND rel_dir=''"; // books sitting directly in the library root
    }
    if (!search.empty()) {
        f.where += " AND title LIKE ?" + std::to_string(n++) + " ESCAPE '\\'";
        f.text_params.push_back("%" + escape_like(search) + "%");
    }
    // User-state filters ride on correlated EXISTS subqueries (book_meta has a
    // PK on book_id, so each test is a single index probe). They add no bind
    // parameters, so the placeholder numbering above stays valid.
    if (fav_only)
        f.where += " AND EXISTS(SELECT 1 FROM book_meta m WHERE m.book_id=books.id AND m.favorite=1)";
    if (read_state == 0)
        f.where += " AND NOT EXISTS(SELECT 1 FROM book_meta m WHERE m.book_id=books.id AND m.read_state>=2)";
    else if (read_state == 1)
        f.where += " AND EXISTS(SELECT 1 FROM book_meta m WHERE m.book_id=books.id AND m.read_state=1)";
    else if (read_state == 2)
        f.where += " AND EXISTS(SELECT 1 FROM book_meta m WHERE m.book_id=books.id AND m.read_state>=2)";
    else if (read_state == -2)   // P-H4：仅"读过"（浏览历史哨兵；UI 循环只走 -1..2）
        f.where += " AND EXISTS(SELECT 1 FROM book_meta m WHERE m.book_id=books.id AND m.last_read_at>0)";
    f.next_index = n;
    return f;
}

void bind_filter(sqlite3_stmt* st, const FilterBuild& f, int64_t lib_id, int& idx) {
    idx = 1;
    sqlite3_bind_int64(st, idx++, lib_id);
    for (const auto& p : f.text_params) bind_text(st, idx++, p);
}

// Book columns + the joined user-state columns (book_meta is LEFT JOINed in the
// page/get queries, so a missing row still yields defaults).
const char* kBookColumns =
    "books.id,books.lib_id,books.path,books.rel_dir,books.kind,books.title,books.ext,books.size,"
    "books.mtime,books.pages,books.cover_key,books.has_cover,books.pages_indexed,books.added_at,"
    "COALESCE(m.favorite,0),COALESCE(m.read_state,0),COALESCE(m.last_page,0)";
const char* kBookJoin = " FROM books LEFT JOIN book_meta m ON m.book_id=books.id";

Book read_book(sqlite3_stmt* st) {
    Book b;
    b.id = sqlite3_column_int64(st, 0);
    b.lib_id = sqlite3_column_int64(st, 1);
    b.path = column_text(st, 2);
    b.rel_dir = column_text(st, 3);
    b.kind = (BookKind)sqlite3_column_int(st, 4);
    b.title = column_text(st, 5);
    b.ext = column_text(st, 6);
    b.size = sqlite3_column_int64(st, 7);
    b.mtime = sqlite3_column_int64(st, 8);
    b.pages = sqlite3_column_int(st, 9);
    b.cover_key = column_text(st, 10);
    b.has_cover = sqlite3_column_int(st, 11) != 0;
    b.pages_indexed = sqlite3_column_int(st, 12) != 0;
    b.added_at = sqlite3_column_int64(st, 13);
    b.favorite = sqlite3_column_int(st, 14) != 0;
    b.read_state = sqlite3_column_int(st, 15);
    b.last_page = sqlite3_column_int(st, 16);
    return b;
}

// Reads a row produced by the bookmark SELECTs below (columns 0..6).
BookmarkRow read_bookmark(sqlite3_stmt* st) {
    BookmarkRow r;
    r.id = sqlite3_column_int64(st, 0);
    r.book_id = sqlite3_column_int64(st, 1);
    r.page = sqlite3_column_int(st, 2);
    r.label = column_text(st, 3);
    r.created_at = sqlite3_column_int64(st, 4);
    r.book_title = column_text(st, 5);
    r.book_path = column_text(st, 6);
    return r;
}

const char* kBookmarkSelect =
    "SELECT bm.id,bm.book_id,bm.page,bm.label,bm.created_at,b.title,b.path "
    "FROM bookmarks bm LEFT JOIN books b ON b.id=bm.book_id";

// Bumps an integer counter kept in the `meta` table (used for glossary_rev).
// Caller must already hold the connection mutex.
void bump_meta_rev(sqlite3* db, const char* key) {
    int64_t v = 0;
    sqlite3_stmt* st = nullptr;
    std::string sel = std::string("SELECT value FROM meta WHERE key='") + key + "'";
    if (sqlite3_prepare_v2(db, sel.c_str(), -1, &st, nullptr) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) {
            const unsigned char* p = sqlite3_column_text(st, 0);
            v = p ? std::atoll(reinterpret_cast<const char*>(p)) : 0;
        }
        sqlite3_finalize(st);
    }
    ++v;
    std::string up = std::string("INSERT INTO meta(key,value) VALUES('") + key + "','" +
                     std::to_string(v) +
                     "') ON CONFLICT(key) DO UPDATE SET value=excluded.value";
    sqlite3_exec(db, up.c_str(), nullptr, nullptr, nullptr);
}

} // namespace

Database::~Database() { close(); }

bool Database::open(const std::string& utf8_path, std::string* err) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (db_) close();

    int rc = sqlite3_open_v2(utf8_path.c_str(), &db_,
                             SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE |
                                 SQLITE_OPEN_FULLMUTEX,
                             nullptr);
    if (rc != SQLITE_OK) {
        if (err && db_) *err = sqlite3_errmsg(db_);
        if (db_) {
            sqlite3_close(db_);
            db_ = nullptr;
        }
        return false;
    }

    sqlite3_busy_timeout(db_, 5000);
    exec("PRAGMA journal_mode=WAL;");
    exec("PRAGMA synchronous=NORMAL;");
    exec("PRAGMA temp_store=MEMORY;");
    exec("PRAGMA cache_size=-80000;");     // ~80 MB page cache
    exec("PRAGMA mmap_size=268435456;");   // 256 MB memory map
    exec("PRAGMA foreign_keys=ON;");

    if (!create_schema(err)) return false;
    migrate();
    return true;
}

void Database::close() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (db_) {
        sqlite3_close(db_);
        db_ = nullptr;
    }
}

bool Database::exec(const std::string& sql, std::string* err) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    char* msg = nullptr;
    int rc = sqlite3_exec(db_, sql.c_str(), nullptr, nullptr, &msg);
    if (rc != SQLITE_OK) {
        if (err) *err = msg ? msg : "sqlite error";
        log_error("sqlite exec failed: " + std::string(msg ? msg : "?"));
        if (msg) sqlite3_free(msg);
        return false;
    }
    return true;
}

bool Database::create_schema(std::string* err) {
    static const char* kBase = R"SQL(
CREATE TABLE IF NOT EXISTS meta(
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS libraries(
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  root      TEXT NOT NULL UNIQUE,
  name      TEXT NOT NULL DEFAULT '',
  added_at  INTEGER NOT NULL DEFAULT 0,
  last_scan INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS books(
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  lib_id        INTEGER NOT NULL,
  path          TEXT NOT NULL,
  rel_dir       TEXT NOT NULL DEFAULT '',
  kind          INTEGER NOT NULL DEFAULT 0,
  title         TEXT NOT NULL DEFAULT '',
  ext           TEXT NOT NULL DEFAULT '',
  size          INTEGER NOT NULL DEFAULT 0,
  mtime         INTEGER NOT NULL DEFAULT 0,
  pages         INTEGER NOT NULL DEFAULT 0,
  cover_key     TEXT NOT NULL DEFAULT '',
  has_cover     INTEGER NOT NULL DEFAULT 0,
  pages_indexed INTEGER NOT NULL DEFAULT 0,
  added_at      INTEGER NOT NULL DEFAULT 0,
  updated_at    INTEGER NOT NULL DEFAULT 0,
  scan_token    INTEGER NOT NULL DEFAULT 0
);
)SQL";
    if (!exec(kBase, err)) return false;

    // Upgrade pre-v2 databases in place.
    if (!has_column("books", "rel_dir")) {
        if (!exec("ALTER TABLE books ADD COLUMN rel_dir TEXT NOT NULL DEFAULT '';", err))
            return false;
    }

    static const char* kIndexes = R"SQL(
CREATE INDEX IF NOT EXISTS ix_books_path            ON books(path);
-- A book is identified per library, so overlapping library roots (e.g. adding
-- both T:\comics and T:\comics\sub) do not steal each other's rows.
CREATE UNIQUE INDEX IF NOT EXISTS ux_books_lib_path ON books(lib_id, path);
CREATE INDEX IF NOT EXISTS ix_books_lib             ON books(lib_id);
CREATE INDEX IF NOT EXISTS ix_books_lib_id          ON books(lib_id, id);
CREATE INDEX IF NOT EXISTS ix_books_lib_title       ON books(lib_id, title);
CREATE INDEX IF NOT EXISTS ix_books_lib_mtime       ON books(lib_id, mtime);
CREATE INDEX IF NOT EXISTS ix_books_lib_size        ON books(lib_id, size);
CREATE INDEX IF NOT EXISTS ix_books_lib_scan        ON books(lib_id, scan_token);
CREATE INDEX IF NOT EXISTS ix_books_lib_reldir      ON books(lib_id, rel_dir);
CREATE INDEX IF NOT EXISTS ix_books_lib_dir_title   ON books(lib_id, rel_dir, title);

CREATE TABLE IF NOT EXISTS dirs(
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  lib_id      INTEGER NOT NULL,
  parent_rel  TEXT NOT NULL DEFAULT '',
  name        TEXT NOT NULL DEFAULT '',
  rel         TEXT NOT NULL DEFAULT '',
  book_count  INTEGER NOT NULL DEFAULT 0,
  total_count INTEGER NOT NULL DEFAULT 0,
  depth       INTEGER NOT NULL DEFAULT 0,
  UNIQUE(lib_id, rel)
);
CREATE INDEX IF NOT EXISTS ix_dirs_parent ON dirs(lib_id, parent_rel, name);

-- schema v4: per-book user state (favorite / read state / progress).
CREATE TABLE IF NOT EXISTS book_meta(
  book_id      INTEGER PRIMARY KEY,
  favorite     INTEGER NOT NULL DEFAULT 0,
  read_state   INTEGER NOT NULL DEFAULT 0,
  last_page    INTEGER NOT NULL DEFAULT 0,
  last_read_at INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_meta_favorite ON book_meta(favorite);
CREATE INDEX IF NOT EXISTS ix_meta_read     ON book_meta(read_state);

CREATE TABLE IF NOT EXISTS bookmarks(
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id    INTEGER NOT NULL,
  page       INTEGER NOT NULL DEFAULT 0,
  label      TEXT    NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0,
  UNIQUE(book_id, page)
);
CREATE INDEX IF NOT EXISTS ix_bookmarks_book ON bookmarks(book_id, page);

CREATE TABLE IF NOT EXISTS tags(
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS book_tags(
  book_id INTEGER NOT NULL,
  tag_id  INTEGER NOT NULL,
  PRIMARY KEY(book_id, tag_id)
);
CREATE INDEX IF NOT EXISTS ix_book_tags_tag ON book_tags(tag_id);

-- schema v5: translation TEXT archive (never images) + per-book opt-in.
CREATE TABLE IF NOT EXISTS book_text_archive(
  book_id     INTEGER PRIMARY KEY,
  payload     BLOB    NOT NULL,
  bytes       INTEGER NOT NULL DEFAULT 0,
  last_access INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS ix_text_archive_lru ON book_text_archive(last_access);

CREATE TABLE IF NOT EXISTS book_translate_pref(
  book_id     INTEGER PRIMARY KEY,
  enabled     INTEGER NOT NULL DEFAULT 0,
  source_lang TEXT NOT NULL DEFAULT '',
  target_lang TEXT NOT NULL DEFAULT '',
  updated_at  INTEGER NOT NULL DEFAULT 0
);

-- Shared glossary (small, cross-book). Editing it bumps meta.glossary_rev so
-- the pipeline hash changes and stale archives are invalidated.
CREATE TABLE IF NOT EXISTS glossary(
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  src  TEXT NOT NULL UNIQUE,
  dst  TEXT NOT NULL DEFAULT '',
  note TEXT NOT NULL DEFAULT ''
);
)SQL";
    return exec(kIndexes, err);
}

bool Database::has_column(const char* table, const char* column) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    std::string sql = std::string("PRAGMA table_info(") + table + ")";
    sqlite3_stmt* st = nullptr;
    bool found = false;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) == SQLITE_OK) {
        while (sqlite3_step(st) == SQLITE_ROW) {
            if (column_text(st, 1) == column) {
                found = true;
                break;
            }
        }
        sqlite3_finalize(st);
    }
    return found;
}

int Database::user_version() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    int v = 0;
    if (sqlite3_prepare_v2(db_, "PRAGMA user_version", -1, &st, nullptr) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) v = sqlite3_column_int(st, 0);
        sqlite3_finalize(st);
    }
    return v;
}

bool Database::set_user_version(int v) {
    return exec("PRAGMA user_version=" + std::to_string(v) + ";");
}

void Database::migrate() {
    int v = user_version();
    if (v >= kSchemaVersion) return;
    log_info("db migrate: v" + std::to_string(v) + " -> v" + std::to_string(kSchemaVersion));
    if (v < 2) {
        backfill_rel_dirs(nullptr);
        for (auto& lib : libraries()) rebuild_dirs(lib.id);
    }
    if (v < 3) {
        // Was UNIQUE(path) globally; a book must be unique per library instead.
        exec("DROP INDEX IF EXISTS ux_books_path;");
        exec("CREATE UNIQUE INDEX IF NOT EXISTS ux_books_lib_path ON books(lib_id, path);");
    }
    set_user_version(kSchemaVersion);
}

// ------------------------------------------------------------------ libraries

int64_t Database::add_library(const std::string& root_utf8, const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    int64_t id = 0;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO libraries(root,name,added_at,last_scan) VALUES(?1,?2,?3,0) "
            "ON CONFLICT(root) DO UPDATE SET name=excluded.name",
            -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, root_utf8);
        bind_text(st, 2, name);
        sqlite3_bind_int64(st, 3, (sqlite3_int64)time(nullptr));
        sqlite3_step(st);
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_, "SELECT id FROM libraries WHERE root=?1", -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, root_utf8);
        if (sqlite3_step(st) == SQLITE_ROW) id = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    return id;
}

bool Database::remove_library(int64_t lib_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = true;
    // Drop dependent user rows first (there is no ON DELETE CASCADE).
    {
        const char* kDeps[] = {
            "DELETE FROM bookmarks WHERE book_id IN (SELECT id FROM books WHERE lib_id=?1)",
            "DELETE FROM book_tags WHERE book_id IN (SELECT id FROM books WHERE lib_id=?1)",
            "DELETE FROM book_meta  WHERE book_id IN (SELECT id FROM books WHERE lib_id=?1)",
            "DELETE FROM book_text_archive   WHERE book_id IN (SELECT id FROM books WHERE lib_id=?1)",
            "DELETE FROM book_translate_pref WHERE book_id IN (SELECT id FROM books WHERE lib_id=?1)",
        };
        for (const char* sql : kDeps) {
            sqlite3_stmt* d = nullptr;
            if (sqlite3_prepare_v2(db_, sql, -1, &d, nullptr) == SQLITE_OK) {
                sqlite3_bind_int64(d, 1, lib_id);
                ok = (sqlite3_step(d) == SQLITE_DONE) && ok;
                sqlite3_finalize(d);
            }
        }
    }
    int64_t n_books = 0;
    if (sqlite3_prepare_v2(db_, "SELECT COUNT(*) FROM books WHERE lib_id=?1", -1, &st, nullptr) ==
        SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        if (sqlite3_step(st) == SQLITE_ROW) n_books = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_, "DELETE FROM dirs WHERE lib_id=?1", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_, "DELETE FROM books WHERE lib_id=?1", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        ok = sqlite3_step(st) == SQLITE_DONE && ok;
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_, "DELETE FROM libraries WHERE id=?1", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        ok = sqlite3_step(st) == SQLITE_DONE && ok;
        sqlite3_finalize(st);
    }
    // 审计：移除书库会连带删掉索引行，值得留痕（避免"书库莫名消失"无从追查）。
    log_info("db: remove_library id=" + std::to_string(lib_id) +
             " books_deleted=" + std::to_string(n_books));
    return ok;
}

std::vector<LibraryRow> Database::libraries() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<LibraryRow> out;
    if (!db_) return out;
    const char* sql =
        "SELECT l.id,l.root,l.name,l.added_at,l.last_scan,"
        "(SELECT COUNT(*) FROM books b WHERE b.lib_id=l.id) "
        "FROM libraries l ORDER BY l.id";
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, sql, -1, &st, nullptr) == SQLITE_OK) {
        while (sqlite3_step(st) == SQLITE_ROW) {
            LibraryRow r;
            r.id = sqlite3_column_int64(st, 0);
            r.root = column_text(st, 1);
            r.name = column_text(st, 2);
            r.added_at = sqlite3_column_int64(st, 3);
            r.last_scan = sqlite3_column_int64(st, 4);
            r.book_count = sqlite3_column_int64(st, 5);
            out.push_back(std::move(r));
        }
        sqlite3_finalize(st);
    }
    return out;
}

bool Database::get_library(int64_t lib_id, LibraryRow& out) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool found = false;
    if (sqlite3_prepare_v2(db_, "SELECT id,root,name,added_at,last_scan FROM libraries WHERE id=?1",
                           -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        if (sqlite3_step(st) == SQLITE_ROW) {
            out.id = sqlite3_column_int64(st, 0);
            out.root = column_text(st, 1);
            out.name = column_text(st, 2);
            out.added_at = sqlite3_column_int64(st, 3);
            out.last_scan = sqlite3_column_int64(st, 4);
            found = true;
        }
        sqlite3_finalize(st);
    }
    return found;
}

bool Database::touch_library_scan(int64_t lib_id, int64_t when) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "UPDATE libraries SET last_scan=?1 WHERE id=?2", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, when);
        sqlite3_bind_int64(st, 2, lib_id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

// -------------------------------------------------------------------- books

UpsertResult Database::upsert_book(const Book& b, int64_t scan_token) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    UpsertResult r;
    if (!db_) return r;

    bool found = false;
    int64_t old_mtime = 0, old_size = 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT id,mtime,size FROM books WHERE lib_id=?1 AND path=?2", -1, &st,
                           nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, b.lib_id);
        bind_text(st, 2, b.path);
        if (sqlite3_step(st) == SQLITE_ROW) {
            found = true;
            r.id = sqlite3_column_int64(st, 0);
            old_mtime = sqlite3_column_int64(st, 1);
            old_size = sqlite3_column_int64(st, 2);
        }
        sqlite3_finalize(st);
    }

    r.is_new = !found;
    r.changed = !found || old_mtime != b.mtime || old_size != b.size;
    r.needs_cover = r.changed;
    const int64_t now = (int64_t)time(nullptr);

    if (!found) {
        const char* sql =
            "INSERT INTO books(lib_id,path,rel_dir,kind,title,ext,size,mtime,added_at,updated_at,"
            "scan_token,pages,pages_indexed,has_cover,cover_key) "
            "VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,0,0,0,'')";
        if (sqlite3_prepare_v2(db_, sql, -1, &st, nullptr) == SQLITE_OK) {
            sqlite3_bind_int64(st, 1, b.lib_id);
            bind_text(st, 2, b.path);
            bind_text(st, 3, b.rel_dir);
            sqlite3_bind_int(st, 4, (int)b.kind);
            bind_text(st, 5, b.title);
            bind_text(st, 6, b.ext);
            sqlite3_bind_int64(st, 7, b.size);
            sqlite3_bind_int64(st, 8, b.mtime);
            sqlite3_bind_int64(st, 9, now);
            sqlite3_bind_int64(st, 10, now);
            sqlite3_bind_int64(st, 11, scan_token);
            if (sqlite3_step(st) == SQLITE_DONE)
                r.id = sqlite3_last_insert_rowid(db_);
            sqlite3_finalize(st);
        }
    } else if (r.changed) {
        const char* sql =
            "UPDATE books SET lib_id=?1,rel_dir=?2,kind=?3,title=?4,ext=?5,size=?6,mtime=?7,"
            "updated_at=?8,scan_token=?9,pages=0,pages_indexed=0,has_cover=0,cover_key='' "
            "WHERE id=?10";
        if (sqlite3_prepare_v2(db_, sql, -1, &st, nullptr) == SQLITE_OK) {
            sqlite3_bind_int64(st, 1, b.lib_id);
            bind_text(st, 2, b.rel_dir);
            sqlite3_bind_int(st, 3, (int)b.kind);
            bind_text(st, 4, b.title);
            bind_text(st, 5, b.ext);
            sqlite3_bind_int64(st, 6, b.size);
            sqlite3_bind_int64(st, 7, b.mtime);
            sqlite3_bind_int64(st, 8, now);
            sqlite3_bind_int64(st, 9, scan_token);
            sqlite3_bind_int64(st, 10, r.id);
            sqlite3_step(st);
            sqlite3_finalize(st);
        }
    } else {
        // Touch only ~ keeps the row alive for this scan with a cheap write.
        if (sqlite3_prepare_v2(db_, "UPDATE books SET scan_token=?1,lib_id=?2,rel_dir=?3 WHERE id=?4",
                               -1, &st, nullptr) == SQLITE_OK) {
            sqlite3_bind_int64(st, 1, scan_token);
            sqlite3_bind_int64(st, 2, b.lib_id);
            bind_text(st, 3, b.rel_dir);
            sqlite3_bind_int64(st, 4, r.id);
            sqlite3_step(st);
            sqlite3_finalize(st);
        }
    }
    return r;
}

bool Database::get_book(int64_t id, Book& out) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    std::string sql = std::string("SELECT ") + kBookColumns + kBookJoin + " WHERE books.id=?1";
    sqlite3_stmt* st = nullptr;
    bool found = false;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, id);
        if (sqlite3_step(st) == SQLITE_ROW) {
            out = read_book(st);
            found = true;
        }
        sqlite3_finalize(st);
    }
    return found;
}

int64_t Database::count_books(int64_t lib_id, const std::string& search,
                              const std::string& dir_rel, bool recursive,
                              bool fav_only, int read_state) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    FilterBuild f = make_filter(dir_rel, recursive, search, fav_only, read_state, lib_id);
    std::string sql = "SELECT COUNT(*) FROM books" + f.where;

    sqlite3_stmt* st = nullptr;
    int64_t n = 0;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) == SQLITE_OK) {
        int idx = 1;
        bind_filter(st, f, lib_id, idx);
        if (sqlite3_step(st) == SQLITE_ROW) n = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    return n;
}

std::vector<Book> Database::page_books(int64_t lib_id, const std::string& search,
                                       SortKey sort, bool desc, int64_t offset, int limit,
                                       const std::string& dir_rel, bool recursive,
                                       bool fav_only, int read_state) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<Book> out;
    if (!db_ || limit <= 0) return out;
    if (offset < 0) offset = 0;

    FilterBuild f = make_filter(dir_rel, recursive, search, fav_only, read_state, lib_id);
    std::string sql = std::string("SELECT ") + kBookColumns + kBookJoin + f.where;
    sql += " ORDER BY ";
    sql += sort_column(sort);
    sql += desc ? " DESC" : " ASC";
    if (sort != SortKey::Added) sql += ", id ASC";

    const int limit_idx = f.next_index;
    const int offset_idx = f.next_index + 1;
    sql += " LIMIT ?" + std::to_string(limit_idx) + " OFFSET ?" + std::to_string(offset_idx);

    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) != SQLITE_OK) return out;
    int idx = 1;
    bind_filter(st, f, lib_id, idx);
    sqlite3_bind_int(st, limit_idx, limit);
    sqlite3_bind_int64(st, offset_idx, offset);

    while (sqlite3_step(st) == SQLITE_ROW) out.push_back(read_book(st));
    sqlite3_finalize(st);
    return out;
}

// ------------------------------------------------------------------ 文件名

std::string Database::book_file_name(int64_t id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return {};
    sqlite3_stmt* st = nullptr;
    std::string out;
    if (sqlite3_prepare_v2(db_, "SELECT title,ext FROM books WHERE id=?1", -1, &st, nullptr) ==
        SQLITE_OK) {
        sqlite3_bind_int64(st, 1, id);
        if (sqlite3_step(st) == SQLITE_ROW) {
            out = column_text(st, 0);
            const std::string ext = column_text(st, 1);
            if (!ext.empty()) {
                out += '.';
                out += ext;
            }
        }
        sqlite3_finalize(st);
    }
    return out;
}

bool Database::update_pages(int64_t id, int pages) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "UPDATE books SET pages=?1,pages_indexed=1 WHERE id=?2",
                           -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(st, 1, pages);
        sqlite3_bind_int64(st, 2, id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::update_cover(int64_t id, const std::string& cover_key) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "UPDATE books SET cover_key=?1,has_cover=?2 WHERE id=?3",
                           -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, cover_key);
        sqlite3_bind_int(st, 2, cover_key.empty() ? 0 : 1);
        sqlite3_bind_int64(st, 3, id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::update_pages_and_cover(int64_t id, int pages, const std::string& cover_key) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "UPDATE books SET pages=?1,pages_indexed=1,cover_key=?2,has_cover=?3 WHERE id=?4",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int(st, 1, pages);
        bind_text(st, 2, cover_key);
        sqlite3_bind_int(st, 3, cover_key.empty() ? 0 : 1);
        sqlite3_bind_int64(st, 4, id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

int64_t Database::delete_unseen(int64_t lib_id, int64_t scan_token) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "DELETE FROM books WHERE lib_id=?1 AND scan_token<>?2",
                           -1, &st, nullptr) != SQLITE_OK)
        return 0;
    sqlite3_bind_int64(st, 1, lib_id);
    sqlite3_bind_int64(st, 2, scan_token);
    sqlite3_step(st);
    sqlite3_finalize(st);
    const int64_t removed = sqlite3_changes(db_);
    if (removed > 0) cleanup_orphan_rows();
    return removed;
}

// The subtree predicate reuses the range-scan convention of make_filter()
// ('/' is 0x2F, '0' is 0x30), so the (lib_id, rel_dir) index stays in play.
int64_t Database::delete_books_in_subtree(int64_t lib_id, const std::string& rel) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_,
            "DELETE FROM books WHERE lib_id=?1 AND (rel_dir=?2 OR "
            "(rel_dir>=?3 AND rel_dir<?4))",
            -1, &st, nullptr) != SQLITE_OK)
        return 0;
    sqlite3_bind_int64(st, 1, lib_id);
    bind_text(st, 2, rel);
    bind_text(st, 3, rel + "/");
    bind_text(st, 4, rel + "0");
    sqlite3_step(st);
    sqlite3_finalize(st);
    const int64_t removed = sqlite3_changes(db_);
    if (removed > 0) cleanup_orphan_rows();
    return removed;
}

int64_t Database::delete_unseen_in_subtree(int64_t lib_id, int64_t scan_token,
                                           const std::string& rel) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_,
            "DELETE FROM books WHERE lib_id=?1 AND scan_token<>?2 AND (rel_dir=?3 OR "
            "(rel_dir>=?4 AND rel_dir<?5))",
            -1, &st, nullptr) != SQLITE_OK)
        return 0;
    sqlite3_bind_int64(st, 1, lib_id);
    sqlite3_bind_int64(st, 2, scan_token);
    bind_text(st, 3, rel);
    bind_text(st, 4, rel + "/");
    bind_text(st, 5, rel + "0");
    sqlite3_step(st);
    sqlite3_finalize(st);
    const int64_t removed = sqlite3_changes(db_);
    if (removed > 0) cleanup_orphan_rows();
    return removed;
}

int64_t Database::delete_book_by_path(int64_t lib_id, const std::string& path) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "DELETE FROM books WHERE lib_id=?1 AND path=?2",
                           -1, &st, nullptr) != SQLITE_OK)
        return 0;
    sqlite3_bind_int64(st, 1, lib_id);
    bind_text(st, 2, path);
    sqlite3_step(st);
    sqlite3_finalize(st);
    const int64_t removed = sqlite3_changes(db_);
    if (removed > 0) cleanup_orphan_rows();
    return removed;
}

void Database::cleanup_orphan_rows() {
    // Keep the user-state tables in sync with the surviving book rows.
    exec("DELETE FROM book_meta WHERE book_id NOT IN (SELECT id FROM books);");
    exec("DELETE FROM bookmarks  WHERE book_id NOT IN (SELECT id FROM books);");
    exec("DELETE FROM book_tags  WHERE book_id NOT IN (SELECT id FROM books);");
    exec("DELETE FROM book_text_archive   WHERE book_id NOT IN (SELECT id FROM books);");
    exec("DELETE FROM book_translate_pref WHERE book_id NOT IN (SELECT id FROM books);");
}

int64_t Database::next_scan_token() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 1;
    int64_t token = 1;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT value FROM meta WHERE key='scan_token'", -1, &st, nullptr) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) token = sqlite3_column_int64(st, 0) + 1;
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO meta(key,value) VALUES('scan_token',?1) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            -1, &st, nullptr) == SQLITE_OK) {
        std::string v = std::to_string(token);
        bind_text(st, 1, v);
        sqlite3_step(st);
        sqlite3_finalize(st);
    }
    return token;
}

int64_t Database::total_books() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    int64_t n = 0;
    if (sqlite3_prepare_v2(db_, "SELECT COUNT(*) FROM books", -1, &st, nullptr) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) n = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    return n;
}

// ------------------------------------------------- per-book state (schema v4)

bool Database::update_library_name(int64_t lib_id, const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "UPDATE libraries SET name=?1 WHERE id=?2", -1, &st, nullptr) ==
        SQLITE_OK) {
        bind_text(st, 1, name);
        sqlite3_bind_int64(st, 2, lib_id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::library_stats(int64_t lib_id, int64_t& books, int64_t& folders, int& max_depth) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    books = folders = 0;
    max_depth = 0;
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT COUNT(*) FROM books WHERE lib_id=?1", -1, &st, nullptr) ==
        SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        if (sqlite3_step(st) == SQLITE_ROW) books = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_, "SELECT COUNT(*),COALESCE(MAX(depth),0) FROM dirs WHERE lib_id=?1",
                           -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        if (sqlite3_step(st) == SQLITE_ROW) {
            folders = sqlite3_column_int64(st, 0);
            max_depth = sqlite3_column_int(st, 1);
        }
        sqlite3_finalize(st);
    }
    return true;
}

int Database::delete_book(int64_t id) { return delete_books({id}); }

int Database::delete_books(const std::vector<int64_t>& ids) {
    if (ids.empty()) return 0;
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    begin();
    auto run = [&](const char* sql) {
        sqlite3_stmt* s = nullptr;
        if (sqlite3_prepare_v2(db_, sql, -1, &s, nullptr) != SQLITE_OK) return;
        for (int64_t id : ids) {
            sqlite3_bind_int64(s, 1, id);
            sqlite3_step(s);
            sqlite3_reset(s);
        }
        sqlite3_finalize(s);
    };
    run("DELETE FROM bookmarks WHERE book_id=?1");
    run("DELETE FROM book_tags WHERE book_id=?1");
    run("DELETE FROM book_meta  WHERE book_id=?1");
    run("DELETE FROM book_text_archive   WHERE book_id=?1");
    run("DELETE FROM book_translate_pref WHERE book_id=?1");
    int removed = 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "DELETE FROM books WHERE id=?1", -1, &st, nullptr) == SQLITE_OK) {
        for (int64_t id : ids) {
            sqlite3_bind_int64(st, 1, id);
            sqlite3_step(st);
            removed += sqlite3_changes(db_);
            sqlite3_reset(st);
        }
        sqlite3_finalize(st);
    }
    commit();
    return removed;
}

bool Database::get_meta(int64_t book_id, BookMeta& out) {
    out = BookMeta{};
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool found = false;
    if (sqlite3_prepare_v2(db_,
            "SELECT favorite,read_state,last_page,last_read_at FROM book_meta WHERE book_id=?1",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        if (sqlite3_step(st) == SQLITE_ROW) {
            out.favorite = sqlite3_column_int(st, 0) != 0;
            out.read_state = sqlite3_column_int(st, 1);
            out.last_page = sqlite3_column_int(st, 2);
            out.last_read_at = sqlite3_column_int64(st, 3);
            found = true;
        }
        sqlite3_finalize(st);
    }
    return found;
}

bool Database::set_favorite(int64_t book_id, bool favorite) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO book_meta(book_id,favorite) VALUES(?1,?2) "
            "ON CONFLICT(book_id) DO UPDATE SET favorite=excluded.favorite",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, favorite ? 1 : 0);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::set_read_state(int64_t book_id, int read_state) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO book_meta(book_id,read_state) VALUES(?1,?2) "
            "ON CONFLICT(book_id) DO UPDATE SET read_state=excluded.read_state",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, read_state);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::set_progress(int64_t book_id, int page) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO book_meta(book_id,last_page,last_read_at) VALUES(?1,?2,?3) "
            "ON CONFLICT(book_id) DO UPDATE SET last_page=excluded.last_page,"
            "last_read_at=excluded.last_read_at",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, page);
        sqlite3_bind_int64(st, 3, (sqlite3_int64)time(nullptr));
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

// ------------------------------------------------------------------ bookmarks

bool Database::add_bookmark(int64_t book_id, int page, const std::string& label) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO bookmarks(book_id,page,label,created_at) VALUES(?1,?2,?3,?4) "
            "ON CONFLICT(book_id,page) DO UPDATE SET label=excluded.label",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, page);
        bind_text(st, 3, label);
        sqlite3_bind_int64(st, 4, (sqlite3_int64)time(nullptr));
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::remove_bookmark(int64_t bookmark_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "DELETE FROM bookmarks WHERE id=?1", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, bookmark_id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::remove_bookmark_at(int64_t book_id, int page) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "DELETE FROM bookmarks WHERE book_id=?1 AND page=?2", -1, &st,
                           nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, page);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::has_bookmark(int64_t book_id, int page) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool found = false;
    if (sqlite3_prepare_v2(db_, "SELECT 1 FROM bookmarks WHERE book_id=?1 AND page=?2", -1, &st,
                           nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, page);
        found = sqlite3_step(st) == SQLITE_ROW;
        sqlite3_finalize(st);
    }
    return found;
}

std::vector<BookmarkRow> Database::bookmarks_of(int64_t book_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<BookmarkRow> out;
    if (!db_) return out;
    std::string sql = std::string(kBookmarkSelect) + " WHERE bm.book_id=?1 ORDER BY bm.page";
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        while (sqlite3_step(st) == SQLITE_ROW) out.push_back(read_bookmark(st));
        sqlite3_finalize(st);
    }
    return out;
}

std::vector<BookmarkRow> Database::all_bookmarks() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<BookmarkRow> out;
    if (!db_) return out;
    std::string sql = std::string(kBookmarkSelect) + " ORDER BY bm.book_id, bm.page";
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, sql.c_str(), -1, &st, nullptr) == SQLITE_OK) {
        while (sqlite3_step(st) == SQLITE_ROW) out.push_back(read_bookmark(st));
        sqlite3_finalize(st);
    }
    return out;
}

// ----------------------------------------------------------------------- tags

bool Database::assign_tag(int64_t book_id, const std::string& name) {
    if (name.empty()) return false;
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "INSERT INTO tags(name) VALUES(?1) ON CONFLICT(name) DO NOTHING",
                           -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, name);
        sqlite3_step(st);
        sqlite3_finalize(st);
    }
    int64_t tag_id = 0;
    if (sqlite3_prepare_v2(db_, "SELECT id FROM tags WHERE name=?1", -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, name);
        if (sqlite3_step(st) == SQLITE_ROW) tag_id = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    if (!tag_id) return false;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "INSERT INTO book_tags(book_id,tag_id) VALUES(?1,?2) "
                                "ON CONFLICT DO NOTHING",
                           -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int64(st, 2, tag_id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::unassign_tag(int64_t book_id, const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "DELETE FROM book_tags WHERE book_id=?1 AND tag_id=(SELECT id FROM tags WHERE name=?2)",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        bind_text(st, 2, name);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::set_book_tags(int64_t book_id, const std::vector<std::string>& tags) {
    {
        std::lock_guard<std::recursive_mutex> lock(mtx_);
        if (!db_) return false;
        sqlite3_stmt* st = nullptr;
        if (sqlite3_prepare_v2(db_, "DELETE FROM book_tags WHERE book_id=?1", -1, &st, nullptr) ==
            SQLITE_OK) {
            sqlite3_bind_int64(st, 1, book_id);
            sqlite3_step(st);
            sqlite3_finalize(st);
        }
    }
    for (const auto& t : tags) assign_tag(book_id, t);
    return true;
}

std::vector<std::string> Database::tags_of(int64_t book_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<std::string> out;
    if (!db_) return out;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_,
            "SELECT t.name FROM tags t JOIN book_tags bt ON bt.tag_id=t.id "
            "WHERE bt.book_id=?1 ORDER BY t.name",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        while (sqlite3_step(st) == SQLITE_ROW) out.push_back(column_text(st, 0));
        sqlite3_finalize(st);
    }
    return out;
}

std::vector<std::string> Database::all_tags() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<std::string> out;
    if (!db_) return out;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT name FROM tags ORDER BY name", -1, &st, nullptr) ==
        SQLITE_OK) {
        while (sqlite3_step(st) == SQLITE_ROW) out.push_back(column_text(st, 0));
        sqlite3_finalize(st);
    }
    return out;
}

bool Database::delete_tag(const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    bool ok = false;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_,
            "DELETE FROM book_tags WHERE tag_id=(SELECT id FROM tags WHERE name=?1)", -1, &st,
            nullptr) == SQLITE_OK) {
        bind_text(st, 1, name);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    if (sqlite3_prepare_v2(db_, "DELETE FROM tags WHERE name=?1", -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, name);
        ok = sqlite3_step(st) == SQLITE_DONE && ok;
        sqlite3_finalize(st);
    }
    return ok;
}

// ------------------------------------------ schema v5: translation archive

bool Database::put_text_archive(int64_t book_id, const std::string& payload, int64_t bytes) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO book_text_archive(book_id,payload,bytes,last_access) VALUES(?1,?2,?3,?4) "
            "ON CONFLICT(book_id) DO UPDATE SET payload=excluded.payload,bytes=excluded.bytes,"
            "last_access=excluded.last_access",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_blob(st, 2, payload.data(), (int)payload.size(), SQLITE_TRANSIENT);
        sqlite3_bind_int64(st, 3, bytes);
        sqlite3_bind_int64(st, 4, (sqlite3_int64)time(nullptr));
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::get_text_archive(int64_t book_id, std::string& payload, int64_t* bytes,
                                int64_t* last_access) {
    payload.clear();
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool found = false;
    if (sqlite3_prepare_v2(db_, "SELECT payload,bytes,last_access FROM book_text_archive WHERE book_id=?1",
                           -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        if (sqlite3_step(st) == SQLITE_ROW) {
            const void* p = sqlite3_column_blob(st, 0);
            const int n = sqlite3_column_bytes(st, 0);
            if (p && n > 0) payload.assign(static_cast<const char*>(p), (size_t)n);
            if (bytes) *bytes = sqlite3_column_int64(st, 1);
            if (last_access) *last_access = sqlite3_column_int64(st, 2);
            found = true;
        }
        sqlite3_finalize(st);
    }
    return found;
}

bool Database::touch_text_archive(int64_t book_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "UPDATE book_text_archive SET last_access=?2 WHERE book_id=?1", -1, &st,
                           nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int64(st, 2, (sqlite3_int64)time(nullptr));
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::delete_text_archive(int64_t book_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "DELETE FROM book_text_archive WHERE book_id=?1", -1, &st, nullptr) ==
        SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

int64_t Database::clear_text_archive() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    int64_t n = 0;
    if (sqlite3_prepare_v2(db_, "DELETE FROM book_text_archive", -1, &st, nullptr) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_DONE) n = sqlite3_changes64(db_);
        sqlite3_finalize(st);
    }
    return n;
}

int64_t Database::text_archive_total_bytes() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    int64_t n = 0;
    if (sqlite3_prepare_v2(db_, "SELECT COALESCE(SUM(bytes),0) FROM book_text_archive", -1, &st,
                           nullptr) == SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) n = sqlite3_column_int64(st, 0);
        sqlite3_finalize(st);
    }
    return n;
}

int64_t Database::evict_text_archive(int64_t max_bytes) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    std::vector<std::pair<int64_t, int64_t>> rows;
    int64_t total = 0;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT book_id,bytes FROM book_text_archive ORDER BY last_access ASC",
                           -1, &st, nullptr) == SQLITE_OK) {
        while (sqlite3_step(st) == SQLITE_ROW) {
            const int64_t id = sqlite3_column_int64(st, 0);
            const int64_t b = sqlite3_column_int64(st, 1);
            rows.emplace_back(id, b);
            total += b;
        }
        sqlite3_finalize(st);
    }
    if (total <= max_bytes) return 0;
    int64_t freed = 0;
    for (const auto& r : rows) {
        if (total <= max_bytes) break;
        if (delete_text_archive(r.first)) {
            total -= r.second;
            freed += r.second;
        }
    }
    return freed;
}

bool Database::set_book_translate_enabled(int64_t book_id, bool enabled) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO book_translate_pref(book_id,enabled,updated_at) VALUES(?1,?2,?3) "
            "ON CONFLICT(book_id) DO UPDATE SET enabled=excluded.enabled,updated_at=excluded.updated_at",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        sqlite3_bind_int(st, 2, enabled ? 1 : 0);
        sqlite3_bind_int64(st, 3, (sqlite3_int64)time(nullptr));
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    return ok;
}

bool Database::get_book_translate_enabled(int64_t book_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool enabled = false;
    if (sqlite3_prepare_v2(db_, "SELECT enabled FROM book_translate_pref WHERE book_id=?1", -1, &st,
                           nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, book_id);
        if (sqlite3_step(st) == SQLITE_ROW) enabled = sqlite3_column_int(st, 0) != 0;
        sqlite3_finalize(st);
    }
    return enabled;
}

std::vector<std::pair<std::string, std::string>> Database::glossary() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<std::pair<std::string, std::string>> out;
    if (!db_) return out;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_, "SELECT src,dst FROM glossary ORDER BY src", -1, &st, nullptr) ==
        SQLITE_OK) {
        while (sqlite3_step(st) == SQLITE_ROW)
            out.emplace_back(column_text(st, 0), column_text(st, 1));
        sqlite3_finalize(st);
    }
    return out;
}

bool Database::set_glossary_term(const std::string& src, const std::string& dst) {
    if (src.empty()) return false;
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO glossary(src,dst) VALUES(?1,?2) ON CONFLICT(src) DO UPDATE SET dst=excluded.dst",
            -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, src);
        bind_text(st, 2, dst);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    if (ok) bump_meta_rev(db_, "glossary_rev");
    return ok;
}

bool Database::remove_glossary_term(const std::string& src) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;
    sqlite3_stmt* st = nullptr;
    bool ok = false;
    if (sqlite3_prepare_v2(db_, "DELETE FROM glossary WHERE src=?1", -1, &st, nullptr) == SQLITE_OK) {
        bind_text(st, 1, src);
        ok = sqlite3_step(st) == SQLITE_DONE;
        sqlite3_finalize(st);
    }
    if (ok) bump_meta_rev(db_, "glossary_rev");
    return ok;
}

int64_t Database::glossary_rev() {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return 0;
    sqlite3_stmt* st = nullptr;
    int64_t v = 0;
    if (sqlite3_prepare_v2(db_, "SELECT value FROM meta WHERE key='glossary_rev'", -1, &st, nullptr) ==
        SQLITE_OK) {
        if (sqlite3_step(st) == SQLITE_ROW) v = std::atoll(column_text(st, 0).c_str());
        sqlite3_finalize(st);
    }
    return v;
}

// --------------------------------------------------------------- folder tree

void Database::rebuild_dirs(int64_t lib_id) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return;

    // 1) direct book count per relative directory
    std::unordered_map<std::string, int64_t> direct;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_,
            "SELECT rel_dir, COUNT(*) FROM books WHERE lib_id=?1 AND rel_dir<>'' GROUP BY rel_dir",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        while (sqlite3_step(st) == SQLITE_ROW) {
            std::string rel = column_text(st, 0);
            if (!rel.empty()) direct[rel] = sqlite3_column_int64(st, 1);
        }
        sqlite3_finalize(st);
    }

    // 2) expand to ancestors, then accumulate totals bottom-up
    std::unordered_map<std::string, int64_t> total;
    total.reserve(direct.size() * 2);
    for (const auto& kv : direct) {
        total[kv.first] = kv.second;
        std::string p = kv.first;
        while (!(p = parent_of(p)).empty())
            total.emplace(p, 0); // emplace keeps an existing value
    }

    std::vector<std::string> by_depth;
    by_depth.reserve(total.size());
    for (const auto& kv : total) by_depth.push_back(kv.first);
    std::sort(by_depth.begin(), by_depth.end(), [](const std::string& a, const std::string& b) {
        return depth_of(a) > depth_of(b); // deepest first
    });
    for (const auto& rel : by_depth) {
        std::string p = parent_of(rel);
        if (!p.empty()) {
            auto it = total.find(p);
            if (it != total.end()) it->second += total[rel];
        }
    }

    // 3) replace the table contents
    begin();
    if (sqlite3_prepare_v2(db_, "DELETE FROM dirs WHERE lib_id=?1", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        sqlite3_step(st);
        sqlite3_finalize(st);
    }

    sqlite3_stmt* ins = nullptr;
    if (sqlite3_prepare_v2(db_,
            "INSERT INTO dirs(lib_id,parent_rel,name,rel,book_count,total_count,depth) "
            "VALUES(?1,?2,?3,?4,?5,?6,?7)",
            -1, &ins, nullptr) == SQLITE_OK) {
        for (const auto& rel : by_depth) {
            const int64_t direct_n = [&] {
                auto it = direct.find(rel);
                return it == direct.end() ? (int64_t)0 : it->second;
            }();
            sqlite3_bind_int64(ins, 1, lib_id);
            bind_text(ins, 2, parent_of(rel));
            bind_text(ins, 3, base_name(rel));
            bind_text(ins, 4, rel);
            sqlite3_bind_int64(ins, 5, direct_n);
            sqlite3_bind_int64(ins, 6, total[rel]);
            sqlite3_bind_int(ins, 7, depth_of(rel));
            sqlite3_step(ins);
            sqlite3_reset(ins);
        }
        sqlite3_finalize(ins);
    }
    commit();
    log_info("dirs rebuilt for lib " + std::to_string(lib_id) + ": " +
             std::to_string(total.size()) + " folders");
}

std::vector<DirRow> Database::child_dirs(int64_t lib_id, const std::string& parent_rel) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    std::vector<DirRow> out;
    if (!db_) return out;
    sqlite3_stmt* st = nullptr;
    if (sqlite3_prepare_v2(db_,
            "SELECT id,lib_id,parent_rel,name,rel,book_count,total_count,depth "
            "FROM dirs WHERE lib_id=?1 AND parent_rel=?2 ORDER BY name",
            -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_int64(st, 1, lib_id);
        bind_text(st, 2, parent_rel);
        while (sqlite3_step(st) == SQLITE_ROW) {
            DirRow d;
            d.id = sqlite3_column_int64(st, 0);
            d.lib_id = sqlite3_column_int64(st, 1);
            d.parent_rel = column_text(st, 2);
            d.name = column_text(st, 3);
            d.rel = column_text(st, 4);
            d.book_count = sqlite3_column_int64(st, 5);
            d.total_count = sqlite3_column_int64(st, 6);
            d.depth = sqlite3_column_int(st, 7);
            out.push_back(std::move(d));
        }
        sqlite3_finalize(st);
    }
    return out;
}

bool Database::backfill_rel_dirs(std::string* err) {
    std::lock_guard<std::recursive_mutex> lock(mtx_);
    if (!db_) return false;

    std::vector<LibraryRow> libs = libraries();
    for (const auto& lib : libs) {
        std::filesystem::path root(paths::from_utf8(lib.root));
        int64_t last_id = 0;
        for (;;) {
            std::vector<std::pair<int64_t, std::string>> batch;
            sqlite3_stmt* st = nullptr;
            if (sqlite3_prepare_v2(db_,
                    "SELECT id,path FROM books WHERE lib_id=?1 AND id>?2 ORDER BY id LIMIT 2000",
                    -1, &st, nullptr) != SQLITE_OK)
                break;
            sqlite3_bind_int64(st, 1, lib.id);
            sqlite3_bind_int64(st, 2, last_id);
            while (sqlite3_step(st) == SQLITE_ROW) {
                int64_t id = sqlite3_column_int64(st, 0);
                std::string path = column_text(st, 1);
                batch.emplace_back(id, path);
                last_id = id;
            }
            sqlite3_finalize(st);
            if (batch.empty()) break;

            begin();
            sqlite3_stmt* up = nullptr;
            if (sqlite3_prepare_v2(db_, "UPDATE books SET rel_dir=?1 WHERE id=?2", -1, &up, nullptr) ==
                SQLITE_OK) {
                for (auto& [id, path] : batch) {
                    std::string rel = paths::relative_dir_utf8(paths::from_utf8(path),
                                                               root);
                    bind_text(up, 1, rel);
                    sqlite3_bind_int64(up, 2, id);
                    sqlite3_step(up);
                    sqlite3_reset(up);
                }
                sqlite3_finalize(up);
            }
            commit();
        }
    }
    if (err) err->clear();
    return true;
}

bool Database::begin() { return exec("BEGIN IMMEDIATE;"); }
bool Database::commit() { return exec("COMMIT;"); }
bool Database::rollback() { return exec("ROLLBACK;"); }

} // namespace cs
