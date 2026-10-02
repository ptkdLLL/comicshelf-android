// ctd detector runtime: GPU forward + DB-style postprocess.
//
// Forward mirrors BallonsTranslator's TextDetector:
//   letterbox to 1024^2 (pad, no stretch), /255, RGB.
//   outputs: blk [1,64512,7] (yolo head, unused here), seg [1,1,1024,1024]
//   (bubble mask), det [1,2,1024,1024] (DB prob + threshold map).
// Postprocess (simplified SegDetectorRepresenter + grouping):
//   prob>0.3 -> connected components -> min-area quads -> unclip(1.8)
//   -> score filter 0.6 -> vertical/horizontal classification
//   -> proximity merge into blocks.
#include "ocr/ctd_det.h"

#include "mat.h"
#include "net.h"
#include "util/logger.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <deque>
#include <functional>
#include <numeric>
#include <vector>

namespace cs::ocr {

namespace {

constexpr int kDetect = 1024;
constexpr float kBinThresh = 0.3f;
constexpr float kBoxThresh = 0.55f;
constexpr float kUnclipRatio = 1.8f;

double now_ms() {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// 4-connected labeling on the binarized prob map (downscaled 2x for speed).
struct Component {
    int min_x = 0, min_y = 0, max_x = 0, max_y = 0;
    long px = 0;
    double sum = 0;
};

std::vector<Component> label_components(const float* prob, int w, int h) {
    std::vector<int> labels((size_t)w * h, -1);
    std::vector<Component> comps;
    std::deque<std::pair<int, int>> queue;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t i = (size_t)y * w + x;
            if (labels[i] != -1 || prob[i] <= kBinThresh) continue;
            const int id = (int)comps.size();
            comps.emplace_back();
            comps[id].min_x = comps[id].max_x = x;
            comps[id].min_y = comps[id].max_y = y;
            queue.clear();
            queue.emplace_back(x, y);
            labels[i] = id;
            while (!queue.empty()) {
                auto [cx, cy] = queue.front();
                queue.pop_front();
                Component& c = comps[id];
                c.min_x = std::min(c.min_x, cx);
                c.max_x = std::max(c.max_x, cx);
                c.min_y = std::min(c.min_y, cy);
                c.max_y = std::max(c.max_y, cy);
                ++c.px;
                c.sum += prob[(size_t)cy * w + cx];
                static const int kDx[4] = {1, -1, 0, 0};
                static const int kDy[4] = {0, 0, 1, -1};
                for (int d = 0; d < 4; ++d) {
                    int nx = cx + kDx[d], ny = cy + kDy[d];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    size_t ni = (size_t)ny * w + nx;
                    if (labels[ni] == -1 && prob[ni] > kBinThresh) {
                        labels[ni] = id;
                        queue.emplace_back(nx, ny);
                    }
                }
            }
        }
    }
    return comps;
}

} // namespace

struct ComicTextDetector::Impl {
    ncnn::Net net;
    ncnn::Mat input; // 3x1024x1024 reused
};

ComicTextDetector::ComicTextDetector() = default;
ComicTextDetector::~ComicTextDetector() { delete impl_; }

bool ComicTextDetector::init(const std::string& dir, bool use_gpu) {
    delete impl_;
    impl_ = new Impl;
    impl_->net.opt.use_vulkan_compute = use_gpu;
    impl_->net.opt.use_fp16_storage = use_gpu;
    impl_->net.opt.use_fp16_arithmetic = use_gpu;
    impl_->net.opt.num_threads = 4;
    const std::string p = dir + "/ctd.ncnn.param";
    const std::string b = dir + "/ctd.ncnn.bin";
    if (impl_->net.load_param(p.c_str()) != 0 || impl_->net.load_model(b.c_str()) != 0) {
        log_error("ctd: failed to load " + p);
        return false;
    }
    impl_->input.create(kDetect, kDetect, 3);
    ready_ = true;
    return true;
}

std::vector<DetBox> ComicTextDetector::detect(const uint8_t* rgba, int w, int h) {
    std::vector<DetBox> out;
    if (!ready_ || !rgba || w <= 0 || h <= 0) return out;
    const double t0 = now_ms();

    // ---- letterbox ---------------------------------------------------------
    const float scale = std::min((float)kDetect / w, (float)kDetect / h);
    const int dw = w * scale, dh = h * scale;
    const int pad_x = (kDetect - dw) / 2, pad_y = (kDetect - dh) / 2;
    {
        float* data = impl_->input.channel(0); // zero-fill once per channel
        for (int c = 0; c < 3; ++c) {
            float* plane = impl_->input.channel(c);
            memset(plane, 0, (size_t)kDetect * kDetect * sizeof(float));
        }
        (void)data;
        // nearest-box bilinear-ish sampling (area-average for downscale)
        for (int y = 0; y < dh; ++y) {
            const int sy0 = (int)((y + 0.5f) / scale);
            const int sy1 = std::min(h - 1, (int)((y + 1.0f) / scale));
            for (int x = 0; x < dw; ++x) {
                const int sx0 = (int)((x + 0.5f) / scale);
                const int sx1 = std::min(w - 1, (int)((x + 1.0f) / scale));
                float r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = sy0; sy <= sy1; ++sy)
                    for (int sx = sx0; sx <= sx1; ++sx) {
                        const uint8_t* px = rgba + ((size_t)sy * w + sx) * 4;
                        r += px[0]; g += px[1]; b += px[2];
                        ++n;
                    }
                const float inv = n ? 1.f / (n * 255.f) : 0;
                const size_t o = (size_t)(y + pad_y) * kDetect + (x + pad_x);
                impl_->input.channel(0)[o] = r * inv;
                impl_->input.channel(1)[o] = g * inv;
                impl_->input.channel(2)[o] = b * inv;
            }
        }
    }

    ncnn::Extractor ex = impl_->net.create_extractor();
    ex.input("in0", impl_->input);
    ncnn::Mat det; // [1,2,1024,1024]
    if (ex.extract("out1", det) != 0) {
        log_warn("ctd: det extract failed");
        return out;
    }
    t_.forward_ms = now_ms() - t0;

    // ---- DB postprocess on a 2x-downscaled prob map -------------------------
    const double t1 = now_ms();
    const int pw = kDetect / 2, ph = kDetect / 2;
    std::vector<float> prob((size_t)pw * ph);
    const float* ch = det.channel(0);
    for (int y = 0; y < ph; ++y) {
        const int sy0 = std::max(0, pad_y / 2 * 2 - 1);
        (void)sy0;
        for (int x = 0; x < pw; ++x) {
            // area average 2x2 of the full-res map
            float v = 0;
            for (int dy = 0; dy < 2; ++dy)
                for (int dx = 0; dx < 2; ++dx)
                    v += ch[(size_t)(y * 2 + dy) * kDetect + (x * 2 + dx)];
            prob[(size_t)y * pw + x] = v * 0.25f;
        }
    }

    std::vector<Component> comps = label_components(prob.data(), pw, ph);
    std::vector<DetBox> lines;
    lines.reserve(comps.size());
    const float inv_scale = 1.f / scale;
    for (const auto& c : comps) {
        if (c.px < 8) continue; // specks
        const float score = (float)(c.sum / c.px);
        if (score < kBoxThresh) continue;
        DetBox b;
        const float bw = (c.max_x - c.min_x + 1);
        const float bh = (c.max_y - c.min_y + 1);
        // unclip: expand by ratio * area/perimeter
        const float area = bw * bh;
        const float perim = 2 * (bw + bh);
        const float dist = area * kUnclipRatio / perim;
        float x0 = (c.min_x * 2.f - dist - pad_x) * inv_scale;
        float y0 = (c.min_y * 2.f - dist - pad_y) * inv_scale;
        float x1 = ((c.max_x + 1) * 2.f + dist - pad_x) * inv_scale;
        float y1 = ((c.max_y + 1) * 2.f + dist - pad_y) * inv_scale;
        b.x0 = std::max(0.f, x0);
        b.y0 = std::max(0.f, y0);
        b.x1 = std::min((float)w - 1, x1);
        b.y1 = std::min((float)h - 1, y1);
        if (b.x1 - b.x0 < 2 || b.y1 - b.y0 < 2) continue;
        b.vertical = (b.y1 - b.y0) > (b.x1 - b.x0) * 1.6f; // CJK column
        b.score = score;
        lines.push_back(b);
    }
    t_.lines = (int)lines.size();

    // ---- merge lines into blocks -------------------------------------------
    // Vertical lines merge when x-ranges overlap heavily and y-gap is small;
    // horizontal lines merge when y-ranges overlap and x-gap is small.
    std::vector<int> parent(lines.size());
    std::iota(parent.begin(), parent.end(), 0);
    std::function<int(int)> find = [&](int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    };
    auto overlap = [](float a0, float a1, float b0, float b1) {
        return std::min(a1, b1) - std::max(a0, b0);
    };
    for (size_t i = 0; i < lines.size(); ++i) {
        for (size_t j = i + 1; j < lines.size(); ++j) {
            const DetBox& a = lines[i];
            const DetBox& b = lines[j];
            const float ow = overlap(a.x0, a.x1, b.x0, b.x1);
            const float oh = overlap(a.y0, a.y1, b.y0, b.y1);
            const float gap_x = std::max(b.x0 - a.x1, a.x0 - b.x1);
            const float gap_y = std::max(b.y0 - a.y1, a.y0 - b.y1);
            const float min_w = std::min(a.x1 - a.x0, b.x1 - b.x0) + 1;
            const float min_h = std::min(a.y1 - a.y0, b.y1 - b.y0) + 1;
            const bool both_v = a.vertical && b.vertical;
            const bool both_h = !a.vertical && !b.vertical;
            bool merge = false;
            if (both_v && ow > 0.6f * min_w && gap_y < 1.2f * min_h) merge = true;
            if (both_h && oh > 0.6f * min_h && gap_x < 1.2f * min_w) merge = true;
            if ((both_v || both_h) && ow > 0 && oh > 0) merge = true; // touching
            if (merge) {
                int ri = find(i), rj = find(j);
                if (ri != rj) parent[rj] = ri;
            }
        }
    }
    std::unordered_map<int, DetBox> merged;
    for (size_t i = 0; i < lines.size(); ++i) {
        const int r = find(i);
        auto it = merged.find(r);
        if (it == merged.end()) {
            merged.emplace(r, lines[i]);
        } else {
            DetBox& m = it->second;
            m.x0 = std::min(m.x0, lines[i].x0);
            m.y0 = std::min(m.y0, lines[i].y0);
            m.x1 = std::max(m.x1, lines[i].x1);
            m.y1 = std::max(m.y1, lines[i].y1);
            m.score = std::max(m.score, lines[i].score);
            m.vertical = (m.y1 - m.y0) > (m.x1 - m.x0);
        }
    }
    out.reserve(merged.size());
    for (const auto& kv : merged) out.push_back(kv.second);

    // reading order: vertical blocks right-to-left first (manga), then rows
    std::sort(out.begin(), out.end(), [](const DetBox& a, const DetBox& b) {
        const bool av = a.vertical, bv = b.vertical;
        if (av != bv) return av > bv; // vertical blocks first (manga RTL)
        if (av) {
            // right column first, then top
            if (std::abs(a.x0 - b.x0) > std::max(a.x1 - a.x0, b.x1 - b.x0) * 0.7f)
                return a.x0 > b.x0;
            return a.y0 < b.y0;
        }
        if (std::abs(a.y0 - b.y0) > std::max(a.y1 - a.y0, b.y1 - b.y0) * 0.7f)
            return a.y0 < b.y0;
        return a.x0 < b.x0;
    });

    t_.post_ms = now_ms() - t1;
    t_.blocks = (int)out.size();
    return out;
}

} // namespace cs::ocr
