# ComicShelf for Android

**NAS 直连的大书库漫画阅读器 · 局域网整书机翻 · 离线 E-Hentai 标签数据库。**

三件事，一个 App：

- 📚 **大书库**：SMB 直连 NAS，SQLite 索引 + 虚拟化分页（十万级书目流畅滚动，百万级不惧）、
  增量扫描、多书库、目录树、搜索、收藏 / 阅读状态 / 标签 / 书签、**浏览历史**（跨库最近 50 本）
- 🈶 **整书后台机翻**：书库页一键为本册启用 → 局域网内的电脑跑 CTBD 检测 + PaddleOCR-VL 识别 +
  Hy-MT2 翻译 → 手机只上传整页并排版，边读边出、随时停续、跨会话续传
- 🏷 **离线 E-Hentai 标签数据库**（v0.5 起）：导入可选的元数据包（约 595MB）→
  **348.8 万图库 / 4388 万 tag 关联**全在本地；按 tag 组合检索自己的书库（"本地命中 N 本"）、
  按书看 EH 标签、标签页直接管理书籍——**全程离线，不依赖官方 API**

## 亮点速览

- **标签检索就是浏览器**：命名空间芯片 + 频次排序 + 中文名；多 tag AND 组合；结果页直接
  打开阅读 / 翻译 / 收藏 / 标记 / 导出（长按封面呼出与书库同源的操作面板）
- **标签面板可反向导航**：读一本书时打开「E-Hentai 标签…」，点任一 tag = 追加进检索条件
- **匹配是本地算的**：导入数据包后自动匹配你的书库（实测 44 万本 ~1.5 分钟），此后检索毫秒级
- **阅读器**：翻页（左右分区点击）、双页对开、RTL（日漫）、捏合缩放 + 双击、旋转、进度记忆、
  三级封面缓存；v0.5.1 起页位图所有权归位——任何页面尺寸的书都不会出现"闪烁重刷"
- **格式**：zip/cbz、rar/cbr、目录型；stb / WebP / AVIF 解码链
- **网络**：SMB2/SMB3 直连（多共享、多凭据）；实时同步（CHANGE_NOTIFY）可用时自动增量

## 截图

| 书库 / 书架（扫描统计、封面、阅读进度） | 翻译设置（局域网后端：地址 / 测试 / 缓存用量） |
|---|---|
| ![书库](screenshots/01-library-shelf.jpg) | ![翻译设置](screenshots/02-settings-backend.jpg) |

| 目录树（多级目录 + 每目录书目数） | 书籍操作（长按：翻译 / 阅读 / 封面 / 标记 / 标签） |
|---|---|
| ![目录树](screenshots/03-folder-tree.jpg) | ![书籍操作](screenshots/04-book-actions.jpg) |

## E-Hentai 标签数据库（v0.5 核心新增）

**数据包从哪来（二选一）**：

1. **桌面构建器自建（推荐）**：用仓库内 [`ehmeta-builder/`](ehmeta-builder/README.md)（Mac/PC 桌面 GUI，
   纯 Python 标准库）→ 一键从已验证的公开来源抽取最新元数据 → 产出 `ehmeta.db.zip`；
   支持填代理端口（如 Clash Verge 混合端口）；每期仅需下载变化的源（~567MB）
2. **他人分发的数据包**：Release 附件提供一份构建好的 `ehmeta.db.zip`（仅元数据：标题/标签，
   **不含任何图像**；来源与许可见 [NOTICE](NOTICE)）

**导入**：把 `ehmeta.db.zip` 放到任一位置（`/sdcard/ComicShelfModels/ehmeta/`、应用外部目录/ehmeta/、
或设置里自定义目录）→ App 设置 →「E-Hentai 数据」→ 导入（约 3 分钟）+ 自动匹配。
设备占用约 1.7GB（元数据库 1.42GB + 键索引 98MB + 匹配结果 149MB），**完全可选、卸载即清、不影响其他功能**。

## 架构

```
┌─ 手机 ComicShelf ────────────────────────────────────────────────┐
│ 书架/阅读器 (Kotlin + Compose)                                    │
│   │ ① 整页 PNG 上传              ② BT Sakura 阶梯（机制在 App 侧） │
│   │   POST /ocr_page             POST /chat {system,user,…}       │
│   ▼                                 ▼                            │
│ 设备档案（框+原文+译文 JSON）── 离线重渲染（墨迹擦除/排版，无网络） │
│ 本地库（library.db）：书目/进度/收藏 + EH 匹配（match.db）         │
└───────────────┬───────────────────────────┬──────────────────────┘
                │ LAN (Wi-Fi 或 adb reverse) │
┌───────────────▼───────────────────────────▼──────────────────────┐
│ 任意电脑（Win/Linux/macOS）: backend/  (FastAPI)                  │
│   ① CTBD onnx 检测(CPU/CUDA) → 后处理链 → PaddleOCR-VL(CUDA/MPS)  │
│   ② /chat 原样转发 ──────────────┐                                │
│   sha1 整页缓存                   ▼                                │
│                     llama-server :8080 (llama.cpp, Metal/CUDA/CPU) │
│                     Hy-MT2-1.8B-Q4_K_M（OpenAI 兼容 /v1）          │
└───────────────────────────────────────────────────────────────────┘
```

后端可一键部署（`backend/deploy.py`，三平台同一入口、模型自动下载、LLM 三层降级），
App 端只认「后端地址」这一个配置项。详见 [backend/README.md](backend/README.md)。

## 目录结构

```
.
├── app/                      # 安卓应用（Kotlin + Compose + 原生 C++ 核心/JNI）
│   └── src/main/cpp/         #   core(数据库/扫描/vfs/压缩包/图像/翻译客户端) + JNI
├── backend/                  # 局域网 OCR/翻译后端（Python, 一键部署）
├── ehmeta-builder/           # E-Hentai 元数据桌面构建器（Mac/PC，Python 纯标准库）
├── build.gradle.kts / settings.gradle.kts
└── third_party/…             # 见 app/src/main/cpp/third_party（Vendored 依赖）
```

## 构建（App）

```bash
# 已知可用工具链组合（重要）：
#   Gradle 9.5.x + JDK 17~21 + AGP 8.13    ← Gradle 9.6+ 与 AGP 8.13 不兼容；JDK 26 的 jlink 会挂
export JAVA_HOME=/path/to/jdk-21
gradle :app:assembleDebug          # 或直接用 Android Studio 打开本目录
```

`local.properties` 需指向你的 Android SDK（`sdk.dir=…`，已被 .gitignore 忽略）。
首次构建会编译 vendored 的 C++ 第三方库（sqlite/ncnn/aom/libavif/webp/unrar/…），耗时数分钟。
**发布构建**：`assembleRelease` 后用 `llvm-strip --strip-debug` 处理 `lib/*.so`（去除 DWARF 中的
构建机路径）再签名——发布纪律见 `PUBLISH.md §21`。

### 可选依赖（缺失会自动裁剪，不影响"局域网后端翻译"主线）

| 组件 | 位置 | 缺失时 |
|---|---|---|
| libsmb2 头/运行库（NAS 支持） | `app/src/main/cpp/third_party/libsmb2` + `jniLibs/…/libsmb2.so` | 仓库已自带 |
| 端侧 NPU OCR（高通 QNN SDK，**专有不可再分发**） | `app/src/main/cpp/ocr/qnn_inc/`（自备） | 该路径裁剪；后端模式不受影响 |
| 端侧离线翻译（llama.cpp，MIT） | `<repo 上级>/deps/llama.cpp` 或 `-DCS_LLAMA_SRC=` | 该路径裁剪；后端模式不受影响 |

## 后端

见 [backend/README.md](backend/README.md)：拷贝目录 → `python deploy.py`（自动建 venv、按平台装依赖、
从 HuggingFace 下载三个模型（含国内镜像自动探测）、启动服务）。

**Windows（NVIDIA）一键部署**：见 [backend/cs-deploy/](backend/cs-deploy/)——双击 `deploy.bat`，
七阶段全自动（Python / 依赖 / llama.cpp 二进制 / 模型 / 启动健康检查，全程幂等可断点续跑），
真机实测约 40 分钟全自动；坑位、根因与运维手册见
[WINDOWS_DEPLOY_RECORD.md](backend/cs-deploy/WINDOWS_DEPLOY_RECORD.md)。

> **分发形态（重要）**：部署脚本的"从零自动下载仓库"兜底要求上游仓库可公开访问；
> 当前以**源码随包**形式分发——把 `comicshelf-android\` 整个文件夹与 `cs-deploy\`
> 放在同一目录（或其父目录）一起拷贝即可。脚本的同目录/父目录扫描原生支持该形态，
> 无需任何改动。

## 元数据构建器（桌面，可选）

见 [ehmeta-builder/README.md](ehmeta-builder/README.md)：在 Mac/PC 上构建 **E-Hentai 元数据数据库**
（产出 `ehmeta.db.zip`，供 App 设置页导入）。手动运行、自动从公开来源取数、支持填代理端口
（如 Clash 混合端口 `http://127.0.0.1:7897`）；纯 Python 标准库实现，含 CLI 与图形界面。
与 App 共用同一套经对账验证的构建管线（计数/键索引与发布包**逐字节一致**）。

## 许可与致谢

- 本项目以 **GPL-3.0** 发布（见 [LICENSE](LICENSE)）。原因：翻译机制（Sakura 模板、两级阶梯、
  重复检测、引号处理）移植自 BalloonTranslator（GPL-3.0，上游仓库现已不可公开访问；
  本项目基于其 0.9 版源码移植），检测后处理链亦以其为对照；衍生作品需保持同一许可。
- 第三方组件与其许可（ncnn / aom / libavif / libwebp / sqlite / stb / miniz / unrar / libsmb2 /
  llama.cpp / onnxruntime / AndroidX 等）见 [NOTICE](NOTICE)。
- **E-Hentai 元数据的数据来源与署名**（tag 中文名来自
  [EhTagTranslation](https://github.com/EhTagTranslation/Database)，**CC BY-NC-SA 4.0**；
  其余来源见 [NOTICE](NOTICE) 与产物 `meta.json.licenses`）。
- 模型权重（CTBD、PaddleOCR-VL-For-Manga、Hy-MT2）**不在本仓库内**，由 `backend/fetch_models.py`
  从 HuggingFace 下载；使用请遵循各模型卡声明的许可。

## 免责声明

本项目是阅读器与翻译辅助工具：不提供、不附带、不托管任何漫画内容；
请遵守当地法律法规与内容版权，仅将翻译功能用于个人学习与研究。
