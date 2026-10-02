#include "vfs/byte_source.h"

namespace cs::vfs {
namespace {

class VfsByteSource : public ByteSource {
public:
    VfsByteSource(std::shared_ptr<Vfs> vfs, std::string rel, std::unique_ptr<File> f)
        : vfs_(std::move(vfs)), rel_(std::move(rel)), f_(std::move(f)) {}

    uint64_t size() const override { return f_->size(); }
    std::string name() const override { return vfs_->full_path(rel_); }

    bool pread(uint64_t off, void* buf, size_t n) override {
        if (n == 0) return true;
        std::lock_guard<std::mutex> g(m_);
        if (!f_->seek(off)) return false;
        uint8_t* p = reinterpret_cast<uint8_t*>(buf);
        size_t got = 0;
        while (got < n) {
            const int64_t r = f_->read(p + got, n - got);
            if (r <= 0) return false;
            got += (size_t)r;
        }
        return true;
    }

private:
    std::shared_ptr<Vfs> vfs_;
    std::string rel_;
    std::unique_ptr<File> f_;
    std::mutex m_;
};

} // namespace

bool ByteSource::read_all(std::vector<uint8_t>* out) {
    const uint64_t n = size();
    if (n == 0 || n > (uint64_t)1 << 32) { // 单个条目不该超过 4GB
        out->clear();
        return n == 0;
    }
    out->resize((size_t)n);
    return pread(0, out->data(), (size_t)n);
}

std::shared_ptr<ByteSource> open_byte_source_on(const std::shared_ptr<Vfs>& vfs,
                                                const std::string& rel,
                                                std::string* err) {
    if (!vfs) {
        if (err) *err = "vfs 为空";
        return nullptr;
    }
    auto f = vfs->open(rel);
    if (!f) {
        if (err) *err = "打不开: " + vfs->full_path(rel);
        return nullptr;
    }
    return std::make_shared<VfsByteSource>(vfs, rel, std::move(f));
}

std::shared_ptr<ByteSource> open_byte_source(const std::string& utf8_path,
                                             std::string* err) {
    std::string rel;
    auto vfs = vfs_for_path(utf8_path, &rel, err);
    if (!vfs) return nullptr;
    return open_byte_source_on(vfs, rel, err);
}

} // namespace cs::vfs
