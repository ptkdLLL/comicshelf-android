// RAR / CBR backend built on the UnRAR sources (RARDLL interface).
// Because RAR is a sequential format we can only read an entry by scanning from
// the start; the requested entry is extracted into a private temp file which is
// deleted immediately. Covers/pages are cached above this layer, so the cost is
// paid once per page at most.
#include "archive/archive.h"

#include "util/logger.h"
#include "util/path_util.h"

#if defined(CS_HAS_RAR) && CS_HAS_RAR

#include <cstdlib>
#include <fstream>
#include <mutex>

#ifdef _WIN32
#include <windows.h>
#endif
#include "dll.hpp"

namespace cs {
namespace {

bool read_file_bytes(const std::filesystem::path& p, std::vector<uint8_t>& out) {
    std::ifstream in(p, std::ios::binary);
    if (!in) return false;
    in.seekg(0, std::ios::end);
    std::streamoff n = in.tellg();
    if (n < 0) return false;
    in.seekg(0, std::ios::beg);
    out.resize((size_t)n);
    if (n > 0) in.read(reinterpret_cast<char*>(out.data()), n);
    return in.good() || in.eof();
}

class RarArchive : public IArchive {
public:
    ~RarArchive() override {
        if (!temp_dir_.empty()) {
            std::error_code ec;
            std::filesystem::remove_all(temp_dir_, ec);
        }
    }

    bool open(const std::string& local_utf8, std::string* err) {
        path_ = paths::from_utf8(local_utf8);
#ifdef _WIN32
        wide_path_ = path_.wstring();
#else
        // POSIX: UnRAR's narrow interface takes UTF-8 natively.
        narrow_path_ = local_utf8;
#endif

        HANDLE h = open_handle(RAR_OM_LIST_INCSPLIT);
        if (!h) {
            if (err) *err = "unrar: cannot open archive";
            return false;
        }

        RARHeaderDataEx hdr{};
        int rc = RARReadHeaderEx(h, &hdr);
        uint64_t order = 0;
        std::vector<ArchiveEntry> raw;
        while (rc == 0) {
            bool is_dir = (hdr.Flags & RHDF_DIRECTORY) != 0;
            if (!is_dir) {
#ifdef _WIN32
                std::string name = paths::to_utf8(hdr.FileNameW);
                if (name.empty()) name = hdr.FileName;
#else
                std::string name = hdr.FileName;
#endif
                std::string ext = paths::lower_ext(paths::from_utf8(name));
                if (paths::is_image_ext(ext)) {
                    ArchiveEntry e;
                    e.name = name;
                    e.size = (uint64_t)hdr.UnpSize | ((uint64_t)hdr.UnpSizeHigh << 32);
                    e.uindex = order;
                    raw.push_back(std::move(e));
                }
            }
            ++order;
            int sr = RARProcessFile(h, RAR_SKIP, nullptr, nullptr);
            if (sr != 0) break;
            rc = RARReadHeaderEx(h, &hdr);
        }
        RARCloseArchive(h);

        if (raw.empty()) {
            if (err) *err = "no images in rar";
            return false;
        }
        sort_entries_naturally(raw);
        entries_ = std::move(raw);
        return true;
    }

    const std::vector<ArchiveEntry>& entries() const override { return entries_; }

    bool read(size_t i, std::vector<uint8_t>& out) override {
        std::lock_guard<std::mutex> lk(mtx_);
        if (i >= entries_.size()) return false;
        ensure_temp_dir();
        if (temp_dir_.empty()) return false;

        // Start from a clean private directory so the post-extract scan below can
        // only ever see this extraction's output.
        std::error_code ec;
        std::filesystem::remove_all(temp_dir_, ec);
        std::filesystem::create_directories(temp_dir_, ec);
        if (ec) return false;

        HANDLE h = open_handle(RAR_OM_EXTRACT);
        if (!h) {
            log_warn("rar: cannot open archive for extraction");
            return false;
        }

        const uint64_t target = entries_[i].uindex;
        // Destination DIRECTORY only (no DestName). If a bare DestName is passed,
        // UnRAR uses it as the ENTIRE destination path (see extract.cpp: it sets
        // DestFileName = DllDestName without joining ExtrPath) and writes the file
        // into the process working directory, so our temp-dir lookup would fail and
        // the page would never display. With DestName == nullptr, UnRAR builds
        // "<temp>/<stored relative name>" under our private directory.
#ifdef _WIN32
        const std::wstring dest = temp_dir_.wstring();

        bool ok = false;
        int extract_code = 0;
        RARHeaderDataEx hdr{};
        int rc = RARReadHeaderEx(h, &hdr);
        uint64_t order = 0;
        while (rc == 0) {
            if (order == target) {
                int pr = RARProcessFileW(h, RAR_EXTRACT, const_cast<wchar_t*>(dest.c_str()),
                                         nullptr);
#else
        const std::string dest = temp_dir_.string();

        bool ok = false;
        int extract_code = 0;
        RARHeaderDataEx hdr{};
        int rc = RARReadHeaderEx(h, &hdr);
        uint64_t order = 0;
        while (rc == 0) {
            if (order == target) {
                int pr = RARProcessFile(h, RAR_EXTRACT, const_cast<char*>(dest.c_str()),
                                        nullptr);
#endif
                if (pr == 0) {
                    // Take whatever was produced inside the private temp dir (the
                    // name may have been normalized/sanitized by UnRAR).
                    for (auto it = std::filesystem::recursive_directory_iterator(temp_dir_, ec);
                         !ec && it != std::filesystem::recursive_directory_iterator();
                         it.increment(ec)) {
                        std::error_code fec;
                        if (!it->is_regular_file(fec) || fec) continue;
                        ok = read_file_bytes(it->path(), out);
                        if (ok) break;
                    }
                } else {
                    extract_code = pr;
                }
                break;
            }
            if (RARProcessFile(h, RAR_SKIP, nullptr, nullptr) != 0) break;
            ++order;
            rc = RARReadHeaderEx(h, &hdr);
        }
        RARCloseArchive(h);

        std::filesystem::remove_all(temp_dir_, ec);
        if (!ok)
            log_warn("rar: failed to read entry " + std::to_string(i) + " (unrar code " +
                     std::to_string(extract_code) + ")");
        return ok;
    }

private:
    HANDLE open_handle(unsigned mode) {
        RAROpenArchiveDataEx data{};
        data.ArcName = nullptr;
#ifdef _WIN32
        data.ArcNameW = const_cast<wchar_t*>(wide_path_.c_str());
#else
        data.ArcName = const_cast<char*>(narrow_path_.c_str());
        data.ArcNameW = nullptr;
#endif
        data.OpenMode = mode;
        return RAROpenArchiveEx(&data);
    }

    void ensure_temp_dir() {
        if (!temp_dir_.empty()) return;
        std::error_code ec;
        // Android has no writable /tmp: the JNI layer points CS_TMPDIR at the
        // app's cache directory before any RAR is opened.
        std::filesystem::path base;
        if (const char* env = ::getenv("CS_TMPDIR"); env && *env)
            base = paths::from_utf8(env);
        else
            base = std::filesystem::temp_directory_path(ec);
        if (ec) return;
        auto dir = base / ("comicshelf_rar_" + std::to_string(paths::fnv1a64(paths::path_to_utf8(path_))));
        std::filesystem::create_directories(dir, ec);
        if (!ec) temp_dir_ = dir;
    }

    std::filesystem::path path_;
#ifdef _WIN32
    std::wstring wide_path_;
#else
    std::string narrow_path_;
#endif
    std::filesystem::path temp_dir_;
    std::vector<ArchiveEntry> entries_;
    std::mutex mtx_;
};

} // namespace

std::unique_ptr<IArchive> make_rar_archive(const std::string& local_utf8, std::string* err) {
    auto a = std::make_unique<RarArchive>();
    if (!a->open(local_utf8, err)) return nullptr;
    return a;
}

} // namespace cs

#endif // CS_HAS_RAR
