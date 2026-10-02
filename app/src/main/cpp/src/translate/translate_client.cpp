#include "translate/translate_client.h"

#include "translate/http_client.h"
#include "util/json_min.h"
#include "util/logger.h"
#include "util/path_util.h"

#include <cstdio>

#include "miniz.h"

namespace cs::translate {
namespace {

std::string to_hex(uint64_t v) {
    char buf[24];
    std::snprintf(buf, sizeof(buf), "%016llx", (unsigned long long)v);
    return buf;
}

// 1-byte marker + payload: 'Z' = zlib, 'R' = raw.
std::string pack(const std::string& in) {
    if (in.empty()) return std::string("R");
    mz_ulong bound = mz_compressBound((mz_ulong)in.size());
    std::string tmp;
    tmp.resize(bound);
    mz_ulong len = bound;
    if (mz_compress2((unsigned char*)tmp.data(), &len, (const unsigned char*)in.data(),
                     (mz_ulong)in.size(), 6) == MZ_OK) {
        tmp.resize(len);
        return std::string("Z") + tmp;
    }
    return std::string("R") + in;
}

bool unpack(const std::string& in, std::string& out) {
    if (in.empty()) return false;
    if (in[0] == 'R') {
        out = in.substr(1);
        return true;
    }
    if (in[0] != 'Z') return false;
    const unsigned char* src = (const unsigned char*)in.data() + 1;
    const size_t src_len = in.size() - 1;
    size_t cap = src_len * 6 + 1024;
    for (int attempt = 0; attempt < 6; ++attempt) {
        out.resize(cap);
        mz_ulong dst_len = (mz_ulong)cap;
        int rc = mz_uncompress((unsigned char*)out.data(), &dst_len, src, (mz_ulong)src_len);
        if (rc == MZ_OK) {
            out.resize(dst_len);
            return true;
        }
        if (rc != MZ_BUF_ERROR) break;
        cap *= 2;
    }
    return false;
}

std::string serialize_book(const std::vector<PageTexts>& pages) {
    json::Value arr = json::Value::make_array();
    for (const auto& p : pages) {
        json::Value o = json::Value::make_object();
        o.set("page", (int64_t)p.page);
        o.set("n", (int64_t)p.n);
        o.set("img_hash", p.img_hash);
        o.set("pipeline", p.pipeline);
        json::Value t = json::Value::make_array();
        for (const auto& s : p.texts) t.push_back(s);
        o.set("texts", t);
        arr.push_back(o);
    }
    return arr.dump();
}

bool deserialize_book(const std::string& text, std::vector<PageTexts>& out) {
    json::Value root;
    std::string err;
    if (!json::parse(text, root, &err) || !root.is_array()) return false;
    for (const auto& e : root.as_array()) {
        PageTexts p;
        p.page = (int)e["page"].as_int();
        p.n = (int)e["n"].as_int();
        p.img_hash = e["img_hash"].as_string();
        p.pipeline = e["pipeline"].as_string();
        const json::Value& t = e["texts"];
        if (t.is_array())
            for (const auto& s : t.as_array()) p.texts.push_back(s.as_string());
        out.push_back(std::move(p));
    }
    return true;
}

} // namespace

std::string TranslateClient::hash_bytes(const std::vector<uint8_t>& data) {
    uint64_t h = 1469598103934665603ull; // FNV-1a 64
    for (uint8_t b : data) {
        h ^= (uint64_t)b;
        h *= 1099511628211ull;
    }
    return to_hex(h);
}

// ------------------------------------------------------------------ service

bool TranslateClient::health(std::string* pipeline, std::string* err, int timeout_ms) {
    http::Request req;
    req.method = "GET";
    req.url = base_url_ + "/health";
    req.timeout_ms = timeout_ms > 0 ? timeout_ms : 5000;
    http::Response r = http::request(req);
    if (!r.ok) {
        if (err) *err = r.error;
        return false;
    }
    json::Value v;
    std::string jerr;
    if (r.status != 200 || !json::parse(r.body, v, &jerr)) {
        if (err) *err = "bad /health response";
        return false;
    }
    if (pipeline) *pipeline = v["pipeline"].as_string();
    if (!v["pipeline"].as_string().empty()) pipeline_ = v["pipeline"].as_string();
    return true;
}

bool TranslateClient::session_open(int64_t book_id, const std::string& source_lang,
                                   const std::string& target_lang, std::string* err) {
    json::Value v = json::Value::make_object();
    v.set("session_key", std::string("book:") + std::to_string(book_id));
    v.set("source_lang", source_lang);
    v.set("target_lang", target_lang);
    http::Request req;
    req.method = "POST";
    req.url = base_url_ + "/session/open";
    req.content_type = "application/json";
    req.body = v.dump();
    req.timeout_ms = 15000;
    http::Response r = http::request(req);
    if (!r.ok) {
        if (err) *err = r.error;
        return false;
    }
    return r.status == 200;
}

bool TranslateClient::session_close(int64_t book_id, int timeout_ms) {
    json::Value v = json::Value::make_object();
    v.set("session_key", std::string("book:") + std::to_string(book_id));
    http::Request req;
    req.method = "POST";
    req.url = base_url_ + "/session/close";
    req.content_type = "application/json";
    req.body = v.dump();
    req.timeout_ms = timeout_ms > 0 ? timeout_ms : 5000;
    http::Response r = http::request(req);
    return r.ok && r.status == 200;
}

bool TranslateClient::session_focus(int64_t book_id, int page) {
    json::Value v = json::Value::make_object();
    v.set("session_key", std::string("book:") + std::to_string(book_id));
    v.set("page", (int64_t)page);
    http::Request req;
    req.method = "POST";
    req.url = base_url_ + "/session/focus";
    req.content_type = "application/json";
    req.body = v.dump();
    req.timeout_ms = 5000;
    http::Response r = http::request(req);
    return r.ok && r.status == 200;
}

// ------------------------------------------------------------- phase ① / ②

bool TranslateClient::analyze(int64_t book_id, int page, const std::string& img_hash,
                              const std::vector<uint8_t>& page_bytes, PageTexts& out,
                              std::string* err) {
    http::Request req;
    req.method = "POST";
    req.url = base_url_ + "/analyze_async";
    req.content_type = "application/octet-stream";
    req.body.assign((const char*)page_bytes.data(), page_bytes.size());
    req.timeout_ms = 60000;
    req.headers.push_back({"X-Session", std::string("book:") + std::to_string(book_id)});
    req.headers.push_back({"X-Page", std::to_string(page)});
    req.headers.push_back({"X-Priority", "0"});
    req.headers.push_back({"X-Image-Hash", img_hash});
    http::Response r = http::request(req);
    if (!r.ok) {
        if (err) *err = r.error;
        return false;
    }
    json::Value v;
    std::string jerr;
    if (r.status != 200 || !json::parse(r.body, v, &jerr)) {
        if (err) *err = "bad analyze response";
        return false;
    }
    out.page = page;
    out.n = (int)v["n"].as_int();
    out.img_hash = img_hash;
    out.pipeline = v["pipeline"].as_string().empty() ? pipeline_ : v["pipeline"].as_string();
    out.texts.clear();
    const json::Value& t = v["texts"];
    if (t.is_array())
        for (const auto& s : t.as_array()) out.texts.push_back(s.as_string());
    if (out.n <= 0) out.n = (int)out.texts.size();
    return true;
}

bool TranslateClient::render(const PageTexts& in, const std::vector<uint8_t>& page_bytes,
                             ImageRGBA& out, std::string* err) {
    json::Value meta = json::Value::make_object();
    meta.set("page", (int64_t)in.page);
    meta.set("n", (int64_t)in.n);
    meta.set("img_hash", in.img_hash);
    meta.set("pipeline", in.pipeline);
    json::Value arr = json::Value::make_array();
    for (const auto& s : in.texts) arr.push_back(s);
    meta.set("texts", arr);

    const std::string boundary = "----ComicShelfBoundary7f3a1c";
    std::string body;
    std::vector<std::pair<std::string, std::string>> fields{{"meta", meta.dump()}};
    std::vector<std::pair<std::string, std::string>> files{
        {"image", std::string((const char*)page_bytes.data(), page_bytes.size())}};
    const std::string ct = http::build_multipart(boundary, fields, files, body);

    http::Request req;
    req.method = "POST";
    req.url = base_url_ + "/render";
    req.content_type = ct;
    req.body = std::move(body);
    req.timeout_ms = 60000;
    req.headers.push_back({"Prefer", "return=raw"});
    http::Response r = http::request(req);
    if (!r.ok) {
        if (err) *err = r.error;
        return false;
    }
    if (r.status != 200) {
        if (err) *err = "render failed: HTTP " + std::to_string(r.status);
        return false;
    }
    return decode_raw_rgba(r.body, out, err);
}

bool TranslateClient::decode_raw_rgba(const std::string& body, ImageRGBA& out, std::string* err) {
    if (body.size() < 16 || body.compare(0, 4, "CSTR") != 0) {
        if (err) *err = "bad raw header";
        return false;
    }
    auto get_u32 = [&](size_t off) -> uint32_t {
        return (uint32_t)(uint8_t)body[off] | ((uint32_t)(uint8_t)body[off + 1] << 8) |
               ((uint32_t)(uint8_t)body[off + 2] << 16) | ((uint32_t)(uint8_t)body[off + 3] << 24);
    };
    const uint32_t w = get_u32(4), h = get_u32(8), ch = get_u32(12);
    if (w == 0 || h == 0 || ch != 4) {
        if (err) *err = "bad raw dims";
        return false;
    }
    const size_t need = (size_t)w * h * 4;
    if (body.size() < 16 + need) {
        if (err) *err = "raw body truncated";
        return false;
    }
    out.w = (int)w;
    out.h = (int)h;
    out.pixels.assign(body.begin() + 16, body.begin() + 16 + need);
    return true;
}

// ---------------------------------------------------------------- archive

bool TranslateClient::has_archive(int64_t book_id) {
    std::string payload;
    return db_.get_text_archive(book_id, payload);
}

bool TranslateClient::load_archive(int64_t book_id, int page, PageTexts& out) {
    std::string payload;
    if (!db_.get_text_archive(book_id, payload)) {
        log_info("archive load: book=" + std::to_string(book_id) + " page=" +
                 std::to_string(page) + " -> 无档案行");
        return false;
    }
    std::string plain;
    if (!unpack(payload, plain)) {
        log_warn("archive load: unpack 失败 book=" + std::to_string(book_id) +
                 " (" + std::to_string(payload.size()) + "B)");
        return false;
    }
    std::vector<PageTexts> pages;
    if (!deserialize_book(plain, pages)) {
        log_warn("archive load: 反序列化失败 book=" + std::to_string(book_id));
        return false;
    }
    for (auto& p : pages) {
        if (p.page == page) {
            out = std::move(p);
            log_info("archive load: book=" + std::to_string(book_id) + " page=" +
                     std::to_string(page) + " -> 命中 n=" + std::to_string(out.n));
            return true;
        }
    }
    log_info("archive load: book=" + std::to_string(book_id) + " page=" +
             std::to_string(page) + " -> 页不存在(共 " + std::to_string(pages.size()) + " 页)");
    return false;
}

bool TranslateClient::save_archive(int64_t book_id, int page, const PageTexts& in) {
    std::vector<PageTexts> pages;
    {
        std::string payload;
        if (db_.get_text_archive(book_id, payload)) {
            std::string plain;
            if (unpack(payload, plain)) deserialize_book(plain, pages);
        }
    }
    bool replaced = false;
    for (auto& p : pages) {
        if (p.page == page) {
            p = in;
            replaced = true;
            break;
        }
    }
    if (!replaced) pages.push_back(in);

    const std::string plain = serialize_book(pages);
    const std::string packed = pack(plain);
    const bool ok = db_.put_text_archive(book_id, packed, (int64_t)packed.size());
    log_info("archive save: book=" + std::to_string(book_id) + " page=" +
             std::to_string(page) + " n=" + std::to_string(in.n) +
             (replaced ? " (替换)" : " (新增)") + " 共" + std::to_string(pages.size()) +
             " 页 " + (ok ? "ok" : "写入失败"));
    return ok;
}

bool TranslateClient::clear_archive(int64_t book_id) { return db_.delete_text_archive(book_id); }

int64_t TranslateClient::archive_total_bytes() { return db_.text_archive_total_bytes(); }

int64_t TranslateClient::evict_to(int64_t max_bytes) { return db_.evict_text_archive(max_bytes); }

} // namespace cs::translate
