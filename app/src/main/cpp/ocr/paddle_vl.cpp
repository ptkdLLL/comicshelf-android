// On-device PaddleOCR-VL runtime on ncnn (Vulkan/Adreno, fp16 storage).
//
// Pipeline (all stages verified against the HF model on export, max diff 1e-4):
//   RGBA -> smart_resize(28|147384..2822400, bicubic) -> normalize(0.5/0.5)
//   -> patches [P,588] + learned 2D pos table (bilinear 27x27 -> gh x gw)
//   -> vision net (27 SigLIP layers, per-axis 2D rope)  [P,1152]
//   -> CPU rearrange (2x2 merge) -> merger net          [M,1024]
//   -> input embeds (chat template + M image tokens) + mrope tables + causal mask
//   -> prefill net (dynamic T) -> logits + 18x(K,V)
//   -> step net loop (KV grows via dynamic Concat) -> greedy ids -> text
#include "ocr/paddle_vl.h"

#include "mat.h"
#include "net.h"
#include "util/json_min.h"
#include "util/logger.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <thread>
#include <unistd.h>

#include <android/log.h>
#include <unordered_map>

namespace cs::ocr {

namespace {

// ncnn reports layer/Vulkan problems on stderr, which Android discards by
// default. Pipe it into logcat so device-side failures are diagnosable.
void redirect_stderr_to_logcat() {
    static bool done = false;
    if (done) return;
    done = true;
    int pfd[2];
    if (pipe(pfd) != 0) return;
    dup2(pfd[1], 2);
    close(pfd[1]);
    std::thread([rfd = pfd[0]] {
        char buf[1024];
        std::string line;
        ssize_t n;
        while ((n = read(rfd, buf, sizeof(buf) - 1)) > 0) {
            buf[n] = 0;
            line += buf;
            size_t pos;
            while ((pos = line.find('\n')) != std::string::npos) {
                __android_log_print(ANDROID_LOG_ERROR, "ncnn", "%s",
                                    line.substr(0, pos).c_str());
                line.erase(0, pos + 1);
            }
        }
    }).detach();
}

double now_ms() {
    return std::chrono::duration<double, std::milli>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

inline float bicubic_kernel(float x) {
    const float a = -0.5f;
    x = std::fabs(x);
    if (x <= 1) return (a + 2) * x * x * x - (a + 3) * x * x + 1;
    if (x < 2) return a * (x * x * x - 5 * x * x + 8 * x - 4);
    return 0.f;
}

struct Vec2 { int h = 0, w = 0; };

// Fixed patch-budget grid: gh * gw == p_fixed, both even (2x2 merge), aspect
// as close to the crop as possible. The exported graphs are static, so every
// crop runs at exactly this many patches.
Vec2 fixed_grid(int h, int w, int p_fixed) {
    // Exact even factorisation of p_fixed closest to the crop's aspect ratio:
    // the exported vision graph is static, so P must be exactly p_fixed.
    const double aspect = (double)h / std::max(1, w);
    int best_h = -1, best_w = -1;
    double best_err = 1e30;
    for (int gh = 2; gh <= p_fixed / 2; gh += 2) {
        if (p_fixed % gh != 0) continue;
        const int gw = p_fixed / gh;
        if (gw < 2 || gw % 2) continue;
        const double err = std::fabs(std::log((double)gh / gw) - std::log(aspect));
        if (err < best_err) {
            best_err = err;
            best_h = gh;
            best_w = gw;
        }
    }
    if (best_h < 0) return {48, 32};
    return {best_h, best_w};
}

void resize_bicubic_rgb(const uint8_t* src, int sh, int sw,
                        float* dst, int dh, int dw) {
    const double scale_y = (double)sh / dh;
    const double scale_x = (double)sw / dw;
    for (int y = 0; y < dh; ++y) {
        double fy = (y + 0.5) * scale_y - 0.5;
        int y0 = (int)std::floor(fy);
        float ty = (float)(fy - y0);
        for (int x = 0; x < dw; ++x) {
            double fx = (x + 0.5) * scale_x - 0.5;
            int x0 = (int)std::floor(fx);
            float tx = (float)(fx - x0);
            float wy[4], wx[4];
            for (int k = -1; k <= 2; ++k) {
                wy[k + 1] = bicubic_kernel(ty - k);
                wx[k + 1] = bicubic_kernel(tx - k);
            }
            float r = 0, g = 0, b = 0;
            for (int j = -1; j <= 2; ++j) {
                int sy = std::clamp(y0 + j, 0, sh - 1);
                float wrow = wy[j + 1];
                if (wrow == 0) continue;
                const uint8_t* row = src + (size_t)sy * sw * 4;
                for (int i = -1; i <= 2; ++i) {
                    int sx = std::clamp(x0 + i, 0, sw - 1);
                    float wgt = wrow * wx[i + 1];
                    if (wgt == 0) continue;
                    const uint8_t* px = row + sx * 4;
                    r += wgt * px[0];
                    g += wgt * px[1];
                    b += wgt * px[2];
                }
            }
            size_t o = ((size_t)y * dw + x) * 3;
            dst[o + 0] = (r * (1.f / 255.f) - 0.5f) * 2.f;
            dst[o + 1] = (g * (1.f / 255.f) - 0.5f) * 2.f;
            dst[o + 2] = (b * (1.f / 255.f) - 0.5f) * 2.f;
        }
    }
}

inline float half_to_float(unsigned short h) {
    unsigned sign = (h >> 15) & 1;
    unsigned exp = (h >> 10) & 0x1F;
    unsigned man = h & 0x3FF;
    float val;
    if (exp == 0) {
        val = std::ldexp((float)man, -24);
    } else if (exp == 31) {
        val = man ? NAN : INFINITY;
    } else {
        val = std::ldexp((float)(man | 0x400), (int)exp - 25);
    }
    return sign ? -val : val;
}

// GPU blobs may be fp16-storage AND/or packed; normalise before CPU access.
static ncnn::Mat to_fp32(const ncnn::Mat& m) {
    ncnn::Mat unpacked = m;
    if (m.elempack != 1) {
        ncnn::convert_packing(m, unpacked, 1);
        log_info("ocr vl: unpacked blob elemsize=" + std::to_string(m.elemsize) +
                 " pack=" + std::to_string(m.elempack) + " -> " +
                 std::to_string(unpacked.elemsize));
    }
    if (unpacked.elemsize == 2) {
        ncnn::Mat out;
        ncnn::cast_float16_to_float32(unpacked, out);
        return out;
    }
    if (unpacked.elemsize != 4) {
        log_error("ocr vl: unexpected elemsize " + std::to_string(unpacked.elemsize));
    }
    return unpacked;
}

int argmax_row(const float* v, int n) {
    int best = 0;
    float bv = v[0];
    for (int i = 1; i < n; ++i)
        if (v[i] > bv) { bv = v[i]; best = i; }
    return best;
}

} // namespace

struct PaddleOcrVl::Impl {
    ncnn::Net vision, merger, prefill, step;

    int head_dim = 128, hidden = 1024, layers = 18;
    int num_heads = 16, num_kv_heads = 2, vocab = 103424;
    int patch = 14, merge = 2;
    int min_pixels = 147384, max_pixels = 2822400;
    double rope_theta = 500000.0;
    int mrope[3] = {16, 24, 24};
    int eos = 2;
    // template: <bos> User: <IMG_START><PH*M><IMG_END> OCR:\n Assistant:
    int cls_id = 1, image_start = -1, image_ph = 100295, image_end = -1;
    std::vector<int> user_prefix, gen_start, prompt_ocr;
    std::vector<unsigned short> embed_w16; // [vocab, hidden] fp16

    std::vector<std::string> id2piece;
    std::unordered_map<std::string, int> piece2id;

    int pos_side = 0, pos_dim = 1152;
    std::vector<float> pos_table;
    // Static-shape runtime (see tools/export_vl.py): fixed patch budget and a
    // preallocated KV cache the CPU memcpys into.
    int p_fixed = 1536, grid_h = 48, grid_w = 32;
    int maxlen = 900, prompt_len = 397;

    bool load_json_runtime(const std::string& path);
    bool load_vocab(const std::string& path);
    bool load_pos_table(const std::string& path);
    bool load_embed(const std::string& path);
    void build_pos_embed(std::vector<float>& out, int gh, int gw) const;
    void build_rope_tables(std::vector<float>& cos, std::vector<float>& sin,
                           int gh, int gw) const;
    void build_llm_cos_sin(std::vector<float>& cos, std::vector<float>& sin,
                           const std::vector<int>& t_pos,
                           const std::vector<int>& h_pos,
                           const std::vector<int>& w_pos) const;
    void llm_cos_sin_at(std::vector<float>& cos, std::vector<float>& sin, int pos) const;
    std::string decode(const std::vector<int>& ids) const;
};

bool PaddleOcrVl::Impl::load_json_runtime(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    json::Value v;
    std::string err;
    if (!json::parse(text, v, &err) || !v.is_object()) {
        log_error("ocr: runtime.json parse failed: " + err);
        return false;
    }
    auto geti = [&](const char* k, int def) { return (int)v[k].as_int(def); };
    head_dim = geti("head_dim", 128);
    hidden = geti("hidden", 1024);
    layers = geti("layers", 18);
    num_heads = geti("num_heads", 16);
    num_kv_heads = geti("num_kv_heads", 2);
    vocab = geti("vocab", 103424);
    patch = geti("patch_size", 14);
    merge = geti("merge_size", 2);
    min_pixels = geti("min_pixels", 147384);
    max_pixels = geti("max_pixels", 2822400);
    eos = geti("eos_id", 2);
    cls_id = geti("cls_id", 1);
    image_start = geti("image_start_id", -1);
    image_ph = geti("image_ph_id", 100295);
    image_end = geti("image_end_id", -1);
    rope_theta = v["rope_theta"].is_number() ? v["rope_theta"].as_number() : 500000.0;
    p_fixed = geti("p_fixed", 1536);
    grid_h = geti("grid_h", 48);
    grid_w = geti("grid_w", 32);
    maxlen = geti("maxlen", 900);
    prompt_len = geti("prompt_len", 397);
    if (const json::Value& m = v["mrope_section"]; m.is_array() && m.as_array().size() == 3) {
        for (int i = 0; i < 3; ++i) mrope[i] = (int)m.as_array()[i].as_int();
    }
    auto ids_of = [&](const char* k, std::vector<int>& out) {
        out.clear();
        const json::Value& a = v[k];
        if (a.is_array())
            for (const auto& e : a.as_array()) out.push_back((int)e.as_int());
    };
    ids_of("user_prefix_ids", user_prefix);
    ids_of("gen_start_ids", gen_start);
    ids_of("prompt_ocr_ids", prompt_ocr);
    return true;
}

bool PaddleOcrVl::Impl::load_vocab(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    json::Value v;
    std::string err;
    if (!json::parse(text, v, &err)) {
        log_error("ocr: tokenizer.json parse failed: " + err);
        return false;
    }
    id2piece.assign(vocab, "");
    long n = 0;
    const json::Value& model = v["model"];
    if (model.is_object() && model["vocab"].is_object()) {
        for (const auto& kv : model["vocab"].as_object()) {
            int id = (int)kv.second.as_int();
            if (id >= 0 && id < vocab) { id2piece[id] = kv.first; ++n; }
        }
    }
    if (const json::Value& added = v["added_tokens"]; added.is_array()) {
        for (const auto& e : added.as_array()) {
            int id = (int)e["id"].as_int();
            if (id >= 0 && id < vocab) { id2piece[id] = e["content"].as_string(); ++n; }
        }
    }
    for (int i = 0; i < vocab; ++i)
        if (!id2piece[i].empty()) piece2id.emplace(id2piece[i], i);
    log_info("ocr: vocab loaded, entries=" + std::to_string(n));
    return n > 50000;
}

bool PaddleOcrVl::Impl::load_pos_table(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    in.read((char*)&pos_side, 4);
    in.read((char*)&pos_dim, 4);
    if (pos_side <= 0 || pos_side > 128 || pos_dim <= 0 || pos_dim > 4096) return false;
    pos_table.resize((size_t)pos_side * pos_side * pos_dim);
    in.read((char*)pos_table.data(), pos_table.size() * 4);
    return (bool)in;
}

bool PaddleOcrVl::Impl::load_embed(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    int v = 0, d = 0;
    in.read((char*)&v, 4);
    in.read((char*)&d, 4);
    if (v <= 0 || d <= 0 || v > 200000 || d != hidden) return false;
    embed_w16.resize((size_t)v * d);
    in.read((char*)embed_w16.data(), embed_w16.size() * 2);
    if (v < vocab) vocab = v;
    return (bool)in;
}

void PaddleOcrVl::Impl::build_pos_embed(std::vector<float>& out, int gh, int gw) const {
    out.resize((size_t)gh * gw * pos_dim);
    const float sx = (float)pos_side / gh;
    const float sy = (float)pos_side / gw;
    for (int y = 0; y < gw; ++y) {
        float fy = ((float)y + 0.5f) * sy - 0.5f;
        if (fy < 0) fy = 0;
        int y0 = std::min((int)fy, pos_side - 1);
        int y1 = std::min(y0 + 1, pos_side - 1);
        float ty = fy - y0;
        for (int x = 0; x < gh; ++x) {
            float fx = ((float)x + 0.5f) * sx - 0.5f;
            if (fx < 0) fx = 0;
            int x0 = std::min((int)fx, pos_side - 1);
            int x1 = std::min(x0 + 1, pos_side - 1);
            float tx = fx - x0;
            const float* p00 = &pos_table[((size_t)y0 * pos_side + x0) * pos_dim];
            const float* p01 = &pos_table[((size_t)y0 * pos_side + x1) * pos_dim];
            const float* p10 = &pos_table[((size_t)y1 * pos_side + x0) * pos_dim];
            const float* p11 = &pos_table[((size_t)y1 * pos_side + x1) * pos_dim];
            float* o = &out[((size_t)y * gh + x) * pos_dim];
            for (int c = 0; c < pos_dim; ++c)
                o[c] = (p00[c] * (1 - tx) + p01[c] * tx) * (1 - ty) +
                       (p10[c] * (1 - tx) + p11[c] * tx) * ty;
        }
    }
}

void PaddleOcrVl::Impl::build_rope_tables(std::vector<float>& cos,
                                          std::vector<float>& sin,
                                          int gh, int gw) const {
    const int half = 36;
    const int nfreq = half / 2;
    std::vector<double> inv(nfreq);
    for (int i = 0; i < nfreq; ++i)
        inv[i] = 1.0 / std::pow(10000.0, (2.0 * i) / half);
    const int P = gh * gw;
    cos.resize((size_t)P * 72);
    sin.resize((size_t)P * 72);
    for (int p = 0; p < P; ++p) {
        const int hy = p / gw, wx = p % gw;
        float* c = &cos[(size_t)p * 72];
        float* s = &sin[(size_t)p * 72];
        for (int i = 0; i < nfreq; ++i) {
            c[i] = (float)std::cos(hy * inv[i]);
            s[i] = (float)std::sin(hy * inv[i]);
            c[i + nfreq] = (float)std::cos(wx * inv[i]);
            s[i + nfreq] = (float)std::sin(wx * inv[i]);
        }
        for (int i = 0; i < half; ++i) {
            c[i + half] = c[i];
            s[i + half] = s[i];
        }
    }
}

void PaddleOcrVl::Impl::build_llm_cos_sin(std::vector<float>& cos,
                                          std::vector<float>& sin,
                                          const std::vector<int>& t_pos,
                                          const std::vector<int>& h_pos,
                                          const std::vector<int>& w_pos) const {
    const int T = (int)t_pos.size();
    const int half = head_dim / 2;
    const int s0 = mrope[0], s1 = mrope[0] + mrope[1];
    std::vector<double> inv(half);
    for (int i = 0; i < half; ++i)
        inv[i] = 1.0 / std::pow(rope_theta, (2.0 * i) / head_dim);
    cos.resize((size_t)T * head_dim);
    sin.resize((size_t)T * head_dim);
    for (int t = 0; t < T; ++t) {
        float* c = &cos[(size_t)t * head_dim];
        float* s = &sin[(size_t)t * head_dim];
        const int tp = t_pos[t], hp = h_pos[t], wp = w_pos[t];
        for (int i = 0; i < half; ++i) {
            const double pos = i < s0 ? tp : (i < s1 ? hp : wp);
            c[i] = (float)std::cos(pos * inv[i]);
            s[i] = (float)std::sin(pos * inv[i]);
        }
        for (int i = 0; i < half; ++i) {
            c[i + half] = c[i];
            s[i + half] = s[i];
        }
    }
}

void PaddleOcrVl::Impl::llm_cos_sin_at(std::vector<float>& cos,
                                       std::vector<float>& sin, int pos) const {
    cos.resize(head_dim);
    sin.resize(head_dim);
    const int half = head_dim / 2;
    for (int i = 0; i < half; ++i) {
        const double f = 1.0 / std::pow(rope_theta, (2.0 * i) / head_dim);
        cos[i] = cos[i + half] = (float)std::cos(pos * f);
        sin[i] = sin[i + half] = (float)std::sin(pos * f);
    }
}

std::string PaddleOcrVl::Impl::decode(const std::vector<int>& ids) const {
    std::string out;
    std::string pending;
    auto flush = [&]() { out += pending; pending.clear(); };
    static const std::string kUnder("\xe2\x96\x81"); // U+2581
    for (int id : ids) {
        if (id == eos || id < 0 || id >= vocab) break;
        const std::string& p = id2piece[id];
        if (p.empty()) continue;
        if (p.size() == 6 && p.compare(0, 3, "<0x") == 0 && p.back() == '>') {
            long b = strtol(p.c_str() + 3, nullptr, 16);
            pending.push_back((char)(b & 0xFF));
        } else {
            flush();
            std::string s = p;
            std::string::size_type i;
            while ((i = s.find(kUnder)) != std::string::npos) s.replace(i, 3, " ");
            out += s;
        }
    }
    flush();
    return out;
}

// ------------------------------------------------------------------ public

PaddleOcrVl::PaddleOcrVl() = default;
PaddleOcrVl::~PaddleOcrVl() { delete impl_; }

static void setup_net(ncnn::Net& net, bool use_gpu) {
    net.opt.use_vulkan_compute = use_gpu;
    // VRAM reality on Adreno: fp32 activation storage overflows the memory the
    // driver gives us for the 824 MB vision graph, so GPU runs with fp16
    // storage/arithmetic. On CPU we keep fp32 activations (measurably 1000x
    // more accurate across the 27 vision layers).
    net.opt.use_fp16_storage = use_gpu;
    net.opt.use_fp16_arithmetic = use_gpu;
    net.opt.use_int8_storage = false;
    net.opt.use_sgemm_convolution = true;
    net.opt.num_threads = 4;
}

// Some Adreno driver builds crash inside ncnn's Vulkan BinaryOp descriptor
// path (vkCmdPushDescriptorSetWithTemplate, SEGV_ACCERR). Those element-wise
// ops are cheap on CPU, so keep them off the GPU while the heavy layers
// (Convolution / MatMul / Softmax) stay accelerated.
static void demote_unsafe_vulkan_layers(ncnn::Net& net) {
    int demoted = 0;
    for (size_t i = 0; i < net.layers().size(); ++i) {
        ncnn::Layer* layer = net.layers()[i];
        if (!layer) continue;
        const std::string& t = layer->type;
        if (t == "BinaryOp" || t == "UnaryOp") {
            layer->support_vulkan = false;
            ++demoted;
        }
    }
    if (demoted) log_info("ocr: demoted " + std::to_string(demoted) +
                          " elementwise layers off Vulkan");
}

bool PaddleOcrVl::init(const std::string& dir, bool use_gpu) {
    redirect_stderr_to_logcat();
    delete impl_;
    impl_ = new Impl;
    if (use_gpu) {
        const int ngpu = ncnn::get_gpu_count();
        log_info("ocr: vulkan device count=" + std::to_string(ngpu));
        if (ngpu > 0) {
            const ncnn::GpuInfo& gi = ncnn::get_gpu_info(0);
            log_info(std::string("ocr: gpu0=") + gi.device_name());
        } else {
            log_warn("ocr: no Vulkan device; falling back to CPU");
        }
    }
    setup_net(impl_->vision, use_gpu);
    setup_net(impl_->merger, use_gpu);
    setup_net(impl_->prefill, use_gpu);
    setup_net(impl_->step, use_gpu);

    auto load = [&](ncnn::Net& net, const char* name) {
        const std::string p = dir + "/" + name + ".ncnn.param";
        const std::string b = dir + "/" + name + ".ncnn.bin";
        int rp = net.load_param(p.c_str());
        if (rp != 0) {
            log_error(std::string("ocr: load_param failed for ") + name + " code=" +
                      std::to_string(rp));
            return false;
        }
        int rb = net.load_model(b.c_str());
        if (rb != 0) {
            log_error(std::string("ocr: load_model failed for ") + name + " code=" +
                      std::to_string(rb));
            return false;
        }
        log_info(std::string("ocr: graph loaded: ") + name);
        return true;
    };
    if (!load(impl_->vision, "vl_vision") || !load(impl_->merger, "vl_merger") ||
        !load(impl_->prefill, "vl_prefill") || !load(impl_->step, "vl_step")) {
        log_error("ocr: failed to load ncnn graphs from " + dir);
        return false;
    }
    if (!impl_->load_json_runtime(dir + "/runtime.json")) {
        log_error("ocr: runtime.json load failed");
        return false;
    }
    if (!impl_->load_vocab(dir + "/tokenizer.json")) {
        log_error("ocr: tokenizer.json load failed");
        return false;
    }
    if (!impl_->load_pos_table(dir + "/vl_postable.bin")) {
        log_error("ocr: missing vl_postable.bin");
        return false;
    }
    if (!impl_->load_embed(dir + "/vl_embed.bin")) {
        log_error("ocr: missing vl_embed.bin");
        return false;
    }
    // NOTE: keeping the elementwise layers ON the GPU is a large win (each
    // CPU-demoted layer costs a device round-trip per step). Guarded by an
    // env kill-switch in case a future driver regresses.
    if (use_gpu && getenv("CS_DEMOTE_ELEMWISE")) {
        demote_unsafe_vulkan_layers(impl_->vision);
        demote_unsafe_vulkan_layers(impl_->merger);
        demote_unsafe_vulkan_layers(impl_->prefill);
        demote_unsafe_vulkan_layers(impl_->step);
    }
    gpu_active_ = use_gpu;
    ready_ = true;
    log_info(std::string("ocr: PaddleOCR-VL ready (gpu=") + (use_gpu ? "1" : "0") + ")");
    return true;
}

std::string PaddleOcrVl::ocr(const uint8_t* rgba, int w, int h) {
    if (!ready_ || !rgba || w <= 0 || h <= 0) return {};
    const double t_total0 = now_ms();

    // ---- preprocess -------------------------------------------------------
    double t0 = now_ms();
    const Vec2 gsel = fixed_grid(h, w, impl_->p_fixed);
    const int gh = gsel.h, gw = gsel.w;
    const int P = gh * gw;
    const int rh = gh * impl_->patch, rw = gw * impl_->patch;
    std::vector<float> rgb((size_t)rh * rw * 3);
    resize_bicubic_rgb(rgba, h, w, rgb.data(), rh, rw);

    std::vector<float> patches((size_t)P * 588);
    for (int py = 0; py < gh; ++py) {
        for (int px = 0; px < gw; ++px) {
            float* o = &patches[((size_t)py * gw + px) * 588];
            for (int c = 0; c < 3; ++c)
                for (int yy = 0; yy < impl_->patch; ++yy) {
                    const float* row = &rgb[(((size_t)py * impl_->patch + yy) * rw +
                                             (size_t)px * impl_->patch) * 3 + c];
                    float* dst = o + c * 196 + yy * impl_->patch;
                    for (int xx = 0; xx < impl_->patch; ++xx)
                        dst[xx] = row[(size_t)xx * 3];
                }
        }
    }
    log_info("ocr vl: prep done gh=" + std::to_string(gh) + " gw=" + std::to_string(gw) +
             " P=" + std::to_string(P));
    std::vector<float> pos_embed, rope_cos, rope_sin;
    impl_->build_pos_embed(pos_embed, gh, gw);
    impl_->build_rope_tables(rope_cos, rope_sin, gh, gw);
    t_.preprocess_ms = now_ms() - t0;

    // ---- vision -----------------------------------------------------------
    t0 = now_ms();
    ncnn::Mat m_patches(588, P, (void*)patches.data());
    ncnn::Mat m_pos(1152, P, (void*)pos_embed.data());
    ncnn::Mat m_vcos(72, P, (void*)rope_cos.data());
    ncnn::Mat m_vsin(72, P, (void*)rope_sin.data());
    ncnn::Extractor ex = impl_->vision.create_extractor();
    ex.input("in0", m_patches);
    ex.input("in1", m_pos);
    ex.input("in2", m_vcos);
    ex.input("in3", m_vsin);
    ncnn::Mat hidden_raw;
    if (ex.extract("out0", hidden_raw) != 0) {
        log_warn("ocr: vision extract failed");
        return {};
    }
    const ncnn::Mat hidden = to_fp32(hidden_raw);
    log_info("ocr vl: vision ok " + std::to_string(hidden.w) + "x" + std::to_string(hidden.h) +
             " elemsize=" + std::to_string(hidden.elemsize) +
             " total=" + std::to_string(hidden.total()));
    // Copy the vision output into our own buffer right away: ncnn's blob
    // memory is pool-owned, and we don't want to keep pointers into it alive
    // across the (long) merger/prefill stages.
    std::vector<float> hidden_own((size_t)hidden.total());
    {
        const float* src_p = (const float*)hidden.data;
        for (size_t i = 0; i < hidden_own.size(); ++i) hidden_own[i] = src_p[i];
    }
    t_.vision_ms = now_ms() - t0;
    t_.patches = P;

    // ---- merge ----------------------------------------------------------
    t0 = now_ms();
    const int mh = gh / impl_->merge, mw = gw / impl_->merge;
    const int M = mh * mw;
    std::vector<float> merged((size_t)M * 4608);
    {
        // einops "(h p1 w p2) d -> (h w) (p1 p2 d)": each merged row is four
        // PATCH-sized segments (p1-major, p2-minor) copied one at a time.
        const float* hd = hidden_own.data();
        for (int y = 0; y < mh; ++y)
            for (int x = 0; x < mw; ++x)
                for (int a = 0; a < 2; ++a)
                    for (int b = 0; b < 2; ++b) {
                        const float* src =
                            &hd[(((size_t)(y * 2 + a) * gw) + (size_t)(x * 2 + b)) * 1152];
                        float* dst =
                            &merged[((size_t)(y * mw + x) * 4 + (a * 2 + b)) * 1152];
                        memcpy(dst, src, 1152 * sizeof(float));
                    }
    }
    log_info("ocr vl: merge built M=" + std::to_string(M) + " mh=" + std::to_string(mh) +
             " mw=" + std::to_string(mw));
    log_info("ocr vl: merge databuf ptr=" + std::to_string((uintptr_t)merged.data()) +
             " bytes=" + std::to_string(merged.size() * 4) +
             " hidden.data=" + std::to_string((uintptr_t)hidden.data) +
             " hidden.total=" + std::to_string(hidden.total()) +
             " hidden.cstep=" + std::to_string(hidden.cstep));
    ncnn::Mat m_merged(4608, M, (void*)merged.data());
    ncnn::Extractor mex = impl_->merger.create_extractor();
    mex.input("in0", m_merged);
    ncnn::Mat embeds_raw; // [M, 1024]
    if (mex.extract("out0", embeds_raw) != 0) return {};
    log_info("ocr vl: merger out raw elemsize=" + std::to_string(embeds_raw.elemsize) +
             " pack=" + std::to_string(embeds_raw.elempack) + " w=" +
             std::to_string(embeds_raw.w) + " h=" + std::to_string(embeds_raw.h));
    const ncnn::Mat embeds = to_fp32(embeds_raw);
    log_info("ocr vl: merge ok M=" + std::to_string(embeds.h));
    t_.merger_ms = now_ms() - t0;
    t_.image_tokens = M;

    // ---- prompt & positions ----------------------------------------------
    std::vector<int> ids;
    ids.push_back(impl_->cls_id);
    for (int t : impl_->user_prefix) ids.push_back(t);
    ids.push_back(impl_->image_start);
    ids.insert(ids.end(), M, impl_->image_ph);
    ids.push_back(impl_->image_end);
    ids.insert(ids.end(), impl_->prompt_ocr.begin(), impl_->prompt_ocr.end());
    ids.insert(ids.end(), impl_->gen_start.begin(), impl_->gen_start.end());
    const int T = (int)ids.size();
    if (T != impl_->prompt_len) {
        log_error("ocr vl: prompt len " + std::to_string(T) + " != exported " +
                  std::to_string(impl_->prompt_len));
        return {};
    }

    std::vector<int> t_pos(T), h_pos(T), w_pos(T);
    {
        int m = 0;
        for (int i = 0; i < T; ++i)
            if (ids[i] == impl_->image_ph) {
                t_pos[i] = 0;
                h_pos[i] = m / mw;
                w_pos[i] = m % mw;
                ++m;
            }
        const int start = std::max(mh - 1, mw - 1) + 1;
        int k = 0;
        for (int i = 0; i < T; ++i)
            if (ids[i] != impl_->image_ph) {
                const int v = start + k++;
                t_pos[i] = h_pos[i] = w_pos[i] = v;
            }
    }
    std::vector<float> cos, sin;
    impl_->build_llm_cos_sin(cos, sin, t_pos, h_pos, w_pos);

    log_info("ocr vl: building embeds T=" + std::to_string(T) + " M=" + std::to_string(M) +
             " hidden=" + std::to_string(impl_->hidden) +
             " vocab=" + std::to_string(impl_->vocab));
    std::vector<float> in_embeds((size_t)T * impl_->hidden);
    {
        const float* img_vec = (const float*)embeds.data;
        int n_img = 0;
        for (int i = 0; i < T; ++i) {
            float* o = &in_embeds[(size_t)i * impl_->hidden];
            if (ids[i] == impl_->image_ph) {
                memcpy(o, img_vec + (size_t)n_img * impl_->hidden,
                       sizeof(float) * impl_->hidden);
                ++n_img;
            } else {
                const unsigned short* row =
                    impl_->embed_w16.data() + (size_t)ids[i] * impl_->hidden;
                for (int c = 0; c < impl_->hidden; ++c)
                    o[c] = half_to_float(row[c]);
            }
        }
    }

    // ---- prefill -----------------------------------------------------------
    t0 = now_ms();
    log_info("ocr vl: prefill input ready, mask=" + std::to_string(T * T));
    ncnn::Mat m_embeds(impl_->hidden, T, (void*)in_embeds.data());
    ncnn::Mat m_cos(impl_->head_dim, T, (void*)cos.data());
    ncnn::Mat m_sin(impl_->head_dim, T, (void*)sin.data());
    std::vector<float> mask((size_t)T * T, 0.f);
    const float neg = -65504.f;
    for (int y = 0; y < T; ++y)
        for (int x = y + 1; x < T; ++x) mask[(size_t)y * T + x] = neg;
    ncnn::Mat m_mask(T, T, (void*)mask.data());

    ncnn::Extractor pex = impl_->prefill.create_extractor();
    pex.input("in0", m_embeds);
    pex.input("in1", m_cos);
    pex.input("in2", m_sin);
    pex.input("in3", m_mask);
    ncnn::Mat logits0_raw;
    std::vector<ncnn::Mat> kv;
    kv.reserve(impl_->layers * 2);
    if (pex.extract("out0", logits0_raw) != 0) {
        log_warn("ocr: prefill extract failed");
        return {};
    }
    const ncnn::Mat logits0 = to_fp32(logits0_raw);
    for (int i = 0; i < impl_->layers * 2; ++i) {
        ncnn::Mat m;
        char name[16];
        snprintf(name, sizeof(name), "out%d", i + 1);
        if (pex.extract(name, m) != 0) {
            log_warn("ocr: prefill kv extract failed");
            return {};
        }
        kv.push_back(m);
    }
    log_info("ocr vl: prefill ok T=" + std::to_string(T) + " vocab=" + std::to_string(impl_->vocab));
    t_.prefill_ms = now_ms() - t0;

    // ---- static KV cache + greedy decode ------------------------------------
    t0 = now_ms();
    // GQA repetition is folded into the k/v projection weights at export time,
    // so caches carry all QH heads directly.
    const int QH = impl_->num_heads, HD = impl_->head_dim;
    const int MAXLEN = impl_->maxlen;
    std::vector<ncnn::Mat> k_cache(impl_->layers), v_cache(impl_->layers);
    for (int i = 0; i < impl_->layers; ++i) {
        k_cache[i].create(HD, MAXLEN, QH);   // [QH, MAXLEN, HD] fp32
        v_cache[i].create(HD, MAXLEN, QH);
        k_cache[i].fill(0.f);
        v_cache[i].fill(0.f);
    }
    // prefill KV: layer i outputs k = kv[2i], v = kv[2i+1], shape [QH, T, HD]
    for (int i = 0; i < impl_->layers; ++i) {
        const ncnn::Mat kf = to_fp32(kv[2 * i]);
        const ncnn::Mat vf = to_fp32(kv[2 * i + 1]);
        for (int q = 0; q < QH; ++q) {
            const float* src_k = kf.channel(q);
            const float* src_v = vf.channel(q);
            float* dst_k = k_cache[i].channel(q);
            float* dst_v = v_cache[i].channel(q);
            const size_t n = (size_t)T * HD;
            if ((size_t)kf.w * kf.h >= n) {
                memcpy(dst_k, src_k, n * sizeof(float));
                memcpy(dst_v, src_v, n * sizeof(float));
            }
        }
    }

    std::vector<int> out_ids;
    int cur = argmax_row((const float*)logits0.data, impl_->vocab);
    out_ids.push_back(cur);
    int slot = T;                       // next position to write
    int pos = t_pos[T - 1] + 1;

    ncnn::Mat step_mask;
    step_mask.create(MAXLEN, 2, 1);          // [1, 2, MAXLEN] -> (w,h=2,c=1)
    {
        float* m = (float*)step_mask.data;
        for (int r = 0; r < 2; ++r)
            for (int i = 0; i < MAXLEN; ++i)
                m[(size_t)r * MAXLEN + i] = i < slot ? 0.f : -8000.f;
    }

    const int max_new = std::min(MAXLEN - T - 1, 128);
    int repeat_run = 0;
    while ((int)out_ids.size() < max_new && cur != impl_->eos) {
        // Early stop: autoregressive OCR that starts repeating one token has
        // nothing more to say (and would otherwise burn the whole budget).
        if (out_ids.size() >= 2 && cur == out_ids[out_ids.size() - 2]) {
            if (++repeat_run >= 12) break;
        } else {
            repeat_run = 0;
        }
        {
            float* m = (float*)step_mask.data;
            for (int r = 0; r < 2; ++r)
                for (int i = 0; i < MAXLEN; ++i)
                    m[(size_t)r * MAXLEN + i] = i < slot ? 0.f : -8000.f;
        }
        std::vector<float> e((size_t)impl_->hidden);
        const unsigned short* row =
            impl_->embed_w16.data() + (size_t)cur * impl_->hidden;
        for (int c = 0; c < impl_->hidden; ++c) e[c] = half_to_float(row[c]);
        std::vector<float> c2, s2;
        impl_->llm_cos_sin_at(c2, s2, pos);
        // The step graph runs with S=2 rows (real token + a dummy), because
        // ncnn collapses size-1 dims and would mis-shape a single-token pass.
        std::vector<float> e2((size_t)2 * impl_->hidden, 0.f);
        memcpy(e2.data(), e.data(), sizeof(float) * impl_->hidden);
        std::vector<float> c22((size_t)2 * impl_->head_dim), s22((size_t)2 * impl_->head_dim);
        for (int j = 0; j < impl_->head_dim; ++j) {
            c22[j] = c22[impl_->head_dim + j] = c2[j];
            s22[j] = s22[impl_->head_dim + j] = s2[j];
        }
        ncnn::Mat m_e(impl_->hidden, 2, (void*)e2.data());       // [2, H]
        ncnn::Mat m_c(impl_->head_dim, 2, (void*)c22.data());    // [2, D]
        ncnn::Mat m_s(impl_->head_dim, 2, (void*)s22.data());

        ncnn::Extractor sex = impl_->step.create_extractor();
        sex.input("in0", m_e);
        sex.input("in1", m_c);
        sex.input("in2", m_s);
        sex.input("in3", step_mask);
        for (int i = 0; i < impl_->layers; ++i) {
            char name[16];
            snprintf(name, sizeof(name), "in%d", 4 + 2 * i);
            sex.input(name, k_cache[i]);
            snprintf(name, sizeof(name), "in%d", 5 + 2 * i);
            sex.input(name, v_cache[i]);
        }
        ncnn::Mat logits1_raw;
        if (sex.extract("out0", logits1_raw) != 0) {
            log_warn("ocr: step extract failed");
            break;
        }
        const ncnn::Mat logits1 = to_fp32(logits1_raw);
        bool ok = true;
        for (int i = 0; i < impl_->layers; ++i) {
            ncnn::Mat kn, vn;
            char name[16];
            snprintf(name, sizeof(name), "out%d", 1 + 2 * i);
            if (sex.extract(name, kn) != 0) { ok = false; break; }
            snprintf(name, sizeof(name), "out%d", 2 + 2 * i);
            if (sex.extract(name, vn) != 0) { ok = false; break; }
            const ncnn::Mat kf = to_fp32(kn);
            const ncnn::Mat vf = to_fp32(vn);
            // kf/vf: [QH, 2, HD] — row 0 is the real token
            for (int q = 0; q < QH; ++q) {
                float* kdst = (float*)k_cache[i].channel(q).row(slot);
                float* vdst = (float*)v_cache[i].channel(q).row(slot);
                memcpy(kdst, kf.channel(q).row(0), HD * sizeof(float));
                memcpy(vdst, vf.channel(q).row(0), HD * sizeof(float));
            }
        }
        if (!ok) break;
        ++slot;
        cur = argmax_row((const float*)logits1.data, impl_->vocab);
        out_ids.push_back(cur);
        ++pos;
    }
    log_info("ocr vl: decode ok tokens=" + std::to_string(out_ids.size()) +
             " (kv slot=" + std::to_string(slot) + ")");
    t_.decode_ms = now_ms() - t0;
    t_.out_tokens = (int)out_ids.size();
    t_.total_ms = now_ms() - t_total0;

    std::string text = impl_->decode(out_ids);
    while (!text.empty() &&
           (text.back() == '\n' || text.back() == ' ' || text.back() == '\r'))
        text.pop_back();
    return text;
}

} // namespace cs::ocr
