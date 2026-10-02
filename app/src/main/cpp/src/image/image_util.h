#pragma once
// Image decode / resize / encode helpers.
//
// decode_image() tries several backends in order:
//     stb_image  -> WebP (libwebp) -> AVIF (libavif + libaom) -> WIC (Windows)
// so JPEG/PNG/BMP/GIF/TGA/PSD/PNM/HDR, WebP, AVIF and anything the OS codecs
// understand (TIFF, HEIF, ...) all work.
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace cs {

struct ImageRGBA {
    int w = 0;
    int h = 0;
    std::vector<uint8_t> pixels; // RGBA8, row-major, w*h*4

    bool empty() const { return w <= 0 || h <= 0 || pixels.size() < (size_t)w * h * 4; }
    size_t bytes() const { return pixels.size(); }
};

// Decodes any supported format from memory into RGBA8.
bool decode_image(const uint8_t* data, size_t size, ImageRGBA& out);

// Individual backends. Each sniffs its own signature and returns false quickly
// when the data is not in that format, or when the backend was not compiled in.
bool decode_webp(const uint8_t* data, size_t size, ImageRGBA& out);
bool decode_avif(const uint8_t* data, size_t size, ImageRGBA& out);
bool decode_wic(const uint8_t* data, size_t size, ImageRGBA& out);

// Compile-time capability probes (for the UI / diagnostics).
bool has_webp_support();
bool has_avif_support();

// CPU resampling algorithms used when building thumbnails / covers.
enum class Resample : int {
    Bilinear = 0, // fast, but aliases (moiré) on large downscales
    Box,          // area average over a box pyramid - best default, no moiré
    CatmullRom,   // bicubic (wider kernel when minifying) - sharper, slight ringing
};

// Downscales so the longest edge is <= max_dim (never upscales).
ImageRGBA downscale_to_fit(const ImageRGBA& src, int max_dim, Resample mode = Resample::Box);

// Encodes to JPEG at `quality` (1..100). Handles non-ASCII paths on Windows.
bool write_jpeg(const std::string& utf8_path, const ImageRGBA& img, int quality);

// Loads and decodes an image file from disk.
bool load_image_file(const std::string& utf8_path, ImageRGBA& out);

} // namespace cs
