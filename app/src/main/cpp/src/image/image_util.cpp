#include "image/image_util.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#define STBI_ONLY_PNG
#define STBI_ONLY_BMP
#define STBI_ONLY_GIF
#define STBI_ONLY_TGA
#define STBI_ONLY_PSD
#define STBI_ONLY_PIC
#define STBI_ONLY_PNM
#define STBI_ONLY_HDR
#include "stb_image.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#ifdef _WIN32
#include <windows.h>
#include <objbase.h>
#include <wincodec.h>
#endif

namespace cs {

namespace {

bool stb_decode(const uint8_t* data, size_t size, ImageRGBA& out) {
    int w = 0, h = 0, comp = 0;
    stbi_uc* px = stbi_load_from_memory(data, (int)size, &w, &h, &comp, 4);
    if (!px) return false;
    out.w = w;
    out.h = h;
    out.pixels.assign(px, px + (size_t)w * h * 4);
    stbi_image_free(px);
    return true;
}

} // namespace

#ifdef _WIN32

namespace {
// COM must be initialized on the calling thread before WIC can be used. Worker
// threads have not done so, hence the lazy per-thread guard.
struct ComGuard {
    bool owns_ref = false;
    ComGuard() {
        HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        owns_ref = (hr == S_OK || hr == S_FALSE);
        // RPC_E_CHANGED_MODE means COM is already up in another mode: usable.
    }
    ~ComGuard() {
        if (owns_ref) CoUninitialize();
    }
};
ComGuard& com_guard() {
    thread_local ComGuard g;
    return g;
}
} // namespace

bool decode_wic(const uint8_t* data, size_t size, ImageRGBA& out) {
    if (!data || size == 0) return false;
    com_guard();

    IWICImagingFactory* factory = nullptr;
    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                IID_PPV_ARGS(&factory))) ||
        !factory)
        return false;

    IWICStream* stream = nullptr;
    IWICBitmapDecoder* decoder = nullptr;
    IWICBitmapFrameDecode* frame = nullptr;
    IWICFormatConverter* conv = nullptr;
    bool ok = false;

    do {
        if (FAILED(factory->CreateStream(&stream))) break;
        if (FAILED(stream->InitializeFromMemory(const_cast<BYTE*>(data), (DWORD)size))) break;
        if (FAILED(factory->CreateDecoderFromStream(stream, nullptr, WICDecodeMetadataCacheOnDemand,
                                                    &decoder)))
            break;
        if (FAILED(decoder->GetFrame(0, &frame))) break;
        if (FAILED(factory->CreateFormatConverter(&conv))) break;
        if (FAILED(conv->Initialize(frame, GUID_WICPixelFormat32bppRGBA, WICBitmapDitherTypeNone,
                                    nullptr, 0.0, WICBitmapPaletteTypeCustom)))
            break;

        UINT w = 0, h = 0;
        if (FAILED(conv->GetSize(&w, &h)) || w == 0 || h == 0) break;
        out.w = (int)w;
        out.h = (int)h;
        out.pixels.resize((size_t)w * h * 4);
        const UINT stride = w * 4;
        const UINT bytes = stride * h;
        if (FAILED(conv->CopyPixels(nullptr, stride, bytes, out.pixels.data()))) {
            out = ImageRGBA{};
            break;
        }
        ok = true;
    } while (0);

    if (conv) conv->Release();
    if (frame) frame->Release();
    if (decoder) decoder->Release();
    if (stream) stream->Release();
    factory->Release();
    return ok;
}

#else

bool decode_wic(const uint8_t*, size_t, ImageRGBA&) { return false; }

#endif // _WIN32

bool decode_image(const uint8_t* data, size_t size, ImageRGBA& out) {
    if (!data || size == 0) return false;

    // Cheap magic-byte probes first so we do not run every decoder on each page.
    const bool webp = size >= 12 && std::memcmp(data, "RIFF", 4) == 0 &&
                      std::memcmp(data + 8, "WEBP", 4) == 0;
    const bool avif = size >= 12 && std::memcmp(data + 4, "ftyp", 4) == 0;

    // Dedicated backends for the formats stb_image cannot handle.
    if (webp && decode_webp(data, size, out)) return true;
    if (avif && decode_avif(data, size, out)) return true;

    // The common case (JPEG/PNG/BMP/GIF/TGA/PSD/HDR/PNM).
    if (stb_decode(data, size, out)) return true;

    // Non-sniffed fallbacks: a mislabelled WebP/AVIF, then the OS codecs
    // (Windows Imaging Component covers TIFF/HEIF and can double as a safety net).
    if (!webp && decode_webp(data, size, out)) return true;
    if (!avif && decode_avif(data, size, out)) return true;
    if (decode_wic(data, size, out)) return true;
    return false;
}

namespace {

// Area average over an arbitrary source box -> removes the high frequency
// detail that causes moiré when a big scan is shown small.
ImageRGBA resample_box(const ImageRGBA& s, int tw, int th) {
    ImageRGBA d;
    d.w = tw;
    d.h = th;
    d.pixels.assign((size_t)tw * th * 4, 0);
    if (tw <= 0 || th <= 0) return d;

    for (int y = 0; y < th; ++y) {
        int sy0 = (int)((int64_t)y * s.h / th);
        int sy1 = (int)((int64_t)(y + 1) * s.h / th);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        if (sy1 > s.h) sy1 = s.h;
        for (int x = 0; x < tw; ++x) {
            int sx0 = (int)((int64_t)x * s.w / tw);
            int sx1 = (int)((int64_t)(x + 1) * s.w / tw);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            if (sx1 > s.w) sx1 = s.w;

            uint64_t acc[4] = {0, 0, 0, 0};
            uint32_t n = 0;
            for (int sy = sy0; sy < sy1; ++sy) {
                const uint8_t* row = s.pixels.data() + ((size_t)sy * s.w + sx0) * 4;
                for (int sx = sx0; sx < sx1; ++sx) {
                    acc[0] += row[0];
                    acc[1] += row[1];
                    acc[2] += row[2];
                    acc[3] += row[3];
                    row += 4;
                    ++n;
                }
            }
            uint8_t* o = d.pixels.data() + ((size_t)y * tw + x) * 4;
            if (n == 0) n = 1;
            for (int c = 0; c < 4; ++c) o[c] = (uint8_t)((acc[c] + n / 2) / n);
        }
    }
    return d;
}

ImageRGBA resample_bilinear(const ImageRGBA& src, int tw, int th) {
    const int sw = src.w, sh = src.h;
    ImageRGBA dst;
    dst.w = tw;
    dst.h = th;
    dst.pixels.resize((size_t)tw * th * 4);
    const uint8_t* s = src.pixels.data();
    uint8_t* d = dst.pixels.data();
    const double sx = (double)sw / (double)tw;
    const double sy = (double)sh / (double)th;

    for (int y = 0; y < th; ++y) {
        double fy = (y + 0.5) * sy - 0.5;
        if (fy < 0) fy = 0;
        int y0 = (int)fy;
        int y1 = y0 + 1 < sh ? y0 + 1 : sh - 1;
        double dy = fy - y0;
        for (int x = 0; x < tw; ++x) {
            double fx = (x + 0.5) * sx - 0.5;
            if (fx < 0) fx = 0;
            int x0 = (int)fx;
            int x1 = x0 + 1 < sw ? x0 + 1 : sw - 1;
            double dx = fx - x0;

            const uint8_t* p00 = s + ((size_t)y0 * sw + x0) * 4;
            const uint8_t* p10 = s + ((size_t)y0 * sw + x1) * 4;
            const uint8_t* p01 = s + ((size_t)y1 * sw + x0) * 4;
            const uint8_t* p11 = s + ((size_t)y1 * sw + x1) * 4;
            uint8_t* o = d + ((size_t)y * tw + x) * 4;
            for (int c = 0; c < 4; ++c) {
                double top = p00[c] + (p10[c] - p00[c]) * dx;
                double bot = p01[c] + (p11[c] - p01[c]) * dx;
                double v = top + (bot - top) * dy;
                o[c] = (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v + 0.5));
            }
        }
    }
    return dst;
}

double catmull_rom(double x) {
    x = x < 0 ? -x : x;
    if (x < 1.0) return 1.5 * x * x * x - 2.5 * x * x + 1.0;
    if (x < 2.0) return -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0;
    return 0.0;
}

// Catmull-Rom with the kernel widened by the minification factor, so large
// downscales are properly low-pass filtered instead of point sampled.
ImageRGBA resample_catmull_rom(const ImageRGBA& src, int tw, int th) {
    const int sw = src.w, sh = src.h;
    ImageRGBA dst;
    dst.w = tw;
    dst.h = th;
    dst.pixels.resize((size_t)tw * th * 4);
    if (sw <= 0 || sh <= 0 || tw <= 0 || th <= 0) return dst;

    const double sx = (double)sw / (double)tw;
    const double sy = (double)sh / (double)th;
    const double rx = sx < 1.0 ? 1.0 : sx;
    const double ry = sy < 1.0 ? 1.0 : sy;

    for (int y = 0; y < th; ++y) {
        const double cy = (y + 0.5) * sy;
        int y0 = (int)std::floor(cy - 2.0 * ry);
        int y1 = (int)std::ceil(cy + 2.0 * ry);
        for (int x = 0; x < tw; ++x) {
            const double cx = (x + 0.5) * sx;
            int x0 = (int)std::floor(cx - 2.0 * rx);
            int x1 = (int)std::ceil(cx + 2.0 * rx);

            double acc[4] = {0, 0, 0, 0};
            double wsum = 0.0;
            for (int syi = y0; syi <= y1; ++syi) {
                const double wy = catmull_rom((syi + 0.5 - cy) / ry);
                if (wy == 0.0) continue;
                int cyi = syi < 0 ? 0 : (syi >= sh ? sh - 1 : syi);
                const uint8_t* row = src.pixels.data() + (size_t)cyi * sw * 4;
                for (int sxi = x0; sxi <= x1; ++sxi) {
                    const double wx = catmull_rom((sxi + 0.5 - cx) / rx);
                    const double w = wx * wy;
                    if (w == 0.0) continue;
                    int cxi = sxi < 0 ? 0 : (sxi >= sw ? sw - 1 : sxi);
                    const uint8_t* p = row + (size_t)cxi * 4;
                    acc[0] += p[0] * w;
                    acc[1] += p[1] * w;
                    acc[2] += p[2] * w;
                    acc[3] += p[3] * w;
                    wsum += w;
                }
            }
            uint8_t* o = dst.pixels.data() + ((size_t)y * tw + x) * 4;
            if (wsum <= 0.0) wsum = 1.0;
            for (int c = 0; c < 4; ++c) {
                double v = acc[c] / wsum;
                o[c] = (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v + 0.5));
            }
        }
    }
    return dst;
}

} // namespace

ImageRGBA downscale_to_fit(const ImageRGBA& src, int max_dim, Resample mode) {
    if (src.empty() || max_dim <= 0) return {};

    const int sw = src.w, sh = src.h;
    int tw, th;
    if (sw >= sh) {
        tw = sw < max_dim ? sw : max_dim;
        th = (int)std::lround((double)sh * (double)tw / (double)sw);
    } else {
        th = sh < max_dim ? sh : max_dim;
        tw = (int)std::lround((double)sw * (double)th / (double)sh);
    }
    if (tw < 1) tw = 1;
    if (th < 1) th = 1;
    if (tw >= sw && th >= sh) return src; // never upscale

    if (mode == Resample::Bilinear) return resample_bilinear(src, tw, th);
    if (mode == Resample::CatmullRom) return resample_catmull_rom(src, tw, th);

    // Box: keep halving while that still gets us at or below 2x the target, so
    // each step averages real pixels (a cheap box pyramid == mipmap levels),
    // then finish with one exact area-average pass.
    ImageRGBA cur = src;
    while (cur.w >= tw * 2 && cur.h >= th * 2 && cur.w > 1 && cur.h > 1) {
        cur = resample_box(cur, std::max(1, cur.w / 2), std::max(1, cur.h / 2));
    }
    return resample_box(cur, tw, th);
}

namespace {
struct FileCtx {
    FILE* f = nullptr;
};
void jpg_write_cb(void* context, void* data, int size) {
    auto* ctx = static_cast<FileCtx*>(context);
    if (ctx && ctx->f) std::fwrite(data, 1, (size_t)size, ctx->f);
}
} // namespace

bool write_jpeg(const std::string& utf8_path, const ImageRGBA& img, int quality) {
    if (img.empty()) return false;
    FILE* f = nullptr;
#ifdef _WIN32
    std::wstring w = std::filesystem::path(utf8_path).wstring();
    if (_wfopen_s(&f, w.c_str(), L"wb") != 0) f = nullptr;
#else
    f = std::fopen(utf8_path.c_str(), "wb");
#endif
    if (!f) return false;

    FileCtx ctx{f};
    int ok = stbi_write_jpg_to_func(jpg_write_cb, &ctx, img.w, img.h, 4,
                                    img.pixels.data(), quality);
    std::fclose(f);
    return ok != 0;
}

bool load_image_file(const std::string& utf8_path, ImageRGBA& out) {
    std::ifstream in(std::filesystem::path(utf8_path), std::ios::binary);
    if (!in) return false;
    in.seekg(0, std::ios::end);
    std::streamoff n = in.tellg();
    if (n <= 0) return false;
    in.seekg(0, std::ios::beg);
    std::vector<uint8_t> buf((size_t)n);
    in.read(reinterpret_cast<char*>(buf.data()), n);
    return decode_image(buf.data(), buf.size(), out);
}

} // namespace cs
