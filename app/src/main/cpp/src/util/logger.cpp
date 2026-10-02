#include "util/logger.h"

#include <chrono>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <string>

#ifdef _WIN32
#include <windows.h>
#endif

#if defined(__ANDROID__)
#include <android/log.h>
#endif

namespace cs {
namespace {

std::mutex g_mtx;
FILE* g_file = nullptr;

const char* level_name(int level) {
    switch (level) {
        case 1: return "WARN ";
        case 2: return "ERROR";
        default: return "INFO ";
    }
}

std::string timestamp() {
    using namespace std::chrono;
    auto now = system_clock::now();
    auto t = system_clock::to_time_t(now);
    auto ms = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
    std::tm tmv{};
#ifdef _WIN32
    localtime_s(&tmv, &t);
#else
    localtime_r(&t, &tmv);
#endif
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%04d-%02d-%02d %02d:%02d:%02d.%03d",
                  tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
                  tmv.tm_hour, tmv.tm_min, tmv.tm_sec, (int)ms.count());
    return buf;
}

void emit(int level, const std::string& msg) {
    std::lock_guard<std::mutex> lock(g_mtx);
    std::string line = timestamp();
    line += " [";
    line += level_name(level);
    line += "] ";
    line += msg;

    if (g_file) {
        std::fputs(line.c_str(), g_file);
        std::fputc('\n', g_file);
        std::fflush(g_file);
    }
#ifdef _WIN32
    std::string dbg = line + "\n";
    OutputDebugStringA(dbg.c_str());
#elif defined(__ANDROID__)
    __android_log_print(level >= 2 ? ANDROID_LOG_ERROR
                        : (level == 1 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO),
                        "ComicShelf", "%s", line.c_str());
#else
    std::fputs((line + "\n").c_str(), stderr);
#endif
}

} // namespace

void log_open(const std::string& utf8_path) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (g_file) {
        std::fclose(g_file);
        g_file = nullptr;
    }
#ifdef _WIN32
    // Convert UTF-8 -> UTF-16 and open with _wfopen.
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8_path.c_str(), -1, nullptr, 0);
    if (wlen > 0) {
        std::wstring w(wlen, L'\0');
        MultiByteToWideChar(CP_UTF8, 0, utf8_path.c_str(), -1, w.data(), wlen);
        g_file = _wfopen(w.c_str(), L"ab");
    }
#else
    g_file = std::fopen(utf8_path.c_str(), "ab");
#endif
}

void log_close() {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (g_file) {
        std::fclose(g_file);
        g_file = nullptr;
    }
}

void log_info(const std::string& msg) { emit(0, msg); }
void log_warn(const std::string& msg) { emit(1, msg); }
void log_error(const std::string& msg) { emit(2, msg); }

} // namespace cs
