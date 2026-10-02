// PaddleOCR-VL-For-Manga on the Hexagon NPU (QNN/HTP). See qnn_vl.h.
//
// v2 shapes (measured on device, SM8550):
//   vision  1024 patches          ~1100 ms   fp32 I/O, fp16 compute
//   merger  [256,4608]->[256,1024]     7 ms
//   prefill T=269 (whole prompt)     ~410 ms   fp16 KV caches
//   step    T=1, MAXLEN=320           ~95 ms   fp16 KV caches
// All four graphs live in ONE context binary (vl_ctx.bin) and are found by
// input signature. All tensors bind via clientBuf (RAW): this HTP build does
// not write back RAW outputs registered as memhandle and mis-delivers
// memhandle inputs larger than 1 MB, so RAW everywhere is the verified path.
//
// Numerical contract (host == model.generate, identical text):
//   tools/vlwork/verify_e2e_host.py
#include "qnn_vl.h"

#include <dlfcn.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>

#include <android/log.h>

#include "QNN/QnnBackend.h"
#include "QNN/QnnContext.h"
#include "QNN/QnnDevice.h"
#include "QNN/QnnGraph.h"
#include "QNN/QnnInterface.h"
#include "QNN/QnnLog.h"
#include "QNN/QnnTensor.h"
#include "QNN/HTP/QnnHtpDevice.h"
#include "System/QnnSystemContext.h"
#include "System/QnnSystemInterface.h"

#define VLOG(...) __android_log_print(ANDROID_LOG_INFO, "QnnVl", __VA_ARGS__)
#define VERR(...) __android_log_print(ANDROID_LOG_ERROR, "QnnVl", __VA_ARGS__)

namespace cs::ocr {
namespace {

// ------------------------------------------------------------- fixed shapes
constexpr int kCanvas = 448;
constexpr int kGrid = 32;
constexpr int kPatches = kGrid * kGrid;          // 1024
constexpr int kImageTokens = kPatches / 4;       // 256
constexpr int kPromptLen = 269;
constexpr int kMaxLen = 320;
constexpr int kLayers = 18;
constexpr int kQH = 16, kHD = 128, kH = 1024, kVocab = 103424;
constexpr int kVDim = 1152;
constexpr int kGenMax = 51;                      // kMaxLen - kPromptLen
constexpr double kRopeTheta = 500000.0;
constexpr float kNeg = -1e4f;

double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

inline float h2f(uint16_t h) {
    const uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t e = (h >> 10) & 0x1fu, m = h & 0x3ffu, bits;
    if (e == 0) {
        if (m == 0) bits = sign;
        else {
            e = 113;
            while (!(m & 0x400u)) { m <<= 1; --e; }
            m &= 0x3ffu;
            bits = sign | (e << 23) | (m << 13);
        }
    } else if (e == 31) bits = sign | 0x7f800000u | (m << 13);
    else bits = sign | ((e - 15 + 127) << 23) | (m << 13);
    float o;
    std::memcpy(&o, &bits, 4);
    return o;
}

inline uint16_t f2h(float v) {
    uint32_t b;
    std::memcpy(&b, &v, 4);
    const uint32_t sign = (b >> 16) & 0x8000u;
    const int32_t e = (int32_t)((b >> 23) & 0xffu) - 127 + 15;
    const uint32_t m = b & 0x7fffffu;
    if (e <= 0) return (uint16_t)sign;
    if (e >= 31) return (uint16_t)(sign | 0x7c00u);
    return (uint16_t)(sign | ((uint32_t)e << 10) | (m >> 13));
}

// vocab 行的转义还原（\n \r \t \\）
std::string unescape2(const std::string& e) {
    std::string o;
    o.reserve(e.size());
    for (size_t i = 0; i < e.size(); ++i) {
        if (e[i] == '\\' && i + 1 < e.size()) {
            char c = e[++i];
            if (c == 'n') o += '\n';
            else if (c == 'r') o += '\r';
            else if (c == 't') o += '\t';
            else o += c;
        } else {
            o += e[i];
        }
    }
    return o;
}

size_t dtype_bytes(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_16: return 2;
        case QNN_DATATYPE_INT_8: case QNN_DATATYPE_UINT_8: return 1;
        case QNN_DATATYPE_INT_64: return 8;
        default: return 4;
    }
}

std::string read_file(const std::string& p) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return {};
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

// ------------------------------------------------------------- QNN runtime
typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*SysGetProvidersFn)(const QnnSystemInterface_t***, uint32_t*);

// ---- 实验开关：环境变量或模型目录下的标志文件（不重装即可切换）----
std::string g_model_dir;
bool flag_on(const char* env, const char* file) {
    if (getenv(env)) return true;
    if (!g_model_dir.empty()) {
        struct stat st;
        if (::stat((g_model_dir + "/" + file).c_str(), &st) == 0) return true;
    }
    return false;
}
bool rt_debug() {
    static const bool v = flag_on("QVL_RTDEBUG", "rtdebug.txt");
    return v;
}
// QVL_KV_TINY=<bytes> / <模型目录>/kv_tiny.txt：把大输入的 clientBuf.dataSize
// 压到指定字节数——用于判定 exec 固定成本是"KV 拷贝"还是"DSP 重读权重"。
size_t kv_tiny_cap() {
    static const size_t v = []() -> size_t {
        if (const char* e = getenv("QVL_KV_TINY")) {
            long x = atol(e);
            return x > 0 ? (size_t)x : (size_t)0;
        }
        if (g_model_dir.empty()) return (size_t)0;
        FILE* f = fopen((g_model_dir + "/kv_tiny.txt").c_str(), "r");
        if (!f) return (size_t)0;
        long x = 0;
        int n = fscanf(f, "%ld", &x);
        fclose(f);
        return (n == 1 && x > 0) ? (size_t)x : (size_t)0;
    }();
    return v;
}

void qnn_log_cb(const char* fmt, QnnLog_Level_t lvl, uint64_t, va_list ap) {
    if (lvl > (rt_debug() ? QNN_LOG_LEVEL_DEBUG : QNN_LOG_LEVEL_WARN)) return;
    char b[512];
    vsnprintf(b, sizeof(b), fmt, ap);
    __android_log_print(lvl <= QNN_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                        : lvl <= QNN_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
                                                    : ANDROID_LOG_DEBUG,
                        "QnnVlRt", "%s", b);
}

struct Rt {
    void* h = nullptr;
    void* hSys = nullptr;
    const QnnSystemInterface_t* sys = nullptr;
    const QnnInterface_t* itf = nullptr;
    Qnn_LogHandle_t log = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    const QNN_INTERFACE_VER_TYPE& api() const { return itf->QNN_INTERFACE_VER_NAME; }
};

bool rt_start(Rt& rt) {
    void* rpc = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!rpc) rpc = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!rpc) rpc = dlopen("/vendor/lib64/libadsprpc.so", RTLD_NOW | RTLD_GLOBAL);

    rt.h = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!rt.h) { VERR("dlopen libQnnHtp: %s", dlerror()); return false; }
    auto gp = (GetProvidersFn)dlsym(rt.h, "QnnInterface_getProviders");
    const QnnInterface_t** provs = nullptr;
    uint32_t n = 0;
    if (!gp || gp(&provs, &n) != QNN_SUCCESS || !n) { VERR("getProviders"); return false; }
    rt.itf = provs[0];

    rt.hSys = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    if (!rt.hSys) { VERR("dlopen libQnnSystem: %s", dlerror()); return false; }
    auto sgp = (SysGetProvidersFn)dlsym(rt.hSys, "QnnSystemInterface_getProviders");
    const QnnSystemInterface_t** sprov = nullptr;
    uint32_t sn = 0;
    if (!sgp || sgp(&sprov, &sn) != QNN_SUCCESS || !sn) { VERR("sys getProviders"); return false; }
    rt.sys = sprov[0];

    if (rt.api().logCreate &&
        rt.api().logCreate(qnn_log_cb, rt_debug() ? QNN_LOG_LEVEL_DEBUG : QNN_LOG_LEVEL_WARN,
                           &rt.log) != QNN_SUCCESS)
        rt.log = nullptr;
    if (rt.api().backendCreate(rt.log, nullptr, &rt.backend) != QNN_SUCCESS) {
        VERR("backendCreate"); return false;
    }
    QnnHtpDevice_CustomConfig_t cfg{};
    cfg.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
    cfg.useSignedProcessDomain.deviceId = 0;
    cfg.useSignedProcessDomain.useSignedProcessDomain = true;
    QnnDevice_Config_t dc{};
    dc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dc.customConfig = &cfg;
    const QnnDevice_Config_t* dcs[] = {&dc, nullptr};
    Qnn_ErrorHandle_t e = rt.api().deviceCreate(rt.log, dcs, &rt.device);
    if (e != QNN_SUCCESS) e = rt.api().deviceCreate(rt.log, nullptr, &rt.device);
    if (e != QNN_SUCCESS) { VERR("deviceCreate 0x%llx", (unsigned long long)e); return false; }
    return true;
}

// --------------------------------------------------------------- tensors
struct Tensor {
    Qnn_Tensor_t desc{};
    std::vector<uint32_t> dims;
    size_t elems = 0, bytes = 0;
    std::vector<uint8_t> raw;
    std::string name;
    float* f32() { return (float*)raw.data(); }               // fp32 tensors only
    const float* f32c() const { return (const float*)raw.data(); }
};

struct Graph {
    Qnn_GraphHandle_t g = nullptr;
    std::vector<Tensor> ins, outs;
    std::vector<Qnn_Tensor_t> ti, to;
    size_t in0 = 0;
    bool ok = false;

    Tensor* in(const char* nm) {
        for (auto& t : ins) if (t.name == nm) return &t;
        return nullptr;
    }
    Tensor* out(const char* nm) {
        for (auto& t : outs) if (t.name == nm) return &t;
        return nullptr;
    }
    bool run(const Rt& rt) {
        const size_t cap = kv_tiny_cap();
        std::vector<uint32_t> saved;
        if (cap) {
            static bool announced = false;
            if (!announced) { announced = true; VLOG("KV_TINY 实验: 大输入 dataSize → %zu B", cap); }
            saved.reserve(ti.size());
            for (auto& t : ti) {
                saved.push_back(t.v1.clientBuf.dataSize);
                if (t.v1.clientBuf.dataSize > (1u << 20)) t.v1.clientBuf.dataSize = (uint32_t)cap;
            }
        }
        auto st = rt.api().graphExecute(g, ti.data(), (uint32_t)ti.size(), to.data(),
                                        (uint32_t)to.size(), nullptr, nullptr);
        if (cap) {
            for (size_t i = 0; i < ti.size(); ++i) ti[i].v1.clientBuf.dataSize = saved[i];
        }
        return st == QNN_SUCCESS;
    }
};

bool graph_bind(const Rt& rt, Qnn_ContextHandle_t ctx,
                const QnnSystemContext_GraphInfoV1_t& gi, Graph& G) {
    if (rt.api().graphRetrieve(ctx, gi.graphName, &G.g) != QNN_SUCCESS) {
        VERR("graphRetrieve %s", gi.graphName);
        return false;
    }
    auto setup = [](Tensor& t, const Qnn_Tensor_t& m) {
        t.desc = m;
        t.name = m.v1.name ? m.v1.name : "";
        t.dims.assign(m.v1.dimensions, m.v1.dimensions + m.v1.rank);
        t.elems = 1;
        for (auto d : t.dims) t.elems *= d;
        t.bytes = t.elems * dtype_bytes(m.v1.dataType);
        t.raw.assign(t.bytes, 0);
    };
    G.ins.resize(gi.numGraphInputs);
    G.outs.resize(gi.numGraphOutputs);
    for (uint32_t i = 0; i < gi.numGraphInputs; ++i) setup(G.ins[i], gi.graphInputs[i]);
    for (uint32_t i = 0; i < gi.numGraphOutputs; ++i) setup(G.outs[i], gi.graphOutputs[i]);
    G.ti.resize(G.ins.size());
    G.to.resize(G.outs.size());
    for (size_t i = 0; i < G.ins.size(); ++i) {
        G.ti[i] = G.ins[i].desc;
        G.ti[i].v1.clientBuf.data = G.ins[i].raw.data();
        G.ti[i].v1.clientBuf.dataSize = (uint32_t)G.ins[i].bytes;
        G.ti[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
    }
    for (size_t i = 0; i < G.outs.size(); ++i) {
        G.to[i] = G.outs[i].desc;
        G.to[i].v1.clientBuf.data = G.outs[i].raw.data();
        G.to[i].v1.clientBuf.dataSize = (uint32_t)G.outs[i].bytes;
        G.to[i].v1.memType = QNN_TENSORMEMTYPE_RAW;
    }
    G.in0 = G.ins.empty() ? 0 : G.ins[0].elems;
    G.ok = true;
    VLOG("graph %s: %u in %u out (in0=%zu elems)", gi.graphName, gi.numGraphInputs,
         gi.numGraphOutputs, G.in0);
    return true;
}

// --------------------------------------------------------- image helpers
void letterbox(const uint8_t* rgba, int w, int h, std::vector<uint8_t>& out) {
    out.assign((size_t)kCanvas * kCanvas * 4, 255);
    const float s = std::min((float)kCanvas / w, (float)kCanvas / h);
    const int nw = std::max(1, (int)std::lround(w * s));
    const int nh = std::max(1, (int)std::lround(h * s));
    const int x0 = (kCanvas - nw) / 2, y0 = (kCanvas - nh) / 2;
    for (int y = 0; y < nh; ++y) {
        float sy = ((y + 0.5f) * h) / nh - 0.5f;
        if (sy < 0) sy = 0;
        int y1 = (int)sy;
        if (y1 > h - 1) y1 = h - 1;
        const int y2 = y1 + 1 < h ? y1 + 1 : y1;
        const float fy = sy - y1;
        for (int x = 0; x < nw; ++x) {
            float sx = ((x + 0.5f) * w) / nw - 0.5f;
            if (sx < 0) sx = 0;
            int x1 = (int)sx;
            if (x1 > w - 1) x1 = w - 1;
            const int x2 = x1 + 1 < w ? x1 + 1 : x1;
            const float fx = sx - x1;
            uint8_t* d = &out[((size_t)(y0 + y) * kCanvas + (x0 + x)) * 4];
            for (int c = 0; c < 3; ++c) {
                const float a = rgba[((size_t)y1 * w + x1) * 4 + c];
                const float b = rgba[((size_t)y1 * w + x2) * 4 + c];
                const float cc = rgba[((size_t)y2 * w + x1) * 4 + c];
                const float e = rgba[((size_t)y2 * w + x2) * 4 + c];
                const float top = a + (b - a) * fx;
                const float bot = cc + (e - cc) * fx;
                d[c] = (uint8_t)(top + (bot - top) * fy + 0.5f);
            }
            d[3] = 255;
        }
    }
}

void patchify(const std::vector<uint8_t>& canvas, float* dst) {
    const int gw = kCanvas / 14;
    for (int py = 0; py < gw; ++py)
        for (int px = 0; px < gw; ++px) {
            const int p = py * gw + px;
            for (int c = 0; c < 3; ++c) {
                float* plane = dst + (size_t)p * 3 * 14 * 14 + (size_t)c * 14 * 14;
                for (int y = 0; y < 14; ++y) {
                    const uint8_t* s =
                        &canvas[((size_t)(py * 14 + y) * kCanvas + (size_t)(px * 14)) * 4];
                    for (int x = 0; x < 14; ++x) plane[y * 14 + x] = s[x * 4 + c] / 127.5f - 1.f;
                }
            }
        }
}

}  // namespace

struct QnnVl::Impl {
    Rt rt;
    Qnn_ContextHandle_t ctx = nullptr;
    Graph vis, mrg, pf, st;
    int vis_b = 1, mrg_b = 1;                // 批容量（batched dlc 为 4）

    std::vector<int64_t> promptIds;
    std::vector<int32_t> mrope;              // [3][525]
    std::vector<float> cosTab, sinTab;       // [525][128]
    std::vector<uint16_t> embed;             // [103424][1024] fp16
    std::vector<std::string> vocab;
    std::vector<float> promptEmb;            // [269][1024]
    std::vector<float> emb;                  // working prompt embeds
    std::vector<uint16_t> kc, vc;            // [18][16*320*128]
    int imgTokSlotBegin = -1;
};

namespace {

void build_rope_tables(const std::vector<int32_t>& mrope,
                       std::vector<float>& cosT, std::vector<float>& sinT) {
    const int T = (int)(mrope.size() / 3);
    const int half = kHD / 2;
    cosT.assign((size_t)T * kHD, 0.f);
    sinT.assign((size_t)T * kHD, 0.f);
    for (int t = 0; t < T; ++t)
        for (int j = 0; j < half; ++j) {
            const int axis = j < 16 ? 0 : (j < 40 ? 1 : 2);
            const double pos = mrope[(size_t)axis * T + t];
            const double ang = pos * std::pow(kRopeTheta, -(2.0 * j) / kHD);
            const float c = (float)std::cos(ang), sn = (float)std::sin(ang);
            // 宿主 llm_rope_tables: emb=[t,h,w 段] 复制成 128 维后取 cos/sin，
            // 因此两个 half 的 sin 同号（配合图内 cat[-t2,t1] 完成旋转）
            cosT[(size_t)t * kHD + j] = cosT[(size_t)t * kHD + half + j] = c;
            sinT[(size_t)t * kHD + j] = sinT[(size_t)t * kHD + half + j] = sn;
        }
}

}  // namespace

QnnVl::QnnVl() = default;
QnnVl::~QnnVl() { delete impl_; }

bool QnnVl::init(const std::string& dir, std::string* err) {
    g_model_dir = dir;
    delete impl_;
    impl_ = new Impl;
    Impl& im = *impl_;
    auto fail = [&](const std::string& m) {
        if (err) *err = m;
        VERR("init: %s", m.c_str());
        return false;
    };

    if (!rt_start(im.rt)) return fail("QNN runtime");
    std::string blob = read_file(dir + "/vl_ctx.bin");
    if (blob.empty()) return fail("vl_ctx.bin missing in " + dir);

    QnnSystemContext_Handle_t sysCtx = nullptr;
    auto& sys = *im.rt.sys;
    if (sys.QNN_SYSTEM_INTERFACE_VER_NAME.systemContextCreate(&sysCtx) != QNN_SUCCESS)
        return fail("systemContextCreate");
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t nInfo = 0;
    Qnn_ErrorHandle_t e = QNN_COMMON_ERROR_GENERAL;
    if (sys.QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetMetaData)
        e = sys.QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetMetaData(
            sysCtx, blob.data(), (uint64_t)blob.size(), &info);
    uint32_t nGraphs = 0;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    if (e == QNN_SUCCESS && info) {
        switch (info->version) {
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
                nGraphs = info->contextBinaryInfoV1.numGraphs;
                graphs = info->contextBinaryInfoV1.graphs; break;
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
                nGraphs = info->contextBinaryInfoV2.numGraphs;
                graphs = info->contextBinaryInfoV2.graphs; break;
            case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
                nGraphs = info->contextBinaryInfoV3.numGraphs;
                graphs = info->contextBinaryInfoV3.graphs; break;
            default: break;
        }
    }
    if (!nGraphs || !graphs) return fail("context metadata");

    if (im.rt.api().contextCreateFromBinary(im.rt.backend, im.rt.device, nullptr,
                                            blob.data(), blob.size(), &im.ctx,
                                            nullptr) != QNN_SUCCESS)
        return fail("contextCreateFromBinary");

    for (uint32_t g = 0; g < nGraphs; ++g) {
        const auto& gv = graphs[g].graphInfoV1;
        Graph G;
        if (!graph_bind(im.rt, im.ctx, gv, G)) continue;
        // 图识别按输入元素数；优先取 b4 版（同 ctx 内不会同时存在两版）。
        if (G.in0 == (size_t)4 * kPatches * 3 * 14 * 14) { im.vis = std::move(G); im.vis_b = 4; }
        else if (G.in0 == (size_t)4 * kImageTokens * 4 * kVDim) { im.mrg = std::move(G); im.mrg_b = 4; }
        else if (G.in0 == (size_t)kPatches * 3 * 14 * 14) { im.vis = std::move(G); im.vis_b = 1; }
        else if (G.in0 == (size_t)kImageTokens * 4 * kVDim) { im.mrg = std::move(G); im.mrg_b = 1; }
        else if (G.ins.size() > 4) {
            if (G.in0 == (size_t)kPromptLen * kH) im.pf = std::move(G);
            else if (G.in0 == (size_t)kH) im.st = std::move(G);
        }
    }
    if (!im.vis.ok || !im.mrg.ok || !im.pf.ok || !im.st.ok)
        return fail(std::string("graphs vis=") + (im.vis.ok ? "1" : "0") +
                    " mrg=" + (im.mrg.ok ? "1" : "0") +
                    " pf=" + (im.pf.ok ? "1" : "0") + " st=" + (im.st.ok ? "1" : "0"));

    {
        std::string b = read_file(dir + "/vl_prompt_ids.i64");
        if (b.size() != (size_t)kPromptLen * 8) return fail("vl_prompt_ids.i64");
        im.promptIds.resize(kPromptLen);
        std::memcpy(im.promptIds.data(), b.data(), b.size());
    }
    {
        std::string b = read_file(dir + "/vl_mrope.i32");
        const int T = kPromptLen + 256;
        if (b.size() != (size_t)3 * T * 4) return fail("vl_mrope.i32");
        im.mrope.resize(3 * T);
        std::memcpy(im.mrope.data(), b.data(), b.size());
        build_rope_tables(im.mrope, im.cosTab, im.sinTab);
    }
    {
        std::string b = read_file(dir + "/vl_embed_f16.bin");
        if (b.size() != (size_t)kVocab * kH * 2) return fail("vl_embed_f16.bin");
        im.embed.resize(kVocab * kH);
        std::memcpy(im.embed.data(), b.data(), b.size());
    }
    {
        std::ifstream f(dir + "/vl_vocab.tsv");
        if (!f) return fail("vl_vocab.tsv");
        im.vocab.resize(kVocab);
        std::string line;
        int id = 0;
        while (id < kVocab && std::getline(f, line)) im.vocab[id++] = line;
    }
    im.promptEmb.assign((size_t)kPromptLen * kH, 0.f);
    for (int t = 0; t < kPromptLen; ++t) {
        const int64_t id = im.promptIds[t];
        if (id < 0 || id >= kVocab) continue;
        const uint16_t* row = &im.embed[(size_t)id * kH];
        float* dst = &im.promptEmb[(size_t)t * kH];
        for (int i = 0; i < kH; ++i) dst[i] = h2f(row[i]);
    }
    im.imgTokSlotBegin = -1;
    {
        const int64_t imgTok = im.promptIds[5];
        int cnt = 0;
        for (int t = 0; t < kPromptLen; ++t)
            if (im.promptIds[t] == imgTok) {
                if (im.imgTokSlotBegin < 0) im.imgTokSlotBegin = t;
                ++cnt;
            }
        if (cnt != kImageTokens) return fail("image slots");
    }
    const size_t cacheLayer = (size_t)kQH * kMaxLen * kHD;
    im.kc.assign((size_t)kLayers * cacheLayer, 0);
    im.vc.assign((size_t)kLayers * cacheLayer, 0);
    im.emb.assign((size_t)kPromptLen * kH, 0.f);

    if (getenv("QVL_DEBUG_ROPE")) {
        std::ofstream f(dir + "/dbg_cos.raw", std::ios::binary);
        f.write((const char*)im.cosTab.data(), im.cosTab.size() * 4);
        std::ofstream g(dir + "/dbg_sin.raw", std::ios::binary);
        g.write((const char*)im.sinTab.data(), im.sinTab.size() * 4);
        VLOG("rope tables dumped (%zu rows)", im.cosTab.size() / kHD);
    }
    ready_ = true;
    VLOG("init ok");
    return true;
}

std::string QnnVl::ocr(const uint8_t* rgba, int w, int h) {
    auto embs = prepare_batch_({rgba}, {w}, {h});
    if (embs.empty() || embs[0].empty()) return {};
    return decode_one_(embs[0].data());
}
std::vector<std::string> QnnVl::ocr_batch(const std::vector<const uint8_t*>& rgba,
                                          const std::vector<int>& ws,
                                          const std::vector<int>& hs,
                                          const std::vector<int>* gen_caps) {
    std::vector<std::string> out;
    if (!ready_ || rgba.empty()) return out;
    auto embs = prepare_batch_(rgba, ws, hs);
    out.reserve(embs.size());
    const bool capsOk = gen_caps && gen_caps->size() == embs.size();
    if (capsOk) {
        int mn = 1 << 30, mx = 0;
        for (int c : *gen_caps) { mn = std::min(mn, c); mx = std::max(mx, c); }
        VLOG("gen caps: n=%zu min=%d max=%d", gen_caps->size(), mn, mx);
    }
    for (size_t i = 0; i < embs.size(); ++i)
        out.push_back(embs[i].empty() ? std::string()
                                      : decode_one_(embs[i].data(),
                                                    capsOk ? (*gen_caps)[i] : 0));
    return out;
}

// 批量准备：letterbox+patchify(B 段一批) -> vision(B) -> merger(B)
// -> 每段完整 inputs_embeds（prompt 文本嵌入 + 该段图像 token），供 decode_one_。
std::vector<std::vector<float>> QnnVl::prepare_batch_(
    const std::vector<const uint8_t*>& rgba, const std::vector<int>& ws,
    const std::vector<int>& hs) {
    std::vector<std::vector<float>> out;
    if (!ready_ || rgba.empty()) return out;
    Impl& im = *impl_;
    const size_t perSeg = (size_t)kPatches * 3 * 14 * 14;   // vision 输入/段（fp32 元素）
    const int Bcap = im.vis_b > 0 ? im.vis_b : 1;
    Tensor* vin = im.vis.in("patches");
    Tensor* min_ = im.mrg.in("merged4");
    if (!vin || !min_) return out;
    if (vin->elems != (size_t)Bcap * perSeg) return out;
    if (min_->elems != (size_t)Bcap * kImageTokens * 4 * kVDim) return out;

    const int N = (int)rgba.size();
    out.resize(N);
    static std::vector<uint8_t> canvas;
    const int gm = kGrid / 2;
    for (int base = 0; base < N; base += Bcap) {
        const int nb = std::min(Bcap, N - base);
        if (base == 0) t_ = VlTimings{};   // 本次批调用的统计从头计（页级累计）
        const double tv = now_ms();
        for (int b = 0; b < nb; ++b) {
            letterbox(rgba[base + b], ws[base + b], hs[base + b], canvas);
            patchify(canvas, vin->f32() + (size_t)b * perSeg);
        }
        for (int b = nb; b < Bcap; ++b)   // 不满一批时补零（输出忽略）
            std::memset(vin->f32() + (size_t)b * perSeg, 0, perSeg * 4);
        if (!im.vis.run(im.rt)) { VERR("vision exec"); return {}; }
        if (base == 0) t_.preprocess_ms = now_ms() - tv;   // 首段含 letterbox/patchify
        t_.vision_ms += now_ms() - tv;

        // 2x2 merge（批）
        const float* F = im.vis.outs[0].f32c();            // [B,1024,1152]
        float* M = min_->f32();                            // [B,256,4608]
        for (int b = 0; b < Bcap; ++b)
            for (int by = 0; by < gm; ++by)
                for (int bx = 0; bx < gm; ++bx)
                    for (int sy = 0; sy < 2; ++sy)
                        for (int sx = 0; sx < 2; ++sx) {
                            const int prow = (2 * by + sy) * kGrid + (2 * bx + sx);
                            std::memcpy(
                                M + ((size_t)b * kImageTokens + by * gm + bx) * (4 * kVDim) +
                                    (size_t)(sy * 2 + sx) * kVDim,
                                F + ((size_t)b * kPatches + prow) * kVDim, kVDim * 4);
                        }
        const double tm = now_ms();
        if (!im.mrg.run(im.rt)) { VERR("merger exec"); return {}; }
        t_.merger_ms += now_ms() - tm;

        const float* E = im.mrg.outs[0].f32c();            // [B,256,1024]
        for (int b = 0; b < nb; ++b) {
            auto& emb = out[base + b];
            emb.resize((size_t)kPromptLen * kH);
            std::memcpy(emb.data(), im.promptEmb.data(), im.promptEmb.size() * 4);
            std::memcpy(&emb[(size_t)im.imgTokSlotBegin * kH],
                        E + (size_t)b * kImageTokens * kH, (size_t)kImageTokens * kH * 4);
        }
    }
    return out;
}

std::string QnnVl::decode_one_(const float* emb, int gen_max) {
    if (!ready_ || !emb) return {};
    Impl& im = *impl_;
    // 逐段生成上限：调用方按裁剪尺寸收紧（§8 #8）；0/越界 → 全局上限。
    const int genCap = (gen_max > 0 && gen_max < kGenMax) ? gen_max : kGenMax;
    const double t0 = now_ms();
    const size_t cacheLayer = (size_t)kQH * kMaxLen * kHD;

    std::fill(im.kc.begin(), im.kc.end(), (uint16_t)0);
    std::fill(im.vc.begin(), im.vc.end(), (uint16_t)0);

    auto bindCaches = [&](Graph& G) {
        for (auto& t : G.ins) {
            if (t.name.size() > 2 && t.name[1] == 'c' &&
                (t.name[0] == 'k' || t.name[0] == 'v')) {
                const int idx = atoi(t.name.c_str() + 2);
                const uint16_t* src = (t.name[0] == 'k' ? im.kc : im.vc).data() +
                                      (size_t)idx * cacheLayer;
                std::memcpy(t.raw.data(), src, cacheLayer * 2);
            }
        }
    };
    // ko/vo 为 fp16 [QH][T][HD]；scatter 到 cache 槽位 r0..r0+T
    auto scatter = [&](Graph& G, int r0, int T) {
        for (auto& t : G.outs) {
            if (t.name.size() > 2 && t.name[1] == 'o' &&
                (t.name[0] == 'k' || t.name[0] == 'v')) {
                const int idx = atoi(t.name.c_str() + 2);
                uint16_t* dst = (t.name[0] == 'k' ? im.kc : im.vc).data() +
                                (size_t)idx * cacheLayer;
                const uint16_t* src = (const uint16_t*)t.raw.data();
                for (int q = 0; q < kQH; ++q)
                    std::memcpy(dst + ((size_t)q * kMaxLen + r0) * kHD,
                                src + ((size_t)q * T) * kHD, (size_t)T * kHD * 2);
            }
        }
    };

    // ---- prefill: one exec, T=269
    double tp = now_ms();
    Tensor* pin = im.pf.in("inputs_embeds");
    Tensor* pcos = im.pf.in("cos");
    Tensor* psin = im.pf.in("sin");
    Tensor* pmask = im.pf.in("mask");
    Tensor* plog = im.pf.out("logits");
    if (!pin || !pcos || !psin || !pmask || !plog) { VERR("pf tensors"); return {}; }
    std::memcpy(pin->f32(), emb, (size_t)kPromptLen * kH * 4);
    std::memcpy(pcos->f32(), im.cosTab.data(), (size_t)kPromptLen * kHD * 4);
    std::memcpy(psin->f32(), im.sinTab.data(), (size_t)kPromptLen * kHD * 4);
    {
        float* m = pmask->f32();                   // [269][320+269]
        std::fill(m, m + pmask->elems, kNeg);
        for (int r = 0; r < kPromptLen; ++r)
            for (int j = 0; j <= r; ++j) m[(size_t)r * (kMaxLen + kPromptLen) + kMaxLen + j] = 0.f;
    }
    double tp_b = now_ms();
    bindCaches(im.pf);
    double tp_e = now_ms();
    if (!im.pf.run(im.rt)) { VERR("prefill exec"); return {}; }
    double tp_x = now_ms();
    scatter(im.pf, 0, kPromptLen);
    t_.prefill_ms = now_ms() - tp;
    VLOG("split pf: setup=%.1f bind=%.1f exec=%.1f scatter=%.1f ms",
         tp_b - tp, tp_e - tp_b, tp_x - tp_e, now_ms() - tp_x);

    // ---- greedy decode
    double td = now_ms();
    Tensor* sin_ = im.st.in("inputs_embeds");
    Tensor* scos = im.st.in("cos");
    Tensor* ssin = im.st.in("sin");
    Tensor* smask = im.st.in("mask");
    Tensor* slog = im.st.out("logits");
    if (!sin_ || !scos || !ssin || !smask || !slog) { VERR("st tensors"); return {}; }

    auto argmax_of = [&](const float* lg) {
        int best = 0;
        float bv = -1e30f;
        for (int i = 0; i < kVocab; ++i)
            if (lg[i] > bv) { bv = lg[i]; best = i; }
        return best;
    };
    // OCR 文本不会以空白开头：首个生成 token 跳过纯空白/控制类
    // （换行/空格类）。fp16 漂移 ~2 logit 时这能救回首字。
    auto textToken = [&](int id) {
        if (id < 0 || id >= (int)im.vocab.size()) return false;
        const std::string& e = im.vocab[id];
        if (e.empty()) return false;
        std::string v = unescape2(e);
        bool onlyWs = true;
        for (size_t i = 0; i < v.size(); ++i) {
            const unsigned char c = (unsigned char)v[i];
            const bool isU2581 = (c == 0xE2 && i + 2 < v.size() &&
                                  (unsigned char)v[i+1] == 0x96 && (unsigned char)v[i+2] == 0x81);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t' && !isU2581) {
                onlyWs = false;
                break;
            }
        }
        return !onlyWs;
    };
    // 首 token：贪心，但跳过空白/控制类
    int next = argmax_of(plog->f32c());
    if (!textToken(next)) {
        int cand = -1;
        float cv = -1e30f;
        const float* lg = plog->f32c();
        for (int i = 0; i < kVocab; ++i)
            if (lg[i] > cv && textToken(i)) { cv = lg[i]; cand = i; }
        if (cand >= 0) next = cand;
    }
    std::vector<int> gen;
    // 调试：打印每步 top5（QVL_DEBUG_TOP=1 时）
    const bool dbgTop = getenv("QVL_DEBUG_TOP") != nullptr;
    auto dumpTop = [&](const char* tag, const float* lg) {
        if (!dbgTop) return;
        int idx[kVocab];
        for (int i = 0; i < kVocab; ++i) idx[i] = i;
        std::partial_sort(idx, idx + 5, idx + kVocab, [&](int a, int b) { return lg[a] > lg[b]; });
        printf("TOP %s:", tag);
        for (int i = 0; i < 5; ++i) printf(" %d(%.2f)", idx[i], lg[idx[i]]);
        printf("\n");
    };
    dumpTop("pf", plog->f32c());
    int rep = 0, prevTok = -1;
    double t_pre = 0, t_bind = 0, t_exec = 0, t_post = 0;
    int nst = 0;
    for (int k = 0; k < genCap; ++k) {
        if (next == 2 || next == 1) break;
        // 连续 3 个相同 token = fp16 漂移下的退化输出，截断
        rep = (next == prevTok) ? rep + 1 : 0;
        prevTok = next;
        if (rep >= 2) break;
        gen.push_back(next);
        const int t = kPromptLen + k;
        if (t >= kMaxLen) break;
        const double s0 = now_ms();
        const uint16_t* row = &im.embed[(size_t)next * kH];
        for (int i = 0; i < kH; ++i) sin_->f32()[i] = h2f(row[i]);
        std::memcpy(scos->f32(), &im.cosTab[(size_t)t * kHD], kHD * 4);
        std::memcpy(ssin->f32(), &im.sinTab[(size_t)t * kHD], kHD * 4);
        float* m = smask->f32();                   // [1][321]
        std::fill(m, m + smask->elems, kNeg);
        for (int j = 0; j < t; ++j) m[j] = 0.f;
        m[kMaxLen] = 0.f;
        const double s1 = now_ms();
        bindCaches(im.st);
        const double s2 = now_ms();
        if (!im.st.run(im.rt)) { VERR("step %d", k); break; }
        const double s3 = now_ms();
        scatter(im.st, t, 1);
        next = argmax_of(slog->f32c());
        dumpTop("st", slog->f32c());
        const double s4 = now_ms();
        t_pre += s1 - s0; t_bind += s2 - s1; t_exec += s3 - s2; t_post += s4 - s3;
        ++nst;
    }
    if (nst)
        VLOG("split st: n=%d pre=%.1f bind=%.1f exec=%.1f post=%.1f (ms/step avg)",
             nst, t_pre / nst, t_bind / nst, t_exec / nst, t_post / nst);
    t_.decode_ms = now_ms() - td;
    t_.out_tokens = (int)gen.size();
    t_.total_ms = now_ms() - t0;

    // vocab 行为转义格式（\n \r \t \\），读取时还原
    auto unescape = [](const std::string& e) {
        std::string o;
        o.reserve(e.size());
        for (size_t i = 0; i < e.size(); ++i) {
            if (e[i] == '\\' && i + 1 < e.size()) {
                char c = e[++i];
                if (c == 'n') o += '\n';
                else if (c == 'r') o += '\r';
                else if (c == 't') o += '\t';
                else o += c;
            } else {
                o += e[i];
            }
        }
        return o;
    };
    // 跳过特殊 token（导出自 tokenizer.all_special_ids）
    auto isSpecial = [](int id) {
        static const int sp[] = {1, 2, 0, 100272, 100273, 100274,
                                 100295, 101304, 101305, 101306, 101307};
        for (int v : sp)
            if (v == id) return true;
        return false;
    };
    // 字节回退 token：Qwen BPE 用 "<0xE5>" 这样的条目表示单个字节，
    // HF decode() 会把它们还原成字节流再 UTF-8 解码（如 3 个字节拼出「嗒」）。
    auto isByteTok = [](const std::string& s, unsigned* byte) {
        if (s.size() != 6 || s[0] != '<' || s[1] != '0' || s[2] != 'x' || s[5] != '>')
            return false;
        auto hex = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        };
        const int h = hex(s[3]), l = hex(s[4]);
        if (h < 0 || l < 0) return false;
        *byte = (unsigned)(h * 16 + l);
        return true;
    };
    std::string out;
    for (int id : gen) {
        if (id < 0 || id >= (int)im.vocab.size() || isSpecial(id)) continue;
        std::string s = unescape(im.vocab[id]);
        unsigned b;
        if (isByteTok(s, &b)) {       // 单字节，直接续进字节流（结尾统一按 UTF-8 看）
            out += (char)b;
            continue;
        }
        for (size_t p; (p = s.find("\xE2\x96\x81")) != std::string::npos;)
            s.replace(p, 3, " ");
        out += s;
    }
    std::istringstream ss(out);
    std::string line, joined;
    while (std::getline(ss, line)) {
        while (!line.empty() && line.front() == ' ') line.erase(line.begin());
        if (!line.empty()) joined += line + "\n";
    }
    if (!joined.empty() && joined.back() == '\n') joined.pop_back();
    return joined;
}

}  // namespace cs::ocr
