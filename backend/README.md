# cs-backend — ComicShelf 漫画 OCR/翻译局域网后端

> 2026-10-03 v2：**跨平台一键部署**（Windows / Linux / macOS 同一入口 `deploy.py`）。
> 把**文本检测 → OCR 识别 → 翻译**从手机移到局域网计算机上执行；手机端只做整页上传、
> BT Sakura 阶梯编排与排版渲染。实测（M 系 Mac）把端侧全链 **104.6s/页压到 16.6s/页**
> （大页）、**2.3s/页**（小页），整本 273 页后台批量翻译 5~10s/页。

---

## 0. 拓扑

```
┌─ 手机 ComicShelf (backend 引擎模式) ─────────────────────────────────────────┐
│ ReaderViewModel ── BookTranslateJob（整本后台队列, 可见页插队/暂停/续传）       │
│   │ ① 整页 PNG 上传                       ② BT Sakura 阶梯（机制在 App 侧）   │
│   │   POST /ocr_page                      POST /chat {system,user,…}          │
│   ▼                                          ▼                                │
│ 设备档案（框+原文+译文 JSON）── 离线重渲染（墨迹擦除/排版, 无网络）              │
└───────────────┬──────────────────────────────┬───────────────────────────────┘
                │ LAN (Wi-Fi 或 adb reverse)    │
┌───────────────▼──────────────────────────────▼───────────────────────────────┐
│ 服务机（任意 Win/Linux/Mac）: cs-backend  :8787  (uvicorn/FastAPI)            │
│   ① CTBD onnx 检测(CPU/CUDA) → 后处理链 → 逐块 PaddleOCR-VL(CUDA/MPS/CPU)     │
│   ② /chat 原样转发 ──────────────────────────┐                                │
│   sha1 整页缓存(RAM+磁盘, fresh=1 可绕)      │                                │
└──────────────────────────────────────────────▼───────────────────────────────┘
                              llama-server :8080 (llama.cpp; Metal/CUDA/CPU)
                              Hy-MT2-1.8B-Q4_K_M（OpenAI 兼容 /v1）
                              或任意外部 OpenAI 兼容服务（ollama/LM Studio…）
```

**职责分工的设计理由**（与"为什么不全放服务端"）：

| 环节 | 在哪 | 为什么 |
|---|---|---|
| 检测 + OCR | **服务机**（本服务） | 端侧 VL OCR 68s/24 块（NPU 448² 画布逐块解码）是最大瓶颈；服务机 GPU/CPU 快一个数量级，且用 BT 官方同款权重 |
| 翻译**机制**（payload「」/重复检测/两级阶梯/逐行兜底/引号剥离） | **App 侧**（Kotlin） | 2026-10-02 刚在真机逐条验收；保持单一实现，服务端只做传输（`/chat` 转发），不双份维护 |
| 排版渲染（墨迹擦除/竖排/字号拟合） | **App 侧** | 已验收且只占 ~4s/页；服务端渲染（BT Qt 引擎）成本高收益低 |
| 整本调度（队列/插队/暂停/续传/进度） | **App 侧**（`BookTranslateJob`） | 与阅读器/书架/设备档案天然一体；服务端保持无状态（除缓存） |

---

## 1. 快速开始（三平台）

### 1.1 一键（推荐）

把 `cs_backend/` 整个目录拷到目标机器（**不需要 venv/、models/、cache/**），然后：

| 平台 | 操作 |
|---|---|
| Windows | 双击 `start.bat`（或命令行 `start.bat`）；**NVIDIA 机器推荐 [cs-deploy/](cs-deploy/) 一键包**（自动装 Python/依赖/llama 二进制/模型，无需任何前置，实测 40 分钟全自动） |
| Linux / macOS | `./start.sh` |

首次运行自动完成：**建 venv → 按平台装依赖（CUDA/CPU/MPS 自动）→ 从 HuggingFace 下载三个模型
（~3.1GB；断点续传）→ 启动 llama-server + cs-backend**。
**网络全程自动选源**：官方不通时自动切国内镜像（模型 → hf-mirror.com；pip → 清华/阿里；
torch 轮子 → 阿里/SJTU），下载失败自动换端点重试；`python3 deploy.py doctor` 会打印"当前会用的源"，
也可用配置项/环境变量固定（§2）。中途 Ctrl+C 可中断，重跑续传。全程无需手工前置。

跑起来后（会打印手机 App 要填的地址）：

```
[deploy] 手机 App 后端地址: http://192.168.x.x:8787  (设置 -> 翻译 -> 后端)
```

### 1.2 分步（可选）

```bash
python3 deploy.py setup          # 只准备环境(装依赖+下模型)
python3 deploy.py start          # 只启动
python3 deploy.py status         # 状态 + /health JSON
python3 deploy.py logs [server|llama]
python3 deploy.py stop | restart
python3 deploy.py doctor [--full]   # 环境体检(--full 含管线自测: 合成图 detect+OCR)
python3 deploy.py fetch-models [--mirror]   # 只下模型(国内加 --mirror)
```

`./run.sh` 保留为兼容入口（等价 `python3 deploy.py "$@"`，旧 `start|stop|status|log` 全可用）。

### 1.3 前置条件

| 项 | 要求 |
|---|---|
| Python | **3.10 ~ 3.12 推荐**（3.13+ 会警告，可能与 PaddleOCR-VL 自定义代码不兼容）。Windows 安装时勾选 Add to PATH；Linux 可能需 `apt install python3-venv` |
| 磁盘 | ≥ 8GB（模型 3.1GB + venv 约 4GB + CUDA 版 torch 另加 ~3GB） |
| 内存 | ≥ 8GB（两服务常驻 ~5.2GB：OCR 2.6GB + llama 2.6GB；见 §7 内存行） |
| 网络 | 首次需能访问 HuggingFace / PyPI / pytorch 轮子源。**官方不通会自动切国内镜像**（模型→hf-mirror，pip→清华/阿里，torch→阿里/SJTU）并自动换端点重试；也可用 `--mirror` / `HF_ENDPOINT` / `PIP_INDEX_URL` / `CS_TORCH_INDEX` 强制指定 |
| GPU | 可选。NVIDIA→CUDA、Apple Silicon→MPS 自动启用；纯 CPU 能跑但慢（见 §6.2） |
| 防火墙 | 放行入站 **8787/tcp**（Windows 首次会弹窗选"允许"；macOS 系统设置→网络→防火墙；Linux `ufw allow 8787`） |

### 1.4 手机侧

设置 →「引擎：本机后端」→ 后端地址填 `http://<服务机局域网IP>:8787`
（USB 调试可用 `adb reverse tcp:8787 tcp:8787` + `http://127.0.0.1:8787`）。
**App 侧零改动**：同一端口、同一 HTTP 契约，换后端只是换个 IP。

### 1.5 验证

```bash
curl -s http://127.0.0.1:8787/health
# {"ok":true,"pipeline":"bt-mac-v1","device":"mps","det_providers":["CPUExecutionProvider"],
#  "det_ms":249.0,"ocr":{"ready":true,"load_ms":1838.0,"error":""},
#  "llm":{"ok":true,"url":"http://127.0.0.1:8080"}}

# 整页 OCR（PNG/JPEG 裸字节）
curl -s -X POST --data-binary @page.png http://127.0.0.1:8787/ocr_page | head -c 400

# 翻译（BT Sakura 0.9 payload 形态，由 App 正常发出；手动验证用）
curl -s -X POST http://127.0.0.1:8787/chat -H 'Content-Type: application/json' \
  -d '{"system":"你是一个轻小说翻译模型…","user":"将下面的日文文本翻译成中文：「テスト」",
       "max_tokens":1024,"temperature":0.1,"top_p":0.3,"frequency_penalty":0.05}'
```

---

## 2. 配置（`config.env`，首次 start 自动生成）

> 优先级：命令行参数 > 环境变量 > `config.env` > 代码默认。改完 `restart` 生效。

| 键 | 默认 | 说明 |
|---|---|---|
| `CS_BACKEND_PORT` | `8787` | cs-backend 端口（App 要连的） |
| `LLAMA_PORT` | `8080` | 本机 llama-server 端口（仅 127.0.0.1） |
| `LLAMA_URL` | 空 | **空=本机托管 llama**；填 `http://host:port` 则用外部 OpenAI 兼容服务（ollama / LM Studio / Sakura），不再起本地 llama |
| `CS_OCR_DEVICE` | 空 | 空=自动 `cuda > mps > cpu`；可强指 `cuda/mps/cpu` |
| `CS_MODELS_DIR` | 空 | 空=`./models`；也可指向别处已有模型 |
| `CS_LLAMA_BIN` | 空 | 空=自动找（`PATH` → `./bin/` → llama-cpp-python） |
| `CS_LLAMA_CACHE_MB` | `1024` | llama-server prompt cache 上限（MiB），0=关。**新版 llama.cpp 此项默认 8192**（实测 RSS 8.9GB→限 1GB 后 ~2.6GB）；保留 1GB 足够复用 BT 系统提示词前缀。老版本 llama-server 无此 flag 时自动跳过 |
| `HF_ENDPOINT` | 空 | 空=自动探测（官方不通走 `https://hf-mirror.com`）；也可显式指定。下载失败会自动换另一个端点重试 |
| `PIP_INDEX_URL` | 空 | 空=自动探测（官方 PyPI 不通自动切清华 → 阿里镜像）；也可显式指定（标准 pip 变量，影响 venv 内所有 pip 操作） |
| `CS_TORCH_INDEX` | 空 | 空=自动探测（官方 pytorch 源不通自动切阿里/SJTU 镜像）；也可显式指定，如 `https://download.pytorch.org/whl/cu121` |
| `EXTRA_LLAMA_ARGS` | 空 | 追加给 llama-server 的参数（如 `--threads 12`） |

其它可用的环境变量：`CS_DET_PROVIDERS`（覆盖检测器 onnxruntime providers，如 `CUDAExecutionProvider,CPUExecutionProvider`）。
`PYTORCH_ENABLE_MPS_FALLBACK=1` 由部署器自动设置（MPS 未实现算子回退 CPU 而非报错）。

**LLM 三层降级**（自动按序尝试，全部失败时 `start` 会打印指引）：
1. 本机 `llama-server` 二进制：`CS_LLAMA_BIN` → `./bin/llama-server[.exe]` → `PATH`
   （macOS `brew install llama.cpp`；Windows 下载官方 release 解压 `llama-server.exe` 到 `bin/`；Linux 包管理器/官方 release/源码构建）
2. `llama-cpp-python`（免装二进制）：`venv/bin/pip install llama-cpp-python`
   （注：CPU/已编译加速；chat 模板按 GGUF 元数据，本仓库实测以第 1 种为准）
3. 外部端点：`config.env` 设 `LLAMA_URL`（如 ollama `http://127.0.0.1:11434`，其 /v1 兼容）

---

## 3. 模型与运行时

标准布局（`CS_MODELS_DIR` 可改根；`python deploy.py fetch-models` 自动下载）：

```
models/
├── CTBD/detector.onnx                  # ogkalu/comic-text-and-bubble-detector  (161MB)
├── PaddleOCR-VL-For-Manga/             # jzhang533/PaddleOCR-VL-For-Manga       (1.8GB)
│   └── model.safetensors + 自定义 modeling/processor .py
└── hymt/Hy-MT2-1.8B-Q4_K_M.gguf        # tencent/Hy-MT2-1.8B-GGUF               (1.1GB)
```

| 环节 | 模型 | 运行时 | 加速（自动探测） | 实测（M 系 Mac） |
|---|---|---|---|---|
| 检测 | **CTBD**（RT-DETR-V2） | onnxruntime | CPU（有 `onnxruntime-gpu` 时自动 CUDA EP） | **123~135ms/页**（含前后处理） |
| OCR | **PaddleOCR-VL-For-Manga** | torch 2.x + transformers **4.57.6**（= BT 官方 pin，勿升级）+ einops；`trust_remote_code=True`，fp16（CUDA/MPS）/fp32（CPU） | **MPS**（Apple GPU）；NVIDIA→**CUDA**；否则 CPU（自动降级并打日志） | **~0.15-0.9s/块**；单页 1.3~9.2s |
| 翻译 | **Hy-MT2-1.8B-Q4_K_M**（腾讯 Hunyuan-MT2） | llama.cpp `llama-server`（或 llama-cpp-python） | **Metal**（`-ngl 99`）/ CUDA / CPU（`-ngl 0`） | **130~200 tok/s**；25 行整页 ≈3s |

**版本要点**
- 模型均可直接从 HuggingFace 获取（上表仓库名），下载带 md5/尺寸校验（GGUF 基准 md5 `436f3ec2…`，为实测部署同款）。三份模型均以**实体文件**存于 `models/`（2026-10-03 起后端自包含，不再依赖外部目录/软链）。
- `transformers==4.57.6` 是 BT 自己的 pin；更高版本模型自定义代码会挂（实测 5.x 需打 rope shim）。
- Python venv：`./venv`（推荐 3.12）。关键包：torch / torchvision（部署器按平台选 CPU/CUDA 轮子）、transformers 4.57.6、onnxruntime(-gpu)、opencv-python(-headless on Linux)、fastapi、uvicorn、numpy、pillow、einops、sentencepiece、huggingface_hub。

---

## 4. HTTP 协议

### GET `/health`
| 字段 | 含义 |
|---|---|
| `ok` | 服务活着 |
| `pipeline` | 管线标签 `bt-mac-v1`（App 用它校验设备档案有效性；**换版本号=旧档自动失效**） |
| `device` | OCR 设备：`cuda` / `mps` / `cpu` |
| `det_providers` | 检测器实际 onnxruntime providers（确认 CUDA 是否真的启用） |
| `det_ms` | CTBD onnx 加载耗时 |
| `ocr.ready / ocr.load_ms / ocr.error` | OCR 模型就绪状态（启动后**后台线程加载**，~2.5s；未就绪时 /ocr_page 返 503） |
| `llm.ok / llm.url` | 探测 llama-server `/health`（1.5s 超时） |

### POST `/ocr_page`（`?fresh=1` 可选）
- 请求体：整页图像**裸字节**（PNG 或 JPEG，`Content-Type` 不限）。App 发 PNG（无损，B/W 页 ~1-3MB）。
- 200 响应：

```jsonc
{
  "w": 2400, "h": 3377,                     // 页面像素尺寸
  "pipeline": "bt-mac-v1",
  "boxes": [                                 // 检测框（页面坐标），与设备端 JSON 同 schema
    {"x0":421,"y0":1806,"x1":626,"y1":2162,
     "vertical":true,                        // h > 1.5w（与设备端同规则）
     "score":0.94,                           // 置信度
     "label":2,                              // 0=气泡, 1/2=文字（App 侧跳过 0）
     "bubble":false}                         // 气泡条目本身为 true
  ],
  "texts": ["肚子里充盈着精液的现役学生会长…", "…"],   // 与 boxes **等长**；气泡/失败段=""
  "timings": {"det":130.1,"ocr":6181.2,"blocks":15,"total":6381.0},
  "cached": false,                           // true = 命中 sha1 整页缓存（timings 为首次值）
  "ms": 6381.2                               // 本次请求耗时
}
```
- 错误：`400` 空 body；`503` OCR 未就绪（含 loading/错误文本）；`500` 解码失败等（`{"error":"…"}`）。
- **缓存**：按请求字节 sha1；RAM 64 条 + 磁盘 `cache/<sha1>.json`；`?fresh=1` 绕过读（仍写）。
  App 的「重译当前页」用 fresh=1；整本后台队列 fresh=false。
- 并发：OCR 单锁串行（模型非线程安全）；多请求自然排队。

### POST `/chat`
把 App 的翻译请求原样转发给 llama-server（**OpenAI 兼容**），服务端不做任何 prompt 逻辑：

```jsonc
// 请求
{"system":"…BT 0.9 模板…", "user":"将下面的日文文本翻译成中文：「…」\n「…」",
 "max_tokens":1024, "temperature":0.1, "top_p":0.3, "frequency_penalty":0.05}
// temperature/top_p/frequency_penalty 由 App 的 BT 风格阶梯给出：
//   precise(0.1/0.3/0.05) → normal(0.3/0.3/0.2) → aggressive(0.3/0.3/0.3)
// 200 响应
{"content":"…译文…", "ms":246.2, "usage":{...llama-server usage...}}
```
- 错误：`400` bad json；`502` llama-server 非 200 或连接异常（App 侧据此走阶梯/兜底）。
- 服务端转发超时 180s（够 1024 token 最慢情形）。

---

## 5. 管线细节（`pipeline.py`）

### 5.1 检测：CTBD onnx（与 BT `detector_ctbd.py` 同构）
预处理（BT 原样，含其**通道约定**）：
```
cv2.resize 640×640(INTER_LINEAR, 非 letterbox——直接 squash)
→ cv2.cvtColor(BGR2RGB)          # BT 源码如此；设备端 QNN 版同约定，勿"修正"
→ /255, NCHW float32；orig_target_sizes=[[w,h]] int64
```
后处理链（**移植自设备端已验证的 `ctbd_qnn.cpp`**，阈值都是 **640 squash 空间**量纲）：

| 步骤 | 参数 | 说明 |
|---|---|---|
| 1 置信过滤 | `score ≥ 0.3` | 按 label 分两类：0=气泡 / 1,2=文字 |
| 2 极小框过滤 | 宽、高 > **5px** | BT `filter_bounding_boxes` |
| 3 去重合并 | IoU ≥ **0.7** 连通分量并成 **union 框**（保留最大分） | BT `merge_duplicate_boxes`（不是丢弃） |
| 4 区域框让位 | 框含 **≥2** 个"段落级"子框（子被 0.8 包含 且 子宽≥页宽 12%、子高≥页高 3.75%）→ 丢父保子 | **本移植的增项**（设备端 §E2 修复）：纯 BT 语义在密排人物介绍页只留标题+整页大框 |
| 5 包含删除 | 同类交面积/inner ≥ **0.8** 删内框；**面积降序** keep 扫描 | BT `remove_contained_boxes`；升序写反是设备端修过的真 bug |
| 6 输出 | ×(w/640, h/640) 回页坐标；`vertical = h > 1.5w` | 与设备端 JSON 一致；**输出顺序 = 气泡类在前 + 类内面积降序** |

### 5.2 ⚠️ 坐标空间坑（初版踩过）
- **设备 QNN 版**输出 640 空间，由 native 乘 `sx=w/640` 回页坐标；
- **本服务 onnx 版**经 `orig_target_sizes` **已经回页坐标**。初版又乘了一次 → 框飞到 5× 页面外。
- 正确做法：先 `/sx, /sy` **归一 640 空间跑后处理链**（阈值量纲），再乘回输出。

### 5.3 OCR：PaddleOCR-VL（与 BT `ocr_paddleVL_manga.py::ocr_img` 同构）
- 逐块（**不用批量**——实测 MPS 批量无速度收益且左 padding 配对错乱 0/10，见 §6）；
- 裁剪 = 纯 bbox（与设备端 `cropBox` 一致，无 padding）；
- 块内 `max(h,w) > 1024` 先 `INTER_AREA` 缩到 1024；
- prompt = BT 原样：`messages=[{user: [image, "OCR:"]}]` + `apply_chat_template(add_generation_prompt=True)`；
- 解码：**greedy**（`do_sample=False`），`max_new_tokens=512`（BT 参数默认），`use_cache=True`；
- 启动预热：加载后跑一张 64×64 合成图（MPS 首次 kernel 编译 ~2.8s，避免吃在第一张真页上）；
- 只对 `label != 0` 的文字框跑；气泡条目 `texts` 留空。

### 5.4 缓存
- 键：请求字节 **sha1**（App 每次上传同一页的 PNG 是确定性的 → 稳定命中）。
- RAM：`dict` ≤64 条；磁盘：`cache/<sha1>.json`（常驻，无淘汰——量级：整本 273 页 ≈1~2MB；手工清理 `rm cache/*.json`）。
- `?fresh=1`：跳过读缓存重跑（仍写回）。语义 = App「重译当前页」。

---

## 6. 性能

### 6.1 实测（2026-10-02/03，M 系 Mac + 魅族 20 真机）
| 场景 | 数据 |
|---|---|
| 检测（onnx CPU） | 123~135ms/页（热态；冷加载 255ms） |
| OCR（MPS fp16） | ~0.15-0.9s/块；15 块页 6.2s；25 块页 9.1s；1 大块小说页 4.0s |
| 翻译（Metal） | 72 tok/0.56s ≈130-200 tok/s；6 行 0.32s；25 行 3.1s |
| 端到端（App 侧整页） | 大页(25块) **16.6s**；小页(6块) **2.3s**；命中服务端缓存 **7.0s**；fresh 重译 ~21s |
| 对照：端侧 NPU 全链 | 同大页 73.8~104.6s（→ 提速 4.4~6.3×；小页 8×） |
| 整本后台（273 页） | 5~10s/页，全书约 25-45 分钟；无字页 0.3~0.5s（缓存命中） |
| 批量 OCR 实验 | 左 padding 一次 generate：**无速度收益**（184 vs 183ms/块）且批次配对错乱（0/10 一致）→ 弃用，逐块顺序 |

### 6.2 其它平台预期（**未真机实测**，按硬件类推）
| 平台 | 预期 | 说明 |
|---|---|---|
| NVIDIA CUDA（如 RTX 3060 12G 以上） | **≥ M 系 Mac** 或更快 | OCR fp16 显存约 4-5GB；`onnxruntime-gpu` 让检测也上 GPU；GGUF 全层 offload |
| Apple Silicon（M1 及以上） | 同 §6.1 | 已被事实选用 |
| x86 纯 CPU（16 核左右） | 检测 ~0.3-1s/页；OCR **每块 2-6s**（fp32）；翻译 **10-30 tok/s** | 整本 273 页预计 **数小时**——建议整本后台队列过夜跑；单页阅读体验会明显变慢。**先跑 `doctor --full` 拿到本机数字再决定** |
| 核显 Windows 笔记本 | 同"纯 CPU"档 | 可将 `LLAMA_URL` 指向更强机器（混合部署） |

---

## 7. 运维

| 项 | 值/做法 |
|---|---|
| 端口 | cs-backend `:8787`（0.0.0.0，局域网可达）；llama-server `:8080`（仅 127.0.0.1，经 /chat 转发） |
| 启停 | `python3 deploy.py start/stop/restart/status/logs`（或 `start.sh` / `start.bat`） |
| 进程管理 | PID 文件在 `pids/`；`stop` 先 SIGTERM 后强杀；POSIX 下无 pidfile 时按命令行特征兜底（兼容早期 run.sh 进程） |
| 配置 | `config.env`（§2）；环境变量可临时覆盖 |
| 日志 | `server.log`（访问日志+启动信息）、`llama_server.log`（含 slot 计时）；超 32MB 启动时自动清空 |
| 内存 | cs-backend RSS ~2.6GB（OCR 模型常驻）；llama-server ~2.6GB（含 1GB prompt cache 上限）；<2GB 的机器建议 `CS_LLAMA_CACHE_MB=512` |
| 启动耗时 | 检测 0.25s（同步）；OCR 1.8~2.6s（后台线程，就绪前 /ocr_page 返 503）；llama 载入 0.3~30s（mmap 冷热差异） |
| USB 调试 | `adb reverse tcp:8787 tcp:8787`，App 填 `http://127.0.0.1:8787` |
| 开机自启（可选） | Linux: 写 systemd unit 调 `deploy.py start`；macOS: launchd/`brew services`；Windows: 任务计划程序。未内置 |
| 防火墙 | 放行 8787 入站（Windows 首次弹窗；macOS 系统设置→网络→防火墙；Linux `ufw allow 8787`） |

**故障排查**

| 症状 | 处理 |
|---|---|
| `start` 报缺模型 | `python3 deploy.py fetch-models`（国内 `--mirror`），或 `CS_MODELS_DIR` 指向已有模型目录 |
| `/health` ocr.ready=false 且 error 非空 | `python3 deploy.py logs` 看尾部；常见=模型目录不存在/venv 包缺失；修完 `restart` |
| `/chat` 502 | llama 没起或被占端口。`python3 deploy.py status`；**注意别开两个 llama-server**（曾遗留重复进程白占 1.2GB：`pgrep -fl llama-server` 清理） |
| llama-server 内存 8GB+ | 新版 llama.cpp prompt cache 默认 8192MiB。config.env 设 `CS_LLAMA_CACHE_MB=1024`（或 512）后 `restart`；日志里 `making room for prompt cache entry` 就是它在淘汰缓存 |
| cs-backend / llama 启动即占 ~5GB | 正常（OCR 模型 2.6GB + llama 模型 2.6GB 常驻）。低内存机器建议只开 backend 或调小 `CS_LLAMA_CACHE_MB` |
| 找不到 llama-server（三平台指引） | `python3 deploy.py start` 会打印三种方案（装二进制 / `pip install llama-cpp-python` / 设 `LLAMA_URL` 用外部 ollama 等） |
| 检测没吃到 CUDA | `/health` 看 `det_providers`：需装 `onnxruntime-gpu`（`setup --cuda` 已含）；仅 CPU 属预期 |
| OCR 掉到 CPU 了 | `/health` 看 `device`：CUDA 机器上应显示 `cuda`；若显示 cpu 看 `logs` 里回退日志（CUDA 驱动/torch 版本不匹配） |
| 国内下载慢/失败 | 默认已自动选源（模型→hf-mirror、pip→清华/阿里、torch→阿里/SJTU）并自动换端点重试；`python3 deploy.py doctor` 打印当前会用的源。仍失败可显式固定：`fetch-models --mirror`、`PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple`、`CS_TORCH_INDEX=...` |
| OCR 输出乱码成片 | 页面裁剪/缩放异常；用 `timing_test.py` 单页复现（同款预处理） |
| 命中缓存但不是最新结果 | 语义如此；App「重译当前页」走 `?fresh=1` |
| 手机连不上 8787 | 同网检查 `adb shell curl -s http://<服务机IP>:8787/health`；被防火墙挡则放行或改用 adb reverse |

---

## 8. 目录结构

```
cs_backend/
├── deploy.py          # 跨平台部署器: setup/start/stop/status/logs/doctor/fetch-models  ← 入口
├── start.sh / start.bat / run.sh   # 平台薄包装(双击/兼容旧入口)
├── cs-deploy/         # Windows(NVIDIA) 独立一键包: deploy.bat/deploy.ps1/README
│                      #   + WINDOWS_DEPLOY_RECORD.md（真机部署归档：坑位/根因/runbook）
├── pipeline.py        # 检测(onnx)+后处理链+OCR(GPU/CPU)+sha1 缓存       ← 核心
├── server.py          # FastAPI: /health /ocr_page /chat                 ← 协议层
├── fetch_models.py    # HF 三模型下载(镜像/续传/md5)
├── requirements.txt   # 依赖(torch/onnxruntime 由 deploy.py 按平台选择)
├── config.env         # 部署配置(首次生成)
├── timing_test.py     # 可行性门/回归：单页 检测+OCR 计时与输出目检
├── models/            # 标准布局实体文件(§3; 可 CS_MODELS_DIR 外置)
├── cache/             # <sha1>.json 整页分析缓存
├── pids/              # 运行时 PID 文件
├── venv/              # Python 依赖(部署器创建)
└── server.log  llama_server.log
```

---

## 9. 已知限制与后续路线

**限制**
- **无鉴权**：假设可信局域网。暴露到更大网络前需加 token。
- **Windows 已真机验证（2026-10-03，RTX 3090 / Win10）**：`deploy.py` 在真实 Windows 上暴露多个阻断点（Python 发现候选表窄/Store stub、llama 二进制无自动获取、系统代理与"镜像直连"冲突、控制台编码），全部案例与解法见 `cs-deploy/WINDOWS_DEPLOY_RECORD.md`；**Windows 用户建议直接用 `cs-deploy/` 一键包**（已修复上述全部问题）。Linux 路径仍未真机验证，先用 `python deploy.py doctor --full` 验环境。
- **缓存无淘汰**：磁盘 `cache/` 只增不删（量级小，可手工清）。
- **无服务端渲染**：排版依赖手机端（有意为之，见 §0）。
- **翻译不可复现**：`/chat` 未固定 seed（llama-server 默认随机）——质量 A/B 或复现个别句时需在转发 payload 里透传 `seed`。
- **OCR 单锁串行**：多手机/多书并发会排队（单用户场景无感）。
- **`llama-cpp-python` 降级档未实测翻译质量**：chat 模板按 GGUF 元数据；以 `llama-server` 为准。

**后续可选**
- **剥离端侧推理遗骸（App 侧）**：`libcomicshelf.so` 静态编入的 llama.cpp 与 QNN/onnxruntime 运行库属死重（端侧路径已弃用，见 App 侧说明）；剥离后可给 APK 瘦身、加快构建。
- `POST /translate_page`：把 BT Sakura 阶梯搬到服务端（payload/重复检测/两级阶梯照 `trans_sakura.py` 移植），实现**离线整本预翻译**（不依赖 App 常驻）；App 侧阶梯保留为另一传输路径。
- 缓存 LRU/上限 + `/stats`。
- 开机自启模板（systemd unit / launchd plist / Win 任务计划）。
- Dockerfile（CUDA base + 模型挂载卷），给"完全不想配环境"的场景。
- CoreML EP 检测 & OCR 批量化再评估（换实现，如 vLLM/MLX）。

---

## 附：相关文档

- 全过程复盘与方法论（时间线 P0~P15 / 踩坑归因 / M1-M13）：`ComicShelfAndroid/docs/PROJECT_RETROSPECTIVE.md`；
  端侧/后端全景、BT 机制移植实录与全部真机数据：`ComicShelfAndroid/docs/BALLOONTRANSLATOR_WORKFLOW.md`
  （附录 F/G/G2/G4/G5）。（`docs/` 为本地开发文档，**未随本仓库分发**。）
- 翻译机制（BT Sakura 两级阶梯/重复检测/引号剥离）的移植说明与理由，见 App 侧
  `app/src/main/java/com/comicshelf/app/reader/OnDeviceTranslator.kt`（顶层注释与阶梯实现）。
- 参考实现（上游 BalloonTranslator，GPL-3.0）：`modules/textdetector/detector_ctbd.py`、
  `modules/ocr/ocr_paddleVL_manga.py`、`modules/translators/trans_sakura.py` —— 本后端管线与
  App 侧翻译机制的移植对照来源；模型由 `fetch_models.py` 从 HuggingFace 下载，运行不依赖上游仓库。
