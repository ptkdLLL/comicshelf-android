#include "archive/archive.h"

#include "util/logger.h"
#include "util/path_util.h"
#include "vfs/byte_source.h"
#include "vfs/vfs.h"

#include <algorithm>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <vector>

namespace cs {

// Implemented in archive_zip.cpp / archive_rar.cpp / (folder backend below).
std::unique_ptr<IArchive> make_zip_archive(const std::shared_ptr<vfs::Vfs>& vfs,
                                           const std::string& rel, std::string* err);
#if defined(CS_HAS_RAR) && CS_HAS_RAR
std::unique_ptr<IArchive> make_rar_archive(const std::string& local_utf8, std::string* err);
#endif

void sort_entries_naturally(std::vector<ArchiveEntry>& entries) {
    std::sort(entries.begin(), entries.end(), [](const ArchiveEntry& a, const ArchiveEntry& b) {
        return paths::natural_less(a.name, b.name);
    });
}

bool is_archive_path(const std::filesystem::path& p) {
    return paths::is_archive_ext(paths::lower_ext(p));
}

namespace {

std::mutex g_cache_mtx;
std::string g_cache_dir;

std::string join_rel(const std::string& dir, const std::string& name) {
    if (dir.empty() || dir == "/") return "/" + name;
    if (dir.back() == '/') return dir + name;
    return dir + "/" + name;
}

// root 的相对部分（页面排序用的展示名）
std::string rel_to(const std::string& root, const std::string& full) {
    if (full.size() <= root.size()) return full;
    std::string r = full.substr(root.size());
    while (!r.empty() && r[0] == '/') r.erase(0, 1);
    return r;
}

std::string lower_ext_str(const std::string& name) {
    auto dot = name.rfind('.');
    if (dot == std::string::npos || dot + 1 >= name.size()) return std::string();
    std::string e = name.substr(dot + 1);
    for (auto& c : e) c = (char)std::tolower((unsigned char)c);
    return e;
}

// ---- 图片目录 = 一本书（VFS 版，本地/SMB 通用）------------------------------

class FolderArchive : public IArchive {
public:
    FolderArchive(std::shared_ptr<vfs::Vfs> vfs, std::vector<ArchiveEntry> e,
                  std::vector<std::string> rels)
        : vfs_(std::move(vfs)), entries_(std::move(e)), rels_(std::move(rels)) {}

    const std::vector<ArchiveEntry>& entries() const override { return entries_; }

    bool read(size_t i, std::vector<uint8_t>& out) override {
        if (i >= rels_.size()) return false;
        auto f = vfs_->open(rels_[i]);
        if (!f) return false;
        out.resize((size_t)f->size());
        size_t got = 0;
        while (got < out.size()) {
            const int64_t r = f->read(out.data() + got, out.size() - got);
            if (r <= 0) return false;
            got += (size_t)r;
        }
        return true;
    }

private:
    std::shared_ptr<vfs::Vfs> vfs_;
    std::vector<ArchiveEntry> entries_;
    std::vector<std::string> rels_;
};

std::unique_ptr<IArchive> make_folder_archive_impl(const std::shared_ptr<vfs::Vfs>& vfs,
                                                   const std::string& root_rel,
                                                   std::string* err) {
    constexpr size_t kMaxPages = 50000; // 防御：目录书不该这么大
    std::vector<ArchiveEntry> entries;
    std::vector<std::string> rels;
    std::vector<std::string> stack{root_rel};
    while (!stack.empty() && entries.size() < kMaxPages) {
        const std::string d = stack.back();
        stack.pop_back();
        std::vector<vfs::DirEntry> items;
        if (!vfs->list(d, &items)) continue;
        for (const auto& it : items) {
            if (it.st.is_dir) {
                stack.push_back(join_rel(d, it.name));
                continue;
            }
            if (!it.st.is_file) continue;
            if (!paths::is_image_ext(lower_ext_str(it.name))) continue;
            const std::string full = join_rel(d, it.name);
            ArchiveEntry e;
            e.name = rel_to(root_rel, full);
            e.size = it.st.size;
            e.uindex = (uint64_t)rels.size();
            entries.push_back(std::move(e));
            rels.push_back(full);
        }
    }
    if (entries.empty()) {
        if (err) *err = "no images found in folder";
        return nullptr;
    }
    // 两个数组按自然序一起重排
    std::vector<size_t> order(entries.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return paths::natural_less(entries[a].name, entries[b].name);
    });
    std::vector<ArchiveEntry> se;
    std::vector<std::string> sr;
    se.reserve(order.size());
    sr.reserve(order.size());
    for (size_t k : order) {
        ArchiveEntry e = entries[k];
        e.uindex = se.size();
        se.push_back(std::move(e));
        sr.push_back(rels[k]);
    }
    return std::make_unique<FolderArchive>(vfs, std::move(se), std::move(sr));
}

// ---- SMB 上的 RAR：整档落缓存后再本地打开 ----------------------------------
// （RAR 是顺序格式，UnRAR 需要真实文件句柄；压缩包封面/翻页都在上层缓存，
//   下载只发生在首次打开。）
bool fetch_to_cache(const std::shared_ptr<vfs::Vfs>& vfs, const std::string& rel,
                    std::string* err, std::string* out_local) {
    std::string dir;
    {
        std::lock_guard<std::mutex> g(g_cache_mtx);
        dir = g_cache_dir;
    }
    if (dir.empty()) {
        if (err) *err = "SMB 上的 RAR 需要缓存目录（未初始化）";
        return false;
    }
    vfs::FileStat st;
    if (!vfs->stat(rel, &st) || !st.is_file) {
        if (err) *err = "RAR 不存在: " + vfs->full_path(rel);
        return false;
    }
    std::error_code ec;
    std::filesystem::create_directories(paths::from_utf8(dir), ec);
    const std::string base =
        std::to_string(paths::fnv1a64(vfs->full_path(rel))) + "-" + std::to_string(st.size);
    const std::string local = dir + "/" + base + ".rar";
    if (std::filesystem::is_regular_file(paths::from_utf8(local), ec) &&
        (uint64_t)std::filesystem::file_size(paths::from_utf8(local), ec) == st.size) {
        *out_local = local;
        return true;
    }

    log_info("smb: 下载 RAR 到缓存 " + vfs->full_path(rel) + " (" +
             std::to_string(st.size / 1024 / 1024) + " MB)");
    auto f = vfs->open(rel);
    if (!f) {
        if (err) *err = "RAR 打开失败: " + vfs->full_path(rel);
        return false;
    }
    const std::string tmp = local + ".part";
    {
        std::ofstream out(paths::from_utf8(tmp), std::ios::binary | std::ios::trunc);
        if (!out) {
            if (err) *err = "缓存文件写不了: " + tmp;
            return false;
        }
        std::vector<uint8_t> buf(1 << 20);
        uint64_t done = 0, next_log = 32ull << 20;
        while (done < st.size) {
            const size_t want = (size_t)std::min<uint64_t>(buf.size(), st.size - done);
            const int64_t r = f->read(buf.data(), want);
            if (r <= 0) {
                out.close();
                std::filesystem::remove(paths::from_utf8(tmp), ec);
                if (err) *err = "下载中断 @" + std::to_string(done);
                return false;
            }
            out.write(reinterpret_cast<const char*>(buf.data()), r);
            done += (uint64_t)r;
            if (done >= next_log) {
                log_info("smb: RAR 缓存 " + std::to_string(done / 1024 / 1024) + " MB");
                next_log += 32ull << 20;
            }
        }
        out.flush();
        if (!out) {
            if (err) *err = "缓存写入失败";
            out.close();
            std::filesystem::remove(paths::from_utf8(tmp), ec);
            return false;
        }
    }
    std::filesystem::rename(paths::from_utf8(tmp), paths::from_utf8(local), ec);
    if (ec) {
        if (err) *err = "缓存重命名失败: " + ec.message();
        return false;
    }
    *out_local = local;
    return true;
}

} // namespace

void set_archive_cache_dir(const std::string& utf8_dir) {
    std::lock_guard<std::mutex> g(g_cache_mtx);
    g_cache_dir = utf8_dir;
}

std::unique_ptr<IArchive> open_archive_path(const std::string& utf8_path, std::string* err) {
    std::string rel, verr;
    auto vfs = vfs::vfs_for_path(utf8_path, &rel, &verr);
    if (!vfs) {
        if (err) *err = verr;
        return nullptr;
    }
    vfs::FileStat st;
    if (!vfs->stat(rel, &st)) {
        if (err) *err = "无法访问: " + utf8_path;
        return nullptr;
    }
    if (st.is_dir) return make_folder_archive_impl(vfs, rel, err);

    const std::string ext = paths::lower_ext(paths::from_utf8(utf8_path));
    if (paths::is_zip_ext(ext)) return make_zip_archive(vfs, rel, err);
    if (paths::is_rar_ext(ext)) {
#if defined(CS_HAS_RAR) && CS_HAS_RAR
        if (vfs->scheme() == "smb") {
            std::string local;
            if (!fetch_to_cache(vfs, rel, err, &local)) return nullptr;
            return make_rar_archive(local, err);
        }
        return make_rar_archive(utf8_path, err);
#else
        if (err) *err = "RAR support not compiled in";
        return nullptr;
#endif
    }
    if (err) *err = "unsupported container: ." + ext;
    return nullptr;
}

std::unique_ptr<IArchive> open_archive(const std::filesystem::path& p, std::string* err) {
    return open_archive_path(paths::path_to_utf8(p), err);
}

} // namespace cs
