// 内置翻译模型（llama.cpp / GGUF）：Hy-MT2-1.8B 等在手机上离线跑。
//
// 设计：单例 + 一把互斥锁。翻译是"一页一次"的批量调用（多句台词一次翻完），
// 每次调用清空 KV 重新 prefill；模型只加载一次常驻（约 1.1GB Q4_K_M）。
#pragma once
#include <cstdint>
#include <mutex>
#include <string>

namespace cs::llm {

struct GenParams {
    int max_tokens = 256;
    float temp = 0.7f;
    float top_p = 0.8f;
    int top_k = 20;
};

class LocalLlm {
public:
    static LocalLlm& instance();

    // gguf_path: HF 官方 GGUF（如 tencent/Hy-MT2-1.8B-GGUF 的 Q4_K_M）
    bool load(const std::string& gguf_path, int n_threads, int n_ctx, std::string* err);
    void unload();
    bool loaded() const;

    // system + user → 生成的正文（已按模型自带 chat 模板套用）
    bool chat(const std::string& system, const std::string& user, const GenParams& p,
              std::string* out, std::string* err);

    // 诊断信息（路径/线程/耗时统计）
    std::string info() const;

private:
    LocalLlm() = default;
    ~LocalLlm();
    LocalLlm(const LocalLlm&) = delete;
    LocalLlm& operator=(const LocalLlm&) = delete;

    struct Impl;
    Impl* impl_ = nullptr;
    mutable std::mutex mtx_;
    std::string path_;
    int threads_ = 4;
    int n_ctx_ = 4096;
    // 统计
    double last_prefill_ms_ = 0, last_decode_ms_ = 0;
    int last_prompt_tok_ = 0, last_out_tok_ = 0;
};

} // namespace cs::llm
