#pragma once
// Uniform read-only access to comics stored as zip/rar archives or plain
// folders of images. Only image entries are exposed, already page-sorted.
#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

namespace cs {

struct ArchiveEntry {
    std::string name;   // UTF-8 path inside the container
    uint64_t size = 0;  // uncompressed size in bytes
    uint64_t uindex = 0;// backend index (zip central-dir index / rar header order)
};

class IArchive {
public:
    virtual ~IArchive() = default;
    virtual const std::vector<ArchiveEntry>& entries() const = 0;
    // Reads entry `i` fully into `out`. Thread-safety is backend-defined; callers
    // must serialize access to a single instance.
    virtual bool read(size_t i, std::vector<uint8_t>& out) = 0;
    size_t count() const { return entries().size(); }
};

// Opens `p` (archive file or image folder). Returns nullptr and fills `err` on
// failure. Detection is by extension for files, by content for directories.
// `p` 可以是本地路径或 "smb://host[:port]/share/dir/file.zip"，两者都走 VFS：
// ZIP 用 miniz 的自定义读回调按需随机读中央目录/条目，不做整档下载。
std::unique_ptr<IArchive> open_archive(const std::filesystem::path& p, std::string* err);

// UTF-8 直入（非 Windows 平台上路径就是字节串；SMB 路径必须走这个入口）。
std::unique_ptr<IArchive> open_archive_path(const std::string& utf8_path, std::string* err);

// SMB 上的 RAR 需要整档缓存（UnRAR 要真实文件句柄）；LibraryManager 初始化时设置。
void set_archive_cache_dir(const std::string& utf8_dir);

bool is_archive_path(const std::filesystem::path& p);

// Natural page ordering used by every backend (page2 < page10).
void sort_entries_naturally(std::vector<ArchiveEntry>& entries);

} // namespace cs
