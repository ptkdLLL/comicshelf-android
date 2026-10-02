#pragma once
// On-device PaddleOCR-VL-For-Manga OCR engine (ncnn / Vulkan GPU).
//
// Mirrors the numerically-verified export in tools/export_vl.py:
//   patches -> vision(27L SigLIP+2D rope) -> merge(2x2) -> ERNIE-4.5
//   prefill(dynamic T) + step(KV grows via dynamic Concat) -> greedy decode.
// The reference Windows deployment ran the same weights on CUDA fp16; here
// the Adreno GPU executes the fp16 ncnn graphs.
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace ncnn {
class Net;
class Mat;
} // namespace ncnn

namespace cs::ocr {

struct VlTimings {
    double preprocess_ms = 0;
    double vision_ms = 0;
    double merger_ms = 0;
    double prefill_ms = 0;
    double decode_ms = 0;
    double total_ms = 0;
    int patches = 0;
    int image_tokens = 0;
    int out_tokens = 0;
};

class PaddleOcrVl {
public:
    PaddleOcrVl();
    ~PaddleOcrVl();

    // dir contains: vl_vision.ncnn.param/bin, vl_merger.*, vl_prefill.*,
    // vl_step.*, tokenizer.json, runtime.json
    bool init(const std::string& dir, bool use_gpu);
    bool ready() const { return ready_; }
    bool gpu_active() const { return gpu_active_; }

    // rgba: 8-bit RGBA input (a crop or a whole page), row stride = w*4.
    // Returns recognized text (lines joined by '\n'), empty on failure.
    std::string ocr(const uint8_t* rgba, int w, int h);

    const VlTimings& last_timings() const { return t_; }

private:
    struct Impl;
    Impl* impl_ = nullptr;
    bool ready_ = false;
    bool gpu_active_ = false;
    VlTimings t_;
};

} // namespace cs::ocr
