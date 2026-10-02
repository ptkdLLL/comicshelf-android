#include "util/crash_handler.h"

#include "util/logger.h"
#include "util/path_util.h"

#include <cstdio>
#include <exception>
#include <filesystem>

#ifdef _WIN32
#include <windows.h>
#include <dbghelp.h>
#include <psapi.h>

namespace cs {
namespace {

std::string g_dump_dir;

std::string describe_address(uint64_t addr) {
    HMODULE mod = nullptr;
    char path[MAX_PATH] = "<unknown>";
    uint64_t base = 0;
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                               GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           reinterpret_cast<LPCSTR>(static_cast<uintptr_t>(addr)), &mod) &&
        mod) {
        GetModuleFileNameA(mod, path, MAX_PATH);
        MODULEINFO mi{};
        if (K32GetModuleInformation(GetCurrentProcess(), mod, &mi, sizeof(mi)))
            base = reinterpret_cast<uint64_t>(mi.lpBaseOfDll);
    }
    const char* leaf = path;
    for (const char* p = path; *p; ++p)
        if (*p == '\\' || *p == '/') leaf = p + 1;

    char buf[64];
    std::snprintf(buf, sizeof(buf), " +0x%llX", (unsigned long long)(addr - base));
    return std::string(leaf) + buf;
}

void write_dump(EXCEPTION_POINTERS* ep) {
    if (g_dump_dir.empty()) return;
    SYSTEMTIME st{};
    GetLocalTime(&st);
    char name[64];
    std::snprintf(name, sizeof(name), "crash_%04d%02d%02d_%02d%02d%02d.dmp", st.wYear, st.wMonth,
                  st.wDay, st.wHour, st.wMinute, st.wSecond);

    std::wstring path = paths::to_wide(g_dump_dir + "/" + name);
    HANDLE f = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        log_error(std::string("could not create minidump: ") + g_dump_dir + "/" + name);
        return;
    }

    MINIDUMP_EXCEPTION_INFORMATION ei{};
    ei.ThreadId = GetCurrentThreadId();
    ei.ExceptionPointers = ep;
    ei.ClientPointers = FALSE;

    const MINIDUMP_TYPE type = static_cast<MINIDUMP_TYPE>(
        MiniDumpWithThreadInfo | MiniDumpWithUnloadedModules |
        MiniDumpWithIndirectlyReferencedMemory);
    BOOL ok = MiniDumpWriteDump(GetCurrentProcess(), GetCurrentProcessId(), f, type,
                                ep ? &ei : nullptr, nullptr, nullptr);
    CloseHandle(f);

    if (ok)
        log_error("minidump written: " + g_dump_dir + "/" + name);
    else
        log_error("MiniDumpWriteDump failed");
}

LONG WINAPI unhandled_filter(EXCEPTION_POINTERS* ep) {
    const uint64_t addr = reinterpret_cast<uint64_t>(ep->ExceptionRecord->ExceptionAddress);
    char buf[256];
    std::snprintf(buf, sizeof(buf), "=== CRASH: code=0x%08X address=0x%llX (%s) ===",
                  ep->ExceptionRecord->ExceptionCode, (unsigned long long)addr,
                  describe_address(addr).c_str());
    log_error(buf);
    write_dump(ep);
    log_close();
    return EXCEPTION_EXECUTE_HANDLER;
}

void on_terminate() {
    log_error("std::terminate() called");
    write_dump(nullptr);
    log_close();
    std::abort();
}

} // namespace

void install_crash_handler(const std::string& dump_dir_utf8) {
    g_dump_dir = dump_dir_utf8;
    std::error_code ec;
    std::filesystem::create_directories(std::filesystem::path(paths::to_wide(dump_dir_utf8)), ec);
    SetUnhandledExceptionFilter(unhandled_filter);
    std::set_terminate(on_terminate);
    log_info("crash handler installed -> " + dump_dir_utf8);
}

} // namespace cs

#else

// POSIX/Android: no minidumps, but a signal handler that logs the faulting
// address to the log (and logcat via the logger) makes native crashes on a
// device diagnosable without a debugger attached.
#include <csignal>
#include <cstdlib>
#include <unistd.h>

#if defined(__ANDROID__)
#include <dlfcn.h>
#include <unwind.h>
#define CS_HAVE_BACKTRACE 1
#define CS_UNWIND_BACKTRACE 1
#elif !defined(_WIN32)
#include <execinfo.h>
#define CS_HAVE_BACKTRACE 1
#endif

namespace cs {
namespace {

std::string g_dump_dir;

extern "C" void on_signal(int sig, siginfo_t* info, void*) {
    char buf[256];
    std::snprintf(buf, sizeof(buf), "=== CRASH: signal %d address=%p ===", sig, info ? info->si_addr : nullptr);
    log_error(buf);
#if defined(CS_UNWIND_BACKTRACE)
    struct UnwindState { void* frames[48]; int n; };
    static UnwindState st;  // signal context: keep it simple and static
    st.n = 0;
    _Unwind_Backtrace(
        [](struct _Unwind_Context* ctx, void* arg) -> _Unwind_Reason_Code {
            auto* s = (UnwindState*)arg;
            if (s->n >= 48) return _URC_END_OF_STACK;
            void* ip = (void*)_Unwind_GetIP(ctx);
            if (ip) s->frames[s->n++] = ip;
            return _URC_NO_REASON;
        },
        &st);
    char msg[256];
    for (int i = 0; i < st.n; ++i) {
        Dl_info info;
        if (dladdr(st.frames[i], &info) && info.dli_fname) {
            const uintptr_t base = (uintptr_t)info.dli_fbase;
            const uintptr_t off = (uintptr_t)st.frames[i] - base;
            std::snprintf(msg, sizeof(msg), "  #%d  +0x%zx  %s (base 0x%zx)", i,
                          (size_t)off, info.dli_sname ? info.dli_sname : "?",
                          (size_t)base);
        } else {
            std::snprintf(msg, sizeof(msg), "  #%d  %p", i, st.frames[i]);
        }
        log_error(msg);
    }
#elif CS_HAVE_BACKTRACE
    void* frames[32];
    int n = backtrace(frames, 32);
    if (n > 0) {
        char msg[128];
        for (int i = 0; i < n; ++i) {
            std::snprintf(msg, sizeof(msg), "  #%d  %p", i, frames[i]);
            log_error(msg);
        }
    }
#endif
    _exit(1);
}

} // namespace

void install_crash_handler(const std::string& dump_dir_utf8) {
    g_dump_dir = dump_dir_utf8;
    std::error_code ec;
    std::filesystem::create_directories(paths::from_utf8(dump_dir_utf8), ec);
    struct sigaction sa{};
    sa.sa_sigaction = on_signal;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS, &sa, nullptr);
    sigaction(SIGFPE, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    std::set_terminate([] {
        log_error("=== CRASH: std::terminate ===");
        std::abort();
    });
    log_info("crash handler installed -> " + dump_dir_utf8);
}

} // namespace cs

#endif
