#pragma once
// 文件系统抽象层：本地路径与 SMB 共享走同一接口。
//
// 设计目标（百万本书库）：
//   * list/stat 面向扫描器，SMB 侧按批枚举并缓存目录项
//   * open/read/seek 面向 zip 中央目录与页面随机读（必须支持远距 seek）
//   * 所有 read 保证“读满或到 EOF”（SMB2 单次返回有窗口上限，由实现内部循环）
//   * 一个 share 一个会话（SmbVfs），扫描/阅读共用，避免重复认证
//
// 路径约定：
//   本地：绝对路径直接透传（vfs_for_path("/sdcard/...")）
//   SMB ："smb://host[:port]/share/dir/file.zip"；Vfs 内使用 share 相对路径
//         （"/dir/file.zip"，以 '/' 开头）
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace cs::vfs {

struct FileStat {
    uint64_t size = 0;
    int64_t mtime = 0;   // unix 秒
    bool is_dir = false;
    bool is_file = false;
};

struct DirEntry {
    std::string name;    // 末级名字（utf-8）
    FileStat st;
};

class File {
public:
    virtual ~File() = default;
    // 读满 len 或 EOF；返回读到的字节数，<0 出错。
    virtual int64_t read(uint8_t* buf, size_t len) = 0;
    virtual bool seek(uint64_t off) = 0;
    virtual uint64_t size() = 0;
    virtual uint64_t tell() = 0;
};

class Vfs {
public:
    virtual ~Vfs() = default;
    virtual std::string scheme() const = 0;               // "file" / "smb"
    virtual std::string display() const = 0;              // 记录用（SMB 含 host/share）
    virtual bool stat(const std::string& path, FileStat* out) = 0;
    virtual bool list(const std::string& path, std::vector<DirEntry>* out) = 0;
    virtual std::unique_ptr<File> open(const std::string& path) = 0;
    // 该 Vfs 上的完整路径（写库用）；rel 为 Vfs 内相对路径
    virtual std::string full_path(const std::string& rel) const = 0;
};

struct SmbConfig {
    std::string host;    // host 或 host:port
    std::string share;
    std::string user;    // 空 = 匿名
    std::string password;
    std::string domain;  // 可选
};

std::shared_ptr<Vfs> make_local_vfs();

// 同一 (host,share,user) 复用会话；线程安全（内部串行化 libsmb2 调用）。
std::shared_ptr<Vfs> make_smb_vfs(const SmbConfig& cfg, std::string* err);

// "smb://host/share/a/b.zip" / "/abs/path" → (vfs, vfs 内相对路径)
std::shared_ptr<Vfs> vfs_for_path(const std::string& path, std::string* rel,
                                  std::string* err);
bool is_smb_path(const std::string& path);
// 解析 smb:// 路径并把已注册的凭据填进 cfg（供监视器等非 Vfs 入口使用）。
bool smb_path_config(const std::string& path, SmbConfig* cfg, std::string* rel,
                     std::string* err);
// 供 UI/受控入口显式注册 SMB 凭据（启动时或添加库时调用）
void register_smb_credentials(const SmbConfig& cfg);

// 枚举服务器上的共享名（走 IPC$ 的 srvsvc ShareEnum；列表里排除隐藏共享）。
// 供“添加 SMB 书库”向导用，避免用户猜共享名。
bool smb_list_shares(const std::string& host, const std::string& user,
                     const std::string& password, const std::string& domain,
                     std::vector<std::string>* out, std::string* err);

}  // namespace cs::vfs
