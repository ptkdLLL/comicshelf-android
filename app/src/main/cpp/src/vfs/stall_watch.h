// v0.3.3d 诊断：跨线程"当前长操作"登记 + 卡死守护线程（自抓原生栈，无需 root）。
//
// 用法（vfs_smb.cpp / bridge.cpp 内）：
//   smbwatch::ensure_watchdog();       // 首次使用 SMB 时调用一次（幂等）
//   smbwatch::op_begin("read off=.."); // 进入一个可能长时间阻塞的操作
//   smbwatch::op_end();                // 退出（务必 finally 语义）
//
// 守护线程每 3s 巡检：任何线程的操作停留 > 12s →
//   ① logcat 打印 [CS_STALL]（线程/操作名/停留时长）
//   ② 向该线程发 SIGUSR2：处理器用 backtrace() 抓 48 帧裸 PC + .so 基址，
//      输出到 logcat 标签 CS_STALL（离线用未剥离 .so addr2line 还原函数名）
//   ③ 信号让 poll() 返回 EINTR → 当前 SMB 调用失败 → 上层 drop_session 换新会话重试（自愈）
// 同一轮卡死只抓一次；全部操作结束后重新武装。
#pragma once
#include <string>

namespace smbwatch {
void ensure_watchdog();
void op_begin(const std::string& what);
void op_end();
}
