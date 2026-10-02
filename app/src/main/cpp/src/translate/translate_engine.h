#pragma once
// Async translation engine used by the reader.
//
// One worker thread pulls prioritized page jobs, reads the page bytes through a
// caller-supplied reader (the archive), reuses the text archive when it is still
// valid, otherwise runs phase① (analyze) then phase② (render), and keeps the
// rendered RGBA pages in a bounded RAM cache. It NEVER writes an image to disk.
#include "core/database.h"
#include "core/types.h"
#include "image/image_util.h"
#include "translate/translate_client.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace cs {

class Settings;

namespace translate {

enum class PageState { None, Queued, Busy, Ready, Failed };

class TranslateEngine {
public:
    explicit TranslateEngine(Database& db);
    ~TranslateEngine();
    TranslateEngine(const TranslateEngine&) = delete;
    TranslateEngine& operator=(const TranslateEngine&) = delete;

    // Reads config from `settings` (translate_enabled / service url / langs /
    // prefetch / cache budget / archive cap).
    void configure(const Settings& settings);
    TranslateClient& client() { return client_; }
    void set_base_url(const std::string& url) { client_.set_base_url(url); }

    bool enabled() const { return enabled_.load(); }
    void set_enabled(bool v) { enabled_ = v; }
    // False once a request has failed; App uses it to respawn a dead sidecar.
    bool service_up() const { return service_up_.load(); }

    // Page bytes provider (usually a shared_ptr<IArchive> read). Must be safe to
    // call from the worker thread; the reader lambda is responsible for bounds.
    void set_page_reader(std::function<bool(int, std::vector<uint8_t>&)> f);

    // Book session.
    void open_book(const Book& b);
    void close_book();
    int64_t book_id() const { return book_id_.load(); }
    const Book& book() const { return book_; }

    // Focus + submission (both non-blocking).
    void set_focus(int page);
    int focus() const { return focus_.load(); }
    void request_page(int page);
    void request_window(int page, int back, int fwd);
    // Requests the focus window using the configured prefetch settings.
    void request_around(int page);
    // Re-renders a single page (drops its cached image and re-queues it).
    void retranslate_page(int page);

    // Queries (UI thread).
    PageState state(int page) const;
    // Returns the rendered page when Ready (shared, ref-counted).
    std::shared_ptr<const ImageRGBA> get(int page) const;
    void invalidate_cache(int page);

    // Clears the RAM cache (keeps the text archive in the DB).
    void clear_cache();

    // Archive helpers (used by menus).
    bool has_archive() const;
    bool clear_archive();           // removes the DB row for this book
    int64_t archive_total_bytes();
    int64_t evict_archive_to(int64_t max_bytes);
    const std::string& pipeline() const { return pipeline_; }
    std::string last_error() const;

    // Re-translate: drop the archive and re-request the focus page.
    void retranslate_current_book();

private:
    struct Job {
        int page = 0;
        int priority = 0;
    };

    void worker();
    void push_locked(Job j);
    void prune_cache_locked(int keep_page);
    // Probes /health with a short timeout and opens the book session when the
    // sidecar has become reachable. Cheap no-op once the session is up.
    void ensure_session();

    Database& db_;
    TranslateClient client_;
    std::function<bool(int, std::vector<uint8_t>&)> page_reader_;

    std::atomic<bool> enabled_{false};
    std::atomic<bool> quit_{false};
    std::atomic<int> focus_{0};
    std::atomic<int64_t> book_id_{0};
    // True once /health succeeded for the current book (sidecar reachable).
    std::atomic<bool> service_up_{false};
    // Focus the worker still has to report to the sidecar (-1 = nothing to do).
    // session_focus/session_open are HTTP calls and must never run on the UI
    // thread, or every page turn would hitch while the socket talks.
    std::atomic<int> pending_focus_{-1};
    Book book_;

    std::thread thread_;
    mutable std::mutex mtx_;
    std::condition_variable cv_;
    std::deque<Job> queue_;
    std::map<int, PageState> states_;
    std::map<int, std::shared_ptr<ImageRGBA>> cache_;
    size_t cache_bytes_ = 0;
    size_t cache_budget_ = 512ull * 1024 * 1024;
    std::string source_lang_ = "日本語";
    std::string target_lang_ = "简体中文";
    int prefetch_fwd_ = 4;
    int prefetch_back_ = 1;
    std::string pipeline_;
    mutable std::mutex err_mtx_;
    std::string last_error_;
};

} // namespace translate
} // namespace cs
