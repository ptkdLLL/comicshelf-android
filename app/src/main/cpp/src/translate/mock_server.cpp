#include "translate/mock_server.h"

#include "image/image_util.h"
#include "util/json_min.h"
#include "util/logger.h"

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace cs::translate {
namespace {

#ifdef _WIN32
using socket_t = SOCKET;
constexpr socket_t kInvalid = INVALID_SOCKET;
void close_socket(socket_t s) { closesocket(s); }
#else
using socket_t = int;
constexpr socket_t kInvalid = -1;
void close_socket(socket_t s) { close(s); }
#endif

bool send_all(socket_t s, const char* data, size_t len) {
    size_t sent = 0;
    while (sent < len) {
#ifdef _WIN32
        int n = ::send(s, data + sent, (int)(len - sent), 0);
#else
        ssize_t n = ::send(s, data + sent, len - sent, 0);
#endif
        if (n <= 0) return false;
        sent += (size_t)n;
    }
    return true;
}

std::string lower(std::string s) {
    for (char& c : s)
        if (c >= 'A' && c <= 'Z') c = (char)(c - 'A' + 'a');
    return s;
}

std::string http_response(int status, const char* status_text, const std::string& content_type,
                          const std::string& body) {
    std::string h = "HTTP/1.1 " + std::to_string(status) + " " + status_text + "\r\n";
    h += "Content-Type: " + content_type + "\r\n";
    h += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    h += "Connection: close\r\n\r\n";
    h += body;
    return h;
}

// Deterministic mock geometry for a page: `blocks` horizontal bands.
struct Box {
    int x0, y0, x1, y1;
};
std::vector<Box> mock_boxes(int w, int h, int blocks) {
    std::vector<Box> out;
    if (blocks < 1) blocks = 1;
    for (int i = 0; i < blocks; ++i) {
        Box b;
        b.x0 = (int)(w * 0.10);
        b.x1 = (int)(w * 0.90);
        const int band = (int)(h * 0.20);
        b.y0 = (int)(h * 0.08) + i * (band + (int)(h * 0.05));
        b.y1 = b.y0 + band;
        if (b.y1 > h) b.y1 = h;
        if (b.y0 >= h) break;
        out.push_back(b);
    }
    return out;
}

// Extracts the value of a multipart field named `name` (simple, adequate here).
std::string multipart_field(const std::string& body, const std::string& boundary,
                            const std::string& name) {
    const std::string delim = "--" + boundary;
    size_t pos = 0;
    while ((pos = body.find(delim, pos)) != std::string::npos) {
        pos += delim.size();
        if (pos + 1 < body.size() && body[pos] == '-' && body[pos + 1] == '-') break;
        if (pos + 1 < body.size() && body[pos] == '\r') pos += 1;
        if (pos < body.size() && body[pos] == '\n') pos += 1;

        const size_t hdr_end = body.find("\r\n\r\n", pos);
        if (hdr_end == std::string::npos) break;
        const std::string headers = body.substr(pos, hdr_end - pos);
        size_t data_start = hdr_end + 4;
        size_t data_end = body.find(delim, data_start);
        if (data_end == std::string::npos) data_end = body.size();
        // trim the trailing CRLF before the delimiter
        size_t de = data_end;
        while (de > data_start && (body[de - 1] == '\r' || body[de - 1] == '\n')) --de;

        if (headers.find("name=\"" + name + "\"") != std::string::npos)
            return body.substr(data_start, de - data_start);
        pos = data_end;
    }
    return {};
}

} // namespace

struct MockServer::Impl {
    std::atomic<bool> stop{false};
    std::thread thread;
    socket_t listen_sock = kInvalid;
    int port = 0;
    int delay_ms = 0;
    int blocks = 3;
    std::atomic<int64_t> analyze_calls{0};
    std::atomic<int64_t> render_calls{0};

    void serve();
    void handle(socket_t client);
};

void MockServer::Impl::handle(socket_t client) {
    std::string req;
    char buf[8192];
    size_t header_end = std::string::npos;
    while (header_end == std::string::npos) {
#ifdef _WIN32
        int n = ::recv(client, buf, sizeof(buf), 0);
#else
        ssize_t n = ::recv(client, buf, sizeof(buf), 0);
#endif
        if (n <= 0) break;
        req.append(buf, (size_t)n);
        header_end = req.find("\r\n\r\n");
        if (req.size() > 64u * 1024u * 1024u) break;
    }
    if (header_end == std::string::npos) {
        close_socket(client);
        return;
    }

    const std::string head = req.substr(0, header_end);
    std::string body = req.substr(header_end + 4);

    // Request line.
    size_t sp1 = head.find(' ');
    size_t sp2 = head.find(' ', sp1 + 1);
    std::string method = head.substr(0, sp1);
    std::string target = (sp2 == std::string::npos) ? head.substr(sp1 + 1)
                                                    : head.substr(sp1 + 1, sp2 - sp1 - 1);
    std::string path = target;
    size_t q = path.find('?');
    if (q != std::string::npos) path = path.substr(0, q);

    // Headers -> map (lower-cased keys).
    std::map<std::string, std::string> hdrs;
    size_t line_start = head.find("\r\n");
    while (line_start != std::string::npos) {
        size_t next = head.find("\r\n", line_start + 2);
        if (next == std::string::npos) break;
        std::string line = head.substr(line_start + 2, next - line_start - 2);
        size_t colon = line.find(':');
        if (colon != std::string::npos) {
            std::string k = lower(line.substr(0, colon));
            std::string v = line.substr(colon + 1);
            size_t b = v.find_first_not_of(" \t");
            if (b != std::string::npos) v = v.substr(b);
            hdrs[k] = v;
        }
        line_start = next;
    }

    // Read the rest of the body according to Content-Length.
    size_t content_len = 0;
    auto it = hdrs.find("content-length");
    if (it != hdrs.end()) content_len = (size_t)std::strtoull(it->second.c_str(), nullptr, 10);
    while (body.size() < content_len) {
#ifdef _WIN32
        int n = ::recv(client, buf, sizeof(buf), 0);
#else
        ssize_t n = ::recv(client, buf, sizeof(buf), 0);
#endif
        if (n <= 0) break;
        body.append(buf, (size_t)n);
    }
    if (body.size() > content_len) body.resize(content_len);

    if (delay_ms > 0) std::this_thread::sleep_for(std::chrono::milliseconds(delay_ms));

    auto json_ok = [&](const std::string& s) {
        const std::string r = http_response(200, "OK", "application/json", s);
        send_all(client, r.data(), r.size());
    };
    auto json_err = [&](int code, const char* txt, const std::string& s) {
        const std::string r = http_response(code, txt, "application/json", s);
        send_all(client, r.data(), r.size());
    };

    if (path == "/health") {
        json::Value v = json::Value::make_object();
        v.set("ok", true);
        v.set("device", std::string("mock"));
        v.set("mock", true);
        v.set("pipeline", std::string("mock-1"));
        v.set("queue", (int64_t)0);
        v.set("analyze_calls", (int64_t)analyze_calls.load());
        v.set("render_calls", (int64_t)render_calls.load());
        json_ok(v.dump());
    } else if (path == "/session/open") {
        json::Value v = json::Value::make_object();
        v.set("session_id", std::string("s_mock"));
        v.set("pipeline", std::string("mock-1"));
        json_ok(v.dump());
    } else if (path == "/session/focus" || path == "/cancel") {
        json_ok("{\"ok\":true}");
    } else if (path == "/session/close") {
        json_ok("{\"ok\":true,\"freed_bytes\":0}");
    } else if (path == "/analyze_async") {
        analyze_calls++;
        ImageRGBA img;
        if (!body.empty()) decode_image((const uint8_t*)body.data(), body.size(), img);
        int n = blocks;
        json::Value texts = json::Value::make_array();
        for (int i = 0; i < n; ++i)
            texts.push_back(std::string("译文") + std::to_string(i + 1));
        json::Value v = json::Value::make_object();
        v.set("job_id", std::string("j_mock"));
        v.set("state", std::string("done"));
        v.set("n", (int64_t)n);
        v.set("texts", texts);
        v.set("decode_w", (int64_t)img.w);
        v.set("decode_h", (int64_t)img.h);
        json_ok(v.dump());
    } else if (path.rfind("/job/", 0) == 0) {
        json::Value texts = json::Value::make_array();
        for (int i = 0; i < blocks; ++i)
            texts.push_back(std::string("译文") + std::to_string(i + 1));
        json::Value v = json::Value::make_object();
        v.set("state", std::string("done"));
        v.set("n", (int64_t)blocks);
        v.set("texts", texts);
        json_ok(v.dump());
    } else if (path == "/render") {
        render_calls++;
        // multipart/form-data with `meta` (JSON) and `image` (page bytes).
        std::string ct = hdrs.count("content-type") ? hdrs["content-type"] : "";
        std::string boundary;
        size_t bp = ct.find("boundary=");
        if (bp != std::string::npos) boundary = ct.substr(bp + 9);
        std::string image = multipart_field(body, boundary, "image");
        std::string meta = multipart_field(body, boundary, "meta");
        int n = blocks;
        json::Value mv;
        std::string jerr;
        if (!meta.empty() && json::parse(meta, mv, &jerr)) {
            if (mv["n"].is_number()) n = (int)mv["n"].as_int();
            const json::Value& t = mv["texts"];
            if (t.is_array() && !t.as_array().empty()) n = (int)t.as_array().size();
        }
        ImageRGBA img;
        if (image.empty() || !decode_image((const uint8_t*)image.data(), image.size(), img)) {
            json_err(400, "Bad Request", "{\"error\":\"cannot decode image\"}");
            close_socket(client);
            return;
        }
        const std::vector<Box> boxes = mock_boxes(img.w, img.h, n);
        for (const Box& b : boxes) {
            for (int y = b.y0; y < b.y1; ++y) {
                uint8_t* row = img.pixels.data() + ((size_t)y * img.w + b.x0) * 4;
                for (int x = b.x0; x < b.x1; ++x, row += 4) {
                    row[0] = (uint8_t)((row[0] + 60) / 2);
                    row[1] = (uint8_t)((row[1] + 200) / 2);
                    row[2] = (uint8_t)((row[2] + 90) / 2);
                    row[3] = 255;
                }
            }
        }
        std::string out;
        out.reserve(16 + img.pixels.size());
        const char magic[4] = {'C', 'S', 'T', 'R'};
        out.append(magic, 4);
        auto put_u32 = [&](uint32_t v) {
            out.push_back((char)(v & 0xFF));
            out.push_back((char)((v >> 8) & 0xFF));
            out.push_back((char)((v >> 16) & 0xFF));
            out.push_back((char)((v >> 24) & 0xFF));
        };
        put_u32((uint32_t)img.w);
        put_u32((uint32_t)img.h);
        put_u32((uint32_t)4);
        put_u32(0);
        out.append((const char*)img.pixels.data(), img.pixels.size());
        const std::string r =
            http_response(200, "OK", "application/octet-stream", out);
        send_all(client, r.data(), r.size());
    } else if (path == "/config" || path == "/stats" || path == "/test_llm" || path == "/models") {
        json_ok("{\"ok\":true,\"mock\":true}");
    } else {
        json_err(404, "Not Found", "{\"error\":\"not found\"}");
    }

    close_socket(client);
}

void MockServer::Impl::serve() {
    while (!stop.load()) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(listen_sock, &rfds);
        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 200000; // 200 ms
        int sel = ::select((int)listen_sock + 1, &rfds, nullptr, nullptr, &tv);
        if (sel <= 0) continue;
        socket_t client = ::accept(listen_sock, nullptr, nullptr);
        if (client == kInvalid) continue;
        handle(client);
    }
}

bool MockServer::start(int port, int delay_ms, int blocks) {
#ifdef _WIN32
    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        log_error("mock server: WSAStartup failed");
        return false;
    }
#endif
    impl_ = new Impl();
    impl_->delay_ms = delay_ms;
    impl_->blocks = blocks;

    impl_->listen_sock = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (impl_->listen_sock == kInvalid) {
        delete impl_;
        impl_ = nullptr;
        return false;
    }
    int one = 1;
    setsockopt(impl_->listen_sock, SOL_SOCKET, SO_REUSEADDR, (const char*)&one, sizeof(one));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons((unsigned short)port);
    if (::bind(impl_->listen_sock, (sockaddr*)&addr, sizeof(addr)) != 0 ||
        ::listen(impl_->listen_sock, 8) != 0) {
        log_error("mock server: bind/listen failed");
        close_socket(impl_->listen_sock);
        delete impl_;
        impl_ = nullptr;
        return false;
    }
    sockaddr_in bound{};
#ifdef _WIN32
    int blen = sizeof(bound);
#else
    socklen_t blen = sizeof(bound);
#endif
    if (getsockname(impl_->listen_sock, (sockaddr*)&bound, &blen) == 0)
        impl_->port = ntohs(bound.sin_port);
    port_ = impl_->port;

    impl_->thread = std::thread([this]() { impl_->serve(); });
    running_ = true;
    log_info("mock translate server listening on 127.0.0.1:" + std::to_string(port_));
    return true;
}

void MockServer::stop() {
    if (!impl_) return;
    impl_->stop = true;
    if (impl_->thread.joinable()) impl_->thread.join();
    if (impl_->listen_sock != kInvalid) close_socket(impl_->listen_sock);
#ifdef _WIN32
    WSACleanup();
#endif
    delete impl_;
    impl_ = nullptr;
    running_ = false;
    port_ = 0;
}

MockServer::~MockServer() { stop(); }

std::string MockServer::base_url() const {
    return "http://127.0.0.1:" + std::to_string(port_);
}

} // namespace cs::translate
