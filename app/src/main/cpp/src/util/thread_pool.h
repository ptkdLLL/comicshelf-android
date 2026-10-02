#pragma once
// A small fixed-size thread pool with an idle-wait primitive.
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <functional>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

namespace cs {

class ThreadPool {
public:
    explicit ThreadPool(unsigned n);
    ~ThreadPool();

    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator=(const ThreadPool&) = delete;

    void submit(std::function<void()> job);

    // Blocks until the queue is empty and no worker is executing a job.
    void wait_idle();

    unsigned size() const { return (unsigned)workers_.size(); }
    size_t pending() const;

private:
    void worker_loop();

    std::vector<std::thread> workers_;
    std::queue<std::function<void()>> queue_;
    mutable std::mutex mtx_;
    std::condition_variable cv_job_;
    std::condition_variable cv_idle_;
    bool stop_ = false;
    size_t active_ = 0;
};

} // namespace cs
