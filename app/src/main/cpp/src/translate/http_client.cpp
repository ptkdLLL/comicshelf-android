// POSIX socket implementation of the tiny HTTP/1.1 client used by the
// translation sidecar client (cs::http). Mirrors the WinHTTP-based Windows
// version: synchronous, never throws, timeout-bounded.
//
// Plain HTTP only — the sidecar is a LAN service. HTTPS requests fail fast
// with a clear error; route them through the platform stack if ever needed.
#include "translate/http_client.h"

#include "util/logger.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <cstring>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>

namespace cs::http {
namespace {

struct ParsedUrl {
    bool https = false;
    std::string host;
    int port = 80;
    std::string path = "/";
};

bool parse_url(const std::string& url, ParsedUrl& out) {
    const char* p = url.c_str();
    if (strncmp(p, "http://", 7) == 0) {
        p += 7;
        out.https = false;
    } else if (strncmp(p, "https://", 8) == 0) {
        p += 8;
        out.https = true;
    } else {
        return false;
    }
    const char* slash = strchr(p, '/');
    std::string hostport = slash ? std::string(p, (size_t)(slash - p)) : std::string(p);
    out.path = slash ? std::string(slash) : "/";
    size_t colon = hostport.rfind(':');
    if (colon != std::string::npos && hostport.find(']') == std::string::npos) {
        out.host = hostport.substr(0, colon);
        out.port = atoi(hostport.c_str() + colon + 1);
        if (out.port <= 0) out.port = out.https ? 443 : 80;
    } else {
        out.host = hostport;
        out.port = out.https ? 443 : 80;
    }
    if (out.host.empty()) return false;
    // Bracketed IPv6 literal without port, e.g. [::1]
    if (out.host.size() >= 2 && out.host.front() == '[' && out.host.back() == ']')
        out.host = out.host.substr(1, out.host.size() - 2);
    return true;
}

bool wait_fd(int fd, short events, int timeout_ms) {
    struct pollfd pfd;
    pfd.fd = fd;
    pfd.events = events;
    pfd.revents = 0;
    int rc;
    do {
        rc = poll(&pfd, 1, timeout_ms);
    } while (rc < 0 && errno == EINTR);
    if (rc <= 0) return false;
    return (pfd.revents & events) != 0;
}

int64_t ms_until(const struct timespec& deadline) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)(deadline.tv_sec - now.tv_sec) * 1000 +
           (int64_t)(deadline.tv_nsec - now.tv_nsec) / 1000000;
}

// Receives until peer close or deadline; one 1s poll at a time so the
// deadline is honored even on platforms whose poll blocks the full span.
bool recv_all_deadline(int fd, std::string& buf, size_t max_total,
                       const struct timespec& deadline) {
    char chunk[65536];
    for (;;) {
        int64_t left = ms_until(deadline);
        if (left <= 0) return false;
        if (!wait_fd(fd, POLLIN, (int)std::min<int64_t>(left, 1000))) {
            if (ms_until(deadline) <= 0) return false;
            continue;
        }
        ssize_t n = ::recv(fd, chunk, sizeof(chunk), 0);
        if (n == 0) return true; // peer closed = end of body
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        buf.append(chunk, (size_t)n);
        if (buf.size() > max_total) return true; // safety valve
    }
}

std::string lower(std::string s) {
    for (auto& c : s) c = (char)tolower((unsigned char)c);
    return s;
}

} // namespace

Response request(const Request& req) {
    Response r;
    ParsedUrl url;
    if (!parse_url(req.url, url)) {
        r.error = "bad url";
        return r;
    }
    if (url.https) {
        r.error = "https is not supported by the built-in client";
        return r;
    }

    const int t = req.timeout_ms > 0 ? req.timeout_ms : 20000;

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    if (getaddrinfo(url.host.c_str(), std::to_string(url.port).c_str(), &hints, &res) != 0 || !res) {
        r.error = "cannot resolve host: " + url.host;
        return r;
    }

    int fd = -1;
    for (struct addrinfo* ai = res; ai; ai = ai->ai_next) {
        fd = ::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
        // Non-blocking connect so the connect timeout can be enforced.
        int nb = 1;
        ioctl(fd, FIONBIO, &nb);
        int rc = ::connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc != 0 && errno != EINPROGRESS) {
            ::close(fd);
            fd = -1;
            continue;
        }
        if (rc != 0) {
            if (!wait_fd(fd, POLLOUT, t)) {
                ::close(fd);
                fd = -1;
                continue;
            }
            int soerr = 0;
            socklen_t len = sizeof(soerr);
            if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &len) < 0 || soerr != 0) {
                ::close(fd);
                fd = -1;
                continue;
            }
        }
        break;
    }
    freeaddrinfo(res);
    if (fd < 0) {
        r.error = "connect failed: " + url.host;
        return r;
    }

    // Back to blocking; send/recv are policed by the deadline below.
    int nb = 0;
    ioctl(fd, FIONBIO, &nb);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    struct timespec deadline;
    clock_gettime(CLOCK_MONOTONIC, &deadline);
    deadline.tv_sec += t / 1000;
    deadline.tv_nsec += (long)(t % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec += 1;
        deadline.tv_nsec -= 1000000000L;
    }

    // ---- build request ----------------------------------------------------
    std::string out = req.method + " " + url.path + " HTTP/1.1\r\n";
    out += "Host: " + url.host + "\r\n";
    out += "Connection: close\r\n";
    out += "Accept: */*\r\n";
    out += "User-Agent: ComicShelf/0.3\r\n";
    for (const auto& h : req.headers) out += h.first + ": " + h.second + "\r\n";
    if (!req.body.empty()) {
        if (!req.content_type.empty()) out += "Content-Type: " + req.content_type + "\r\n";
        out += "Content-Length: " + std::to_string(req.body.size()) + "\r\n";
    }
    out += "\r\n";
    if (!req.body.empty()) out += req.body;

    // ---- send -------------------------------------------------------------
    size_t sent = 0;
    while (sent < out.size()) {
        if (!wait_fd(fd, POLLOUT, t)) {
            r.error = "send timeout";
            ::close(fd);
            return r;
        }
        ssize_t n = ::send(fd, out.data() + sent, out.size() - sent, MSG_NOSIGNAL);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            r.error = "send failed";
            ::close(fd);
            return r;
        }
        sent += (size_t)n;
    }

    // ---- receive ----------------------------------------------------------
    std::string raw;
    if (!recv_all_deadline(fd, raw, 512ull * 1024 * 1024, deadline)) {
        r.error = "recv timeout";
        ::close(fd);
        return r;
    }
    ::close(fd);

    size_t hdr_end = raw.find("\r\n\r\n");
    if (hdr_end == std::string::npos) {
        r.error = "bad response (no header terminator)";
        return r;
    }
    const std::string head = raw.substr(0, hdr_end);
    std::string body = raw.substr(hdr_end + 4);

    if (head.compare(0, 5, "HTTP/") != 0) {
        r.error = "bad response (not http)";
        return r;
    }
    r.status = atoi(head.c_str() + 9);
    r.ok = true;

    bool chunked = false;
    for (size_t pos = head.find("\r\n"); pos != std::string::npos;
         pos = head.find("\r\n", pos + 2)) {
        size_t next = head.find("\r\n", pos + 2);
        std::string line = head.substr(
            pos + 2, next == std::string::npos ? std::string::npos : next - pos - 2);
        size_t colon = line.find(':');
        if (colon == std::string::npos) continue;
        std::string k = lower(line.substr(0, colon));
        std::string v = line.substr(colon + 1);
        while (!v.empty() && (v.front() == ' ' || v.front() == '\t')) v.erase(v.begin());
        if (k == "content-type") r.content_type = v;
        if (k == "transfer-encoding" && v.find("chunked") != std::string::npos) chunked = true;
    }

    if (chunked) {
        std::string de;
        de.reserve(body.size());
        size_t p = 0;
        while (p + 2 <= body.size()) {
            size_t eol = body.find("\r\n", p);
            if (eol == std::string::npos) break;
            size_t sz = (size_t)strtoull(body.c_str() + p, nullptr, 16);
            if (sz == 0) break;
            if (eol + 2 + sz > body.size()) sz = body.size() - eol - 2;
            de.append(body, eol + 2, sz);
            p = eol + 2 + sz + 2;
        }
        body = std::move(de);
    }

    r.body = std::move(body);
    return r;
}

std::string build_multipart(const std::string& boundary,
                            const std::vector<std::pair<std::string, std::string>>& fields,
                            const std::vector<std::pair<std::string, std::string>>& files,
                            std::string& out) {
    for (const auto& f : fields) {
        out += "--" + boundary + "\r\n";
        out += "Content-Disposition: form-data; name=\"" + f.first + "\"\r\n\r\n";
        out += f.second + "\r\n";
    }
    for (const auto& f : files) {
        out += "--" + boundary + "\r\n";
        out += "Content-Disposition: form-data; name=\"" + f.first +
               "\"; filename=\"page.bin\"\r\n";
        out += "Content-Type: application/octet-stream\r\n\r\n";
        out += f.second + "\r\n";
    }
    out += "--" + boundary + "--\r\n";
    return "multipart/form-data; boundary=" + boundary;
}

} // namespace cs::http
