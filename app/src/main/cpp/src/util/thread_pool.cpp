#include "util/thread_pool.h"

namespace cs {

ThreadPool::ThreadPool(unsigned n) {
    if (n == 0) n = 1;
    workers_.reserve(n);
    for (unsigned i = 0; i < n; ++i)
        workers_.emplace_back([this] { worker_loop(); });
}

ThreadPool::~ThreadPool() {
    {
        std::lock_guard<std::mutex> lock(mtx_);
        stop_ = true;
    }
    cv_job_.notify_all();
    for (auto& t : workers_)
        if (t.joinable()) t.join();
}

void ThreadPool::submit(std::function<void()> job) {
    {
        std::lock_guard<std::mutex> lock(mtx_);
        if (stop_) return;
        queue_.push(std::move(job));
    }
    cv_job_.notify_one();
}

size_t ThreadPool::pending() const {
    std::lock_guard<std::mutex> lock(mtx_);
    return queue_.size() + active_;
}

void ThreadPool::wait_idle() {
    std::unique_lock<std::mutex> lock(mtx_);
    cv_idle_.wait(lock, [this] { return queue_.empty() && active_ == 0; });
}

void ThreadPool::worker_loop() {
    for (;;) {
        std::function<void()> job;
        {
            std::unique_lock<std::mutex> lock(mtx_);
            cv_job_.wait(lock, [this] { return stop_ || !queue_.empty(); });
            if (stop_ && queue_.empty()) return;
            job = std::move(queue_.front());
            queue_.pop();
            ++active_;
        }

        job();

        {
            std::lock_guard<std::mutex> lock(mtx_);
            --active_;
            if (queue_.empty() && active_ == 0)
                cv_idle_.notify_all();
        }
    }
}

} // namespace cs
