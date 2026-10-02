#pragma once
// CTBD (comic-text-and-bubble-detector, RT-DETR-V2) on the Hexagon NPU.
//
// Same BalloonTranslator detector as modules/textdetector/detector_ctbd.py:
//   page --bilinear stretch--> 640x640 --/255, RGB--> NCHW fp16
//   -> ctbd_ctx.bin graph -> scores[300] / boxes[300,4] / labels[300]
//   -> BT postprocess: conf>=0.3, drop <5px, dedup IoU>=0.7,
//      remove contained>=0.8; label 0=bubble, 1/2=text.
//
// Verified on-device (see docs/BALLOONTRANSLATOR_WORKFLOW.md): 21 boxes vs
// onnxruntime CPU reference, max coord deviation 0.88px, labels equal,
// ~244ms/inference. App-side buffers follow the ctx tensor dtypes (fp16 for
// the float tensors, int32-compatible read for labels).
#include <cstdint>
#include <string>
#include <vector>

namespace cs::ocr {

struct CtbdBox {
    float x0 = 0, y0 = 0, x1 = 0, y1 = 0;   // page coordinates
    float score = 0;
    int label = 2;                           // 0=bubble 1/2=text
    bool bubble = false;
    bool vertical = false;
};

class QnnCtbd {
public:
    QnnCtbd();
    ~QnnCtbd();

    // dir contains ctbd_ctx.bin (+ libQnnHtpV73Skel.so alongside).
    bool init(const std::string& dir, std::string* err = nullptr);
    bool ready() const { return ready_; }

    // rgba: 8-bit RGBA page, stride w*4. Boxes come back in page space.
    std::vector<CtbdBox> detect(const uint8_t* rgba, int w, int h);

    float last_fwd_ms() const { return fwd_ms_; }
    float last_post_ms() const { return post_ms_; }

private:
    struct Impl;
    Impl* impl_ = nullptr;
    bool ready_ = false;
    float fwd_ms_ = 0, post_ms_ = 0;
};

}  // namespace cs::ocr
