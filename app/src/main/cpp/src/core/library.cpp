#include "core/library.h"

#include "util/logger.h"
#include "util/path_util.h"
#include "vfs/vfs.h"

#include <algorithm>
#include <chrono>
#include <filesystem>

namespace cs {

namespace {
int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// 内容层面的“永久”失败：重试多少次都一样（与网络无关）。
// 其余（IO/网络/超时/会话池忙）都按瞬态处理，可冷却重试。
bool is_permanent_cover_error(const std::string& e) {
    static const char* kPerm[] = {
        "no images",  // zip/rar/目录：包里没有图片（"no images in zip" 等）
        "not a valid zip",
        "cannot open archive",  // unrar：包本身打不开
        "RAR support not compiled in",
        "unsupported container",
        "需要缓存目录",
    };
    for (const char* p : kPerm)
        if (e.find(p) != std::string::npos) return true;
    return false;
}

constexpr int64_t kTransientCooldownMs = 60 * 1000;       // 瞬态失败后的重试冷却
constexpr int64_t kInflightSafetyMs = 10 * 60 * 1000;     // inflight 保险丝：超时视为陈旧可重投
constexpr size_t kCoverQueueMax = 600;                    // 待生成队列上限（防病态膨胀）
}  // namespace

LibraryManager::LibraryManager(Settings& settings, ThreadPool& pool, std::string data_dir_utf8)
    : settings_(settings),
      pool_(pool),
      // NOTE: copy, never move — `thumbs_` below needs the same string, and
      // members initialise in declaration order.
      data_dir_(data_dir_utf8),
      thumbs_(data_dir_utf8 + "/covers", (size_t)settings.get_int("cover_memory_mb", 256) * 1024 * 1024) {
    thumb_size_ = settings.get_int("thumb_size", 400);
    // 封面并发上限：默认取线程池的一半、封顶 3（扫描/阅读页解码共用同一池，
    // 封面是“可延迟的后台活”，不该占满整个池）；可用 cover_max_inflight 覆盖。
    const int def_cap = std::clamp((int)pool_.size() / 2, 1, 3);
    cover_cap_ = std::clamp((int)settings.get_int("cover_max_inflight", def_cap), 1, 6);
    log_info("cover_max_inflight=" + std::to_string(cover_cap_) +
             " (pool=" + std::to_string(pool_.size()) + ")");
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

LibraryManager::CoverGenResult LibraryManager::generate_cover(const Book& b, ImageRGBA& out) const {
    CoverGenResult r;
    std::string err;
    auto arch = open_archive_path(b.path, &err);
    if (!arch) {
        log_warn("cover: open failed " + b.path + " : " + err);
        r.reason = err.empty() ? "open failed" : err;
        r.permanent = is_permanent_cover_error(r.reason);
        return r;
    }
    if (arch->count() == 0) {
        log_warn("cover: empty archive " + b.path);
        r.reason = "empty archive";
        r.permanent = true;
        return r;
    }

    // 前几页里找第一张可解码的图。按“可解码”而非“可读出字节”判断：
    // 包首常有非图片条目（url/txt/损坏页），读得出但解不了不应算整本无封面。
    std::vector<uint8_t> bytes;
    const size_t tries = std::min<size_t>(arch->count(), 4);
    bool any_bytes = false;
    for (size_t i = 0; i < tries; ++i) {
        bytes.clear();
        if (!arch->read(i, bytes) || bytes.empty()) continue;
        any_bytes = true;
        ImageRGBA img;
        if (!decode_image(bytes.data(), bytes.size(), img)) continue;
        out = downscale_to_fit(img, thumb_size_, thumb_resample_);
        if (!out.empty()) {
            r.ok = true;
            return r;
        }
    }
    if (any_bytes) {
        r.reason = "decode failed";  // 内容坏了（前 4 页没有一张能解码）
        r.permanent = true;
    } else {
        r.reason = "read failed";    // 读不出字节：更像传输/缓存问题，可重试
        r.permanent = false;
    }
    log_warn("cover: " + r.reason + " " + b.path);
    return r;
}

// 状态机核心（须持 state_mtx_）：按归档状态决定是否提交生成任务，返回操作后的状态。
//   Inflight           → 已在跑（或刚提交），稍后轮询
//   Failed             → 不可用（永久失败，或瞬态失败冷却未到）
//   None               → 让位：并发已满，调用方可稍后再试（不排队、不阻塞）
LibraryManager::CoverState LibraryManager::schedule_cover_locked(const Book& b,
                                                                 const std::string& key,
                                                                 int64_t now) {
    auto& e = cover_state_[key];
    switch (e.state) {
        case CoverState::Inflight:
            if (now - e.stamp_ms <= kInflightSafetyMs) return CoverState::Inflight;
            // 保险丝熔断：任务因半死连接长时间未归位 → 视为陈旧，允许重投一次
            log_warn("cover: inflight 超时(" + std::to_string(now - e.stamp_ms) +
                     "ms)，重新提交 " + b.path);
            e.state = CoverState::None;
            break;
        case CoverState::Failed:
            if (e.permanent) return CoverState::Failed;
            if (now - e.stamp_ms < kTransientCooldownMs) return CoverState::Failed;  // 冷却中
            e.state = CoverState::None;  // 冷却已过 → 重试
            break;
        case CoverState::Ready:  // 状态 Ready 但缓存未命中（被清/换算法）→ 重新生成
        case CoverState::None:
            break;
    }

    if (cover_inflight_ >= cover_cap_) return CoverState::None;  // 让位

    const uint64_t token = ++cover_token_;
    e.state = CoverState::Inflight;
    e.permanent = false;
    e.token = token;
    e.stamp_ms = now;
    e.epoch = vfs::smb_epoch();
    ++cover_inflight_;
    const uint64_t epoch0 = e.epoch;
    pool_.submit([this, b, key, token, epoch0]() {
        CoverGenResult res;
        ImageRGBA img;
        if (thumbs_.try_get(key)) {
            res.ok = true;  // 磁盘/内存已有成品（warm 预热大多走这条）：无需再连 SMB
        } else {
            res = generate_cover(b, img);
            if (res.ok) {
                thumbs_.store(key, img, 85);
                db_.update_cover(b.id, key);
            } else if (res.permanent && vfs::smb_epoch() != epoch0) {
                // 本次生成期间 SMB 断过线：坏会话说出来的“内容错误”不可信，按可重试处理
                log_warn("cover: 断线期间的失败改判可重试 " + b.path + " (" + res.reason + ")");
                res.permanent = false;
            }
        }
        std::lock_guard<std::mutex> lk(state_mtx_);
        if (cover_inflight_ > 0) --cover_inflight_;
        auto it = cover_state_.find(key);
        if (it != cover_state_.end() && it->second.state == CoverState::Inflight &&
            it->second.token == token) {
            it->second.stamp_ms = now_ms();
            if (res.ok) {
                it->second.state = CoverState::Ready;
                it->second.permanent = false;
            } else {
                it->second.state = CoverState::Failed;
                it->second.permanent = res.permanent;
            }
        }
        pump_warm_locked();  // 接力：腾出的并发额度立刻给下一个待办
    });
    return CoverState::Inflight;
}

void LibraryManager::enqueue_cover_locked(const Book& b, const std::string& key, bool front) {
    if (cover_queued_.size() >= kCoverQueueMax) return;  // 病态输入：丢弃，轮询会再试
    if (!cover_queued_.insert(key).second) return;       // 去重：已在队列
    if (front)
        cover_queue_.push_front(b);
    else
        cover_queue_.push_back(b);
}

void LibraryManager::pump_warm_locked() {
    while (cover_inflight_ < cover_cap_ && !cover_queue_.empty()) {
        Book b = std::move(cover_queue_.front());
        cover_queue_.pop_front();
        const std::string key = ThumbnailCache::make_key(b);
        cover_queued_.erase(key);
        if (schedule_cover_locked(b, key, now_ms()) == CoverState::None) {
            // 防御：额度又被占满（正常不会走到），放回队首等下一次接力
            cover_queued_.insert(key);
            cover_queue_.push_front(std::move(b));
            break;
        }
    }
}

void LibraryManager::forget_key_locked(const std::string& key) {
    cover_state_.erase(key);
    if (cover_queued_.erase(key)) {
        cover_queue_.erase(std::remove_if(cover_queue_.begin(), cover_queue_.end(),
                                          [&](const Book& q) {
                                              return ThumbnailCache::make_key(q) == key;
                                          }),
                           cover_queue_.end());
    }
}

LibraryManager::CoverPoll LibraryManager::cover_poll(const Book& b) {
    const std::string key = ThumbnailCache::make_key(b);
    const int64_t now = now_ms();

    // 快路径：内存/磁盘已有成品（不持锁，避免磁盘 IO 串行化所有轮询）
    if (auto t = thumbs_.try_get(key)) {
        std::lock_guard<std::mutex> lk(state_mtx_);
        auto& e = cover_state_[key];
        if (e.state != CoverState::Ready) {
            e.state = CoverState::Ready;
            e.permanent = false;
            e.stamp_ms = now;
        }
        return {2, t};
    }

    std::lock_guard<std::mutex> lk(state_mtx_);
    const CoverState st = schedule_cover_locked(b, key, now);
    if (st == CoverState::Failed) return {3, nullptr};
    if (st == CoverState::None) {
        // 并发已满：不阻塞、不排队抢跑，登记到队首（屏上单元格优先），等接力
        enqueue_cover_locked(b, key, /*front=*/true);
    }
    return {1, nullptr};
}

ThumbPtr LibraryManager::cover(const Book& b) { return cover_poll(b).thumb; }

int LibraryManager::retry_failed_covers() {
    std::lock_guard<std::mutex> lk(state_mtx_);
    int n = 0;
    for (auto it = cover_state_.begin(); it != cover_state_.end();) {
        if (it->second.state == CoverState::Failed) {
            // 状态清成 None：下一次轮询即可重新提交；已在队列里的条目
            // 由 pump/接力按 None 正常调度（无需动队列）。
            it = cover_state_.erase(it);
            ++n;
        } else {
            ++it;
        }
    }
    if (n > 0)
        log_info("retry_failed_covers: 重置失败标记 " + std::to_string(n) + " 条");
    return n;
}

bool LibraryManager::cover_ready(const Book& b) {
    const std::string key = ThumbnailCache::make_key(b);
    if (thumbs_.try_get(key)) return true;
    std::lock_guard<std::mutex> lk(state_mtx_);
    auto it = cover_state_.find(key);
    return it != cover_state_.end() && it->second.state == CoverState::Ready;
}

void LibraryManager::warm_covers(int64_t lib_id, int max_books) {
    auto rows = db_.page_books(lib_id, "", SortKey::Added, false, 0, max_books);
    std::lock_guard<std::mutex> lk(state_mtx_);
    for (const auto& b : rows) {
        const std::string key = ThumbnailCache::make_key(b);
        auto it = cover_state_.find(key);
        if (it != cover_state_.end()) {
            if (it->second.state == CoverState::Inflight) continue;
            if (it->second.state == CoverState::Failed && it->second.permanent) continue;
        }
        enqueue_cover_locked(b, key, /*front=*/false);
    }
    pump_warm_locked();  // 接力式启动：只占满 cover_cap_ 个额度，完成一个补一个
}

void LibraryManager::forget_cover(const Book& b) {
    const std::string key = ThumbnailCache::make_key(b);
    thumbs_.erase(key);
    {
        std::lock_guard<std::mutex> lk(state_mtx_);
        forget_key_locked(key);
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
        forget_key_locked(key);
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
