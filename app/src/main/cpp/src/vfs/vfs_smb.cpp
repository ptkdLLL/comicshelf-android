// SMB2 后端（libsmb2）：会话池 + 逻辑/传输错误区分 + 断线重连。
//
// 并发模型：一次网络操作 = 从池里取一个会话、独占执行、归还。会话（TCP+认证）
// 常驻复用，避免每次列目录都重连；扫描时 N 个 worker 各自持有一个会话并发跑。
// 打开的文件在生命周期内持有会话；会话掉线时 read() 会用新会话重开文件并 seek
// 回原位置（对调用方透明）。
#include "vfs/vfs.h"

#include <chrono>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <vector>

#include "util/logger.h"

#if !defined(CS_HAS_SMB2) || !CS_HAS_SMB2

// 裁剪构建（没有 libsmb2）：给出明确的失败实现。
namespace cs::vfs {
uint64_t smb_epoch() { return 0; }  // 无 SMB：纪元恒 0
std::shared_ptr<Vfs> make_smb_vfs_impl(const SmbConfig&, std::string* err) {
    if (err) *err = "本构建未包含 SMB2 客户端（libsmb2 缺失）";
    return nullptr;
}
std::shared_ptr<SmbWatcher> make_smb_watcher(const SmbConfig&, const std::string&,
                                             SmbWatcher::Callback, std::string* err) {
    if (err) *err = "本构建未包含 SMB2 客户端（libsmb2 缺失）";
    return nullptr;
}
bool smb_list_shares(const std::string&, const std::string&, const std::string&,
                     const std::string&, std::vector<std::string>*,
                     std::string* err) {
    if (err) *err = "本构建未包含 SMB2 客户端（libsmb2 缺失）";
    return false;
}
} // namespace cs::vfs

#else

#include <fcntl.h>
// libsmb2.h 不是自包含的：协议常量/类型在 smb2.h 里（官方示例也是两行一起 include）。
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>
#include <smb2/libsmb2-share-enum.h>

// 本版本头文件漏了声明（.so 里有导出符号）
extern "C" void smb2_free_data(struct smb2_context* smb2, void* ptr);

#include "vfs/smb_watch.h"
#include "vfs/stall_watch.h"

#include <atomic>
#include <csignal>
#include <dlfcn.h>
#include <unwind.h>
#include <sys/select.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <android/log.h>
#include <thread>
#include <unordered_map>

namespace cs::vfs {
namespace {

constexpr int kMaxSessions = 8;        // 扫描 worker + 阅读器 + 余量（NAS 常限制每用户连接数）
constexpr int kAcquireTimeoutMs = 30000;
constexpr int kSmbTimeoutSec = 30;     // 单次请求超时（卡死检测）
constexpr uint32_t kReadChunk = 1u << 20;

// 传输健康度纪元：任何会话被判为“损坏/断连”时 +1（语义见 vfs.h 的 smb_epoch()）。
std::atomic<uint64_t> g_smb_epoch{0};
void bump_smb_epoch() { g_smb_epoch.fetch_add(1, std::memory_order_relaxed); }

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::string smb_err(smb2_context* c) {
    const char* e = c ? smb2_get_error(c) : nullptr;
    return e && *e ? std::string(e) : std::string("smb2: unknown error");
}

// "对象不存在/没权限" 之类的逻辑错误不该触发重连重试。
bool looks_transport(const std::string& e) {
    // 服务端主动断连 / 超时 都属于"换个会话再来"的传输故障
    if (e.find("POLLHUP") != std::string::npos ||
        e.find("POLLERR") != std::string::npos ||
        e.find("remote closed") != std::string::npos ||
        e.find("Connection reset") != std::string::npos) {
        return true;
    }
    static const char* kLogical[] = {
        "STATUS_OBJECT_NAME_NOT_FOUND", "STATUS_OBJECT_PATH_NOT_FOUND",
        "STATUS_NO_SUCH_FILE",          "STATUS_ACCESS_DENIED",
        "STATUS_NOT_SUPPORTED",         "STATUS_INVALID_PARAMETER",
        "STATUS_FILE_IS_A_DIRECTORY",   "STATUS_NOT_A_DIRECTORY",
        "STATUS_OBJECT_NAME_INVALID",   "STATUS_OBJECT_NAME_COLLISION",
        "STATUS_DISK_FULL",             "STATUS_DIRECTORY_NOT_EMPTY",
    };
    for (auto* k : kLogical)
        if (e.find(k) != std::string::npos) return false;
    return true;
}

std::string norm_path(const std::string& p) {
    if (p.empty()) return "/";
    if (p[0] == '/') return p;
    return "/" + p;
}


struct SmbSession {
    smb2_context* ctx = nullptr;
    std::mutex mtx;       // 一个 libsmb2 上下文不可并发使用
    int64_t last_use = 0;
    bool broken = false;  // 传输层故障：归还时销毁，下次取用时重建
};
using SessionPtr = std::shared_ptr<SmbSession>;

class SmbPool {
public:
    explicit SmbPool(SmbConfig cfg) : cfg_(std::move(cfg)) { smbwatch::ensure_watchdog(); }

    ~SmbPool() {
        std::lock_guard<std::mutex> g(mtx_);
        for (auto& s : idle_) destroy(s.get());
        for (auto& s : busy_) destroy(s.get());
    }

    SmbPool(const SmbPool&) = delete;
    SmbPool& operator=(const SmbPool&) = delete;

    SessionPtr acquire(std::string* err) {
        std::unique_lock<std::mutex> lk(mtx_);
        const int64_t t0 = now_ms();
        for (;;) {
            if (!idle_.empty()) {
                SessionPtr s = idle_.back();
                idle_.pop_back();
                busy_.push_back(s);
                return s;
            }
            if ((int)(idle_.size() + busy_.size()) < kMaxSessions) {
                smbwatch::op_begin("smb connect share（新建会话）");
                smb2_context* c = connect(&cfg_, err);
                smbwatch::op_end();
                if (!c) return nullptr;
                auto s = std::make_shared<SmbSession>();
                s->ctx = c;
                s->last_use = now_ms();
                busy_.push_back(s);
                return s;
            }
            if (cv_.wait_for(lk, std::chrono::milliseconds(kAcquireTimeoutMs)) ==
                std::cv_status::timeout) {
                if (err) *err = "SMB 会话池忙（" + std::to_string(kMaxSessions) + " 个会话全在用）";
                log_warn("[SMB] 会话池等待超时（30s）：idle=" + std::to_string(idle_.size()) +
                         " busy=" + std::to_string(busy_.size()));
                return nullptr;
            }
            if (now_ms() - t0 > 1000)
                log_info("[SMB] 会话池等待 " + std::to_string(now_ms() - t0) + "ms 后取到会话");
        }
    }

    void release(const SessionPtr& s) {
        if (!s) return;
        std::lock_guard<std::mutex> g(mtx_);
        auto it = std::find(busy_.begin(), busy_.end(), s);
        if (it != busy_.end()) busy_.erase(it);
        s->last_use = now_ms();
        if (s->broken) {
            destroy(s.get());
        } else {
            idle_.push_back(s);
        }
        cv_.notify_one();
    }

    static smb2_context* connect(const SmbConfig* cfg, std::string* err) {
        const int64_t t0 = now_ms();
        smb2_context* c = smb2_init_context();
        if (!c) {
            if (err) *err = "smb2_init_context 失败";
            return nullptr;
        }
        smb2_set_timeout(c, kSmbTimeoutSec);
        smb2_set_security_mode(c, SMB2_NEGOTIATE_SIGNING_ENABLED);
        if (!cfg->user.empty()) smb2_set_user(c, cfg->user.c_str());
        if (!cfg->password.empty()) smb2_set_password(c, cfg->password.c_str());
        if (!cfg->domain.empty()) smb2_set_domain(c, cfg->domain.c_str());
        if (smb2_connect_share(c, cfg->host.c_str(), cfg->share.c_str(),
                               cfg->user.empty() ? nullptr : cfg->user.c_str()) < 0) {
            if (err) *err = smb_err(c);
            log_warn("[SMB] 新建会话失败（" + std::to_string(now_ms() - t0) + "ms）: " +
                     (err ? *err : "?"));
            smb2_destroy_context(c);
            return nullptr;
        }
        log_info("[SMB] 新建会话成功（" + std::to_string(now_ms() - t0) + "ms）" +
                 (now_ms() - t0 > 3000 ? " ← 慢！" : ""));
        return c;
    }

    static void destroy(SmbSession* s) {
        if (!s || !s->ctx) return;
        smb2_destroy_context(s->ctx);
        s->ctx = nullptr;
    }

    bool warm(std::string* err) { // 建连一次（提前暴露地址/凭据错误）
        SessionPtr s = acquire(err);
        if (!s) return false;
        release(s);
        return true;
    }

private:
    SmbConfig cfg_;
    mutable std::mutex mtx_;
    std::condition_variable cv_;
    std::vector<SessionPtr> idle_;
    std::vector<SessionPtr> busy_;
};

class SmbVfs;

// 打开的文件：持有会话；掉线后 read() 会用新会话重开 + seek 恢复。
class SmbFile : public File {
public:
    SmbFile(std::shared_ptr<Vfs> owner, SmbPool* pool, std::string path)
        : owner_(std::move(owner)), pool_(pool), path_(std::move(path)) {}

    ~SmbFile() override { drop_session(false); }

    int64_t read(uint8_t* buf, size_t len) override {
        if (pending_err_) return -1;
        if (len == 0) return 0;
        for (int attempt = 0; attempt < 2; ++attempt) {
            std::string err;
            if (!ensure_open(&err)) {
                last_err_ = err;
                return -1;
            }
            size_t total = 0;
            bool failed = false;
            {
                smbwatch::op_begin("等会话锁 " + path_);   // v0.3.3d：锁等待也可被守护线程抓栈
                std::lock_guard<std::mutex> g(session_->mtx);
                int chunk = 0;
                const int nchunks = (int)((len + kReadChunk - 1) / kReadChunk);
                while (total < len) {
                    uint32_t want = (uint32_t)std::min<size_t>(len - total, kReadChunk);
                    smbwatch::op_begin("smb2_read 第" + std::to_string(++chunk) + "/" +
                                       std::to_string(nchunks) + "块 off=" + std::to_string(pos_ + total) +
                                       " len=" + std::to_string(want) + " " + path_);
                    const int64_t ct0 = now_ms();
                    int got = smb2_read(session_->ctx, fh_, buf + total, want);
                    const int64_t cms = now_ms() - ct0;
                    if (got < 0) {
                        last_err_ = smb_err(session_->ctx);
                        log_warn("[SMB] read 失败 chunk=" + std::to_string(chunk) + "/" +
                                 std::to_string(nchunks) + " off=" + std::to_string(pos_ + total) +
                                 " 耗时=" + std::to_string(cms) + "ms err=" + last_err_ + " " + path_);
                        failed = true;
                        break;
                    }
                    if (got == 0) break; // EOF
                    if (cms > 1500)
                        log_info("[SMB] read 慢块 " + std::to_string(cms) + "ms off=" +
                                 std::to_string(pos_ + total) + " got=" + std::to_string(got) +
                                 " " + path_);
                    total += (size_t)got;
                }
                smbwatch::op_end();
            }
            if (!failed) {
                pos_ += total;
                return (int64_t)total;
            }
            drop_session(true); // 传输故障：会话交池销毁，下次重建
            if (total > 0) {
                // 已经交付了部分数据：先把水位推进，错误留给下一次 read。
                pending_err_ = true;
                pos_ += total;
                return (int64_t)total;
            }
        }
        return -1;
    }

    bool seek(uint64_t off) override {
        for (int attempt = 0; attempt < 2; ++attempt) {
            std::string err;
            if (!ensure_open(&err)) {
                last_err_ = err;
                return false;
            }
            bool ok = false;
            {
                std::lock_guard<std::mutex> g(session_->mtx);
                uint64_t cur = 0;
                ok = smb2_lseek(session_->ctx, fh_, (int64_t)off, SEEK_SET, &cur) >= 0;
                if (!ok) last_err_ = smb_err(session_->ctx);
            }
            if (ok) {
                pos_ = off;
                return true;
            }
            drop_session(true);
        }
        return false;
    }

    uint64_t size() override { return size_; }
    uint64_t tell() override { return pos_; }

    // 打开成功后由 SmbVfs::open 填充（fstat 优先，stat 兜底）。
    bool init(std::string* err) {
        if (!ensure_open(err)) return false;
        std::lock_guard<std::mutex> g(session_->mtx);
        struct smb2_stat_64 st;
        std::memset(&st, 0, sizeof(st));
        if (smb2_fstat(session_->ctx, fh_, &st) == 0 && st.smb2_size > 0) {
            size_ = st.smb2_size;
        } else {
            struct smb2_stat_64 st2;
            std::memset(&st2, 0, sizeof(st2));
            if (smb2_stat(session_->ctx, path_.c_str(), &st2) == 0) size_ = st2.smb2_size;
        }
        size_known_ = true;
        return true;
    }

    uint64_t known_size() const { return size_; }
    bool size_known() const { return size_known_; }

private:
    // 归还/丢弃当前会话。绝不在持有 session_->mtx 时调用。
    void drop_session(bool broken) {
        if (!session_) return;
        if (!broken && fh_) {
            std::lock_guard<std::mutex> g(session_->mtx);
            smb2_close(session_->ctx, fh_);
            fh_ = nullptr;
        }
        fh_ = nullptr;
        if (broken && !session_->broken) {
            session_->broken = true;
            bump_smb_epoch();
        }
        pool_->release(session_);
        session_.reset();
    }

    bool ensure_open(std::string* err) {
        if (session_ && !session_->broken && fh_) return true;
        drop_session(session_ && session_->broken);
        session_ = pool_->acquire(err);
        if (!session_) return false;
        bool ok = false, transport = false;
        {
            const int64_t t0 = now_ms();
            smbwatch::op_begin("smb2_open+lseek " + path_);
            std::lock_guard<std::mutex> g(session_->mtx);
            fh_ = smb2_open(session_->ctx, path_.c_str(), O_RDONLY);
            if (!fh_) {
                if (err) *err = smb_err(session_->ctx);
                transport = looks_transport(err ? *err : "");
            } else if (pos_ != 0) {
                uint64_t cur = 0;
                if (smb2_lseek(session_->ctx, fh_, (int64_t)pos_, SEEK_SET, &cur) < 0) {
                    if (err) *err = smb_err(session_->ctx);
                    smb2_close(session_->ctx, fh_);
                    fh_ = nullptr;
                    transport = true;
                } else {
                    ok = true;
                }
            } else {
                ok = true;
            }
            smbwatch::op_end();
            if (now_ms() - t0 > 1000)
                log_info("[SMB] open+lseek 耗时 " + std::to_string(now_ms() - t0) +
                         "ms ok=" + std::to_string((int)ok) + " " + path_);
        }
        if (!ok) drop_session(transport);
        return ok;
    }

    std::shared_ptr<Vfs> owner_; // 保活 Vfs/池
    SmbPool* pool_ = nullptr;
    std::string path_;
    SessionPtr session_;
    smb2fh* fh_ = nullptr;
    uint64_t pos_ = 0;
    uint64_t size_ = 0;
    bool size_known_ = false;
    bool pending_err_ = false;
    std::string last_err_;
};

class SmbVfs : public Vfs, public std::enable_shared_from_this<SmbVfs> {
public:
    SmbVfs(SmbConfig cfg, std::shared_ptr<SmbPool> pool)
        : host_(cfg.host), share_(cfg.share), pool_(std::move(pool)) {}

    std::string scheme() const override { return "smb"; }
    std::string display() const override { return host_ + "/" + share_; }

    bool warm(std::string* err) { return pool_->warm(err); }

    bool stat(const std::string& path, FileStat* out) override {
        struct smb2_stat_64 st;
        std::memset(&st, 0, sizeof(st));
        std::string err;
        bool ok = run(&err, [&](smb2_context* c) {
            return smb2_stat(c, norm_path(path).c_str(), &st) == 0;
        });
        if (!ok) return false;
        out->size = st.smb2_size;
        out->mtime = (int64_t)st.smb2_mtime;
        out->is_dir = st.smb2_type == SMB2_TYPE_DIRECTORY;
        out->is_file = !out->is_dir;
        return true;
    }

    bool list(const std::string& path, std::vector<DirEntry>* out) override {
        out->clear();
        const std::string p = norm_path(path);
        std::string err;
        bool ok = run(&err, [&](smb2_context* c) {
            struct smb2dir* dir = smb2_opendir(c, p.c_str());
            if (!dir) return false;
            struct smb2dirent* ent;
            while ((ent = smb2_readdir(c, dir)) != nullptr) {
                if (!std::strcmp(ent->name, ".") || !std::strcmp(ent->name, "..")) continue;
                DirEntry e;
                e.name = ent->name;
                e.st.size = ent->st.smb2_size;
                e.st.mtime = (int64_t)ent->st.smb2_mtime;
                e.st.is_dir = ent->st.smb2_type == SMB2_TYPE_DIRECTORY;
                e.st.is_file = !e.st.is_dir;
                out->push_back(std::move(e));
            }
            smb2_closedir(c, dir);
            return true;
        });
        if (!ok) out->clear();
        return ok;
    }

    std::unique_ptr<File> open(const std::string& path) override {
        auto f = std::make_unique<SmbFile>(shared_from_this(), pool_.get(),
                                           norm_path(path));
        std::string err;
        if (!f->init(&err)) {
            log_warn("smb open " + path + ": " + err);
            return nullptr;
        }
        return f;
    }

    std::string full_path(const std::string& rel) const override {
        std::string base = "smb://" + host_ + "/" + share_;
        if (rel.empty() || rel == "/") return base;
        if (rel[0] == '/') return base + rel;
        return base + "/" + rel;
    }

    // 供 Scanner/Reader 显式建连（错误信息更早暴露）
    bool probe(std::string* err) { return pool_->warm(err); }

private:
    // 池上重试一次：任何失败都换一个会话重来（idle 会话可能已被服务端断开）。
    template <typename F>
    bool run(std::string* err, F&& fn) {
        for (int attempt = 0; attempt < 2; ++attempt) {
            std::string aerr;
            SessionPtr s = pool_->acquire(&aerr);
            if (!s) {
                if (err) *err = aerr;
                return false;
            }
            bool ok = false;
            {
                std::lock_guard<std::mutex> g(s->mtx);
                ok = fn(s->ctx);
                if (!ok) {
                    aerr = smb_err(s->ctx);
                    if (looks_transport(aerr)) {
                        s->broken = true;
                        bump_smb_epoch();
                    }
                }
            }
            pool_->release(s);
            if (ok) return true;
            if (!s->broken) { // 逻辑错误（不存在等），重试无意义
                if (err) *err = aerr;
                return false;
            }
            if (attempt == 0) log_warn("smb 传输失败，重连重试: " + aerr);
        }
        if (err) *err = "smb: 重试后仍失败";
        return false;
    }

    std::string host_;
    std::string share_;
    std::shared_ptr<SmbPool> pool_;
};

} // namespace

uint64_t smb_epoch() { return g_smb_epoch.load(std::memory_order_relaxed); }

// ---------------------------------------------------------------- 工厂实现

std::shared_ptr<Vfs> make_smb_vfs_impl(const SmbConfig& cfg, std::string* err) {
    auto pool = std::make_shared<SmbPool>(cfg);
    auto vfs = std::make_shared<SmbVfs>(cfg, pool);
    std::string perr;
    if (!vfs->probe(&perr)) { // 建连失败就不返回半残的 Vfs
        if (err) *err = "SMB 连接失败 " + cfg.host + "/" + cfg.share + ": " + perr;
        return nullptr;
    }
    return vfs;
}

// ---------------------------------------------------------------- CHANGE_NOTIFY

namespace {

// 监视器自带一条连接（长挂一个未完成的 NOTIFY 请求，不能和池里的请求复用）。
class SmbWatcherImpl : public SmbWatcher {
public:
    SmbWatcherImpl(SmbConfig cfg, std::string dir, Callback cb)
        : cfg_(std::move(cfg)), dir_(std::move(dir)), cb_(std::move(cb)) {}

    ~SmbWatcherImpl() override { stop(); }

    bool start(std::string* err) {
        // 先探一次，把凭据/路径错误立刻报给调用者；随后交给线程（含重连）
        smb2_context* probe = SmbPool::connect(&cfg_, err);
        if (!probe) return false;
        smb2_destroy_context(probe);
        stop_ = false;
        th_ = std::thread([this] { thread_main(); });
        return true;
    }

    void stop() override {
        stop_ = true;
        if (th_.joinable()) th_.join();
        destroy_ctx();
        alive_ = false;
    }

    bool alive() const override { return alive_.load(); }

private:
    void thread_main() {
        // 断线自愈：NAS 会主动断开长挂的空闲连接（也会限制每用户连接数），
        // 断开后退避重连并重新订阅，保证"NAS 上加书 → 手机几秒内出现"的承诺。
        int backoff_ms = 2000;
        std::string unsupported;
        while (!stop_) {
            std::string err;
            if (!connect_ctx(&err)) {
                if (err.find("STATUS_NOT_SUPPORTED") != std::string::npos) {
                    unsupported = err;
                    break; // 服务器就是不支持，别死循环
                }
                log_warn("smb watch: 重连失败: " + err);
                sleep_ms(backoff_ms);
                backoff_ms = std::min(backoff_ms * 2, 60000);
                continue;
            }
            if (smb2_notify_change_async(ctx_, dir_.c_str(), SMB2_CHANGE_NOTIFY_WATCH_TREE,
                                         kFilter, 1 /* loop：每次回复后自动续订 */,
                                         &SmbWatcherImpl::on_notify, this) < 0) {
                std::string e = smb_err(ctx_);
                destroy_ctx();
                if (e.find("STATUS_NOT_SUPPORTED") != std::string::npos) {
                    unsupported = e;
                    break;
                }
                log_warn("smb watch: 订阅失败: " + e);
                sleep_ms(backoff_ms);
                backoff_ms = std::min(backoff_ms * 2, 60000);
                continue;
            }
            alive_ = true;
            backoff_ms = 2000; // 连上了就把退避复位
            log_info("smb watch: 已订阅 " + dir_);
            while (!stop_) {
                fd_set rfds;
                FD_ZERO(&rfds);
                const int fd = smb2_get_fd(ctx_);
                if (fd >= 0) FD_SET(fd, &rfds);
                struct timeval tv{};
                tv.tv_sec = 0;
                tv.tv_usec = 200 * 1000;
                const int rc = ::select(fd + 1, &rfds, nullptr, nullptr, &tv);
                int revents = 0;
                if (rc > 0 && fd >= 0 && FD_ISSET(fd, &rfds)) revents = smb2_which_events(ctx_);
                if (smb2_service(ctx_, revents) < 0) {
                    log_warn("smb watch: 连接断开（稍后重连）: " + smb_err(ctx_));
                    bump_smb_epoch();  // 连接已死：在途任务的结果不可信
                    break;
                }
                flush_pending();
            }
            destroy_ctx();
            alive_ = false;
        }
        destroy_ctx();
        alive_ = false;
        if (!stop_) {
            if (!unsupported.empty())
                log_warn("smb watch: 服务器不支持 CHANGE_NOTIFY: " + unsupported);
            else
                log_warn("smb watch: 监视已停止 " + dir_);
        }
    }

    static void sleep_ms(int ms) {
        std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    }

    bool connect_ctx(std::string* err) {
        if (ctx_) return true;
        ctx_ = SmbPool::connect(&cfg_, err);
        return ctx_ != nullptr;
    }

    void destroy_ctx() {
        if (!ctx_) return;
        smb2_destroy_context(ctx_);
        ctx_ = nullptr;
    }

    // 事件批量攒 1.5s 再回抛：一次拷贝大量文件会产生成百上千条事件。
    void flush_pending() {
        std::vector<SmbChange> batch;
        {
            std::lock_guard<std::mutex> g(m_);
            if (pending_.empty()) return;
            if (now_ms() - first_event_ms_ < kDebounceMs) return;
            batch.swap(pending_);
            first_event_ms_ = 0;
        }
        if (cb_) cb_(batch);
    }

    static void on_notify(smb2_context* c, int status, void* cmd_data, void* cb_data) {
        auto* self = static_cast<SmbWatcherImpl*>(cb_data);
        if (status || !cmd_data) {
            if (status) {
                log_warn("smb watch: notify 失败: " + smb_err(c));
                self->alive_ = false;
            }
            return;
        }
        std::vector<SmbChange> evs;
        for (auto* it = static_cast<struct smb2_file_notify_change_information*>(cmd_data);
             it; it = it->next) {
            if (!it->name) continue;
            SmbChange e;
            e.rel = self->join_share(it->name);
            e.action = (int)it->action;
            evs.push_back(std::move(e));
        }
        if (evs.empty()) return;
        std::lock_guard<std::mutex> g(self->m_);
        if (self->pending_.empty()) self->first_event_ms_ = now_ms();
        for (auto& e : evs) {
            // 同一路径只留最后一条（避免 added+modified+renamed 三连刷）
            bool merged = false;
            for (auto& p : self->pending_) {
                if (p.rel == e.rel) {
                    p.action = e.action;
                    merged = true;
                    break;
                }
            }
            if (!merged) self->pending_.push_back(std::move(e));
        }
    }

    // 事件里的名字是“相对被监视目录”的路径 → 共享根相对路径
    std::string join_share(const char* name) const {
        std::string n(name);
        if (n.empty()) return dir_;
        if (n[0] != '/') n = "/" + n;
        if (dir_ == "/" || dir_.empty()) return n;
        return dir_ + n;
    }

    static constexpr uint32_t kFilter =
        SMB2_CHANGE_NOTIFY_FILE_NOTIFY_CHANGE_FILE_NAME |
        SMB2_CHANGE_NOTIFY_FILE_NOTIFY_CHANGE_DIR_NAME |
        SMB2_CHANGE_NOTIFY_FILE_NOTIFY_CHANGE_SIZE |
        SMB2_CHANGE_NOTIFY_FILE_NOTIFY_CHANGE_LAST_WRITE |
        SMB2_CHANGE_NOTIFY_FILE_NOTIFY_CHANGE_CREATION;
    static constexpr int64_t kDebounceMs = 1500;

    SmbConfig cfg_;
    std::string dir_;
    Callback cb_;
    smb2_context* ctx_ = nullptr;
    std::thread th_;
    std::atomic<bool> stop_{false};
    std::atomic<bool> alive_{false};
    std::mutex m_;
    std::vector<SmbChange> pending_;
    int64_t first_event_ms_ = 0;
};

} // namespace

// ---------------------------------------------------------------- 共享枚举

bool smb_list_shares(const std::string& host, const std::string& user,
                     const std::string& password, const std::string& domain,
                     std::vector<std::string>* out, std::string* err) {
    SmbConfig cfg;
    cfg.host = host;
    cfg.share = "IPC$";
    cfg.user = user;
    cfg.password = password;
    cfg.domain = domain;
    smb2_context* ctx = SmbPool::connect(&cfg, err);
    if (!ctx) return false;
    bool ok = false;
    struct srvsvc_NetrShareEnum_rep* rep =
        smb2_share_enum_sync(ctx, SHARE_INFO_1);
    if (!rep) {
        if (err) *err = std::string("共享枚举失败: ") + smb_err(ctx);
    } else {
        ok = true;
        const auto& lvl = rep->ses.ShareEnum.Level1;
        for (uint32_t i = 0; i < lvl.EntriesRead; ++i) {
            const char* name = lvl.share_info_1[i].netname;
            if (!name || !*name) continue;
            const std::string n(name);
            if (n.back() == '$') continue; // C$ / ADMIN$ 之类的隐藏共享
            out->push_back(n);
        }
        smb2_free_data(ctx, rep);
    }
    smb2_disconnect_share(ctx);
    smb2_destroy_context(ctx);
    return ok;
}

std::shared_ptr<SmbWatcher> make_smb_watcher(const SmbConfig& cfg, const std::string& dir_rel,
                                             SmbWatcher::Callback cb, std::string* err) {
    // 先确认目录可访问（凭据/路径错误在这里就能报出来）
    std::string verr;
    auto vfs = make_smb_vfs_impl(cfg, &verr);
    if (!vfs) {
        if (err) *err = verr;
        return nullptr;
    }
    vfs::FileStat st;
    if (!vfs->stat(dir_rel, &st) || !st.is_dir) {
        if (err) *err = "监视目录不存在: " + dir_rel;
        return nullptr;
    }
    auto w = std::make_shared<SmbWatcherImpl>(cfg, norm_path(dir_rel), std::move(cb));
    std::string serr;
    if (!w->start(&serr)) {
        if (err) *err = serr;
        return nullptr;
    }
    return w;
}

} // namespace cs::vfs

// ---------------------------------------------------------------- v0.3.3d 卡死守护
// 见 vfs/stall_watch.h：登记"当前长操作"，>12s 打日志 + SIGUSR2 自抓栈 + EINTR 自愈。
namespace smbwatch {
namespace {
constexpr long long kStallCheckMs = 3000;
constexpr long long kStallMs = 12000;

struct OpRec { long long start_ms; std::string what; };
std::mutex g_mtx;
std::unordered_map<pid_t, OpRec> g_ops;
std::atomic<bool> g_wd_started{false};
std::atomic<bool> g_reported{false};   // 一轮卡死只抓一次；全部清空后重新武装

long long sw_now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

// SIGUSR2 处理器：在"被卡住的线程上"执行。只打裸 PC + 本 .so 基址（无 malloc），
// 离线用 build/intermediates 里未剥离的 libcomicshelf.so 做 addr2line 还原。
// 用 _Unwind_Backtrace（平台常驻 libunwind，API<33 也可用；backtrace() 被 INTO_33 门控）。
struct UWCtx { void** frames; int n; int max; };

_Unwind_Reason_Code uw_cb(struct _Unwind_Context* ctx, void* arg) {
    auto* c = static_cast<UWCtx*>(arg);
    uintptr_t ip = _Unwind_GetIP(ctx);
    if (ip && c->n < c->max) c->frames[c->n++] = (void*)ip;
    return _URC_NO_REASON;
}

void stack_handler(int) {
    void* frames[48];
    UWCtx ctx{frames, 0, 48};
    _Unwind_Backtrace(uw_cb, &ctx);
    Dl_info info{};
    void* base = nullptr;
    if (dladdr((void*)&stack_handler, &info)) base = info.dli_fbase;
    pid_t tid = gettid();
    // 拼成一行多帧，减少日志行数（logcat 单行上限远大于此）
    char line[48 * 20 + 80];
    int off = snprintf(line, sizeof line, "FRAMES tid=%d so_base=%p |", (int)tid, base);
    for (int i = 0; i < ctx.n && off < (int)sizeof line - 24; i++)
        off += snprintf(line + off, sizeof line - off, " %p", frames[i]);
    __android_log_print(ANDROID_LOG_ERROR, "CS_STALL", "%s", line);
}

void watchdog_loop() {
    for (;;) {
        std::this_thread::sleep_for(std::chrono::milliseconds(kStallCheckMs));
        std::vector<std::pair<long long, std::pair<pid_t, std::string>>> stale;
        {
            std::lock_guard<std::mutex> g(g_mtx);
            long long t = sw_now_ms();
            for (auto& kv : g_ops) {
                long long age = t - kv.second.start_ms;
                if (age > kStallMs) stale.push_back({age, {kv.first, kv.second.what}});
            }
        }
        if (stale.empty()) { g_reported = false; continue; }
        if (g_reported) continue;
        g_reported = true;
        for (auto& it : stale)
            cs::log_warn("[CS_STALL] 线程 " + std::to_string((int)it.second.first) +
                     " 卡在「" + it.second.second + "」已 " +
                     std::to_string(it.first / 1000) + "s → 抓栈并中断(EINTR 自愈)");
        for (auto& it : stale)
            // 登记的是 TID（见 op_begin 的 gettid()），必须用 tgkill 按 TID 定向发信号。
            // 不能用 pthread_kill：它要求 pthread_t（不透明句柄），把 TID 传进去会被
            // bionic 判为致命错误直接 abort（实测 Android 14 模拟器：离网黑洞下
            // SMB connect 挂起 → 看门狗触发 → "invalid pthread_t passed to pthread_kill"
            // → SIGABRT 整进程退出——v0.3.9 修复）。tgkill 对已退出的 TID 返回 ESRCH，
            // 无害。
            syscall(SYS_tgkill, getpid(), it.second.first, SIGUSR2);
    }
}
} // namespace

void ensure_watchdog() {
    if (g_wd_started.exchange(true)) return;
    struct sigaction sa{};
    sa.sa_handler = stack_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;   // 不设 SA_RESTART：让 poll() 得到 EINTR（这是自愈的一部分）
    sigaction(SIGUSR2, &sa, nullptr);
    std::thread(watchdog_loop).detach();
    cs::log_info("stall watchdog 已启动（12s 阈值，SIGUSR2 抓栈）");
}

void op_begin(const std::string& what) {
    pid_t tid = gettid();
    std::lock_guard<std::mutex> g(g_mtx);
    g_ops[tid] = OpRec{sw_now_ms(), what};
}

void op_end() {
    pid_t tid = gettid();
    std::lock_guard<std::mutex> g(g_mtx);
    g_ops.erase(tid);
}
} // namespace smbwatch


#endif // CS_HAS_SMB2
