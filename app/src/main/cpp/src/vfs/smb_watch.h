// SMB2 实时变更监视（CHANGE_NOTIFY + WATCH_TREE）。
//
// 为什么需要它：目录 mtime 只反映“直接子项”的增删，深层新增文件不会改上层
// 目录的 mtime，所以任何“mtime 没变就整棵跳过”的增量都会静默漏书；而百万级
// 书库每次都全量遍历又要几十分钟。SMB2 的服务端事件是唯一既正确又快的增量
// 手段：订阅一次、递归（watch tree）生效，服务端在有变更时推送路径+动作。
#pragma once
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "vfs/vfs.h"

namespace cs::vfs {

struct SmbChange {
    std::string rel;  // 共享根相对路径（'/' 开头）
    int action = 0;   // 1=新增 2=删除 3=修改 4=改名(旧名) 5=改名(新名)
};

// 回调在工作线程上调用，每次一批（内部已做事件去重/合并）。
class SmbWatcher {
public:
    using Callback = std::function<void(const std::vector<SmbChange>&)>;
    virtual ~SmbWatcher() = default;
    virtual void stop() = 0;
    // 是否还在监视（服务端不支持 / 连接断开后为 false）
    virtual bool alive() const = 0;
};

// dir_rel 为共享内相对路径（'/' 开头，'/' = 共享根）。
// 服务器不支持 CHANGE_NOTIFY 时返回 nullptr 并填充 err。
std::shared_ptr<SmbWatcher> make_smb_watcher(const SmbConfig& cfg, const std::string& dir_rel,
                                             SmbWatcher::Callback cb, std::string* err);

} // namespace cs::vfs
