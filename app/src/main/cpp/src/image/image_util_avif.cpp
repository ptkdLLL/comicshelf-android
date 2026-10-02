// AVIF backend (libavif + libaom decoder), isolated in its own translation unit.
#include "image/image_util.h"

#include <cstring>

#if defined(CS_HAS_AVIF) && CS_HAS_AVIF

#include "avif/avif.h"

namespace cs {

bool has_avif_support() { return true; }

bool decode_avif(const uint8_t* data, size_t size, ImageRGBA& out) {
    if (!data || size < 16) return false;
    // ISO-BMFF: '....ftyp<brand>'
    if (std::memcmp(data + 4, "ftyp", 4) != 0) return false;
    const char* brand = reinterpret_cast<const char*>(data + 8);
    if (std::memcmp(brand, "avif", 4) != 0 && std::memcmp(brand, "avis", 4) != 0 &&
        std::memcmp(brand, "mif1", 4) != 0)
        return false;

    avifDecoder* decoder = avifDecoderCreate();
    if (!decoder) return false;
    decoder->maxThreads = 4;

    bool ok = false;
    avifRGBImage rgb;
    std::memset(&rgb, 0, sizeof(rgb));
    bool rgb_allocated = false;

    do {
        if (avifDecoderSetIOMemory(decoder, data, size) != AVIF_RESULT_OK) break;
        if (avifDecoderParse(decoder) != AVIF_RESULT_OK) break;
        if (avifDecoderNextImage(decoder) != AVIF_RESULT_OK) break;

        avifImage* image = decoder->image;
        if (!image || image->width == 0 || image->height == 0) break;

        avifRGBImageSetDefaults(&rgb, image);
        rgb.format = AVIF_RGB_FORMAT_RGBA;
        rgb.depth = 8;
        rgb.alphaPremultiplied = AVIF_FALSE;

        if (avifRGBImageAllocatePixels(&rgb) != AVIF_RESULT_OK) break;
        rgb_allocated = true;

        if (avifImageYUVToRGB(image, &rgb) != AVIF_RESULT_OK) break;

        out.w = (int)rgb.width;
        out.h = (int)rgb.height;
        const size_t row_bytes = (size_t)rgb.width * 4;
        out.pixels.resize(row_bytes * rgb.height);
        // Respect rowBytes in case the converter padded the rows.
        for (uint32_t y = 0; y < rgb.height; ++y) {
            std::memcpy(out.pixels.data() + (size_t)y * row_bytes,
                        rgb.pixels + (size_t)y * rgb.rowBytes, row_bytes);
        }
        ok = true;
    } while (0);

    if (rgb_allocated) avifRGBImageFreePixels(&rgb);
    avifDecoderDestroy(decoder);
    if (!ok) out = ImageRGBA{};
    return ok;
}

} // namespace cs

#else

namespace cs {
bool has_avif_support() { return false; }
bool decode_avif(const uint8_t*, size_t, ImageRGBA&) { return false; }
} // namespace cs

#endif
