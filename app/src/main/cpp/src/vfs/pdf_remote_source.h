#pragma once
// Random-access PDF byte source with a small block cache.
//
// SMB PDFs are read on demand through a ProxyFileDescriptor; PDFium issues
// many small reads (xref, object streams, page resources). Reading each one
// through libsmb2 would pay a round trip per call, so this class coalesces
// them into 1 MiB aligned blocks with a tiny LRU. It is intentionally not a
// whole-file cache: the 99 MB comic sample touches only ~2 MB for three pages.
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "vfs/byte_source.h"

namespace cs::vfs {

class PdfRemoteSource {
public:
    static std::shared_ptr<PdfRemoteSource> open(const std::string& utf8_path,
                                                 std::string* err);

    uint64_t size() const;
    // Reads exactly n bytes at off. Returns false when the range is invalid or
    // the underlying source fails; the JNI layer clamps requests to EOF first.
    bool pread(uint64_t off, void* buf, size_t n);

private:
    explicit PdfRemoteSource(std::shared_ptr<ByteSource> src) : src_(std::move(src)) {}

    static constexpr size_t kBlock = 1u << 20;  // 1 MiB
    // The 47 MB text sample touches ~11.5 MB while rendering page 0 (cover),
    // so 8 slots thrash; 12 slots keep the cover working set resident.
    static constexpr size_t kSlots = 12;

    struct Slot {
        uint64_t idx = ~0ull;
        uint64_t age = 0;
        std::vector<uint8_t> data;
        size_t len = 0;
    };

    std::shared_ptr<ByteSource> src_;
    mutable std::mutex mu_;
    std::array<Slot, kSlots> slots_{};
    uint64_t tick_ = 0;
};

}  // namespace cs::vfs
