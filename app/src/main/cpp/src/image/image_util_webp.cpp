// WebP backend (libwebp). Isolated in its own translation unit so the third-party
// headers never leak into the rest of the code base.
#include "image/image_util.h"

#include <cstring>

#if defined(CS_HAS_WEBP) && CS_HAS_WEBP

#include "webp/decode.h"
#include "webp/demux.h"

namespace cs {

namespace {

// 动画 WebP（VP8X + ANIM）用简单 API 解不了：取第一帧（合成后的画布），
// 与阅读器走 Android ImageDecoder 时"显示首帧"的行为一致（封面/预览足够）。
bool decode_anim_first_frame(const uint8_t* data, size_t size, ImageRGBA& out) {
    WebPData wd{data, size};
    WebPAnimDecoderOptions opt;
    if (!WebPAnimDecoderOptionsInit(&opt)) return false;
    opt.color_mode = MODE_RGBA;
    WebPAnimDecoder* dec = WebPAnimDecoderNew(&wd, &opt);
    if (!dec) return false;

    bool ok = false;
    WebPAnimInfo info;
    if (WebPAnimDecoderGetInfo(dec, &info) && info.canvas_width > 0 &&
        info.canvas_height > 0) {
        uint8_t* buf = nullptr;
        int ts = 0;
        if (WebPAnimDecoderGetNext(dec, &buf, &ts) && buf) {
            out.w = (int)info.canvas_width;
            out.h = (int)info.canvas_height;
            out.pixels.assign(buf, buf + (size_t)out.w * out.h * 4);
            ok = true;
        }
    }
    WebPAnimDecoderDelete(dec);
    return ok;
}

} // namespace

bool has_webp_support() { return true; }

bool decode_webp(const uint8_t* data, size_t size, ImageRGBA& out) {
    if (!data || size < 12) return false;
    if (std::memcmp(data, "RIFF", 4) != 0 || std::memcmp(data + 8, "WEBP", 4) != 0) return false;
    if (size > 0xFFFFFFFFull) return false; // libwebp takes a size_t, but cap for sanity

    int w = 0, h = 0;
    if (!WebPGetInfo(data, size, &w, &h) || w <= 0 || h <= 0) return false;

    uint8_t* px = WebPDecodeRGBA(data, size, &w, &h);
    if (px) {
        out.w = w;
        out.h = h;
        out.pixels.assign(px, px + (size_t)w * h * 4);
        WebPFree(px);
        return true;
    }
    return decode_anim_first_frame(data, size, out);
}

} // namespace cs

#else

namespace cs {
bool has_webp_support() { return false; }
bool decode_webp(const uint8_t*, size_t, ImageRGBA&) { return false; }
} // namespace cs

#endif
