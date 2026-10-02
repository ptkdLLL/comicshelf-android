// VFS 工厂与凭据注册：把 "smb://host[:port]/share/path" 和本地绝对路径
// 统一解析成 (Vfs, 相对路径)。
#include "vfs/vfs.h"

#include <algorithm>
#include <map>
#include <mutex>

#include "util/logger.h"

namespace cs::vfs {

// vfs_smb.cpp 提供（内部会做一次建连探测）
std::shared_ptr<Vfs> make_smb_vfs_impl(const SmbConfig& cfg, std::string* err);

namespace {

std::mutex g_mtx;
std::map<std::string, SmbConfig> g_creds;              // key: host|share
std::map<std::string, std::weak_ptr<Vfs>> g_shares;    // key: host|share|user
std::shared_ptr<Vfs> g_local;

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    return s;
}

std::string cred_key(const std::string& host, const std::string& share) {
    return lower(host) + "|" + share;
}
std::string share_key(const SmbConfig& c) {
    return cred_key(c.host, c.share) + "|" + c.user;
}

// "smb://host[:port]/share[/dir...]" → cfg + vfs 内相对路径
bool parse_smb_path(const std::string& path, SmbConfig* cfg, std::string* rel,
                    std::string* err) {
    std::string rest = path.substr(6); // 去掉 "smb://"
    auto slash = rest.find('/');
    if (slash == std::string::npos || slash == 0) {
        if (err) *err = "SMB 路径缺少共享名: " + path;
        return false;
    }
    cfg->host = rest.substr(0, slash);
    std::string after = rest.substr(slash + 1); // "share[/dir...]"
    auto s2 = after.find('/');
    cfg->share = (s2 == std::string::npos) ? after : after.substr(0, s2);
    std::string sub = (s2 == std::string::npos) ? std::string() : after.substr(s2);
    if (cfg->share.empty()) {
        if (err) *err = "SMB 路径缺少共享名: " + path;
        return false;
    }
    *rel = sub.empty() ? "/" : sub;
    return true;
}

} // namespace

bool is_smb_path(const std::string& p) { return p.rfind("smb://", 0) == 0; }

void register_smb_credentials(const SmbConfig& cfg) {
    std::lock_guard<std::mutex> g(g_mtx);
    const std::string key = cred_key(cfg.host, cfg.share);
    auto it = g_creds.find(key);
    const bool changed = it != g_creds.end() &&
                         (it->second.user != cfg.user ||
                          it->second.password != cfg.password ||
                          it->second.domain != cfg.domain);
    g_creds[key] = cfg;
    if (changed) {
        // 凭据变了：丢弃已认证的旧会话，下次取用时按新凭据重连。
        for (auto sit = g_shares.begin(); sit != g_shares.end();) {
            if (sit->first.rfind(key + "|", 0) == 0) sit = g_shares.erase(sit);
            else ++sit;
        }
    }
}

std::shared_ptr<Vfs> make_smb_vfs(const SmbConfig& cfg, std::string* err) {
    {
        std::lock_guard<std::mutex> g(g_mtx);
        auto it = g_shares.find(share_key(cfg));
        if (it != g_shares.end()) {
            if (auto v = it->second.lock()) return v;
        }
    }
    std::shared_ptr<Vfs> v = make_smb_vfs_impl(cfg, err);
    if (!v) return nullptr;
    std::lock_guard<std::mutex> g(g_mtx);
    g_shares[share_key(cfg)] = v;
    return v;
}

bool smb_path_config(const std::string& path, SmbConfig* cfg, std::string* rel,
                     std::string* err) {
    if (!parse_smb_path(path, cfg, rel, err)) return false;
    std::lock_guard<std::mutex> g(g_mtx);
    auto it = g_creds.find(cred_key(cfg->host, cfg->share));
    if (it == g_creds.end()) {
        if (err)
            *err = "SMB 凭据未注册: " + cfg->host + "/" + cfg->share +
                   "（请先在书库设置里填写账号密码）";
        return false;
    }
    cfg->user = it->second.user;
    cfg->password = it->second.password;
    cfg->domain = it->second.domain;
    return true;
}

std::shared_ptr<Vfs> vfs_for_path(const std::string& path, std::string* rel,
                                  std::string* err) {
    if (!is_smb_path(path)) {
        if (rel) *rel = path;
        std::lock_guard<std::mutex> g(g_mtx);
        if (!g_local) g_local = make_local_vfs();
        return g_local;
    }
    SmbConfig cfg;
    std::string sub;
    if (!smb_path_config(path, &cfg, &sub, err)) return nullptr;
    auto v = make_smb_vfs(cfg, err);
    if (!v) return nullptr;
    if (rel) *rel = sub;
    return v;
}

} // namespace cs::vfs
