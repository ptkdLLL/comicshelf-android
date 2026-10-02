// ZIP / CBZ backend built on miniz. Reads the central directory only; single
// entries are inflated to memory on demand (no full extraction, no temp files).
//
// IO 走 vfs::ByteSource：本地文件与 SMB 共用同一条路。SMB 上这是真正的
// 随机访问——只读中央目录 + 目标条目数据，几十 MB 的压缩包打开只需几百 KB
// 传输（SMB2 的 READ 请求自带偏移，seek 是本地操作）。
#include "archive/archive.h"

#include "util/logger.h"
#include "util/path_util.h"
#include "vfs/byte_source.h"
#include "vfs/vfs.h"

#include <memory>
#include <mutex>

#include "miniz.h"

namespace cs {
namespace {

// miniz 自定义读回调（pOpaque = vfs::ByteSource*）。返回实际读到的字节数；
// 短读会被 miniz 当成截断错误，这正是我们要的行为。
size_t zip_read_cb(void* opaque, mz_uint64 file_ofs, void* buf, size_t n) {
    auto* src = static_cast<vfs::ByteSource*>(opaque);
    return src->pread(file_ofs, buf, n) ? n : 0;
}

class ZipArchive : public IArchive {
public:
    ZipArchive() { mz_zip_zero_struct(&zip_); }
    ~ZipArchive() override {
        if (opened_) mz_zip_reader_end(&zip_);
    }

    bool open(const std::shared_ptr<vfs::Vfs>& vfs, const std::string& rel, std::string* err) {
        src_ = vfs::open_byte_source_on(vfs, rel, err);
        if (!src_) return false;

        zip_.m_pRead = zip_read_cb;
        zip_.m_pIO_opaque = src_.get();
        const mz_uint64 total = src_->size();
        if (total == 0 || !mz_zip_reader_init(&zip_, total, 0)) {
            if (err) *err = "miniz: not a valid zip";
            return false;
        }
        opened_ = true;

        const mz_uint n = mz_zip_reader_get_num_files(&zip_);
        std::vector<ArchiveEntry> raw;
        for (mz_uint i = 0; i < n; ++i) {
            mz_zip_archive_file_stat st;
            if (!mz_zip_reader_file_stat(&zip_, i, &st)) continue;
            if (st.m_is_directory) continue;
            std::string name = st.m_filename ? st.m_filename : "";
            const std::string ext = paths::lower_ext(paths::from_utf8(name));
            if (!paths::is_image_ext(ext)) continue;

            ArchiveEntry e;
            e.name = name;
            e.size = (uint64_t)st.m_uncomp_size;
            e.uindex = (uint64_t)i;
            raw.push_back(std::move(e));
        }
        if (raw.empty()) {
            if (err) *err = "no images in zip";
            return false;
        }
        sort_entries_naturally(raw);
        entries_ = std::move(raw);
        return true;
    }

    const std::vector<ArchiveEntry>& entries() const override { return entries_; }

    bool read(size_t i, std::vector<uint8_t>& out) override {
        std::lock_guard<std::mutex> lk(mtx_);
        if (!opened_ || i >= entries_.size()) return false;
        const ArchiveEntry& e = entries_[i];
        if (e.size == 0) {
            out.clear();
            return true;
        }
        out.resize((size_t)e.size);
        if (!mz_zip_reader_extract_to_mem(&zip_, (mz_uint)e.uindex, out.data(), (size_t)e.size, 0))
            return false;
        return true;
    }

private:
    mz_zip_archive zip_{};
    std::shared_ptr<vfs::ByteSource> src_;
    bool opened_ = false;
    std::vector<ArchiveEntry> entries_;
    std::mutex mtx_;
};

} // namespace

std::unique_ptr<IArchive> make_zip_archive(const std::shared_ptr<vfs::Vfs>& vfs,
                                           const std::string& rel, std::string* err) {
    auto a = std::make_unique<ZipArchive>();
    if (!a->open(vfs, rel, err)) return nullptr;
    return a;
}

} // namespace cs
