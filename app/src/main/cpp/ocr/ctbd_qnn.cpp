// CTBD detector on QNN/HTP — see ctbd_qnn.h for the verified pipeline.
//
// Session plumbing mirrors qnn_vl.cpp (rt_start / metadata-driven graph_bind /
// RAW clientBuf tensors): one context binary, tensors bound from the ctx
// metadata so dtypes and names always match what was compiled on-device.
#include "ctbd_qnn.h"

#include <android/log.h>
#include <dlfcn.h>
#include <math.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <string>
#include <vector>

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

namespace cs::ocr {
namespace {

#define CLOG(...) __android_log_print(ANDROID_LOG_INFO, "QnnCtbd", __VA_ARGS__)
#define CERR(...) __android_log_print(ANDROID_LOG_ERROR, "QnnCtbd", __VA_ARGS__)

constexpr int kDet = 640;
constexpr float kConfThresh = 0.3f;      // BT confidence_threshold
constexpr float kMinSidePx = 5.0f;       // BT filter_bounding_boxes (640 space)
constexpr float kDedupIou = 0.7f;        // BT merge_duplicate_boxes
constexpr float kContainment = 0.8f;     // BT remove_contained_text

double now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

uint16_t f2h(float v) {
    // round-to-nearest-even fp32->fp16 (bit twiddle, matches the reference pack)
    uint32_t x; memcpy(&x, &v, 4);
    const uint16_t sign = (uint16_t)((x >> 16) & 0x8000);
    int32_t exp = (int32_t)((x >> 23) & 0xFF) - 127 + 15;
    uint32_t mant = x & 0x7FFFFF;
    if (((x >> 23) & 0xFF) == 0xFF) return sign | 0x7C00;          // inf/nan
    if (((x >> 23) & 0xFF) == 0) return sign;                       // zero/denormal
    if (exp >= 0x1F) return sign | 0x7C00;                          // overflow -> inf
    if (exp <= 0) {                                                  // underflow -> 0
        if (exp < -1) return sign;
        mant |= 0x800000;
        const uint32_t shift = (uint32_t)(14 - exp);
        const uint32_t kept = mant >> shift;
        return sign | (uint16_t)(kept >> 1);
    }
    uint32_t half = (uint32_t)exp << 10 | (mant >> 13);
    const uint32_t rem = mant & 0x1FFF;
    if (rem > 0x1000 || (rem == 0x1000 && (half & 1))) ++half;
    return sign | (uint16_t)half;
}

float h2f(uint16_t h) {
    uint32_t sign = (uint32_t)(h & 0x8000) << 16;
    uint32_t exp = (h >> 10) & 0x1F;
    uint32_t mant = h & 0x3FF;
    uint32_t x;
    if (exp == 0) {
        if (mant == 0) x = sign;
        else {  // denormal
            exp = 127 - 15 + 1;
            while (!(mant & 0x400)) { mant <<= 1; --exp; }
            mant &= 0x3FF;
            x = sign | (exp << 23) | (mant << 13);
        }
    } else if (exp == 0x1F) {
        x = sign | 0x7F800000 | (mant << 13);
    } else {
        x = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f; memcpy(&f, &x, 4);
    return f;
}

typedef Qnn_ErrorHandle_t (*GetProvidersFn)(const QnnInterface_t***, uint32_t*);
typedef Qnn_ErrorHandle_t (*SysGetProvidersFn)(const QnnSystemInterface_t***, uint32_t*);

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
    rt.h = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!rt.h) { CERR("dlopen libQnnHtp: %s", dlerror()); return false; }
    auto gp = (GetProvidersFn)dlsym(rt.h, "QnnInterface_getProviders");
    const QnnInterface_t** provs = nullptr;
    uint32_t n = 0;
    if (!gp || gp(&provs, &n) != QNN_SUCCESS || !n) { CERR("getProviders"); return false; }
    rt.itf = provs[0];

    rt.hSys = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
    if (!rt.hSys) { CERR("dlopen libQnnSystem: %s", dlerror()); return false; }
    auto sgp = (SysGetProvidersFn)dlsym(rt.hSys, "QnnSystemInterface_getProviders");
    const QnnSystemInterface_t** sprov = nullptr;
    uint32_t sn = 0;
    if (!sgp || sgp(&sprov, &sn) != QNN_SUCCESS || !sn) { CERR("sys getProviders"); return false; }
    rt.sys = sprov[0];

    if (rt.api().backendCreate(nullptr, nullptr, &rt.backend) != QNN_SUCCESS) {
        CERR("backendCreate");
        return false;
    }
    QnnHtpDevice_CustomConfig_t cfg{};
    cfg.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
    cfg.useSignedProcessDomain.deviceId = 0;
    cfg.useSignedProcessDomain.useSignedProcessDomain = true;
    QnnDevice_Config_t dc{};
    dc.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    dc.customConfig = &cfg;
    const QnnDevice_Config_t* dcs[] = {&dc, nullptr};
    Qnn_ErrorHandle_t e = rt.api().deviceCreate(nullptr, dcs, &rt.device);
    if (e != QNN_SUCCESS) e = rt.api().deviceCreate(nullptr, nullptr, &rt.device);
    if (e != QNN_SUCCESS) { CERR("deviceCreate 0x%llx", (unsigned long long)e); return false; }
    return true;
}

struct Tensor {
    Qnn_Tensor_t desc{};
    std::vector<uint32_t> dims;
    size_t elems = 0, bytes = 0;
    std::vector<uint8_t> raw;
    std::string name;
    float* f32() { return (float*)raw.data(); }
    uint16_t* f16() { return (uint16_t*)raw.data(); }
};

size_t dtype_bytes(Qnn_DataType_t t) {
    switch (t) {
        case QNN_DATATYPE_FLOAT_16: return 2;
        case QNN_DATATYPE_FLOAT_32: return 4;
        case QNN_DATATYPE_INT_32: return 4;
        case QNN_DATATYPE_UINT_8: return 1;
        case QNN_DATATYPE_INT_8: return 1;
        case QNN_DATATYPE_INT_64: return 8;
        default: return 4;
    }
}

bool graph_bind(const Rt& rt, Qnn_ContextHandle_t ctx,
                const QnnSystemContext_GraphInfoV1_t& gi,
                std::vector<Tensor>& ins, std::vector<Tensor>& outs,
                Qnn_GraphHandle_t& g) {
    if (rt.api().graphRetrieve(ctx, gi.graphName, &g) != QNN_SUCCESS) {
        CERR("graphRetrieve %s", gi.graphName);
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
    ins.resize(gi.numGraphInputs);
    outs.resize(gi.numGraphOutputs);
    for (uint32_t i = 0; i < gi.numGraphInputs; ++i) setup(ins[i], gi.graphInputs[i]);
    for (uint32_t i = 0; i < gi.numGraphOutputs; ++i) setup(outs[i], gi.graphOutputs[i]);
    for (auto& t : ins) {
        t.desc.v1.clientBuf.data = t.raw.data();
        t.desc.v1.clientBuf.dataSize = (uint32_t)t.bytes;
        t.desc.v1.memType = QNN_TENSORMEMTYPE_RAW;
    }
    for (auto& t : outs) {
        t.desc.v1.clientBuf.data = t.raw.data();
        t.desc.v1.clientBuf.dataSize = (uint32_t)t.bytes;
        t.desc.v1.memType = QNN_TENSORMEMTYPE_RAW;
    }
    CLOG("graph %s bound: %u in / %u out", gi.graphName, gi.numGraphInputs,
         gi.numGraphOutputs);
    return true;
}

std::string read_file(const std::string& p) {
    FILE* f = fopen(p.c_str(), "rb");
    if (!f) return {};
    fseek(f, 0, SEEK_END);
    const long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    std::string b;
    b.resize((size_t)n);
    if (n > 0 && fread(b.data(), 1, (size_t)n, f) != (size_t)n) b.clear();
    fclose(f);
    return b;
}

float iou(const float* a, const float* b) {
    const float ix0 = fmaxf(a[0], b[0]), iy0 = fmaxf(a[1], b[1]);
    const float ix1 = fminf(a[2], b[2]), iy1 = fminf(a[3], b[3]);
    const float iw = fmaxf(0.f, ix1 - ix0), ih = fmaxf(0.f, iy1 - iy0);
    const float inter = iw * ih;
    const float ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
    return ua > 0 ? inter / ua : 0.f;
}

float contained(const float* inner, const float* outer) {
    const float ix0 = fmaxf(inner[0], outer[0]), iy0 = fmaxf(inner[1], outer[1]);
    const float ix1 = fminf(inner[2], outer[2]), iy1 = fminf(inner[3], outer[3]);
    const float iw = fmaxf(0.f, ix1 - ix0), ih = fmaxf(0.f, iy1 - iy0);
    const float ia = (inner[2] - inner[0]) * (inner[3] - inner[1]);
    return ia > 0 ? (iw * ih) / ia : 0.f;
}

}  // namespace

struct QnnCtbd::Impl {
    Rt rt;
    Qnn_ContextHandle_t ctx = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::vector<Tensor> ins, outs;
    std::vector<float> stage;  // fp32 CHW scratch (3*640*640)
    bool ok = false;
};

QnnCtbd::QnnCtbd() = default;
QnnCtbd::~QnnCtbd() { delete impl_; }

bool QnnCtbd::init(const std::string& dir, std::string* err) {
    delete impl_;
    impl_ = new Impl;
    auto fail = [&](const std::string& m) {
        if (err) *err = m;
        CERR("init: %s", m.c_str());
        return false;
    };
    if (!rt_start(impl_->rt)) return fail("QNN runtime");

    // DSP skel lives next to the ctx binary (staged by the app).
    if (::access((dir + "/libQnnHtpV73Skel.so").c_str(), R_OK) == 0) {
        setenv("ADSP_LIBRARY_PATH", dir.c_str(), 1);
    }

    const std::string blob = read_file(dir + "/ctbd_ctx.bin");
    if (blob.empty()) return fail("ctbd_ctx.bin missing in " + dir);

    QnnSystemContext_Handle_t sysCtx = nullptr;
    auto& sys = *impl_->rt.sys;
    if (sys.QNN_SYSTEM_INTERFACE_VER_NAME.systemContextCreate(&sysCtx) != QNN_SUCCESS)
        return fail("systemContextCreate");
    const QnnSystemContext_BinaryInfo_t* info = nullptr;
    Qnn_ContextBinarySize_t nInfo = 0;
    if (sys.QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetMetaData &&
        sys.QNN_SYSTEM_INTERFACE_VER_NAME.systemContextGetMetaData(
            sysCtx, blob.data(), (uint64_t)blob.size(), &info) != QNN_SUCCESS)
        return fail("context metadata");
    uint32_t nGraphs = 0;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    if (info) {
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
    if (!nGraphs || !graphs) return fail("no graphs in ctx");

    if (impl_->rt.api().contextCreateFromBinary(impl_->rt.backend, impl_->rt.device,
                                                nullptr, blob.data(), blob.size(),
                                                &impl_->ctx, nullptr) != QNN_SUCCESS)
        return fail("contextCreateFromBinary");

    // Identify the detector graph by its single 1x3x640x640 input.
    bool bound = false;
    for (uint32_t g = 0; g < nGraphs && !bound; ++g) {
        const auto& gv = graphs[g].graphInfoV1;
        std::vector<Tensor> ti, to;
        Qnn_GraphHandle_t gh = nullptr;
        if (!graph_bind(impl_->rt, impl_->ctx, gv, ti, to, gh)) continue;
        if (ti.size() == 1 && ti[0].elems == (size_t)3 * kDet * kDet) {
            impl_->ins = std::move(ti);
            impl_->outs = std::move(to);
            impl_->graph = gh;
            bound = true;
        }
    }
    if (!bound) return fail("no 1x3x640x640-input graph found");

    impl_->stage.resize((size_t)3 * kDet * kDet);
    impl_->ok = true;
    ready_ = true;
    CLOG("init ok (%zu in / %zu out tensors)", impl_->ins.size(), impl_->outs.size());
    return true;
}

std::vector<CtbdBox> QnnCtbd::detect(const uint8_t* rgba, int w, int h) {
    std::vector<CtbdBox> out;
    if (!ready_ || !rgba || w <= 0 || h <= 0) return out;
    Impl& im = *impl_;
    const double t0 = now_ms();

    // BT preprocessing: bilinear stretch to 640x640, RGB /255, NCHW.
    Tensor& in = im.ins[0];
    const bool inF16 = in.desc.v1.dataType == QNN_DATATYPE_FLOAT_16;
    const float fx = (float)w / kDet, fy = (float)h / kDet;
    const size_t plane = (size_t)kDet * kDet;
    for (int c = 0; c < 3; ++c) {
        float* dst = im.stage.data() + (size_t)c * plane;
        for (int y = 0; y < kDet; ++y) {
            float sy = (y + 0.5f) * fy - 0.5f;
            if (sy < 0) sy = 0;
            int y0 = (int)sy; if (y0 > h - 1) y0 = h - 1;
            const int y1 = y0 + 1 < h ? y0 + 1 : y0;
            const float wy = sy - y0;
            for (int x = 0; x < kDet; ++x) {
                float sx = (x + 0.5f) * fx - 0.5f;
                if (sx < 0) sx = 0;
                int x0 = (int)sx; if (x0 > w - 1) x0 = w - 1;
                const int x1 = x0 + 1 < w ? x0 + 1 : x0;
                const float wx = sx - x0;
                const uint8_t* p00 = rgba + ((size_t)y0 * w + x0) * 4;
                const uint8_t* p01 = rgba + ((size_t)y0 * w + x1) * 4;
                const uint8_t* p10 = rgba + ((size_t)y1 * w + x0) * 4;
                const uint8_t* p11 = rgba + ((size_t)y1 * w + x1) * 4;
                const float wgt[4] = {(1 - wx) * (1 - wy), wx * (1 - wy),
                                      (1 - wx) * wy, wx * wy};
                const uint8_t* ps[4] = {p00, p01, p10, p11};
                float v = 0;
                for (int k = 0; k < 4; ++k) v += ps[k][c] * wgt[k];
                dst[(size_t)y * kDet + x] = v * (1.f / 255.f);
            }
        }
    }
    if (inF16) {
        uint16_t* p = in.f16();
        for (size_t i = 0; i < in.elems; ++i) p[i] = f2h(im.stage[i]);
    } else {
        memcpy(in.f32(), im.stage.data(), in.bytes);
    }

    std::vector<Qnn_Tensor_t> ti(1, in.desc), to;
    to.reserve(im.outs.size());
    for (auto& t : im.outs) to.push_back(t.desc);
    const double t1 = now_ms();
    if (im.rt.api().graphExecute(im.graph, ti.data(), 1, to.data(),
                                (uint32_t)to.size(), nullptr, nullptr) != QNN_SUCCESS) {
        CERR("graphExecute failed");
        return out;
    }
    fwd_ms_ = (float)(now_ms() - t1);

    // Decode the three outputs by name (scores/labels/boxes as verified).
    const float* scores = nullptr;
    const float* boxes = nullptr;
    const int32_t* labels = nullptr;
    int n = 0;
    std::vector<float> scratchF, scratchB;   // fp16 -> fp32 decode scratch
    for (auto& t : im.outs) {
        const size_t e = t.elems;
        if (t.name == "scores" && e >= 3) {
            n = (int)e;
            if (t.desc.v1.dataType == QNN_DATATYPE_FLOAT_16) {
                scratchF.resize(e);
                for (size_t i = 0; i < e; ++i) scratchF[i] = h2f(t.f16()[i]);
                scores = scratchF.data();
            } else {
                scores = t.f32();
            }
        } else if (t.name == "boxes" && e >= 12) {
            if (t.desc.v1.dataType == QNN_DATATYPE_FLOAT_16) {
                scratchB.resize(e);
                for (size_t i = 0; i < e; ++i) scratchB[i] = h2f(t.f16()[i]);
                boxes = scratchB.data();
            } else {
                boxes = t.f32();
            }
        } else if (t.name == "labels" && e >= 3) {
            labels = (const int32_t*)t.raw.data();
        }
    }
    if (!scores || !boxes || !labels) {
        CERR("missing outputs (s=%p b=%p l=%p)", (const void*)scores, (const void*)boxes,
             (const void*)labels);
        return out;
    }

    // BT postprocess (detector_ctbd.py::_detect L763-789, verified):
    struct Raw { float b[4]; float s; int lab; };
    //  1) conf >= 0.3, split by label: 0=bubble list, 1/2=text list
    //  2) per-category 5px filter
    //  3) per-category merge_duplicate_boxes: IoU>=0.7 connected components
    //     merged into their UNION box (not dropped)
    //  4) per-category region collapse (this port's addition, see below)
    //  5) per-category remove_contained_boxes: same-category containment
    //     (intersection >= 0.8 * inner area) removes the inner box.
    //     BT sorts areas DESCENDING; an ascending scan never drops a
    //     contained box (fixed 2026-10-02: nested 人物紹介 boxes leaked
    //     through and were translated/rendered 2-3x).
    // Text and bubble lists never prune each other; a text box inside a
    // bubble is "text_bubble" and is kept for OCR.
    std::vector<Raw> lists[2];   // [0]=bubbles(label 0), [1]=text(1/2)
    for (int i = 0; i < n; ++i) {
        if (scores[i] < kConfThresh) continue;
        const float* b = boxes + (size_t)i * 4;
        // labels come back as int32 class ids; some HTP builds materialise
        // them as fp32 bit patterns (0x3F800000==1.0f) - normalise both.
        int32_t lab = labels[i];
        if (lab > 8) {
            float f; memcpy(&f, &lab, 4);
            lab = (int32_t)(f + 0.5f);
        }
        if (lab != 0 && lab != 1 && lab != 2) continue;
        lists[lab == 0 ? 0 : 1].push_back({{b[0], b[1], b[2], b[3]}, scores[i], lab});
    }
    for (int c = 0; c < 2; ++c) {
        std::vector<Raw>& v = lists[c];
        // 5px filter (BT filter_bounding_boxes)
        size_t w2 = 0;
        for (auto& r : v) {
            if (r.b[2] - r.b[0] > kMinSidePx && r.b[3] - r.b[1] > kMinSidePx) v[w2++] = r;
        }
        v.resize(w2);
        if (v.empty()) continue;
        const size_t nPre = v.size();
        // merge IoU>=0.7 into union boxes (BT merge_duplicate_boxes)
        {
            const size_t m = v.size();
            std::vector<char> adj(m * m, 0);
            for (size_t i = 0; i < m; ++i)
                for (size_t j = i + 1; j < m; ++j)
                    if (iou(v[i].b, v[j].b) >= kDedupIou) adj[i * m + j] = adj[j * m + i] = 1;
            std::vector<char> vis(m, 0);
            std::vector<Raw> merged;
            for (size_t s = 0; s < m; ++s) {
                if (vis[s]) continue;
                std::vector<size_t> q{s};
                vis[s] = 1;
                Raw u = v[s];
                for (size_t h = 0; h < q.size(); ++h) {
                    for (size_t t = 0; t < m; ++t) {
                        if (!vis[t] && adj[q[h] * m + t]) {
                            vis[t] = 1;
                            q.push_back(t);
                            u.b[0] = fminf(u.b[0], v[t].b[0]);
                            u.b[1] = fminf(u.b[1], v[t].b[1]);
                            u.b[2] = fmaxf(u.b[2], v[t].b[2]);
                            u.b[3] = fmaxf(u.b[3], v[t].b[3]);
                            u.s = fmaxf(u.s, v[t].s);
                        }
                    }
                }
                merged.push_back(u);
            }
            v = std::move(merged);
        }
        const size_t nMerge = v.size();
        // Region collapse (port addition; pure-BT keeps the largest box,
        // which destroys paragraph layout on dense pages). On horizontal
        // text pages the model emits boxes at three scales: single lines,
        // paragraph strips and region boxes spanning several paragraphs.
        // A box containing >= 2 paragraph-level children is a REGION box ->
        // drop it, keep the children (each paragraph then gets its own
        // OCR/translation/render slot, preserving the page's layout).
        // Title merges survive: single-line children sit below the
        // multi-line thresholds and don't count as substantial.
        // Thresholds are page fractions measured in the 640 squash space
        // (x fraction maps 1:1 to 640-space x, same for y; tuned against
        // the 退魔シスターズ 人物紹介 page, see BALLOONTRANSLATOR_WORKFLOW §E).
        {
            constexpr float kKidW = 0.12f * kDet;    // 子框宽 >= 页宽 12%
            constexpr float kKidH = 0.0375f * kDet;  // 子框高 >= 页高 3.75%
            std::vector<char> drop(v.size(), 0);
            for (size_t i = 0; i < v.size(); ++i) {
                int sub = 0;
                for (size_t j = 0; j < v.size() && sub < 2; ++j) {
                    if (i == j || contained(v[j].b, v[i].b) < kContainment) continue;
                    if (v[j].b[2] - v[j].b[0] >= kKidW &&
                        v[j].b[3] - v[j].b[1] >= kKidH) ++sub;
                }
                if (sub >= 2) drop[i] = 1;
            }
            size_t w2 = 0;
            for (size_t i = 0; i < v.size(); ++i) if (!drop[i]) v[w2++] = v[i];
            if (w2 != v.size())
                CLOG("region collapse: %zu -> %zu boxes", v.size(), w2);
            v.resize(w2);
        }
        const size_t nRegion = v.size();
        // same-category containment >= 0.8 removes the inner box (BT
        // remove_contained_boxes: area-DESCENDING keep scan)
        {
            std::vector<size_t> order(v.size());
            for (size_t i = 0; i < v.size(); ++i) order[i] = i;
            std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
                const float aa = (v[a].b[2] - v[a].b[0]) * (v[a].b[3] - v[a].b[1]);
                const float ab = (v[b].b[2] - v[b].b[0]) * (v[b].b[3] - v[b].b[1]);
                return aa > ab;
            });
            std::vector<Raw> kept;
            for (size_t idx : order) {
                bool inside = false;
                for (const auto& k : kept) {
                    if (contained(v[idx].b, k.b) >= kContainment) { inside = true; break; }
                }
                if (!inside) kept.push_back(v[idx]);
            }
            v = std::move(kept);
        }
        CLOG("cat%d: 5px %zu -> merge %zu -> region %zu -> final %zu",
             c, nPre, nMerge, nRegion, v.size());
    }
    const float sx = (float)w / kDet, sy = (float)h / kDet;
    for (int c = 0; c < 2; ++c) {
        for (const auto& r : lists[c]) {
            CtbdBox c2;
            c2.x0 = r.b[0] * sx; c2.y0 = r.b[1] * sy;
            c2.x1 = r.b[2] * sx; c2.y1 = r.b[3] * sy;
            c2.score = r.s;
            c2.label = r.lab;
            c2.bubble = (r.lab == 0);
            c2.vertical = (c2.y1 - c2.y0) > (c2.x1 - c2.x0) * 3.f / 2.f;
            out.push_back(c2);
        }
    }
    post_ms_ = (float)(now_ms() - t1) - fwd_ms_;
    CLOG("detect: %d raw -> %d text + %d bubble (%.0fms fwd)", n,
         (int)lists[1].size(), (int)lists[0].size(), fwd_ms_);
    return out;
}

}  // namespace cs::ocr
