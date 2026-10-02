#pragma once
// Two-level cover cache:
//   * disk   : downscaled JPEGs in <covers_dir>/<key>.jpg  (survives restarts)
//   * memory : bounded LRU of decoded RGBA thumbnails
// The memory budget keeps RAM flat no matter how many books exist.
#include "core/types.h"
#include "image/image_util.h"

#include <cstddef>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

namespace cs {

using ThumbPtr = std::shared_ptr<const ImageRGBA>;

class ThumbnailCache {
public:
    ThumbnailCache(std::string covers_dir_utf8, size_t memory_budget_bytes);

    void set_memory_budget(size_t bytes);
    size_t memory_budget() const { return budget_; }
    const std::string& covers_dir() const { return dir_; }

    // Deterministic cache key for a book (path + mtime + size + resample
    // variant), so switching the CPU resampling algorithm regenerates covers.
    static std::string make_key(const Book& b);
    static void set_variant(const std::string& v);
    static const std::string& variant();

    std::string cover_file(const std::string& key) const;

    // Returns a decoded thumbnail if it is in memory, else loads it from disk
    // (and caches it). Returns nullptr when nothing is cached for `key`.
    ThumbPtr try_get(const std::string& key);

    // Persists a generated cover to disk and puts it into the memory LRU.
    void store(const std::string& key, const ImageRGBA& img, int jpeg_quality = 85);

    // Drops both disk and memory entries.
    void erase(const std::string& key);
    /** 清空全部封面：磁盘 JPEG + 内存 LRU（设置页"释放"用）。 */
    void clear_all();

    size_t memory_bytes() const;

private:
    void touch_locked(const std::string& key, std::list<std::string>::iterator it);
    void evict_locked();

    struct Node {
        ThumbPtr img;
        size_t bytes = 0;
        std::list<std::string>::iterator pos;
    };

    std::string dir_;
    size_t budget_ = 0;
    size_t used_ = 0;
    std::list<std::string> lru_; // front = most recent
    std::unordered_map<std::string, Node> map_;
    mutable std::mutex mtx_;
};

} // namespace cs
