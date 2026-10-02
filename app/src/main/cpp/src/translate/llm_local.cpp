#include "translate/llm_local.h"

#include "util/logger.h"

// 端侧离线翻译为可选依赖（llama.cpp 源码，见 cpp/CMakeLists.txt）：源码缺失时构建裁剪，
// 本文件退化为同名 API 的报错桩——局域网后端翻译路径不受影响。
#if CS_HAS_LLAMA

#include <chrono>
#include <vector>

#include "llama.h"

namespace cs::llm {
namespace {

double now_ms() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count() / 1000.0;
}

} // namespace

struct LocalLlm::Impl {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;
};

LocalLlm& LocalLlm::instance() {
    static LocalLlm inst;
    return inst;
}

LocalLlm::~LocalLlm() { unload(); }

bool LocalLlm::loaded() const {
    std::lock_guard<std::mutex> lk(mtx_);
    return impl_ && impl_->model && impl_->ctx;
}

void LocalLlm::unload() {
    std::lock_guard<std::mutex> lk(mtx_);
    if (!impl_) return;
    if (impl_->ctx) llama_free(impl_->ctx);
    if (impl_->model) llama_model_free(impl_->model);
    delete impl_;
    impl_ = nullptr;
    log_info("llm_local: 模型已卸载");
}

bool LocalLlm::load(const std::string& gguf_path, int n_threads, int n_ctx, std::string* err) {
    std::lock_guard<std::mutex> lk(mtx_);
    if (impl_ && path_ == gguf_path && threads_ == n_threads) return true; // 已加载同一份
    // 换模型：先释放
    if (impl_) {
        if (impl_->ctx) llama_free(impl_->ctx);
        if (impl_->model) llama_model_free(impl_->model);
        delete impl_;
        impl_ = nullptr;
    }

    static bool backend_inited = false;
    if (!backend_inited) {
        llama_backend_init();
        backend_inited = true;
    }

    const double t0 = now_ms();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;                      // Android 上只编了 CPU 后端
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;      // GGUF 直接映射，省一次 1GB 拷贝
    llama_model* model = llama_model_load_from_file(gguf_path.c_str(), mparams);
    if (!model) {
        if (err) *err = "加载 GGUF 失败: " + gguf_path;
        return false;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)(n_ctx > 0 ? n_ctx : 4096);
    cparams.n_batch = 1024;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        if (err) *err = "创建推理上下文失败（内存不足？）";
        return false;
    }

    impl_ = new Impl();
    impl_->model = model;
    impl_->ctx = ctx;
    impl_->vocab = llama_model_get_vocab(model);
    path_ = gguf_path;
    threads_ = n_threads;
    n_ctx_ = (int)cparams.n_ctx;
    log_info("llm_local: 已加载 " + gguf_path + " threads=" + std::to_string(n_threads) +
             " ctx=" + std::to_string(n_ctx_) + " in " + std::to_string((int)(now_ms() - t0)) +
             "ms");
    return true;
}

bool LocalLlm::chat(const std::string& system, const std::string& user, const GenParams& p,
                    std::string* out, std::string* err) {
    std::lock_guard<std::mutex> lk(mtx_);
    if (!impl_ || !impl_->ctx) {
        if (err) *err = "模型未加载";
        return false;
    }
    llama_context* ctx = impl_->ctx;
    const llama_vocab* vocab = impl_->vocab;

    // ---- 套用模型自带的 chat 模板（Hy-MT 的 <｜hy_User｜>/<｜hy_Assistant｜> 等）
    const char* tmpl = llama_model_chat_template(impl_->model, nullptr);
    std::vector<llama_chat_message> msgs;
    msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});
    std::vector<char> buf(system.size() + user.size() + 512);
    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(),
                                            (int32_t)buf.size());
    if (need > (int32_t)buf.size()) {
        buf.resize((size_t)need + 64);
        need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(),
                                         (int32_t)buf.size());
    }
    if (need <= 0) {
        if (err) *err = "chat 模板套用失败";
        return false;
    }
    std::string prompt(buf.data(), (size_t)need);

    // ---- tokenize（模板已含 BOS/特殊标记 → 不再自动加 special）
    std::vector<llama_token> toks(prompt.size() + 64);
    int n_tok = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(),
                               (int32_t)toks.size(), false, true);
    if (n_tok < 0) {
        toks.resize((size_t)(-n_tok));
        n_tok = llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(),
                               (int32_t)toks.size(), false, true);
    }
    if (n_tok <= 0) {
        if (err) *err = "tokenize 失败";
        return false;
    }
    toks.resize((size_t)n_tok);
    last_prompt_tok_ = n_tok;
    if (n_tok >= n_ctx_ - p.max_tokens) { // 上下文不够：截断最前面的（保留模板尾部）
        const int keep = n_ctx_ - p.max_tokens - 8;
        toks.erase(toks.begin(), toks.end() - keep);
        log_warn("llm_local: prompt 超长，已截断到 " + std::to_string(toks.size()));
    }

    // 每次调用独立：清空 KV
    llama_memory_clear(llama_get_memory(ctx), true);

    // ---- prefill
    const double t0 = now_ms();
    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t)toks.size());
    if (llama_decode(ctx, batch) != 0) {
        if (err) *err = "prefill 失败";
        return false;
    }
    last_prefill_ms_ = now_ms() - t0;

    // ---- 采样链（官方推荐：top_k → top_p → temp → dist）
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    if (p.top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(p.top_k));
    if (p.top_p > 0 && p.top_p < 1.f)
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(p.top_p, 1));
    if (p.temp > 0) llama_sampler_chain_add(smpl, llama_sampler_init_temp(p.temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ---- 贪心/采样解码
    const double t1 = now_ms();
    std::string result;
    int produced = 0;
    char piece[512];
    std::vector<llama_token> one(1);
    while (produced < p.max_tokens) {
        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        const int np = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (np > 0) result.append(piece, (size_t)np);
        ++produced;
        one[0] = tok;
        llama_batch nb = llama_batch_get_one(one.data(), 1);
        if (llama_decode(ctx, nb) != 0) break;
    }
    last_decode_ms_ = now_ms() - t1;
    last_out_tok_ = produced;
    llama_sampler_free(smpl);

    // 去掉首尾空白（模板可能在生成结束后带残留标记）
    while (!result.empty() && (result.front() == '\n' || result.front() == ' '))
        result.erase(result.begin());
    while (!result.empty() && (result.back() == '\n' || result.back() == ' ' ||
                               result.back() == '\r'))
        result.pop_back();
    *out = result;
    return true;
}

std::string LocalLlm::info() const {
    std::lock_guard<std::mutex> lk(mtx_);
    if (!impl_ || !impl_->ctx) return "未加载";
    char b[256];
    snprintf(b, sizeof(b), "已加载 %s | threads=%d ctx=%d | 上次: prompt %d tok %.0fms, 输出 %d tok %.0fms (%.1f tok/s)",
             path_.c_str(), threads_, n_ctx_, last_prompt_tok_, last_prefill_ms_, last_out_tok_,
             last_decode_ms_,
             last_decode_ms_ > 1 ? last_out_tok_ * 1000.0 / last_decode_ms_ : 0.0);
    return b;
}

} // namespace cs::llm

#else // !CS_HAS_LLAMA —— 裁剪版：提供同名 API 的报错桩（调用方按"失败+错误文本"处理）

namespace cs::llm {

LocalLlm& LocalLlm::instance() {
    static LocalLlm inst;
    return inst;
}

LocalLlm::~LocalLlm() = default;

bool LocalLlm::load(const std::string&, int, int, std::string* err) {
    if (err) *err = "端侧翻译未编入：缺少 llama.cpp 源码（可选依赖，见 cpp/CMakeLists.txt）";
    return false;
}

void LocalLlm::unload() {}

bool LocalLlm::loaded() const { return false; }

bool LocalLlm::chat(const std::string&, const std::string&, const GenParams&, std::string* out,
                    std::string* err) {
    (void)out;
    if (err) *err = "端侧翻译未编入：缺少 llama.cpp 源码（可选依赖，见 cpp/CMakeLists.txt）";
    return false;
}

std::string LocalLlm::info() const { return "未编入（llama.cpp 缺失）"; }

} // namespace cs::llm

#endif // CS_HAS_LLAMA
