#pragma once
// Tiny synchronous HTTP/1.1 client built on WinHTTP, for talking to the local
// translation sidecar / mock server (127.0.0.1). Kept dependency-free so the
// core library (and the self-test) can use it without pulling a third party.
#include <string>
#include <utility>
#include <vector>

namespace cs::http {

struct Response {
    bool ok = false;       // transport succeeded (any HTTP status)
    int status = 0;        // HTTP status code (0 when transport failed)
    std::string body;      // raw response bytes
    std::string content_type;
    std::string error;     // transport error, when ok == false
};

struct Request {
    std::string method = "GET";
    std::string url; // http://host:port/path  (https supported)
    std::vector<std::pair<std::string, std::string>> headers;
    std::string body;         // raw body (empty = none)
    std::string content_type; // added as a header when body is non-empty
    int timeout_ms = 20000;
};

// Performs the request. Never throws. `ok` is false on connection/timeout
// errors (with `error` filled); an HTTP 4xx/5xx is reported via `status`.
Response request(const Request& req);

// Builds a multipart/form-data body. Returns the Content-Type header value
// (with boundary) and appends the encoded body to `out`.
std::string build_multipart(const std::string& boundary,
                            const std::vector<std::pair<std::string, std::string>>& fields,
                            const std::vector<std::pair<std::string, std::string>>& files,
                            std::string& out);

} // namespace cs::http
