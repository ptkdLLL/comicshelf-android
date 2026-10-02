#pragma once
// Local mock translation server implementing the sidecar HTTP contract.
//
// Purpose: end-to-end debugging of the C++ client / store / reader integration
// WITHOUT any Python, torch models or an LLM. It decodes the submitted page
// image, fabricates deterministic "translations" and renders colored boxes, so
// the whole pipeline (protocol, archive, caches, LRU, fallbacks) can be tested
// on any machine. The real sidecar (Python, BT modules) speaks the same
// protocol, so nothing on the client changes when swapping it in.
#include <cstdint>
#include <string>

namespace cs::translate {

class MockServer {
public:
    MockServer() = default;
    ~MockServer();
    MockServer(const MockServer&) = delete;
    MockServer& operator=(const MockServer&) = delete;

    // Binds 127.0.0.1:<port> (0 = ephemeral) and starts serving in a thread.
    bool start(int port = 0, int delay_ms = 0, int blocks = 3);
    void stop();
    bool running() const { return running_; }
    int port() const { return port_; }
    // "http://127.0.0.1:<port>"
    std::string base_url() const;

private:
    struct Impl;
    Impl* impl_ = nullptr;
    bool running_ = false;
    int port_ = 0;
};

} // namespace cs::translate
