// QNN/HTP implementation of the manga OCR engine. See qnn_ocr.h for the
// contract; this file owns the QNN runtime, the two graphs and the
// preprocessing/decoding around them.
#include "qnn_ocr.h"

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

#define QLOG(...) __android_log_print(ANDROID_LOG_INFO, "QnnOcr", __VA_ARGS__)
#define QERR(...) __android_log_print(ANDROID_LOG_ERROR, "QnnOcr", __VA_ARGS__)

namespace cs::ocr {
namespace {

std::string proc_stat(const char* key) {
    std::ifstream f("/proc/self/status");
    std::string line;
    while (std::getline(f, line)) {
        if (line.rfind(key, 0) == 0) return line;
    }
    return "?";
}

double now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

std::string read_file(const std::string& path, bool* ok = nullptr) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        if (ok) *ok = false;
        return {};
    }
    std::ostringstream ss;
    ss << f.rdbuf();
    if (ok) *ok = true;
    return ss.str();
}

inline float half_to_float(uint16_t h) {
    const uint32_t sign = (uint32_t)(h & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1fu;
    uint32_t man = h & 0x3ffu;
    uint32_t bits;
    if (exp == 0) {
        if (man == 0) {
            bits = sign;
        } else {  // subnormal
            exp = 127 - 15 + 1;
            while ((man & 0x400u) == 0) {
                man <<= 1;
                --exp;
            }
            man &= 0x3ffu;
            bits = sign | (exp << 23) | (man << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000u | (man << 13);
    } else {
        bits = sign | ((exp - 15 + 127) << 23) | (man << 13);
    }
    float out;
    std::memcpy(&out, &bits, sizeof(out));
    return out;
}

inline uint16_t float_to_half(float v) {
    uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    const uint32_t sign = (bits >> 16) & 0x8000u;
    const int32_t exp = (int32_t)((bits >> 23) & 0xffu) - 127 + 15;
    const uint32_t man = bits & 0x7fffffu;
    if (exp <= 0) return (uint16_t)sign;
    if (exp >= 31) return (uint16_t)(sign | 0x7c00u);
    return (uint16_t)(sign | ((uint32_t)exp << 10) | (man >> 13));
}

// Bilinear resize of an interleaved RGBA8 buffer into planar float CHW with
// per-channel affine applied: out = v * mul[c] + add[c].
void resize_rgba_to_chw(const uint8_t* rgba, int sw, int sh, float* dst, int dw, int dh,
                        const float* mul, const float* add) {
    const float fx = (float)sw / (float)dw;
    const float fy = (float)sh / (float)dh;
    const int plane = dw * dh;
    for (int y = 0; y < dh; ++y) {
        float sy = (y + 0.5f) * fy - 0.5f;
        if (sy < 0) sy = 0;
        int y0 = (int)sy;
        if (y0 > sh - 1) y0 = sh - 1;
        const int y1 = y0 + 1 < sh ? y0 + 1 : y0;
        const float wy = sy - (float)y0;
        for (int x = 0; x < dw; ++x) {
            float sx = (x + 0.5f) * fx - 0.5f;
            if (sx < 0) sx = 0;
            int x0 = (int)sx;
            if (x0 > sw - 1) x0 = sw - 1;
            const int x1 = x0 + 1 < sw ? x0 + 1 : x0;
            const float wx = sx - (float)x0;
            const uint8_t* p00 = rgba + ((size_t)y0 * sw + x0) * 4;
            const uint8_t* p01 = rgba + ((size_t)y0 * sw + x1) * 4;
            const uint8_t* p10 = rgba + ((size_t)y1 * sw + x0) * 4;
            const uint8_t* p11 = rgba + ((size_t)y1 * sw + x1) * 4;
            const float w00 = (1 - wx) * (1 - wy), w01 = wx * (1 - wy);
            const float w10 = (1 - wx) * wy, w11 = wx * wy;
            const size_t o = (size_t)y * dw + x;
            for (int c = 0; c < 3; ++c) {
                const float v = p00[c] * w00 + p01[c] * w01 + p10[c] * w10 + p11[c] * w11;
                dst[(size_t)c * plane + o] = v * mul[c] + add[c];
            }
        }
    }
}

// ------------------------------------------------------------ QNN interface

using GetProvidersFn = Qnn_ErrorHandle_t (*)(const QnnInterface_t***, uint32_t*);

// rpcmem lives in the vendor fastrpc library: buffers allocated through it are
// visible to the DSP, which the HTP backend requires for graph I/O.
struct Rpcmem {
    void* (*alloc)(int heapid, uint32_t flags, int size) = nullptr;
    int (*to_fd)(void* p) = nullptr;
    void (*release)(void* p) = nullptr;
    bool ok() const { return alloc && to_fd && release; }
};

Rpcmem& rpcmem() {
    static Rpcmem r = [] {
        Rpcmem x;
        void* h = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
        if (!h) h = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
        if (!h) return x;
        x.alloc = (decltype(x.alloc))dlsym(h, "rpcmem_alloc");
        x.to_fd = (decltype(x.to_fd))dlsym(h, "rpcmem_to_fd");
        x.release = (decltype(x.release))dlsym(h, "rpcmem_free");
        if (!x.ok()) QERR("rpcmem symbols missing: %s", dlerror());
        return x;
    }();
    return r;
}

constexpr int kRpcHeapSystem = 25;   // RPCMEM_HEAP_ID_SYSTEM
constexpr uint32_t kRpcFlagsDefault = 1;

struct QnnRt {
    void* handle = nullptr;
    const QnnInterface_t* itf = nullptr;
    Qnn_LogHandle_t log = nullptr;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    bool ok = false;

    const QNN_INTERFACE_VER_TYPE& api() const { return itf->QNN_INTERFACE_VER_NAME; }
};

void qnn_log(const char* fmt, QnnLog_Level_t lvl, uint64_t, va_list ap) {
    char buf[1024];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    if (lvl <= QNN_LOG_LEVEL_ERROR)
        __android_log_print(ANDROID_LOG_ERROR, "QnnOcrRt", "%s", buf);
    else if (lvl <= QNN_LOG_LEVEL_WARN)
        __android_log_print(ANDROID_LOG_WARN, "QnnOcrRt", "%s", buf);
    else
        __android_log_print(ANDROID_LOG_INFO, "QnnOcrRt", "%s", buf);
}

bool runtime_start(QnnRt& rt, const std::string& skelDir, std::string* err) {
    // The skel shipped in the DSP firmware partition is not necessarily the
    // build that matches the host-side QNN we bundle, so point the loader at
    // our own copy first.
    setenv("ADSP_LIBRARY_PATH", skelDir.c_str(), 1);

    // The HTP stub links against the vendor fastrpc library. It is listed in
    // /vendor/etc/public.libraries.txt, but the classloader namespace a JNI
    // library is loaded into does not search /vendor, so pre-load it here and
    // let the linker resolve the stub's DT_NEEDED against the loaded copy.
    void* rpc = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!rpc) rpc = dlopen("/vendor/lib64/libcdsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!rpc) rpc = dlopen("/vendor/lib64/libadsprpc.so", RTLD_NOW | RTLD_GLOBAL);
    if (!rpc) QERR("preload fastrpc failed: %s", dlerror());

    rt.handle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
    if (!rt.handle) {
        *err = std::string("dlopen libQnnHtp.so: ") + dlerror();
        return false;
    }
    auto getProviders = (GetProvidersFn)dlsym(rt.handle, "QnnInterface_getProviders");
    if (!getProviders) {
        *err = "QnnInterface_getProviders missing";
        return false;
    }
    const QnnInterface_t** providers = nullptr;
    uint32_t count = 0;
    if (getProviders(&providers, &count) != QNN_SUCCESS || count == 0) {
        *err = "no QNN providers";
        return false;
    }
    rt.itf = providers[0];
    auto& api = rt.api();

    if (api.logCreate && api.logCreate(qnn_log, QNN_LOG_LEVEL_DEBUG, &rt.log) != QNN_SUCCESS)
        rt.log = nullptr;
    Qnn_ErrorHandle_t e = api.backendCreate(rt.log, nullptr, &rt.backend);
    if (e != QNN_SUCCESS) {
        char m[64];
        snprintf(m, sizeof(m), "backendCreate failed (0x%llx)", (unsigned long long)e);
        *err = m;
        return false;
    }
    // Full HTP performance needs a signed process domain; the default is an
    // unsigned PD, which the DSP schedules without the acceleration resources.
    QnnHtpDevice_CustomConfig_t htpCfg{};
    htpCfg.option = QNN_HTP_DEVICE_CONFIG_OPTION_SIGNEDPD;
    htpCfg.useSignedProcessDomain.useSignedProcessDomain = true;
    QnnDevice_Config_t devCfg{};
    devCfg.option = QNN_DEVICE_CONFIG_OPTION_CUSTOM;
    devCfg.customConfig = &htpCfg;
    const QnnDevice_Config_t* devCfgs[] = {&devCfg, nullptr};

    e = api.deviceCreate(rt.log, devCfgs, &rt.device);
    if (e != QNN_SUCCESS) {
        QERR("signed-PD deviceCreate failed (0x%llx); retrying unsigned",
             (unsigned long long)e);
        e = api.deviceCreate(rt.log, nullptr, &rt.device);
    } else {
        QLOG("device created with signed process domain");
    }
    if (e != QNN_SUCCESS) {
        char m[64];
        snprintf(m, sizeof(m), "deviceCreate failed (0x%llx)", (unsigned long long)e);
        *err = m;
        return false;
    }
    rt.ok = true;
    return true;
}

// ------------------------------------------------------------- one QNN graph

struct Graph {
    std::string name;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    Qnn_Tensor_t input{};
    Qnn_Tensor_t output{};
    uint32_t inId = 1;
    uint32_t outId = 0;
    void* inMem = nullptr;   // rpcmem buffers registered with the backend
    void* outMem = nullptr;
    Qnn_MemHandle_t inHandle = nullptr;
    Qnn_MemHandle_t outHandle = nullptr;
    bool shared = false;
    bool fullShared = true;
    int style = 0;  // which tensor construction the backend accepted
    std::vector<uint32_t> inDims, outDims;
    std::vector<float> inBuf, outBuf;
    bool ok = false;

    size_t inCount() const {
        size_t n = 1;
        for (auto d : inDims) n *= d;
        return n;
    }
    size_t outCount() const {
        size_t n = 1;
        for (auto d : outDims) n *= d;
        return n;
    }
};

// One context can host several graphs; keeping both models in a single
// context binary matters because the on-device HTP only supports one DSP
// session per process.
bool context_load(const QnnRt& rt, std::string& blob, Qnn_ContextHandle_t* out,
                  std::string* err) {
    if (blob.empty()) {
        *err = "empty context binary";
        return false;
    }
    if (rt.api().contextCreateFromBinary(rt.backend, rt.device, nullptr, blob.data(), blob.size(),
                                         out, nullptr) != QNN_SUCCESS) {
        *err = "contextCreateFromBinary failed";
        return false;
    }
    return true;
}

bool graph_bind(const QnnRt& rt, Qnn_ContextHandle_t context, const std::string& graphName,
                const uint32_t* inDims, uint32_t inRank, uint32_t inId, const uint32_t* outDims,
                uint32_t outRank, uint32_t outId, Graph& g, std::string* err) {
    g.name = graphName;
    g.context = context;
    if (rt.api().graphRetrieve(context, graphName.c_str(), &g.graph) != QNN_SUCCESS) {
        *err = "graphRetrieve failed: " + graphName;
        return false;
    }
    QLOG("graph %s retrieved (h=%p)", graphName.c_str(), (void*)g.graph);
    g.inDims.assign(inDims, inDims + inRank);
    g.outDims.assign(outDims, outDims + outRank);
    g.inId = inId;
    g.outId = outId;
    QLOG("graph %s dims ok (in=%zu out=%zu elems) vmsize=%s", graphName.c_str(), g.inCount(),
         g.outCount(), proc_stat("VmSize").c_str());
    g.inBuf.assign(g.inCount(), 0);
    QLOG("graph %s inBuf ok %s", graphName.c_str(), proc_stat("VmRSS").c_str());
    g.outBuf.assign(g.outCount(), 0);
    QLOG("graph %s outBuf ok %s", graphName.c_str(), proc_stat("VmRSS").c_str());

    auto make = [&](Qnn_Tensor_t& t, const char* nm, uint32_t id, Qnn_TensorType_t type,
                    std::vector<uint32_t>& dims, std::vector<float>& buf) {
        t = Qnn_Tensor_t{};
        t.version = QNN_TENSOR_VERSION_1;
        t.v1.id = id;
        t.v1.name = (char*)nm;
        t.v1.type = type;
        t.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
        t.v1.dataType = QNN_DATATYPE_FLOAT_32;
        t.v1.rank = (uint32_t)dims.size();
        t.v1.dimensions = dims.data();
        t.v1.memType = QNN_TENSORMEMTYPE_RAW;
        t.v1.clientBuf.data = buf.data();
        t.v1.clientBuf.dataSize = (uint32_t)(buf.size() * 4);
    };

    // The graph comes from a context binary, so its I/O tensors already live
    // in the backend and must not be re-created (the on-device HTP backend
    // does not support graph composition). QnnGraph_execute matches them by
    // id/name, so describing them here is enough.
    make(g.input, "x", inId, QNN_TENSOR_TYPE_APP_WRITE, g.inDims, g.inBuf);
    make(g.output, "output_0", outId, QNN_TENSOR_TYPE_APP_READ, g.outDims, g.outBuf);
    // The graphs in a context binary own their I/O memory, so the client hands
    // the backend DSP-visible buffers instead of clientBuf: allocate through
    // rpcmem, register them with the context, and mark the tensors MEMHANDLE.
    Rpcmem& rpc = rpcmem();
    if (rpc.ok()) {
        const size_t inBytes = g.inBuf.size() * 4;
        const size_t outBytes = g.outBuf.size() * 4;
        g.inMem = rpc.alloc(kRpcHeapSystem, kRpcFlagsDefault, (int)inBytes);
        g.outMem = rpc.alloc(kRpcHeapSystem, kRpcFlagsDefault, (int)outBytes);
        if (g.inMem && g.outMem) {
            Qnn_MemDescriptor_t d{};
            d.dataType = QNN_DATATYPE_FLOAT_32;
            d.memType = QNN_MEM_TYPE_ION;

            d.memShape.numDim = (uint32_t)g.inDims.size();
            d.memShape.dimSize = g.inDims.data();
            d.memShape.shapeConfig = nullptr;
            d.ionInfo.fd = rpc.to_fd(g.inMem);
            Qnn_ErrorHandle_t e1 = rt.api().memRegister(context, &d, 1, &g.inHandle);

            d.memShape.numDim = (uint32_t)g.outDims.size();
            d.memShape.dimSize = g.outDims.data();
            d.ionInfo.fd = rpc.to_fd(g.outMem);
            Qnn_ErrorHandle_t e2 = rt.api().memRegister(context, &d, 1, &g.outHandle);

            if (e1 == QNN_SUCCESS && e2 == QNN_SUCCESS) {
                // Output via the registered buffer (the DSP writes into it);
                // the input goes through clientBuf so the backend performs the
                // copy - handing the DSP a pointer it cannot see leaves the
                // graph reading zeros.
                g.output.v1.memType = QNN_TENSORMEMTYPE_MEMHANDLE;
                g.output.v1.memHandle = g.outHandle;
                g.input.v1.memType = QNN_TENSORMEMTYPE_RAW;
                g.input.v1.clientBuf.data = g.inBuf.data();
                g.input.v1.clientBuf.dataSize = (uint32_t)(g.inBuf.size() * 4);
                g.shared = true;
                g.fullShared = false;
            } else {
                QERR("memRegister %s failed: in=0x%llx out=0x%llx", graphName.c_str(),
                     (unsigned long long)e1, (unsigned long long)e2);
            }
        } else {
            QERR("rpcmem_alloc %s failed (%zu/%zu bytes)", graphName.c_str(), inBytes, outBytes);
        }
    }
    QLOG("graph %s bound: in id=%u out id=%u shared=%d in=%p out=%p", graphName.c_str(), inId,
         outId, (int)g.shared, g.inMem, g.outMem);
    g.ok = true;
    return true;
}

bool graph_run(const QnnRt& rt, Graph& g) {
    if (g.shared && g.fullShared && g.inMem)
        std::memcpy(g.inMem, g.inBuf.data(), g.inBuf.size() * 4);
    // 实验：把大输入的 clientBuf.dataSize 压小，用于标定 QNN 输入拷贝的真实带宽
    // （标志文件与 QnnVl 共用；不设则完全无副作用）。
    const size_t cap = []() -> size_t {
        static const size_t v = []() -> size_t {
            if (const char* e = getenv("QVL_KV_TINY")) {
                long x = atol(e);
                return x > 0 ? (size_t)x : (size_t)0;
            }
            FILE* f = fopen("/sdcard/ComicShelfModels/vlmodel/kv_tiny.txt", "r");
            if (!f) return (size_t)0;
            long x = 0;
            int n = fscanf(f, "%ld", &x);
            fclose(f);
            return (n == 1 && x > 0) ? (size_t)x : (size_t)0;
        }();
        return v;
    }();
    const uint32_t savedIn = g.input.v1.clientBuf.dataSize;
    // Try the tensor descriptions recorded in the context binary first, then a
    // couple of variants: HTP builds differ in whether they key on id or name
    // and in the data format they expect.
    static const Qnn_TensorDataFormat_t fmts[3] = {QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER,
                                                   QNN_TENSOR_DATA_FORMAT_DENSE,
                                                   QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER};
    const uint32_t ids[3][2] = {{g.inId, g.outId}, {g.inId, g.outId}, {0, 0}};
    bool shrunk = false;
    for (int a = 0; a < 3; ++a) {
        g.input.v1.id = ids[a][0];
        g.output.v1.id = ids[a][1];
        g.input.v1.dataFormat = fmts[a];
        g.output.v1.dataFormat = fmts[a];
        if (cap && savedIn > (1u << 20) && g.input.v1.memType == QNN_TENSORMEMTYPE_RAW) {
            g.input.v1.clientBuf.dataSize = (uint32_t)cap;
            shrunk = true;
        }
        const Qnn_ErrorHandle_t e =
            rt.api().graphExecute(g.graph, &g.input, 1, &g.output, 1, nullptr, nullptr);
        g.input.v1.clientBuf.dataSize = savedIn;
        if (shrunk) {
            static bool announced = false;
            if (!announced) {
                announced = true;
                QLOG("KV_TINY 实验(det): inSize %u → %zu", savedIn, cap);
            }
        }
        if (e == QNN_SUCCESS) {
            g.style = a;
            if (g.shared && g.outMem)
                std::memcpy(g.outBuf.data(), g.outMem, g.outBuf.size() * 4);
            return true;
        }
        QLOG("execute %s style=%d (fmt=%d ids=%u/%u) -> 0x%llx", g.name.c_str(), a, (int)fmts[a],
             ids[a][0], ids[a][1], (unsigned long long)e);
    }
    return false;
}

// ------------------------------------------------------- det postprocessing

struct Blob {
    int x0, y0, x1, y1;
    float score;
    int area;
};

// 4-connected flood fill over the binarized probability map.
void collect_blobs(const float* prob, int w, int h, float thresh, std::vector<Blob>& out) {
    std::vector<uint8_t> seen((size_t)w * h, 0);
    std::vector<int> stack;
    stack.reserve(4096);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t idx = (size_t)y * w + x;
            if (seen[idx] || prob[idx] < thresh) continue;
            stack.clear();
            stack.push_back((int)idx);
            seen[idx] = 1;
            int x0 = x, x1 = x, y0 = y, y1 = y, area = 0;
            double sum = 0;
            while (!stack.empty()) {
                const int cur = stack.back();
                stack.pop_back();
                const int cy = cur / w, cx = cur - cy * w;
                sum += prob[cur];
                ++area;
                if (cx < x0) x0 = cx;
                if (cx > x1) x1 = cx;
                if (cy < y0) y0 = cy;
                if (cy > y1) y1 = cy;
                const int nb[4][2] = {{cx - 1, cy}, {cx + 1, cy}, {cx, cy - 1}, {cx, cy + 1}};
                for (const auto& n : nb) {
                    if (n[0] < 0 || n[0] >= w || n[1] < 0 || n[1] >= h) continue;
                    const size_t ni = (size_t)n[1] * w + n[0];
                    if (seen[ni] || prob[ni] < thresh) continue;
                    seen[ni] = 1;
                    stack.push_back((int)ni);
                }
            }
            Blob b;
            b.x0 = x0;
            b.y0 = y0;
            b.x1 = x1;
            b.y1 = y1;
            b.area = area;
            b.score = area > 0 ? (float)(sum / area) : 0.f;
            out.push_back(b);
        }
    }
}

}  // namespace

// ------------------------------------------------------------------ engine

struct QnnOcr::Impl {
    QnnRt rt;
    std::string ctxBlob;   // kept alive: the backend may reference it
    Graph det, rec;
    std::vector<float> probF;   // det probability map, dequantized
    std::vector<float> stage;   // fp32 staging for preprocessing
    std::vector<std::string> dict;
    bool detOk = false, recOk = false;

    bool loadDict(const std::string& path) {
        std::ifstream f(path, std::ios::binary);
        if (!f) return false;
        std::string line;
        while (std::getline(f, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            dict.push_back(line);
        }
        return !dict.empty();
    }

};

QnnOcr::QnnOcr() = default;
QnnOcr::~QnnOcr() { delete impl_; }

bool QnnOcr::init(const std::string& modelsDir, const std::string& dataDir) {
    impl_ = new Impl();
    err_.clear();

    // Stage the DSP skel where ADSP_LIBRARY_PATH will point.
    // The skel ships alongside the context binary in the model assets, which
    // the app already staged; point the DSP loader straight at that directory.
    const std::string skelDir = modelsDir;
    if (::access((skelDir + "/libQnnHtpV73Skel.so").c_str(), R_OK) != 0) {
        err_ = "no V73 skel in " + skelDir;
        QERR("%s", err_.c_str());
        return false;
    }
    QLOG("skel dir: %s", skelDir.c_str());

    if (!runtime_start(impl_->rt, skelDir, &err_)) {
        QERR("runtime: %s", err_.c_str());
        return false;
    }

    static const uint32_t detIn[] = {1, 3, 960, 960};
    static const uint32_t detOut[] = {1, 1, 960, 960};
    static const uint32_t recIn[] = {1, 3, 48, 320};
    static const uint32_t recOut[] = {1, 40, 18385};

    Qnn_ContextHandle_t ctx = nullptr;
    impl_->ctxBlob = read_file(modelsDir + "/ocr_ctx.bin");
    if (!context_load(impl_->rt, impl_->ctxBlob, &ctx, &err_)) {
        QERR("context: %s", err_.c_str());
        return false;
    }
    // The two graphs were compiled together on the target HTP; tensor ids come
    // from that binary (detect/recognize are both fp32 I/O).
    impl_->detOk =
        graph_bind(impl_->rt, ctx, "graph_tw2m5soj", detIn, 4, 516, detOut, 4, 1114, impl_->det,
               &err_);
    if (!impl_->detOk) QERR("det: %s", err_.c_str());
    impl_->recOk =
        graph_bind(impl_->rt, ctx, "graph_cw3jq54q", recIn, 4, 1, recOut, 3, 515, impl_->rec,
               &err_);
    if (!impl_->recOk) QERR("rec: %s", err_.c_str());

    if (!impl_->loadDict(modelsDir + "/ppocrv5_dict.txt")) {
        QERR("dictionary missing in %s", modelsDir.c_str());
        return false;
    }

    impl_->probF.resize(960u * 960u);
    impl_->stage.resize(3u * 960u * 960u);
    ready_ = impl_->detOk && impl_->recOk;
    QLOG("init: det=%d rec=%d dict=%zu skel=bundled", (int)impl_->detOk, (int)impl_->recOk,
         impl_->dict.size());
    return ready_;
}

std::vector<DetBox> QnnOcr::detect(const uint8_t* rgba, int w, int h) {
    std::vector<DetBox> boxes;
    if (!ready_ || w <= 0 || h <= 0) return boxes;
    const double t0 = now_ms();

    // PP-OCR det preprocessing: RGB, ImageNet normalization, 960x960.
    static const float mul[3] = {(1.f / 255.f) / 0.229f, (1.f / 255.f) / 0.224f,
                                 (1.f / 255.f) / 0.225f};
    static const float add[3] = {-0.485f / 0.229f, -0.456f / 0.224f, -0.406f / 0.225f};
    resize_rgba_to_chw(rgba, w, h, impl_->det.inBuf.data(), 960, 960, mul, add);
    t_.pre_ms = now_ms() - t0;

    const double t1 = now_ms();
    if (!graph_run(impl_->rt, impl_->det)) {
        QERR("det execute failed");
        return boxes;
    }
    t_.fwd_ms = now_ms() - t1;

    const double t2 = now_ms();
    std::vector<Blob> blobs;
    collect_blobs(impl_->det.outBuf.data(), 960, 960, 0.3f, blobs);

    const float sx = (float)w / 960.f, sy = (float)h / 960.f;
    int kept = 0;
    for (const auto& b : blobs) {
        if (b.area < 12) continue;
        if (b.score < 0.5f) continue;
        const float bw = (float)(b.x1 - b.x0 + 1), bh = (float)(b.y1 - b.y0 + 1);
        if (bw < 3 && bh < 3) continue;
        const float pad = std::max(1.5f, std::min(bw, bh) * 0.12f);
        DetBox d;
        d.x0 = std::max(0.f, (b.x0 - pad) * sx);
        d.y0 = std::max(0.f, (b.y0 - pad) * sy);
        d.x1 = std::min((float)w, (b.x1 + 1 + pad) * sx);
        d.y1 = std::min((float)h, (b.y1 + 1 + pad) * sy);
        d.vertical = (b.y1 - b.y0) > (b.x1 - b.x0) * 3 / 2;
        d.score = b.score;
        boxes.push_back(d);
        ++kept;
    }
    t_.post_ms = now_ms() - t2;
    t_.lines = kept;
    t_.total_ms = now_ms() - t0;
    return boxes;
}

std::string QnnOcr::recognize(const uint8_t* rgba, int w, int h) {
    if (!ready_ || w <= 0 || h <= 0) return {};
    const double t0 = now_ms();

    // Vertical CJK columns are rotated so the model reads left-to-right text.
    std::vector<uint8_t> rotated;
    const uint8_t* src = rgba;
    int sw = w, sh = h;
    if (h > w * 3 / 2) {
        rotated.assign((size_t)w * h * 4, 0);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                // counter-clockwise: (x,y) -> (y, w-1-x); the column's top lands
                // on the left so the text reads left-to-right
                uint8_t* d = &rotated[((size_t)(w - 1 - x) * h + y) * 4];
                const uint8_t* p = rgba + ((size_t)y * w + x) * 4;
                d[0] = p[0];
                d[1] = p[1];
                d[2] = p[2];
                d[3] = 255;
            }
        }
        src = rotated.data();
        sw = h;
        sh = w;
    }

    // A long column does not fit the recogniser's aspect, so it is cut into
    // pieces of at most 320/48 times its height.
    const int maxPieceW = std::max(1, 320 * sh / 48);
    std::vector<uint8_t> piece;
    std::string text;
    for (int x0 = 0; x0 < sw; x0 += maxPieceW) {
        const int pw = std::min(maxPieceW, sw - x0);
        if (pw < 4) break;
        piece.assign((size_t)pw * sh * 4, 0);
        for (int y = 0; y < sh; ++y)
            std::memcpy(&piece[(size_t)y * pw * 4], src + ((size_t)y * sw + x0) * 4,
                        (size_t)pw * 4);
        text += recognize_piece_(piece.data(), pw, sh);
    }
    t_.rec_ms += now_ms() - t0;
    ++t_.rec_lines;
    return text;
}

std::string QnnOcr::recognize_piece_(const uint8_t* rgba, int pw, int sh) {
    if (!ready_ || pw <= 0 || sh <= 0) return {};
    // fit into 320x48 keeping the aspect ratio (PaddleOCR recipe)
    int rw = 320, rh = 48;
    const float ratio = (float)pw / (float)sh;
    if (48.f * ratio <= 320.f)
        rw = std::max(1, (int)std::lround(48.f * ratio));
    else
        rh = std::max(1, (int)std::lround(320.f / ratio));

    static const float mulp[3] = {2.f / 255.f, 2.f / 255.f, 2.f / 255.f};
    static const float addp[3] = {-1.f, -1.f, -1.f};
    impl_->stage.resize(std::max<size_t>(impl_->stage.size(), (size_t)3 * rw * rh));
    std::vector<float>& tmp = impl_->stage;
    resize_rgba_to_chw(rgba, pw, sh, tmp.data(), rw, rh, mulp, addp);

    auto& buf = impl_->rec.inBuf;
    std::fill(buf.begin(), buf.end(), -1.0f);  // pad with black
    const size_t plane = 320u * 48u;
    for (int c = 0; c < 3; ++c) {
        const float* p = tmp.data() + (size_t)c * rw * rh;
        for (int y = 0; y < rh; ++y)
            for (int x = 0; x < rw; ++x)
                buf[(size_t)c * plane + (size_t)y * 320 + x] = p[(size_t)y * rw + x];
    }

    if (!graph_run(impl_->rt, impl_->rec)) {
        QERR("rec execute failed");
        return {};
    }

    // greedy CTC decode over [40,18385]; class 0 is blank
    std::string out;
    int prev = -1;
    const size_t classes = 18385;
    for (int t = 0; t < 40; ++t) {
        const float* row = &impl_->rec.outBuf[(size_t)t * classes];
        int best = 0;
        float bestV = -1e30f;
        for (size_t c = 0; c < classes; ++c) {
            const float v = row[c];
            if (v > bestV) {
                bestV = v;
                best = (int)c;
            }
        }
        if (best != prev && best > 0) {
            const int di = best - 1;
            if (di < (int)impl_->dict.size())
                out += impl_->dict[di];
            else if (di == (int)impl_->dict.size())
                out += ' ';
        }
        prev = best;
    }
    return out;
}

}  // namespace cs::ocr
