#include "core/library.h"

#include "util/logger.h"
#include "util/path_util.h"
#include "vfs/vfs.h"

#include <algorithm>
#include <filesystem>

namespace cs {

LibraryManager::LibraryManager(Settings& settings, ThreadPool& pool, std::string data_dir_utf8)
    : settings_(settings),
      pool_(pool),
      // NOTE: copy, never move — `thumbs_` below needs the same string, and
      // members initialise in declaration order.
      data_dir_(data_dir_utf8),
      thumbs_(data_dir_utf8 + "/covers", (size_t)settings.get_int("cover_memory_mb", 256) * 1024 * 1024) {
    thumb_size_ = settings.get_int("thumb_size", 400);
}

LibraryManager::~LibraryManager() {
    if (scanner_) scanner_->cancel();
    if (scanner_) scanner_->wait();
    pool_.wait_idle();
}

bool LibraryManager::init(std::string* err) {
    std::error_code ec;
    std::filesystem::create_directories(paths::from_utf8(data_dir_), ec);
    std::filesystem::create_directories(
        paths::from_utf8(thumbs_.covers_dir()), ec);
    log_info("data_dir=" + data_dir_ + " covers=" + thumbs_.covers_dir() +
             " ec=" + ec.message() +
             " covers_exists=" + (std::filesystem::exists(paths::from_utf8(thumbs_.covers_dir())) ? "1" : "0"));

    const std::string db_path = data_dir_ + "/library.db";
    if (!db_.open(db_path, err)) return false;
    if (!writer_.open(db_path, err)) return false;

    scanner_ = std::make_unique<Scanner>(writer_, pool_, progress_);
    // SMB 上的 RAR 需要整档缓存目录（UnRAR 要真实句柄）
    set_archive_cache_dir(data_dir_ + "/smbcache");
    log_info("library initialized at " + db_path);
    return true;
}

// ------------------------------------------------------------------ libraries

std::vector<LibraryRow> LibraryManager::libraries() { return db_.libraries(); }

int64_t LibraryManager::add_library_root(const std::string& utf8_root, bool start_scan) {
    std::string name;
    if (vfs::is_smb_path(utf8_root)) {
        // "smb://host/share/dir" → 末级目录名（或共享名）
        std::string s = utf8_root;
        while (s.size() > 6 && s.back() == '/') s.pop_back();
        auto slash = s.rfind('/');
        name = (slash == std::string::npos) ? s : s.substr(slash + 1);
        if (name == "smb:" || name.empty()) name = s;
    } else {
        std::filesystem::path p(paths::from_utf8(utf8_root));
        name = paths::path_to_utf8(p.filename());
    }
    if (name.empty()) name = utf8_root;
    int64_t id = db_.add_library(utf8_root, name);
    if (id && start_scan) this->start_scan(id);
    return id;
}

void LibraryManager::remove_library(int64_t lib_id) {
    db_.remove_library(lib_id);
}

bool LibraryManager::update_library_name(int64_t lib_id, const std::string& name) {
    return db_.update_library_name(lib_id, name);
}

bool LibraryManager::library_stats(int64_t lib_id, int64_t& books, int64_t& folders,
                                   int& max_depth) {
    return db_.library_stats(lib_id, books, folders, max_depth);
}

void LibraryManager::start_scan(int64_t lib_id) {
    LibraryRow row;
    if (!db_.get_library(lib_id, row)) return;
    ScanOptions opt;
    opt.include_folders = settings_.get_bool("include_folders", true);
    opt.workers = (int)settings_.get_int("scan_workers", 0);
    opt.full = true;
    scanner_->start(lib_id, row.root, opt);
}

void LibraryManager::refresh_subtree(int64_t lib_id, const std::string& rel) {
    LibraryRow row;
    if (!db_.get_library(lib_id, row)) return;
    ScanOptions opt;
    opt.include_folders = settings_.get_bool("include_folders", true);
    opt.workers = (int)settings_.get_int("scan_workers", 0);
    opt.full = false;
    opt.start_rel = rel;
    scanner_->start(lib_id, row.root, opt);
}

int64_t LibraryManager::remove_subtree(int64_t lib_id, const std::string& rel) {
    const int64_t n = writer_.delete_books_in_subtree(lib_id, rel);
    if (n > 0) writer_.rebuild_dirs(lib_id);
    return n;
}

void LibraryManager::cancel_scan() {
    if (scanner_) scanner_->cancel();
}

void LibraryManager::pause_scan(bool paused) {
    if (scanner_) scanner_->pause(paused);
}

// -------------------------------------------------------------------- queries

int64_t LibraryManager::count(int64_t lib_id, const std::string& search,
                              const std::string& dir_rel, bool recursive, bool fav_only,
                              int read_state) {
    return db_.count_books(lib_id, search, dir_rel, recursive, fav_only, read_state);
}

std::vector<Book> LibraryManager::page(int64_t lib_id, const std::string& search,
                                       SortKey sort, bool desc, int64_t offset, int limit,
                                       const std::string& dir_rel, bool recursive, bool fav_only,
                                       int read_state) {
    return db_.page_books(lib_id, search, sort, desc, offset, limit, dir_rel, recursive, fav_only,
                          read_state);
}

std::string LibraryManager::book_file_name(int64_t id) {
    return db_.book_file_name(id);
}

std::vector<DirRow> LibraryManager::child_dirs(int64_t lib_id, const std::string& parent_rel) {
    return db_.child_dirs(lib_id, parent_rel);
}

void LibraryManager::rebuild_dirs(int64_t lib_id) { db_.rebuild_dirs(lib_id); }

// --------------------------------------------------------------------- covers

void LibraryManager::set_thumb_resample(Resample r) {
    thumb_resample_ = r;
    const char* v = "box";
    if (r == Resample::Bilinear) v = "bilinear";
    else if (r == Resample::CatmullRom) v = "catmullrom";
    ThumbnailCache::set_variant(v);
}

bool LibraryManager::generate_cover(const Book& b, ImageRGBA& out) const {
    std::string err;
    auto arch = open_archive_path(b.path, &err);
    if (!arch || arch->count() == 0) {
        log_warn("cover: open failed " + b.path + " : " + err);
        return false;
    }

    std::vector<uint8_t> bytes;
    const size_t tries = std::min<size_t>(arch->count(), 4);
    for (size_t i = 0; i < tries; ++i) {
        bytes.clear();
        if (arch->read(i, bytes) && !bytes.empty()) break;
    }
    if (bytes.empty()) return false;

    ImageRGBA img;
    if (!decode_image(bytes.data(), bytes.size(), img)) return false;
    out = downscale_to_fit(img, thumb_size_, thumb_resample_);
    return !out.empty();
}

ThumbPtr LibraryManager::cover(const Book& b) {
    const std::string key = ThumbnailCache::make_key(b);
    static std::atomic<int> g_cover_calls{0};
    if (g_cover_calls.fetch_add(1) < 3) log_info("cover() first calls for " + b.title);

    {
        std::lock_guard<std::mutex> lk(state_mtx_);
        auto it = cover_state_.find(key);
        if (it != cover_state_.end() &&
            (it->second == CoverState::Failed || it->second == CoverState::Inflight))
            return nullptr;
    }

    if (auto t = thumbs_.try_get(key)) return t; // memory hit, or disk hit when Ready

    {
        std::lock_guard<std::mutex> lk(state_mtx_);
        auto& st = cover_state_[key];
        if (st == CoverState::Inflight || st == CoverState::Failed) return nullptr;
        // Deliberately do NOT trust Book::has_cover here: it only records that a
        // cover was generated at some point. The cache file (keyed by path +
        // mtime + size + resampling variant) is authoritative, so a miss means
        // "regenerate" - otherwise changing the thumbnail algorithm would leave
        // every existing book stuck on "(no cover)".
        st = CoverState::Inflight;
    }

    pool_.submit([this, b, key]() {
        ImageRGBA img;
        bool ok = generate_cover(b, img);
        if (ok) {
            thumbs_.store(key, img, 85);
            db_.update_cover(b.id, key);
        }
        std::lock_guard<std::mutex> lk(state_mtx_);
        cover_state_[key] = ok ? CoverState::Ready : CoverState::Failed;
    });
    return nullptr;
}

bool LibraryManager::cover_ready(const Book& b) {
    const std::string key = ThumbnailCache::make_key(b);
    if (thumbs_.try_get(key)) return true;
    std::lock_guard<std::mutex> lk(state_mtx_);
    auto it = cover_state_.find(key);
    return it != cover_state_.end() && it->second == CoverState::Ready;
}

void LibraryManager::warm_covers(int64_t lib_id, int max_books) {
    auto rows = db_.page_books(lib_id, "", SortKey::Added, false, 0, max_books);
    for (const auto& b : rows) cover(b);
}

void LibraryManager::forget_cover(const Book& b) {
    const std::string key = ThumbnailCache::make_key(b);
    thumbs_.erase(key);
    {
        std::lock_guard<std::mutex> lk(state_mtx_);
        cover_state_.erase(key);
    }
    db_.update_cover(b.id, "");
}


void LibraryManager::clear_all_covers() {
    thumbs_.clear_all();
    log_info("covers cleared (all)");
}

// ------------------------------------------------------------ user state / meta

BookMeta LibraryManager::meta(int64_t book_id) {
    BookMeta m;
    db_.get_meta(book_id, m);
    return m;
}

void LibraryManager::set_favorite(int64_t book_id, bool favorite) {
    db_.set_favorite(book_id, favorite);
}

void LibraryManager::set_read_state(int64_t book_id, int read_state) {
    db_.set_read_state(book_id, read_state);
}

void LibraryManager::save_progress(int64_t book_id, int page) {
    db_.set_progress(book_id, page);
}

bool LibraryManager::delete_book(int64_t book_id) {
    Book b;
    if (db_.get_book(book_id, b)) {
        const std::string key = ThumbnailCache::make_key(b);
        thumbs_.erase(key);
        std::lock_guard<std::mutex> lk(state_mtx_);
        cover_state_.erase(key);
    }
    return db_.delete_book(book_id) > 0;
}

// ------------------------------------------------------------------ bookmarks

std::vector<BookmarkRow> LibraryManager::bookmarks(int64_t book_id) {
    return db_.bookmarks_of(book_id);
}

std::vector<BookmarkRow> LibraryManager::all_bookmarks() { return db_.all_bookmarks(); }

bool LibraryManager::add_bookmark(int64_t book_id, int page, const std::string& label) {
    return db_.add_bookmark(book_id, page, label);
}

bool LibraryManager::remove_bookmark(int64_t bookmark_id) {
    return db_.remove_bookmark(bookmark_id);
}

bool LibraryManager::has_bookmark(int64_t book_id, int page) {
    return db_.has_bookmark(book_id, page);
}

// ----------------------------------------------------------------------- tags

std::vector<std::string> LibraryManager::all_tags() { return db_.all_tags(); }

std::vector<std::string> LibraryManager::book_tags(int64_t book_id) {
    return db_.tags_of(book_id);
}

void LibraryManager::set_book_tags(int64_t book_id, const std::vector<std::string>& tags) {
    db_.set_book_tags(book_id, tags);
}

bool LibraryManager::delete_tag(const std::string& name) { return db_.delete_tag(name); }

// --------------------------------------------------------------------- reading

std::unique_ptr<IArchive> LibraryManager::open_book(const Book& b, std::string* err) {
    return open_archive_path(b.path, err);
}

int LibraryManager::index_pages(const Book& b) {
    std::string err;
    auto arch = open_archive_path(b.path, &err);
    if (!arch) return 0;
    int n = (int)arch->count();
    db_.update_pages(b.id, n);
    return n;
}

void LibraryManager::request_index_pages(Book b) {
    if (b.pages_indexed) return;
    pool_.submit([this, b]() { index_pages(b); });
}

} // namespace cs
