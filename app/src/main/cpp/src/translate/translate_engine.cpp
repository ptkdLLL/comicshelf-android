#include "translate/translate_engine.h"

#include "core/settings.h"
#include "util/logger.h"

#include <cstdlib>

namespace cs::translate {

TranslateEngine::TranslateEngine(Database& db) : db_(db), client_(db) {
    thread_ = std::thread([this]() { worker(); });
}

TranslateEngine::~TranslateEngine() {
    quit_ = true;
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void TranslateEngine::configure(const Settings& settings) {
    enabled_ = settings.get_bool("translate_enabled", false);
    // In spawn mode the sidecar listens on service_port, so the URL must be
    // derived from it. Using translate_service_url here (default 8674) silently
    // disconnected a sidecar that App had already started on another port.
    if (settings.get("service_mode", "spawn") == "spawn") {
        const int port = settings.get_int("service_port", 8674);
        client_.set_base_url("http://127.0.0.1:" + std::to_string(port));
    } else {
        client_.set_base_url(settings.get("translate_service_url", "http://127.0.0.1:8674"));
    }
    source_lang_ = settings.get("source_lang", "日本語");
    target_lang_ = settings.get("target_lang", "简体中文");
    prefetch_fwd_ = settings.get_int("prefetch_fwd", 4);
    prefetch_back_ = settings.get_int("prefetch_back", 1);
    const int cache_mb = settings.get_int("cache_mb", 512);
    cache_budget_ = (size_t)(cache_mb > 0 ? cache_mb : 512) * 1024 * 1024;
}

void TranslateEngine::set_page_reader(std::function<bool(int, std::vector<uint8_t>&)> f) {
    std::lock_guard<std::mutex> lk(mtx_);
    page_reader_ = std::move(f);
}

void TranslateEngine::open_book(const Book& b) {
    close_book();
    book_ = b;
    book_id_ = b.id;
    service_up_ = false;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        queue_.clear();
        states_.clear();
        cache_.clear();
        cache_bytes_ = 0;
    }
    if (enabled_) {
        log_info("translate: session open book=" + std::to_string(b.id) + " url=" +
                 client_.base_url());
        // Do NOT probe/open the session here: this runs on the UI thread and a
        // cold sidecar would freeze the window. The worker does it.
        pending_focus_ = 0;
    }
    cv_.notify_one();
}

void TranslateEngine::ensure_session() {
    if (service_up_.load() || book_id_ == 0) return;
    std::string err;
    // Short probe: opening a book must never stall the UI while the sidecar is
    // still cold-starting (loading OCR/LLM models can take a while).
    if (!client_.health(&pipeline_, &err, 400)) {
        log_warn("translate: /health not ready yet: " + err);
        return;
    }
    service_up_ = true;
    log_info("translate: service ready, opening session book=" + std::to_string(book_id_.load()));
    client_.session_open(book_id_.load(), source_lang_, target_lang_, &err);
}

void TranslateEngine::close_book() {
    // Short timeout: closing a book must not hang the UI thread if the sidecar
    // has gone away or is busy loading models.
    if (book_id_ != 0 && service_up_.load())
        client_.session_close(book_id_, 200);
    {
        std::lock_guard<std::mutex> lk(mtx_);
        queue_.clear();
        states_.clear();
        cache_.clear();
        cache_bytes_ = 0;
    }
    book_id_ = 0;
    service_up_ = false;
    book_ = Book{};
}

void TranslateEngine::set_focus(int page) {
    // UI thread: record only. The worker turns this into an HTTP call.
    focus_ = page;
    pending_focus_ = page;
}

void TranslateEngine::request_window(int page, int back, int fwd) {
    if (!enabled_ || book_id_ == 0) return;
    set_focus(page);
    request_page(page);
    for (int d = 1; d <= fwd; ++d) request_page(page + d);
    for (int d = 1; d <= back; ++d) request_page(page - d);
}

void TranslateEngine::request_around(int page) {
    request_window(page, prefetch_back_, prefetch_fwd_);
}

void TranslateEngine::retranslate_page(int page) {
    invalidate_cache(page);
    request_page(page);
}

void TranslateEngine::request_page(int page) {
    if (!enabled_ || book_id_ == 0 || page < 0) return;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        auto it = states_.find(page);
        if (it != states_.end() && it->second != PageState::None && it->second != PageState::Failed)
            return; // already queued / busy / ready
        states_[page] = PageState::Queued;
        Job j;
        j.page = page;
        const int d = page - focus_.load();
        j.priority = (d == 0) ? 0 : (d > 0 ? d : 1000 + (-d));
        push_locked(std::move(j));
    }
    cv_.notify_one();
}

void TranslateEngine::push_locked(Job j) {
    auto it = queue_.begin();
    while (it != queue_.end() && it->priority <= j.priority) ++it;
    queue_.insert(it, std::move(j));
}

PageState TranslateEngine::state(int page) const {
    std::lock_guard<std::mutex> lk(mtx_);
    auto it = states_.find(page);
    return it == states_.end() ? PageState::None : it->second;
}

std::shared_ptr<const ImageRGBA> TranslateEngine::get(int page) const {
    std::lock_guard<std::mutex> lk(mtx_);
    auto it = cache_.find(page);
    return it == cache_.end() ? nullptr : it->second;
}

void TranslateEngine::invalidate_cache(int page) {
    std::lock_guard<std::mutex> lk(mtx_);
    auto it = cache_.find(page);
    if (it != cache_.end()) {
        cache_bytes_ -= it->second->bytes();
        cache_.erase(it);
    }
    states_[page] = PageState::None;
}

void TranslateEngine::clear_cache() {
    std::lock_guard<std::mutex> lk(mtx_);
    cache_.clear();
    cache_bytes_ = 0;
}

bool TranslateEngine::has_archive() const {
    return book_id_ != 0 && const_cast<TranslateClient&>(client_).has_archive(book_id_);
}

bool TranslateEngine::clear_archive() {
    if (book_id_ == 0) return false;
    clear_cache();
    bool ok = client_.clear_archive(book_id_);
    std::lock_guard<std::mutex> lk(mtx_);
    states_.clear();
    return ok;
}

int64_t TranslateEngine::archive_total_bytes() { return client_.archive_total_bytes(); }

int64_t TranslateEngine::evict_archive_to(int64_t max_bytes) {
    return client_.evict_to(max_bytes);
}

std::string TranslateEngine::last_error() const {
    std::lock_guard<std::mutex> lk(err_mtx_);
    return last_error_;
}

void TranslateEngine::retranslate_current_book() {
    if (book_id_ == 0) return;
    clear_archive();
    request_page(focus_.load());
}

void TranslateEngine::prune_cache_locked(int keep_page) {
    while (cache_bytes_ > cache_budget_ && cache_.size() > 1) {
        auto victim = cache_.begin();
        for (auto it = cache_.begin(); it != cache_.end(); ++it) {
            if (it->first == keep_page) continue;
            const int dv = std::abs(it->first - focus_.load());
            const int db_ = std::abs(victim->first - focus_.load());
            if (victim->first == keep_page || dv > db_) victim = it;
        }
        if (victim == cache_.end() || victim->first == keep_page) break;
        cache_bytes_ -= victim->second->bytes();
        states_[victim->first] = PageState::None;
        cache_.erase(victim);
    }
}

void TranslateEngine::worker() {
    for (;;) {
        Job job;
        {
            std::unique_lock<std::mutex> lk(mtx_);
            cv_.wait(lk, [&]() { return quit_.load() || !queue_.empty(); });
            if (quit_.load()) return;
            job = queue_.front();
            queue_.pop_front();
            if (book_id_ == 0) {
                states_[job.page] = PageState::None;
                continue;
            }
            states_[job.page] = PageState::Busy;
        }

        // Drop jobs that drifted far from the focus (page was skipped).
        const int span = (prefetch_fwd_ > prefetch_back_ ? prefetch_fwd_ : prefetch_back_) + 2;
        if (std::abs(job.page - focus_.load()) > span) {
            std::lock_guard<std::mutex> lk(mtx_);
            if (states_[job.page] == PageState::Busy) states_[job.page] = PageState::None;
            continue;
        }

        const int64_t bid = book_id_.load();
        if (bid == 0) continue;

        // All sidecar chatter happens here, never on the UI thread.
        ensure_session();
        const int want_focus = pending_focus_.exchange(-1);
        if (want_focus >= 0 && service_up_.load())
            client_.session_focus(bid, want_focus);

        // 1) page bytes via the archive reader.
        std::vector<uint8_t> bytes;
        {
            std::function<bool(int, std::vector<uint8_t>&)> reader;
            {
                std::lock_guard<std::mutex> lk(mtx_);
                reader = page_reader_;
            }
            if (!reader || !reader(job.page, bytes) || bytes.empty()) {
                std::lock_guard<std::mutex> lk(mtx_);
                states_[job.page] = PageState::Failed;
                continue;
            }
        }
        const std::string img_hash = TranslateClient::hash_bytes(bytes);

        // 2) reuse a still-valid archive record? (img_hash + pipeline + n)
        PageTexts pt;
        bool have_texts = false;
        if (client_.load_archive(bid, job.page, pt)) {
            if (pt.img_hash == img_hash && pt.valid() &&
                (pipeline_.empty() || pt.pipeline == pipeline_))
                have_texts = true;
        }

        // 3) phase① if needed.
        if (!have_texts) {
            std::string err;
            if (!client_.analyze(bid, job.page, img_hash, bytes, pt, &err)) {
                {
                    std::lock_guard<std::mutex> lk(err_mtx_);
                    last_error_ = err;
                }
                log_warn("translate: analyze failed p" + std::to_string(job.page) + ": " + err);
                // Drop the "reachable" flag so the next job re-probes /health and
                // re-opens the session (covers a sidecar that exited on idle).
                service_up_ = false;
                std::lock_guard<std::mutex> lk(mtx_);
                states_[job.page] = PageState::Failed;
                continue;
            }
            client_.save_archive(bid, job.page, pt);
        }

        // 4) phase② render (RAM only).
        ImageRGBA img;
        std::string rerr;
        if (!client_.render(pt, bytes, img, &rerr) || img.empty()) {
            {
                std::lock_guard<std::mutex> lk(err_mtx_);
                last_error_ = rerr;
            }
            log_warn("translate: render failed p" + std::to_string(job.page) + ": " + rerr);
            service_up_ = false;
            std::lock_guard<std::mutex> lk(mtx_);
            states_[job.page] = PageState::Failed;
            continue;
        }

        const int out_w = img.w;
        const int out_h = img.h;
        const size_t blocks = pt.texts.size();
        {
            std::lock_guard<std::mutex> lk(mtx_);
            auto sp = std::make_shared<ImageRGBA>(std::move(img));
            cache_bytes_ += sp->bytes();
            cache_[job.page] = sp;
            states_[job.page] = PageState::Ready;
            prune_cache_locked(job.page);
        }
        log_info("translate: page ready p" + std::to_string(job.page) + " (" +
                 std::to_string(out_w) + "x" + std::to_string(out_h) + ", " +
                 std::to_string(blocks) + " blocks)");
    }
}

} // namespace cs::translate
