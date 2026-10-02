#include "image/thumbnail_cache.h"

#include "util/logger.h"
#include "util/path_util.h"

#include <filesystem>

namespace cs {

namespace {
// CPU resample algorithm baked into the cache key, so switching it makes the
// covers regenerate instead of showing stale thumbnails.
std::string g_variant = "box";
} // namespace

void ThumbnailCache::set_variant(const std::string& v) { g_variant = v; }
const std::string& ThumbnailCache::variant() { return g_variant; }

ThumbnailCache::ThumbnailCache(std::string covers_dir_utf8, size_t memory_budget_bytes)
    : dir_(std::move(covers_dir_utf8)), budget_(memory_budget_bytes) {
    std::error_code ec;
    std::filesystem::create_directories(paths::from_utf8(dir_), ec);
}

void ThumbnailCache::set_memory_budget(size_t bytes) {
    std::lock_guard<std::mutex> lk(mtx_);
    budget_ = bytes;
    evict_locked();
}

std::string ThumbnailCache::make_key(const Book& b) {
    uint64_t h = paths::fnv1a64(b.path);
    h ^= (uint64_t)b.mtime * 1099511628211ull;
    h ^= (uint64_t)b.size * 0x9E3779B97F4A7C15ull;
    h ^= paths::fnv1a64(g_variant + "@thumb"); // different algorithm -> new key
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%016llx", (unsigned long long)h);
    return buf;
}

std::string ThumbnailCache::cover_file(const std::string& key) const {
    return dir_ + "/" + key + ".jpg";
}

ThumbPtr ThumbnailCache::try_get(const std::string& key) {
    {
        std::lock_guard<std::mutex> lk(mtx_);
        auto it = map_.find(key);
        if (it != map_.end()) {
            lru_.splice(lru_.begin(), lru_, it->second.pos);
            it->second.pos = lru_.begin();
            return it->second.img;
        }
    }

    // Miss in memory -> try disk (outside the lock to avoid blocking others).
    ImageRGBA img;
    if (!load_image_file(cover_file(key), img)) return nullptr;
    auto ptr = std::make_shared<const ImageRGBA>(std::move(img));

    std::lock_guard<std::mutex> lk(mtx_);
    auto existing = map_.find(key);
    if (existing != map_.end()) {
        lru_.splice(lru_.begin(), lru_, existing->second.pos);
        existing->second.pos = lru_.begin();
        return existing->second.img;
    }
    lru_.push_front(key);
    Node n;
    n.img = ptr;
    n.bytes = ptr->bytes();
    n.pos = lru_.begin();
    used_ += n.bytes;
    map_.emplace(key, n);
    evict_locked();
    return ptr;
}

void ThumbnailCache::store(const std::string& key, const ImageRGBA& img, int jpeg_quality) {
    if (img.empty()) return;
    write_jpeg(cover_file(key), img, jpeg_quality);

    auto ptr = std::make_shared<const ImageRGBA>(img);
    std::lock_guard<std::mutex> lk(mtx_);
    auto it = map_.find(key);
    if (it != map_.end()) {
        used_ -= it->second.bytes;
        it->second.img = ptr;
        it->second.bytes = ptr->bytes();
        used_ += it->second.bytes;
        lru_.splice(lru_.begin(), lru_, it->second.pos);
        it->second.pos = lru_.begin();
    } else {
        lru_.push_front(key);
        Node n;
        n.img = ptr;
        n.bytes = ptr->bytes();
        n.pos = lru_.begin();
        used_ += n.bytes;
        map_.emplace(key, n);
    }
    evict_locked();
}

void ThumbnailCache::erase(const std::string& key) {
    std::lock_guard<std::mutex> lk(mtx_);
    auto it = map_.find(key);
    if (it != map_.end()) {
        used_ -= it->second.bytes;
        lru_.erase(it->second.pos);
        map_.erase(it);
    }
    std::error_code ec;
    std::filesystem::remove(paths::from_utf8(cover_file(key)), ec);
}

size_t ThumbnailCache::memory_bytes() const {
    std::lock_guard<std::mutex> lk(mtx_);
    return used_;
}

void ThumbnailCache::clear_all() {
    {
        std::lock_guard<std::mutex> lk(mtx_);
        map_.clear();
        lru_.clear();
        used_ = 0;
    }
    std::error_code ec;
    const auto dir = paths::from_utf8(dir_);
    if (std::filesystem::exists(dir, ec)) {
        for (auto& e : std::filesystem::directory_iterator(dir, ec))
            std::filesystem::remove_all(e.path(), ec);
    }
}

void ThumbnailCache::evict_locked() {
    while (used_ > budget_ && !lru_.empty()) {
        const std::string victim = lru_.back();
        lru_.pop_back();
        auto it = map_.find(victim);
        if (it != map_.end()) {
            used_ -= it->second.bytes;
            map_.erase(it);
        }
    }
}

} // namespace cs
