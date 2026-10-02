#pragma once
// High-level translation client + text-archive store.
//
// Owns the HTTP calls to the sidecar (or the mock server) and the persistent
// archive in `library.db` (schema v5). NOTHING here ever writes an image to
// disk: pages live only in RAM; the archive holds translated TEXT only.
#include "core/database.h"
#include "image/image_util.h"

#include <cstdint>
#include <string>
#include <vector>

namespace cs::translate {

// One page's archive record (see docs/TRANSLATION_DESIGN.md §4.1).
struct PageTexts {
    int page = 0;
    int n = 0;
    std::string img_hash;
    std::string pipeline;
    std::vector<std::string> texts;
    bool valid() const { return n > 0 && (int)texts.size() == n; }
};

class TranslateClient {
public:
    explicit TranslateClient(Database& db) : db_(db) {}

    void set_base_url(const std::string& url) { base_url_ = url; }
    const std::string& base_url() const { return base_url_; }

    // Cheap content hash for a page (FNV-1a hex) - used as img_hash.
    static std::string hash_bytes(const std::vector<uint8_t>& data);

    // ---- service ---------------------------------------------------------
    // GET /health; fills `pipeline` (config hash) when available.
    // `timeout_ms` is kept short by callers on the UI thread so a missing
    // service never stalls the interface.
    bool health(std::string* pipeline = nullptr, std::string* err = nullptr, int timeout_ms = 5000);
    bool session_open(int64_t book_id, const std::string& source_lang,
                      const std::string& target_lang, std::string* err = nullptr);
    bool session_close(int64_t book_id, int timeout_ms = 5000);
    bool session_focus(int64_t book_id, int page);

    // ---- phase ①: analyze + translate (page bytes -> texts) --------------
    bool analyze(int64_t book_id, int page, const std::string& img_hash,
                 const std::vector<uint8_t>& page_bytes, PageTexts& out, std::string* err = nullptr);

    // ---- phase ②: render texts onto the page (RAM only) ------------------
    bool render(const PageTexts& in, const std::vector<uint8_t>& page_bytes, ImageRGBA& out,
                std::string* err = nullptr);

    // ---- archive (DB, text only) ----------------------------------------
    bool load_archive(int64_t book_id, int page, PageTexts& out);
    bool save_archive(int64_t book_id, int page, const PageTexts& in);
    bool clear_archive(int64_t book_id);
    bool has_archive(int64_t book_id);
    int64_t archive_total_bytes();
    // Evicts least-recently-used books until the total is <= max_bytes.
    int64_t evict_to(int64_t max_bytes);

    static bool decode_raw_rgba(const std::string& body, ImageRGBA& out, std::string* err = nullptr);

private:
    Database& db_;
    std::string base_url_;
    std::string pipeline_;
};

} // namespace cs::translate
