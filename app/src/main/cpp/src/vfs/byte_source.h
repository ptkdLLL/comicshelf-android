// 随机读字节源：ZIP 中央目录解析、单条目解压、封面读取都走它，
// 本地文件与 SMB 共享同一条代码路径。
#pragma once
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "vfs/vfs.h"

namespace cs::vfs {

class ByteSource {
public:
    virtual ~ByteSource() = default;
    virtual uint64_t size() const = 0;
    // 从 off 读满 n 字节（越界/出错返回 false）。线程安全（内部串行化）。
    virtual bool pread(uint64_t off, void* buf, size_t n) = 0;
    // 诊断用：完整路径（本地路径或 smb://…）
    virtual std::string name() const = 0;

    // 全量读（小文件、封面等）
    bool read_all(std::vector<uint8_t>* out);
};

// utf8_path 可以是本地绝对路径或 "smb://host[:port]/share/…"。
std::shared_ptr<ByteSource> open_byte_source(const std::string& utf8_path,
                                             std::string* err);
// 复用已有 Vfs（例如扫描器/阅读器已解析过路径时）。
std::shared_ptr<ByteSource> open_byte_source_on(const std::shared_ptr<Vfs>& vfs,
                                                const std::string& rel,
                                                std::string* err);

} // namespace cs::vfs
