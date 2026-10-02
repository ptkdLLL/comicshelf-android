#pragma once
// Comic text detector (ctd) on ncnn — GPU letterbox-1024 forward plus an
// on-device DB postprocess: binarize the probability map, connected
// components -> quads, unclip, score-filter, then merge lines into
// text blocks for the OCR stage. This replaces the Windows pipeline's
// CUDA torch detector with the same weights (ONNX -> ncnn fp16).
#include <cstdint>
#include <string>
#include <vector>

namespace ncnn {
class Net;
class Mat;
}

namespace cs::ocr {

struct DetBox {
    // axis-aligned rect in the ORIGINAL image coordinates
    float x0 = 0, y0 = 0, x1 = 0, y1 = 0;
    // dominant text angle inside (radians); vertical = true for CJK columns
    bool vertical = false;
    float score = 0;
};

struct DetTimings {
    double forward_ms = 0;
    double post_ms = 0;
    int lines = 0;
    int blocks = 0;
};

class ComicTextDetector {
public:
    ComicTextDetector();
    ~ComicTextDetector();

    bool init(const std::string& dir, bool use_gpu);
    bool ready() const { return ready_; }

    // rgba: 8-bit page pixels (stride = w*4). Detect size fixed at 1024.
    // Returns text blocks (merged lines) in original coordinates.
    std::vector<DetBox> detect(const uint8_t* rgba, int w, int h);

    const DetTimings& last_timings() const { return t_; }

private:
    struct Impl;
    Impl* impl_ = nullptr;
    bool ready_ = false;
    DetTimings t_;
};

} // namespace cs::ocr
