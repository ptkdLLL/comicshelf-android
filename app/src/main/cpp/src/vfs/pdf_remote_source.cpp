#include "vfs/pdf_remote_source.h"

namespace cs::vfs {

std::shared_ptr<PdfRemoteSource> PdfRemoteSource::open(const std::string& utf8_path,
                                                       std::string* err) {
    auto src = open_byte_source(utf8_path, err);
    if (!src) return nullptr;
    return std::shared_ptr<PdfRemoteSource>(new PdfRemoteSource(std::move(src)));
}

uint64_t PdfRemoteSource::size() const {
    return src_->size();
}

bool PdfRemoteSource::pread(uint64_t off, void* buf, size_t n) {
    if (n == 0) return true;
    std::lock_guard<std::mutex> lk(mu_);
    const uint64_t total = src_->size();
    if (off > total || n > total - off) return false;

    uint8_t* out = static_cast<uint8_t*>(buf);
    size_t done = 0;
    while (done < n) {
        const uint64_t abs = off + done;
        const uint64_t idx = abs / kBlock;
        Slot* slot = nullptr;
        for (auto& x : slots_) {
            if (x.idx == idx) {
                slot = &x;
                break;
            }
        }
        if (!slot) {
            // LRU victim: smallest age.
            Slot* victim = &slots_[0];
            for (auto& x : slots_) {
                if (x.age < victim->age) victim = &x;
            }
            const uint64_t base = idx * kBlock;
            const size_t want =
                static_cast<size_t>(std::min<uint64_t>(kBlock, total - base));
            victim->data.resize(want);
            if (!src_->pread(base, victim->data.data(), want)) return false;
            victim->idx = idx;
            victim->len = want;
            victim->age = ++tick_;
            slot = victim;
        } else {
            slot->age = ++tick_;
        }

        const size_t in_block = static_cast<size_t>(abs - idx * kBlock);
        if (in_block >= slot->len) return false;
        const size_t take = std::min(n - done, slot->len - in_block);
        std::memcpy(out + done, slot->data.data() + in_block, take);
        done += take;
    }
    return true;
}

}  // namespace cs::vfs
