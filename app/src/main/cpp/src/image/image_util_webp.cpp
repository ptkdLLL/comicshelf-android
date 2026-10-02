// WebP backend (libwebp). Isolated in its own translation unit so the third-party
// headers never leak into the rest of the code base.
#include "image/image_util.h"

#include <cstring>

#if defined(CS_HAS_WEBP) && CS_HAS_WEBP

#include "webp/decode.h"

namespace cs {

bool has_webp_support() { return true; }

bool decode_webp(const uint8_t* data, size_t size, ImageRGBA& out) {
    if (!data || size < 12) return false;
    if (std::memcmp(data, "RIFF", 4) != 0 || std::memcmp(data + 8, "WEBP", 4) != 0) return false;
    if (size > 0xFFFFFFFFull) return false; // libwebp takes a size_t, but cap for sanity

    int w = 0, h = 0;
    if (!WebPGetInfo(data, size, &w, &h) || w <= 0 || h <= 0) return false;

    uint8_t* px = WebPDecodeRGBA(data, size, &w, &h);
    if (!px) return false;

    out.w = w;
    out.h = h;
    out.pixels.assign(px, px + (size_t)w * h * 4);
    WebPFree(px);
    return true;
}

} // namespace cs

#else

namespace cs {
bool has_webp_support() { return false; }
bool decode_webp(const uint8_t*, size_t, ImageRGBA&) { return false; }
} // namespace cs

#endif
