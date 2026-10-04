// 本地文件系统实现：std::filesystem + ifstream。
#include "vfs/vfs.h"

#include <filesystem>
#include <fstream>

#include "util/path_util.h"

namespace cs::vfs {
namespace {

namespace fs = std::filesystem;

// Portable file_time -> epoch seconds（与 scanner 同款）
int64_t to_epoch(fs::file_time_type ft) {
    using namespace std::chrono;
    auto sys = time_point_cast<system_clock::duration>(
        ft - fs::file_time_type::clock::now() + system_clock::now());
    return (int64_t)system_clock::to_time_t(sys);
}

class LocalFile : public File {
public:
    explicit LocalFile(const std::string& p) : path_(p), in_(p, std::ios::binary) {}
    bool ok() const { return (bool)in_; }

    int64_t read(uint8_t* buf, size_t len) override {
        in_.read(reinterpret_cast<char*>(buf), (std::streamsize)len);
        const auto got = in_.gcount();
        if (got == 0 && in_.bad()) return -1;
        return (int64_t)got;
    }
    bool seek(uint64_t off) override {
        in_.clear();
        in_.seekg((std::streamoff)off, std::ios::beg);
        return (bool)in_;
    }
    uint64_t size() override {
        std::error_code ec;
        const auto n = fs::file_size(path_, ec);
        return ec ? 0 : (uint64_t)n;
    }
    uint64_t tell() override { return (uint64_t)in_.tellg(); }

private:
    std::string path_;
    std::ifstream in_;
};

class LocalVfs : public Vfs {
public:
    explicit LocalVfs(std::string root) : root_(std::move(root)) {}

    std::string scheme() const override { return "file"; }
    std::string display() const override { return root_; }
    std::string full_path(const std::string& rel) const override {
        if (root_.empty() || rel.empty()) return root_.empty() ? rel : root_;
        if (root_.back() == '/' || rel.front() == '/') return root_ + rel;
        return root_ + "/" + rel;
    }

    bool stat(const std::string& path, FileStat* out) override {
        std::error_code ec;
        const fs::path p = fs::path(paths::from_utf8(path));
        const auto s = fs::status(p, ec);
        if (ec) return false;
        out->is_dir = fs::is_directory(s);
        out->is_file = fs::is_regular_file(s);
        out->size = out->is_file ? (uint64_t)fs::file_size(p, ec) : 0;
        if (ec) out->size = 0;
        std::error_code tec;
        const auto ft = fs::last_write_time(p, tec);
        out->mtime = tec ? 0 : (int64_t)to_epoch(ft);
        return true;
    }

    bool list(const std::string& path, std::vector<DirEntry>* out) override {
        std::error_code ec;
        fs::directory_iterator it(fs::path(paths::from_utf8(path)),
                                  fs::directory_options::skip_permission_denied, ec);
        if (ec) return false;
        for (; it != fs::directory_iterator(); it.increment(ec)) {
            if (ec) break;
            DirEntry e;
            e.name = paths::path_to_utf8(it->path().filename());
            std::error_code fec;
            const auto s = it->status(fec);
            if (fec) continue;
            e.st.is_dir = fs::is_directory(s);
            e.st.is_file = fs::is_regular_file(s);
            if (e.st.is_file) {
                std::error_code sec;
                e.st.size = (uint64_t)it->file_size(sec);
                if (sec) e.st.size = 0;
            }
            std::error_code tec;
            const auto ft = it->last_write_time(tec);
            e.st.mtime = tec ? 0 : (int64_t)to_epoch(ft);
            out->push_back(std::move(e));
        }
        return true;
    }

    std::unique_ptr<File> open(const std::string& path) override {
        auto f = std::make_unique<LocalFile>(path);
        if (!f->ok()) return nullptr;
        return f;
    }

private:
    std::string root_;   // 未使用（本地为绝对路径透传），保留以复用 display
};

}  // namespace

std::shared_ptr<Vfs> make_local_vfs() { return std::make_shared<LocalVfs>(""); }

}  // namespace cs::vfs
