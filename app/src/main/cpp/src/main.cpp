// ComicShelf - WinMain, window creation and the render loop.
#include "ui/app.h"

#include "util/logger.h"
#include "util/path_util.h"

#include <windows.h>
#include <objbase.h>

#include <chrono>
#include <cstdlib>

#include "imgui.h"
#include "imgui_impl_win32.h"

// Provided by imgui_impl_win32.cpp.
extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND hWnd, UINT msg,
                                                             WPARAM wParam, LPARAM lParam);

namespace {

cs::App* g_app = nullptr;

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    if (ImGui_ImplWin32_WndProcHandler(hwnd, msg, wparam, lparam)) return TRUE;

    switch (msg) {
        case WM_SIZE:
            if (g_app && wparam != SIZE_MINIMIZED)
                g_app->on_resize(LOWORD(lparam), HIWORD(lparam));
            return 0;
        case WM_SYSCOMMAND:
            if ((wparam & 0xfff0) == SC_KEYMENU) return 0; // disable ALT menu
            break;
        case WM_CLOSE:
            cs::log_info("WndProc: WM_CLOSE");
            PostQuitMessage(0);
            return 0;
        case WM_DESTROY:
            cs::log_info("WndProc: WM_DESTROY");
            PostQuitMessage(0);
            return 0;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wparam, lparam);
}

} // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR, int) {
    // Per-monitor DPI awareness when available.
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    WNDCLASSEXW wc = {};
    wc.cbSize = sizeof(wc);
    wc.style = CS_OWNDC;
    wc.lpfnWndProc = WndProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.lpszClassName = L"ComicShelfWindow";
    ::RegisterClassExW(&wc);

    const int width = 1360;
    const int height = 860;
    HWND hwnd = ::CreateWindowExW(0, wc.lpszClassName, L"ComicShelf", WS_OVERLAPPEDWINDOW,
                                  CW_USEDEFAULT, CW_USEDEFAULT, width, height,
                                  nullptr, nullptr, instance, nullptr);
    if (!hwnd) {
        MessageBoxW(nullptr, L"Failed to create window.", L"ComicShelf", MB_OK | MB_ICONERROR);
        return 1;
    }

    ::ShowWindow(hwnd, SW_SHOWDEFAULT);
    ::UpdateWindow(hwnd);

    // Initialize COM-backed modern folder picker; already CoInitialize'd.

    cs::App app;
    g_app = &app;
    if (!app.init(hwnd, width, height)) {
        MessageBoxW(nullptr, L"ComicShelf failed to initialize.", L"ComicShelf",
                    MB_OK | MB_ICONERROR);
        app.shutdown();
        return 1;
    }
    ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, (LONG_PTR)&app);

    // Test hook: CS_AUTOQUIT=<seconds> posts WM_CLOSE after N seconds so headless
    // runs exercise the same graceful teardown as the window's close button.
    int autoquit_sec = 0;
    {
        char buf[16] = {};
        if (::GetEnvironmentVariableA("CS_AUTOQUIT", buf, (DWORD)sizeof(buf)) > 0)
            autoquit_sec = std::atoi(buf);
    }
    const auto t_start = std::chrono::steady_clock::now();

    bool quit = false;
    long long frames = 0;
    while (!quit) {
        MSG msg;
        while (::PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            ::TranslateMessage(&msg);
            ::DispatchMessageW(&msg);
            if (msg.message == WM_QUIT) {
                cs::log_info("message loop: WM_QUIT received (wParam=" +
                             std::to_string((long long)msg.wParam) + ")");
                quit = true;
            }
        }
        if (quit) {
            cs::log_info("loop exit: quit");
            break;
        }
        if (app.quit()) {
            cs::log_info("loop exit: app.quit()");
            break;
        }

        if (autoquit_sec > 0) {
            const auto dt = (long long)std::chrono::duration_cast<std::chrono::seconds>(
                                std::chrono::steady_clock::now() - t_start)
                                .count();
            if (dt >= autoquit_sec) {
                cs::log_info("autoquit: WM_CLOSE after " + std::to_string(dt) + "s");
                ::PostMessageW(hwnd, WM_CLOSE, 0, 0);
                autoquit_sec = 0;
            }
        }

        app.frame();
        ++frames;
    }
    cs::log_info("message loop ended after " + std::to_string(frames) + " frames");

    ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
    g_app = nullptr;
    app.shutdown();
    ::DestroyWindow(hwnd);
    ::UnregisterClassW(wc.lpszClassName, instance);
    CoUninitialize();
    return 0;
}
