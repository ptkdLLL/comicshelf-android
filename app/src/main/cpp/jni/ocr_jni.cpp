// JNI surface for the on-device translation stack:
//   detector + recognizer (NPU via QNN/HTP, ncnn/Vulkan as fallback)
//   + LLM text translation.
#include "ocr/ctbd_qnn.h"
#include "ocr/ctd_det.h"
#include "ocr/paddle_vl.h"
#include "ocr/qnn_ocr.h"
#include "ocr/qnn_vl.h"
#include "translate/http_client.h"
#include "util/json_min.h"
#include "util/logger.h"

#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

using namespace cs;

namespace {

std::mutex g_ocr_mtx; // one inference pipeline instance
ocr::ComicTextDetector g_ctd;
ocr::PaddleOcrVl g_vl;
ocr::QnnOcr g_qnn;
ocr::QnnVl g_qvl;
ocr::QnnCtbd g_ctbd;
bool g_ctd_ready = false;
bool g_vl_ready = false;
bool g_qnn_ready = false;
bool g_qvl_ready = false;
bool g_ctbd_ready = false;
std::string g_models_dir;

std::string to_std(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out(p ? p : "");
    env->ReleaseStringUTFChars(s, p);
    return out;
}

jstring from_std(JNIEnv* env, const std::string& s) {
    // ASCII-safe path is fine for box payloads; OCR text goes through byte[].
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrInit(JNIEnv* env, jobject,
                                                        jstring dir, jboolean use_gpu) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    g_models_dir = to_std(env, dir);
    const bool gpu = use_gpu == JNI_TRUE;
    g_ctd_ready = g_ctd.init(g_models_dir, gpu);
    g_vl_ready = g_vl.init(g_models_dir, gpu);
    log_info(std::string("ocr init: ctd=") + (g_ctd_ready ? "ok" : "FAIL") +
             " vl=" + (g_vl_ready ? "ok" : "FAIL") + " gpu=" + (gpu ? "1" : "0"));
    return (g_ctd_ready || g_vl_ready) ? JNI_TRUE : JNI_FALSE;
}

// Detector-only init (ncnn comic-text-detector), used alongside the NPU
// recognizer so the big VL graph is never loaded.
JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrInitCtd(JNIEnv* env, jobject, jstring dir,
                                                           jboolean use_gpu) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    g_ctd_ready = g_ctd.init(to_std(env, dir), use_gpu == JNI_TRUE);
    log_info(std::string("ocr ctd init: ") + (g_ctd_ready ? "ok" : "FAIL"));
    return g_ctd_ready ? JNI_TRUE : JNI_FALSE;
}

// NPU path: QNN/HTP context binaries extracted from assets by the app.
// modelsDir holds det_ctx.bin / rec_ctx.bin / ppocrv5_dict.txt; dataDir is
// app-private scratch (the DSP skel is staged there).
JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrInitQnn(JNIEnv* env, jobject,
                                                           jstring models_dir, jstring data_dir) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    g_qnn_ready = g_qnn.init(to_std(env, models_dir), to_std(env, data_dir));
    log_info(std::string("ocr qnn init: ") + (g_qnn_ready ? "ok" : "FAIL") + " " +
             g_qnn.last_error());
    return g_qnn_ready ? JNI_TRUE : JNI_FALSE;
}

// NPU VL: PaddleOCR-VL-For-Manga 四图上下文（vl_ctx.bin，与 skel 同目录）。
// modelsDir 需含 vl_ctx.bin / vl_embed_f16.bin / vl_vocab.tsv /
// vl_prompt_ids.i64 / vl_mrope.i32 / libQnnHtpV73Skel.so。
JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrInitQnnVl(JNIEnv* env, jobject,
                                                             jstring models_dir) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    std::string err;
    g_qvl_ready = g_qvl.init(to_std(env, models_dir), &err);
    log_info(std::string("ocr qnn-vl init: ") + (g_qvl_ready ? "ok" : "FAIL") +
             (g_qvl_ready ? "" : " " + err));
    return g_qvl_ready ? JNI_TRUE : JNI_FALSE;
}

// JSON: [{x0,y0,x1,y1,vertical,score}] + {fwdMs, postMs, lines, blocks}
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrDetect(JNIEnv* env, jobject,
                                                          jintArray rgba, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    jint* px = env->GetIntArrayElements(rgba, nullptr);
    if (g_qnn_ready) {
        auto boxes = g_qnn.detect((const uint8_t*)px, w, h);
        env->ReleaseIntArrayElements(rgba, px, JNI_ABORT);
        json::Value arr = json::Value::make_array();
        for (const auto& b : boxes) {
            json::Value o = json::Value::make_object();
            o.set("x0", (int64_t)b.x0);
            o.set("y0", (int64_t)b.y0);
            o.set("x1", (int64_t)b.x1);
            o.set("y1", (int64_t)b.y1);
            o.set("vertical", b.vertical);
            o.set("score", b.score);
            arr.push_back(o);
        }
        json::Value meta = json::Value::make_object();
        meta.set("boxes", arr);
        const auto& t = g_qnn.last_timings();
        meta.set("fwdMs", t.fwd_ms);
        meta.set("postMs", t.post_ms);
        meta.set("lines", (int64_t)t.lines);
        meta.set("blocks", (int64_t)t.lines);
        meta.set("backend", std::string("qnn-htp"));
        return from_std(env, meta.dump());
    }
    if (!g_ctd_ready) {
        env->ReleaseIntArrayElements(rgba, px, JNI_ABORT);
        return env->NewStringUTF("{\"error\":\"ctd not ready\"}");
    }
    auto boxes = g_ctd.detect((const uint8_t*)px, w, h);
    env->ReleaseIntArrayElements(rgba, px, JNI_ABORT);

    json::Value arr = json::Value::make_array();
    for (const auto& b : boxes) {
        json::Value o = json::Value::make_object();
        o.set("x0", (int64_t)b.x0);
        o.set("y0", (int64_t)b.y0);
        o.set("x1", (int64_t)b.x1);
        o.set("y1", (int64_t)b.y1);
        o.set("vertical", b.vertical);
        o.set("score", b.score);
        arr.push_back(o);
    }
    json::Value meta = json::Value::make_object();
    meta.set("boxes", arr);
    const auto& t = g_ctd.last_timings();
    meta.set("fwdMs", t.forward_ms);
    meta.set("postMs", t.post_ms);
    meta.set("lines", (int64_t)t.lines);
    meta.set("blocks", (int64_t)t.blocks);
    return from_std(env, meta.dump());
}

// CTBD 检测器（BalloonTranslator 同款 RT-DETR-V2）：dir 需含
// ctbd_ctx.bin（设备端构建的 HTP context）与 libQnnHtpV73Skel.so。
JNIEXPORT jboolean JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrInitCtbdQnn(JNIEnv* env, jobject,
                                                               jstring dir) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    std::string err;
    g_ctbd_ready = g_ctbd.init(to_std(env, dir), &err);
    log_info(std::string("ocr ctbd init: ") + (g_ctbd_ready ? "ok" : "FAIL") +
             (g_ctbd_ready ? "" : " " + err));
    return g_ctbd_ready ? JNI_TRUE : JNI_FALSE;
}

// JSON: [{x0,y0,x1,y1,vertical,score,label,bubble}]（页面坐标，BT 后处理已做：
// conf 0.3 / 5px / IoU0.7 去重 / 0.8 去包含）。
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrDetectCtbd(JNIEnv* env, jobject,
                                                              jintArray rgba, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    if (!g_ctbd_ready)
        return env->NewStringUTF("{\"error\":\"ctbd not ready\"}");
    jint* px = env->GetIntArrayElements(rgba, nullptr);
    auto boxes = g_ctbd.detect((const uint8_t*)px, w, h);
    env->ReleaseIntArrayElements(rgba, px, JNI_ABORT);

    json::Value arr = json::Value::make_array();
    for (const auto& b : boxes) {
        json::Value o = json::Value::make_object();
        o.set("x0", (double)b.x0);
        o.set("y0", (double)b.y0);
        o.set("x1", (double)b.x1);
        o.set("y1", (double)b.y1);
        o.set("vertical", b.vertical);
        o.set("score", (double)b.score);
        o.set("label", (int64_t)b.label);
        o.set("bubble", b.bubble);
        arr.push_back(o);
    }
    json::Value meta = json::Value::make_object();
    meta.set("boxes", arr);
    meta.set("fwdMs", (double)g_ctbd.last_fwd_ms());
    meta.set("postMs", (double)g_ctbd.last_post_ms());
    meta.set("backend", std::string("ctbd-htp"));
    return from_std(env, meta.dump());
}

// 小框快速通道：PP-OCRv5 rec（~0.16s），用于标点/极短小框，
// 避免为几个像素的「・」付出整条 VL 流程（~1.5s）。
JNIEXPORT jbyteArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrRecSmall(JNIEnv* env, jobject,
                                                            jintArray rgba, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    if (!g_qnn_ready) return nullptr;
    jint* px = env->GetIntArrayElements(rgba, nullptr);
    std::string text = g_qnn.recognize((const uint8_t*)px, w, h);
    env->ReleaseIntArrayElements(rgba, px, JNI_ABORT);
    if (text.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)text.size());
    env->SetByteArrayRegion(out, 0, (jsize)text.size(), (const jbyte*)text.data());
    return out;
}

// Returns recognized text for one crop (byte[] UTF-8), or null.
JNIEXPORT jbyteArray JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrVl(JNIEnv* env, jobject,
                                                      jintArray rgba, jint w, jint h) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    jint* px = env->GetIntArrayElements(rgba, nullptr);
    std::string text;
    if (g_qvl_ready) {
        text = g_qvl.ocr((const uint8_t*)px, w, h);
    } else if (g_qnn_ready) {
        text = g_qnn.recognize((const uint8_t*)px, w, h);
    } else if (g_vl_ready) {
        text = g_vl.ocr((const uint8_t*)px, w, h);
    }
    env->ReleaseIntArrayElements(rgba, px, JNI_ABORT);
    if (text.empty()) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)text.size());
    env->SetByteArrayRegion(out, 0, (jsize)text.size(), (const jbyte*)text.data());
    return out;
}

// 页面级批量 VL OCR：px 为所有段像素顺序拼接；ws/hs 为每段宽高；
// caps（可空）为逐段生成上限（按裁剪尺寸收紧，§8 #8）。
// 返回 JSON {"texts":[...]}（与输入等长；失败段为空串）。
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrVlPage(JNIEnv* env, jobject,
                                                          jintArray px, jintArray ws,
                                                          jintArray hs, jintArray caps) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    const jsize n = env->GetArrayLength(ws);
    if (n <= 0) return env->NewStringUTF("{\"texts\":[]}");
    jint* p = env->GetIntArrayElements(px, nullptr);
    jint* w = env->GetIntArrayElements(ws, nullptr);
    jint* h = env->GetIntArrayElements(hs, nullptr);
    jint* c = caps ? env->GetIntArrayElements(caps, nullptr) : nullptr;
    std::vector<const uint8_t*> ptrs(n);
    std::vector<int> vw(n), vh(n);
    std::vector<int> vcap;
    if (c && (int)env->GetArrayLength(caps) == n) {
        vcap.resize((size_t)n);
        for (int i = 0; i < n; ++i) vcap[(size_t)i] = (int)c[i];
    }
    const uint8_t* base = (const uint8_t*)p;
    size_t off = 0;                       // 以 int 元素计
    for (int i = 0; i < n; ++i) {
        ptrs[i] = base + off * 4;
        vw[i] = (int)w[i];
        vh[i] = (int)h[i];
        off += (size_t)w[i] * (size_t)h[i];
    }
    std::vector<std::string> texts;
    if (g_qvl_ready) {
        texts = g_qvl.ocr_batch(ptrs, vw, vh, vcap.empty() ? nullptr : &vcap);
    } else if (g_qnn_ready) {              // 无 VL 时退化为逐段 rec
        for (int i = 0; i < n; ++i)
            texts.push_back(g_qnn.recognize(ptrs[i], vw[i], vh[i]));
    } else if (g_vl_ready) {
        for (int i = 0; i < n; ++i) texts.push_back(g_vl.ocr(ptrs[i], vw[i], vh[i]));
    }
    env->ReleaseIntArrayElements(px, p, JNI_ABORT);
    env->ReleaseIntArrayElements(ws, w, JNI_ABORT);
    env->ReleaseIntArrayElements(hs, h, JNI_ABORT);
    if (c) env->ReleaseIntArrayElements(caps, c, JNI_ABORT);
    json::Value arr = json::Value::make_array();
    for (auto& t : texts) arr.push_back(json::Value(t));
    json::Value o = json::Value::make_object();
    o.set("texts", arr);
    const auto& tt = g_qvl.last_timings();
    o.set("vis", tt.vision_ms);
    o.set("pf", tt.prefill_ms);
    o.set("dec", tt.decode_ms);
    o.set("total", tt.total_ms);
    return from_std(env, o.dump());
}

// Timing snapshot JSON of the last run.
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeOcrVlTimings(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_ocr_mtx);
    if (g_qvl_ready) {
        const auto& t = g_qvl.last_timings();
        json::Value o = json::Value::make_object();
        o.set("pre", t.preprocess_ms);
        o.set("vis", t.vision_ms);
        o.set("mrg", t.merger_ms);
        o.set("pf", t.prefill_ms);
        o.set("dec", t.decode_ms);
        o.set("total", t.total_ms);
        o.set("outTokens", (int64_t)t.out_tokens);
        o.set("backend", std::string("qnn-htp-vl"));
        return from_std(env, o.dump());
    }
    if (g_qnn_ready) {
        const auto& t = g_qnn.last_timings();
        json::Value o = json::Value::make_object();
        o.set("pre", t.pre_ms);
        o.set("det", t.fwd_ms);
        o.set("post", t.post_ms);
        o.set("rec", t.rec_ms);
        o.set("total", t.total_ms);
        o.set("lines", (int64_t)t.lines);
        o.set("recLines", (int64_t)t.rec_lines);
        o.set("backend", std::string("qnn-htp-v73"));
        return from_std(env, o.dump());
    }
    const auto& t = g_vl.last_timings();
    json::Value o = json::Value::make_object();
    o.set("pre", t.preprocess_ms);
    o.set("vis", t.vision_ms);
    o.set("mrg", t.merger_ms);
    o.set("pf", t.prefill_ms);
    o.set("dec", t.decode_ms);
    o.set("total", t.total_ms);
    o.set("patches", (int64_t)t.patches);
    o.set("imgTokens", (int64_t)t.image_tokens);
    o.set("outTokens", (int64_t)t.out_tokens);
    return from_std(env, o.dump());
}

// LLM translation via OpenAI-compatible chat completions.
// texts_json: JSON array of source strings; returns JSON array of translations
// or {"error": "..."}.
JNIEXPORT jstring JNICALL
Java_com_comicshelf_app_core_NativeBridge_nativeLlmTranslate(JNIEnv* env, jobject,
                                                             jstring base_url,
                                                             jstring api_key,
                                                             jstring model,
                                                             jstring prompt,
                                                             jstring texts_json,
                                                             jstring glossary_json) {
    const std::string url = to_std(env, base_url);
    const std::string key = to_std(env, api_key);
    const std::string mdl = to_std(env, model);
    const std::string pfx = to_std(env, prompt);
    json::Value texts;
    if (!json::parse(to_std(env, texts_json), texts, nullptr) || !texts.is_array())
        return from_std(env, "{\"error\":\"bad texts\"}");
    json::Value glossary;
    const bool has_glossary =
        json::parse(to_std(env, glossary_json), glossary, nullptr) && glossary.is_array();

    // numbered source lines; the LLM answers with numbered translations.
    std::string src;
    for (size_t i = 0; i < texts.as_array().size(); ++i) {
        src += std::to_string(i + 1) + ". " + texts.as_array()[i].as_string() + "\n";
    }
    std::string sys = pfx.empty()
        ? "You are a professional manga/comic translator. Translate each numbered "
          "Japanese text line into natural Simplified Chinese, keeping the line numbers. "
          "Keep onomatopoeia short. Answer with ONLY the numbered translated lines."
        : pfx;
    if (has_glossary) {
        sys += "\nGlossary (apply when matching terms appear):";
        for (const auto& g : glossary.as_array())
            sys += "\n" + g["src"].as_string() + " => " + g["dst"].as_string();
    }

    json::Value body = json::Value::make_object();
    body.set("model", mdl.empty() ? "gpt-4o-mini" : mdl);
    json::Value msgs = json::Value::make_array();
    {
        json::Value m = json::Value::make_object();
        m.set("role", "system");
        m.set("content", sys);
        msgs.push_back(m);
        json::Value u = json::Value::make_object();
        u.set("role", "user");
        u.set("content", src);
        msgs.push_back(u);
    }
    body.set("messages", msgs);
    body.set("temperature", 0.1);
    body.set("max_tokens", 2048);

    http::Request req;
    req.method = "POST";
    req.url = url; // full endpoint URL, e.g. http://ip:8000/v1/chat/completions
    req.content_type = "application/json";
    req.body = body.dump();
    if (!key.empty()) req.headers.push_back({"Authorization", "Bearer " + key});
    req.timeout_ms = 60000;
    http::Response r = http::request(req);
    if (!r.ok) {
        json::Value o = json::Value::make_object();
        o.set("error", r.error.empty() ? "transport" : r.error);
        return from_std(env, o.dump());
    }
    json::Value resp;
    std::string perr;
    if (r.status != 200 || !json::parse(r.body, resp, &perr) ||
        !resp["choices"].is_array() || resp["choices"].as_array().empty()) {
        json::Value o = json::Value::make_object();
        o.set("error", "llm http " + std::to_string(r.status) + ": " +
                           r.body.substr(0, 200));
        return from_std(env, o.dump());
    }
    const std::string answer =
        resp["choices"].as_array()[0]["message"]["content"].as_string();

    // parse numbered lines back
    json::Value out = json::Value::make_array();
    std::vector<std::string> got(texts.as_array().size());
    size_t pos = 0;
    while (pos < answer.size()) {
        size_t eol = answer.find('\n', pos);
        std::string line = answer.substr(pos, eol == std::string::npos
                                                  ? std::string::npos
                                                  : eol - pos);
        pos = eol == std::string::npos ? answer.size() : eol + 1;
        size_t dot = line.find('.');
        if (dot == std::string::npos || dot > 4) continue;
        long idx = strtol(line.c_str(), nullptr, 10);
        if (idx >= 1 && idx <= (long)got.size()) {
            std::string t = line.substr(dot + 1);
            while (!t.empty() && (t.front() == ' ' || t.front() == '\t')) t.erase(t.begin());
            got[idx - 1] = t;
        }
    }
    for (const auto& g : got) out.push_back(g.empty() ? std::string("?") : g);
    json::Value wrap = json::Value::make_object();
    wrap.set("texts", out);
    return from_std(env, wrap.dump());
}

} // extern "C"
