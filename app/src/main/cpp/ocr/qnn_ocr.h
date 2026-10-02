#pragma once
// Manga OCR on the Hexagon NPU (HTP V73) through Qualcomm AI Engine Direct.
//
// Two graphs, both compiled offline for V73 and shipped as QNN context
// binaries (rec_ctx.bin / det_ctx.bin), so the app never pays a graph
// compile on device:
//
//   det : 960x960 page   -> DB probability map   (PP-OCRv5 mobile det)
//   rec : 48x320 textline -> CTC logits [40,18385] (PP-OCRv5 mobile rec, ja+vertical)
//
// Runtime libraries (libQnnHtp.so, libQnnHtpV73Stub.so) are bundled in the
// APK; the DSP-side skel is taken from the device firmware and pointed at
// through ADSP_LIBRARY_PATH.
#include <cstdint>
#include <string>
#include <vector>

#include "ctd_det.h"

namespace cs::ocr {

struct QnnTimings {
    double pre_ms = 0;   // det preprocess
    double fwd_ms = 0;   // det NPU forward
    double post_ms = 0;  // det DB postprocess
    double rec_ms = 0;   // cumulative recognition time on the last page
    double total_ms = 0;
    int lines = 0;
    int rec_lines = 0;
};

class QnnOcr {
public:
    QnnOcr();
    ~QnnOcr();

    // modelsDir: directory holding det_ctx.bin / rec_ctx.bin / ppocrv5_dict.txt
    //            (an assets-extracted copy is fine).
    // dataDir   : app-private writable dir (used to stage the DSP skel).
    bool init(const std::string& modelsDir, const std::string& dataDir);
    bool ready() const { return ready_; }
    const std::string& last_error() const { return err_; }

    // Text line detection on an 8-bit RGBA page (stride = w*4).
    // Returned boxes are in the coordinate system of the passed buffer.
    std::vector<DetBox> detect(const uint8_t* rgba, int w, int h);

    // Recognition of one text-line crop; returns UTF-8 text (may be empty).
    std::string recognize(const uint8_t* rgba, int w, int h);

    const QnnTimings& last_timings() const { return t_; }
    void reset_timings() { t_ = QnnTimings{}; }

private:
    // One column piece (already normalised, planar CHW) -> CTC text.
    std::string recognize_piece_(const uint8_t* rgba, int pw, int sh);
    struct Impl;
    Impl* impl_ = nullptr;
    bool ready_ = false;
    QnnTimings t_;
    std::string err_;
};

} // namespace cs::ocr
