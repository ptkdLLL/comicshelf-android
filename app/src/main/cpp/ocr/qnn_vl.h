#pragma once
// PaddleOCR-VL-For-Manga on the Hexagon NPU (QNN/HTP).
//
// Numerically verified pipeline (host == model.generate, byte-identical text):
//   crop --letterbox--> 448x448 --patchify--> [1024,3,14,14]
//   -> vision graph (SigLIP 27L, pos-embed/2D-rope baked)  -> [1024,1152]
//   -> 2x2 merge                                           -> [256,4608]
//   -> merger graph                                        -> [256,1024]
//   -> prompt embeds (269 = 13 text + 256 image slots)
//   -> prefill: 8x chunk graph (T=32) + 13 step graph rows
//   -> greedy decode (step graph, KV cache slots patched in place)
//   -> vocab.tsv detokenize
// All four graphs live in ONE context binary (one DSP session per process).
#include <cstdint>
#include <string>
#include <vector>

#include "paddle_vl.h"   // 复用 VlTimings（ncnn 引擎同款统计结构）

namespace cs::ocr {

class QnnVl {
public:
    QnnVl();
    ~QnnVl();

    // dir contains: vl_ctx.bin (4 graphs), vl_embed_f16.bin, vl_vocab.tsv,
    // vl_prompt_ids.i64, vl_mrope.i32 (see stage_vl.py for the exact pack).
    bool init(const std::string& dir, std::string* err = nullptr);
    bool ready() const { return ready_; }

    // rgba: 8-bit RGBA crop (or page), stride w*4. Returns text with '\n'
    // between detected lines, empty on failure.
    std::string ocr(const uint8_t* rgba, int w, int h);

    // 页面级批量 OCR：一批裁剪一次批量 vision+merger（batch=4 图），
    // 再逐段 prefill+decode。指针在调用期间有效；返回与输入等长的文本。
    // vision 图 1067ms/exec 中 57% 是 softmax+eltwise（内存受限），批处理
    // 摊薄其固定成本（真机 profiling 依据，见 BALLOONTRANSLATOR_WORKFLOW.md）。
    // gen_caps（可选，与 rgba 等长）：逐段生成上限——按裁剪尺寸收紧（漫画台词
    // 10~30 字），噪声块不再跑满 kGenMax=51（§8 #8）。缺省/0 = kGenMax。
    std::vector<std::string> ocr_batch(const std::vector<const uint8_t*>& rgba,
                                       const std::vector<int>& ws,
                                       const std::vector<int>& hs,
                                       const std::vector<int>* gen_caps = nullptr);

    const VlTimings& last_timings() const { return t_; }

private:
    // 批量准备：letterbox+patchify(批) -> vision -> merger -> 每段完整
    // inputs_embeds [kPromptLen*kH]（prompt 文本嵌入 + 图像段）。
    std::vector<std::vector<float>> prepare_batch_(const std::vector<const uint8_t*>& rgba,
                                                   const std::vector<int>& ws,
                                                   const std::vector<int>& hs);
    // 单段解码：emb（完整 inputs_embeds）-> 文本（prefill + greedy decode）。
    // gen_max：本段生成上限（≤0 → kGenMax）。
    std::string decode_one_(const float* emb, int gen_max = 0);
    struct Impl;
    Impl* impl_ = nullptr;
    bool ready_ = false;
    VlTimings t_;
};

}  // namespace cs::ocr
