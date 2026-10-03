# ComicShelf 后端（cs-backend）Windows 本机部署归档

> 归档日期：2026-10-03
> 仓库：https://github.com/ptkdLLL/comicshelf-android（main 分支，浅克隆 7369 文件）
> 部署位置：`<部署盘>:\...\comicshelf-android\backend\`
> 结果：**部署成功，全链路（检测 → OCR → 翻译）验证通过**，OCR 跑在 CUDA（RTX 3090）上
> 耗时：约 40 分钟（含约 6.5GB 下载：CUDA torch 2.6GB + 模型 3.1GB + llama.cpp 二进制 0.65GB）
> 本机代理：`http://127.0.0.1:17897`（用户提供的混合全局/系统代理，见 §5.2 —— 本次最深的一课）

---

## 0. 一页速览

| 项 | 值 |
|---|---|
| 后端服务 cs-backend | `http://127.0.0.1:8787`（0.0.0.0 监听，局域网可达） |
| 翻译服务 llama-server | `http://127.0.0.1:8080`（仅本机回环） |
| **手机 App 填写的地址** | `http://<Win机IP>:8787`（物理网卡 IP） |
| OCR 设备 | `cuda`（RTX 3090 24GB，驱动 576.02 / CUDA 12.9） |
| venv Python | 3.11.14（`backend\venv\`，4.7GB） |
| 模型 | `backend\models\`（3.1GB，三个模型全部 md5/尺寸校验通过） |
| llama.cpp | b11368 CUDA 12.4 构建（`backend\bin\`，1.2GB 含 cudart） |
| 端到端实测 | OCR：检测 362ms + 识别 814ms/页；翻译：422ms/句 |
| 磁盘总占用 | ≈ 9GB（venv 4.7G + models 3.1G + bin 1.2G） |

日常操作（在 `backend\` 目录）：

```
venv\Scripts\python.exe deploy.py status | start | stop | restart | logs
```

---

## 1. 项目背景与部署目标

ComicShelf 是一个 NAS 直连的漫画阅读器 Android 应用，配套一个跑在局域网电脑上的
OCR/翻译后端（`backend/`，FastAPI）。手机把整页漫画 PNG 上传给后端，后端完成：

```
CTBD onnx 检测（气泡/文字框）→ PaddleOCR-VL 逐块识别（日漫优化）
→ App 侧 BT Sakura 阶梯编排 → /chat 转发给 llama-server（Hy-MT2 翻译模型）→ 译文回传
```

官方 README 声称"三平台一键部署"（`deploy.py` / `start.bat`），实际在本次 Windows
部署中遭遇多个阻断性问题，均需人工介入排查。本文档完整记录过程、根因与解法，
并沉淀为可复用的 runbook 与脚本设计建议。

### 官方给定的前置条件（README §1.3）

| 项 | 要求 | 本机实际情况 |
|---|---|---|
| Python | 3.10~3.12（3.13+ 与 PaddleOCR-VL 自定义代码不兼容；transformers 必须 pin 4.57.6） | ❌ 不满足，见 §4.1 |
| 磁盘 | ≥8GB | ✅ G 盘剩 935GB |
| 内存 | ≥8GB（双服务常驻 ~5.2GB） | ✅ 64GB |
| GPU | 可选，NVIDIA→CUDA 自动 | ✅ RTX 3090 24GB |
| 网络 | 首次需访问 HF / PyPI / pytorch 源 | ⚠️ 需代理，且代理形态影响巨大（§5） |

---

## 2. 目标机器环境快照（2026-10-03 采集）

```
OS          Windows 10 19045 x64（Git Bash 环境）
CPU/内存     64GB RAM
GPU         NVIDIA GeForce RTX 3090（24GB）
驱动         576.02，CUDA Version: 12.9（可跑 cu126 轮子）
Python      C:\Python313（3.13.5，超版本范围）
            C:\Users\<user>\.local\bin\python3.11.exe（3.11.14 ✅ 最终采用）
            C:\Users\<user>\AppData\...\WindowsApps\python.exe（Store 占位 stub ⚠️）
            py 启动器：不存在
网络        直连 GitHub/HF 被重置；有本地代理 127.0.0.1:17897；
            Windows 注册表内设有系统代理（后文详述，当时未知）
```

---

## 3. 部署时间线（七阶段实录）

### 阶段 ①：克隆仓库

| 尝试 | 命令 | 结果 |
|---|---|---|
| 1 | `git clone https://github.com/ptkdLLL/comicshelf-android.git` | ❌ `Recv failure: Connection was reset`（直连被墙） |
| 2 | `git -c http.proxy=http://127.0.0.1:17897 clone --depth 1 …` | ✅ 成功（用户提供代理端口后） |

**教训**：国内直连 GitHub 基本不可行，git 的代理要靠 `-c http.proxy=...` 临时注入
（好处：不污染全局 git 配置）。

### 阶段 ②：解读仓库与部署入口

- 后端 = `backend/` 目录，入口 `deploy.py`（纯标准库引导），子命令
  `setup / start / stop / restart / status / logs / doctor / fetch-models / up`。
- `start.bat` 只是 `deploy.py` 的薄包装 → **跑 deploy.py 前提是先有可用 Python**，
  这里埋下了第一颗雷（§4.1）。
- `deploy.py` 的网络策略：官方源不通自动切国内镜像（HF→hf-mirror、pip→清华/阿里、
  torch→阿里/SJTU）。听起来美好，实际在"系统代理"环境下会翻车（§5）。

### 阶段 ③：Python 发现失败与绕行（坑 1，详见 §4.1）

`deploy.py` 的 Windows Python 候选表是 `py -3.12 / py -3 / python / python3`，
本机一个都不可用（py 不存在；python 是 Store stub；python3 同为 stub）。
真正能用的 `python3.11` 不在候选表里。

**采用的绕行**（不修改仓库代码）：

```bash
cd /g/task1/comicshelf-android/backend
/c/Users/<user>/.local/bin/python3.11.exe -m venv venv   # 预建 venv
```

`ensure_venv()` 检测到 `venv\Scripts\python.exe` 已存在就直接使用，
**完全跳过** `find_system_python()`。这是本次最关键的一步妙手。

### 阶段 ④：`setup --cuda` 装依赖（顺利）

```bash
export HTTPS_PROXY=http://127.0.0.1:17897（等代理变量）
./venv/Scripts/python.exe deploy.py setup --cuda
```

pip 链路全部成功（走代理直连官方源）：

| 包 | 版本 | 来源 |
|---|---|---|
| torch / torchvision | 2.14.1+cu126 / 0.29.1+cu126（wheel 2602.8MB） | download.pytorch.org/whl/cu126 |
| onnxruntime-gpu | 1.30.0 | PyPI |
| transformers | **4.57.6**（README 钦定 pin，勿升级） | PyPI |
| fastapi / uvicorn | 0.142.2 / 0.54.0 | PyPI |
| 其余 | opencv-python 5.0.0.93、numpy 2.4.6、pillow 12.3.0、einops、sentencepiece、huggingface_hub 0.36.2 等 | PyPI |

> 注意 `detect_accel()` 靠 `shutil.which("nvidia-smi")` 判定 CUDA，本机成立，
> 所以不传 `--cuda` 也会走 CUDA 分支；显式传参只是保险。

### 阶段 ⑤：llama-server.exe 手动部署（坑 2，详见 §4.2）

`deploy.py` 不负责下载 llama.cpp 二进制——找不到时只打印"三选一指引"然后退出。
手动处理：

1. 查 GitHub API：`releases/latest` 的 `v0.5.0` 是占位 tag（只有 nightly-tag.txt），
   **真实构建在逐 commit 的 b 号 pre-release 里**（b11368 最新）。
2. 下载两个 zip（走代理，curl）：
   - `llama-b11368-bin-win-cuda-12.4-x64.zip`（263MB，主程序 + ggml DLL）
   - `cudart-llama-bin-win-cuda-12.4-x64.zip`（391MB，cublas/cudart 运行时 DLL）
3. 全部解压到 `backend\bin\`（55 个文件）。
4. 验证：`bin\llama-server.exe --version` → `0.5.0-dev (build 11368)` ✅

驱动 576/CUDA 12.9 完全兼容 cuda-12.4 构建的运行时（向后兼容）。

### 阶段 ⑥：模型下载三连败一成（坑 3/4，详见 §5）

三次尝试的完整对照：

| # | 触发方式 | HF 端点 | 实际路由 | 结果 |
|---|---|---|---|---|
| 1 | setup 内置（env 设了代理变量，端点自动探测） | `https://hf-mirror.com` | 代理 → hf-mirror | ❌ `FileMetadataError: Distant resource does not seem to be on huggingface.co` → `LocalEntryNotFoundError`（HEAD 拿到响应但缺 HF 元数据头） |
| 2 | `unset` 代理变量 + `HF_ENDPOINT=https://hf-mirror.com` 重跑 fetch-models | `https://hf-mirror.com` | **仍是代理**（注册表系统代理，当时未知） | ❌ 同样 `LocalEntryNotFoundError`，卡在第一个文件 CTBD/detector.onnx 的 HEAD |
| 3 | `HF_ENDPOINT=https://huggingface.co` 直跑 `fetch_models.py` | `https://huggingface.co` | 系统代理 → 官方 → CDN | ✅ 三个模型全下完，md5 校验通过 |

诊断过程（§5.2 详述）：用 venv 里的 Python 打印
`urllib.request.getproxies()` 才发现 **Windows 注册表里设着系统代理 127.0.0.1:17897**，
`unset` 环境变量根本切不断它。而"hf-mirror 直连"用 `ProxyHandler({})` 强制绕开代理后
返回 **403 Forbidden**——即本机网络上，hf-mirror 只有走代理才可达，官方 HF 也只有走
代理才可达，"国内镜像免代理"的官方假设在这台机器上完全不成立。

最终下载量：CTBD 0.17GB + PaddleOCR-VL 1.93GB（15 文件快照）+ Hy-MT2 GGUF 1.13GB
（md5 `436f3ec2…` 与基准一致）= **3.1GB**。
（附带警告：仓库启用 Xet Storage，未装 hf_xet 包，自动回退普通 HTTP 下载，无碍。）

### 阶段 ⑦：启动与端到端验证（含坑 5：控制台编码）

```bash
./venv/Scripts/python.exe deploy.py start
```

- llama-server 就绪 2.5s（`-ngl 99` 全层 GPU offload；新版支持 `--cache-ram`，
  脚本自动探测后加了 `--cache-ram 1024` 把 prompt cache 限到 1GB，防止新版默认 8GB 吃爆内存）
- cs-backend 就绪 10.1s；CTBD 检测器同步加载 2.9s（`det_ms: 2923.6`）
- OCR 模型后台线程加载 5.6s 后 `ocr.ready: true`

健康检查（最终态）：

```json
{"ok":true,"pipeline":"bt-mac-v1","device":"cuda",
 "det_providers":["CUDAExecutionProvider","CPUExecutionProvider"],
 "det_ms":2923.6,
 "ocr":{"ready":true,"load_ms":5628.6,"error":""},
 "llm":{"ok":true,"url":"http://127.0.0.1:8080"}}
```

**端到端测试 ×2**：

1. OCR：Python 生成 480×720 白底黑字合成图 → `POST /ocr_page`（裸 PNG 字节）
   → 检出 2 个文字框 + 1 个误报气泡框（合成图属预期）；文字读出
   `HELLO 456` / `ＴＥＴＴ 123`（拉丁合成字体被按全角读错，模型面向日漫竖排，
   官方 `doctor --full` 用同款合成图仅验证管线通不通，不影响真实漫画）
   → 耗时：det 362ms + ocr 814ms。
2. 翻译：`POST /chat`，`「今日はとても良い天気ですね、散歩に行きましょう」`
   → `今天天气真好啊，我们去散步吧。`（422ms，11 completion tokens）
   → 首次尝试在 Git Bash 里 `curl -d '…中文日文…'` 收到 `{"error":"bad json"}`，
   原因是 cp936 控制台把 UTF-8 JSON 体打烂了；改为 **Python 写 UTF-8 文件 +
   `curl --data-binary @file`** 后成功（§4.3）。

启动时打印的局域网地址有 4 个：`172.24.x.x`、`192.168.209.x`、`<Win机IP>`、
`192.168.76.x`。前三个是虚拟网卡（WSL/Hyper-V/VMware），**手机应填物理网卡的那个：
`http://<Win机IP>:8787`**。

---

## 4. 坑位详解（根因分析）

### 4.1 坑 1：Python 发现逻辑在真实 Windows 上失效

**现象**：`deploy.py setup` 尚未开始就会 `die("找不到 Python 3.10+")`。

**根因链**：
1. deploy.py Windows 候选写死 `["py","-3.12"] / ["py","-3"] / ["python"] / ["python3"]`，
   靠 `shutil.which` 找；
2. 本机 `py` 启动器不存在；`python`/`python3` 命中 `WindowsApps\python.exe`
   ——这是 Microsoft Store 的**占位 stub**：`shutil.which` 找得到，但执行时
   要么弹商店要么静默无输出（`_py_ver()` 拿不到版本号只能跳过）；
3. 唯一合格的 `python3.11.exe` 装在 `~/.local/bin`（不在候选表，也不在常规安装位）；
4. `C:\Python313` 是 3.13.5，超出 3.10~3.12 兼容窗（PaddleOCR-VL 自定义 modeling 代码
   + transformers 4.57.6 pin 的硬约束），用它能建 venv 但跑 OCR 有兼容风险。

**解法（零仓库改动）**：预建 venv 绕过发现逻辑——
`ensure_venv()` 的第一行就是"venv 存在则直接用"，先手工
`python3.11 -m venv venv` 即可让整条流水线正常走完。

**普适教训**：
- Windows 的 Python 生态位远多于候选表所能枚举（Store stub / py launcher /
  `.local/bin` / conda / scoop / winget 装的 `%LOCALAPPDATA%\Programs\Python`），
  **扫描必须广 + 必须排除 `WindowsApps` 路径 + 必须真跑 `--version` 验证**；
- "venv 已存在即跳过发现"是个很好的逃生门，手动预建 venv 永远是合法注入手段。

### 4.2 坑 2：llama-server 二进制无自动获取路径

**现象**：一切就绪后 `deploy.py start` 将死于 `LLAMA_GUIDE`（打印三选一指引：
手动下 release / pip 装 llama-cpp-python / 配外部 LLAMA_URL）。

**根因**：llama.cpp 官方只发 GitHub release，国内直连不可达；deploy.py 对此零处理。
另外 `releases/latest` 指向的 `v0.5.0` 是占位 tag，**真正的每日构建在 b 号
pre-release**（如 b11368），用错 API 会以为没有产物。

**解法**：
```
GET https://api.github.com/repos/ggml-org/llama.cpp/releases?per_page=8   # 注意列 pre-release
下载 llama-b<NNNN>-bin-win-cuda-12.4-x64.zip + cudart-llama-bin-win-cuda-12.4-x64.zip
解压到 backend\bin\（find_llama_binary() 的第二优先级查找位）
llama-server.exe --version 验证（同时验证了 DLL 依赖完整性）
```

**普适教训**：
- "找不到就打印人工指引然后退出"在一键部署语境里等于失败；
  tier-2（`pip install llama-cpp-python`，国内 pip 镜像可得）应当**自动降级**而非仅提示；
- 用 GitHub release 资产前先搞清 tag 结构（占位 tag vs 逐 commit pre-release）；
- CUDA 运行时 DLL 与主程序分两个 zip，**两个都要下**，只下主程序会缺 cublas64_12.dll。

### 4.3 坑 3：Windows 控制台编码打烂 UTF-8 请求体

**现象**：Git Bash 里 `curl -d '{"user":"…日文…"}'` → 服务端 `{"error":"bad json"}`。

**根因**：cp936 代码页下，shell 里的非 ASCII 字节在组包时被转码破坏。deploy.py 自身
对 stdout 做了 `reconfigure(utf-8)` 防御，但**用户在壳里手工构造的请求体没有这层防御**。

**解法**：非 ASCII 的 curl 调试请求一律走文件：
```bash
python -c "open('b.json','w',encoding='utf-8').write(json.dumps(obj,ensure_ascii=False))"
curl -H 'Content-Type: application/json' --data-binary @b.json http://127.0.0.1:8787/chat
```

**普适教训**：Windows 下验证 UTF-8 API，别在命令行参数里内联非 ASCII；
bat 入口应 `chcp 65001`。

### 4.4 坑 4：多虚拟网卡干扰"手机该填哪个 IP"

**现象**：start 输出 4 个候选 IP，无法直判。

**根因**：WSL/Hyper-V/VMware 各自注册虚拟网卡，`lan_ips()` 全部列出。

**解法**：识别物理网卡 IP（本机为 <Win机IP>；简单判别法——与手机同网段、
且不是常见的虚拟网段 172.1x/192.168.7x/192.168.20x）。更稳妥的验证：
`adb shell curl -s http://<IP>:8787/health` 或直接 USB `adb reverse`。

---

## 5. 网络路由专题：系统代理与"镜像直连"的冲突（本次核心教训）

### 5.1 三层代理概念澄清

Windows 上"走不走代理"实际有三层，行为不一致：

| 层 | 谁读它 | 本次值 |
|---|---|---|
| ① 环境变量 `HTTP(S)_PROXY` | pip / curl / 显式读 env 的库 | 我按需 export / unset |
| ② **注册表系统代理**（Internet Settings） | Python `urllib.request.getproxies()` → **requests 默认继承**；大多数 GUI 程序 | `127.0.0.1:17897`（Clash 类工具设置，**全程存在**） |
| ③ 代理客户端自身的分流规则 | 代理进程内部 | 未知，黑盒 |

**致命组合**：`unset` 环境变量 ≠ 断开代理。层②依然让 requests/urllib 走 127.0.0.1:17897。
而 **curl（Git Bash 版）不读注册表**，所以"我用 curl 验证了直连 hf-mirror 可达"
是**伪结论**——探测工具与下载工具网络栈不一致。

### 5.2 本次实测的路由矩阵（用 venv 里 Python 探测，与下载同栈）

| 目标 | 路由 | 结果 |
|---|---|---|
| hf-mirror `config.json` | 强制无代理（`ProxyHandler({})`） | ❌ **403 Forbidden** |
| hf-mirror `config.json` | 默认（注册表代理） | 200，有 ETag/X-Repo-Commit |
| hf-mirror `detector.onnx`（LFS，requests 跟随重定向） | 默认（注册表代理） | 200，终点点位 `us.aws.cdn.hf.co`，content-length 168481531 ✅ |
| huggingface.co `detector.onnx`（LFS） | 默认（注册表代理） | 200，同一 CDN，同样大小 ✅ |
| GitHub API / release zip | curl 显式 `-x` 代理 | ✅ |

结论：这台机器上，**官方 HF 与 hf-mirror 都只有经代理才可达；hf-mirror 的"国内直连
免代理"假设不成立**（可能是代理客户端 TUN/分流接管了全部流量，或网络出口策略所致）。
而 setup 内置的自动探测（urllib、8s 超时）在此环境下先得出"官方不通→切 hf-mirror"，
随后 huggingface_hub 经代理访问 hf-mirror 又拿不到完整 HF 元数据头
（`FileMetadataError`），进退两难。**显式指定官方端点 + 接受系统代理**（第 3 次尝试）
一次性解决。

### 5.3 沉淀的探测方法论

1. **探测必须与下载同栈**：要下 HF 就用 venv 里的 requests/huggingface_hub 探，
   别拿 curl 的连通性当结论（deploy.py 源码注释其实写了这一点，但它没防住注册表层）；
2. **校验业务特征而非 HTTP 200**：HF 文件 HEAD 必须带 `x-repo-commit`/`etag`/
   `x-linked-size`；GitHub 资产要跟到最终 CDN 拿 content-length；
   本次 #1 失败正是"200 但缺元数据头"这种半通状态；
3. **先打印再假设**：一行 `print(urllib.request.getproxies())` 就能揭穿
   "我以为没代理"——排查网络问题的第一步永远是确认实际路由；
4. **路由要显式传给子进程**：direct 意图必须 export `NO_PROXY=*` + 空代理变量，
   否则 venv 里的 pip/huggingface_hub 又读注册表，路由再次分裂；
5. 自动探测的结果应**记住**（写 config），避免每次冷启动都重新试错一遍坏路径。

---

## 6. 最终部署状态（本机现状）

### 6.1 拓扑

```
┌─ cs-backend (FastAPI/uvicorn)  :8787  0.0.0.0 ──────────────┐
│  ├─ CTBD onnx 检测   onnxruntime-gpu → CUDAExecutionProvider │
│  ├─ PaddleOCR-VL     torch 2.14.1+cu126 → cuda, fp16        │
│  ├─ sha1 整页缓存    RAM 64 条 + backend\cache\<sha1>.json   │
│  └─ /chat 转发 ──► llama-server :8080 (127.0.0.1 only)       │
│                     Hy-MT2-1.8B-Q4_K_M.gguf, ngl=99 (全GPU)  │
│                     --cache-ram 1024MiB                      │
└──────────────────────────────────────────────────────────────┘
进程管理：pids\llama.pid / pids\server.pid（deploy.py stop 用，DETACHED 常驻）
日志：backend\server.log / backend\llama_server.log（>32MB 启动时自动清空）
```

### 6.2 磁盘与版本清单

```
backend\venv\    4.7GB   Python 3.11.14
  torch 2.14.1+cu126 / torchvision 0.29.1+cu126 / onnxruntime-gpu 1.30.0
  transformers 4.57.6 (pin!) / fastapi 0.142.2 / uvicorn 0.54.0
  opencv-python 5.0.0.93 / numpy 2.4.6 / pillow 12.3.0 / einops 0.8.2
  sentencepiece 0.2.2 / huggingface_hub 0.36.2
backend\models\  3.1GB
  CTBD\detector.onnx                       161MB
  PaddleOCR-VL-For-Manga\（15 文件快照）    1.8GB
  hymt\Hy-MT2-1.8B-Q4_K_M.gguf             1.1GB  md5 ✔
backend\bin\     1.2GB   llama.cpp b11368（CUDA 12.4 构建 + cudart DLL，55 文件）
```

### 6.3 启动后预期指标（本机实测）

| 项 | 值 |
|---|---|
| llama-server 就绪 | ~2.5s |
| cs-backend HTTP 就绪 | ~10s（CTBD 同步加载 2.9s） |
| OCR 后台加载完成 | +5.6s（此前 /ocr_page 返 503 属正常） |
| 检测/页 | 362ms（CUDA EP） |
| OCR/页（合成小图） | 814ms；真实漫画页预期 1~9s（README §6 量级，3090 应显著更快） |
| 翻译/句 | 422ms |
| 常驻内存 | 约 5GB（OCR ~2.6GB + llama ~2.6GB） |

---

## 7. 运维手册（本机）

```bash
cd <部署盘>:\...\comicshelf-android\backend

venv\Scripts\python.exe deploy.py status     # 两服务状态 + health JSON
venv\Scripts\python.exe deploy.py stop       # 停止（按 pidfile，SIGTERM→强杀）
venv\Scripts\python.exe deploy.py start      # 启动（模型已就位，秒级）
venv\Scripts\python.exe deploy.py restart    # 改完 config.env 后用
venv\Scripts\python.exe deploy.py logs       # 看两份日志尾部
venv\Scripts\python.exe deploy.py doctor --full   # 体检 + 合成图管线自测
```

**手机连接**：App 设置 → 翻译 → 后端地址填 `http://<Win机IP>:8787`；
USB 调试可 `adb reverse tcp:8787 tcp:8787` 后填 `http://127.0.0.1:8787`。

**手动验证**（注意 §4.3：非 ASCII 走文件）：

```bash
curl -s http://127.0.0.1:8787/health
curl -s -X POST --data-binary @page.png http://127.0.0.1:8787/ocr_page
curl -s -X POST http://127.0.0.1:8787/chat -H 'Content-Type: application/json' --data-binary @b.json
```

**常见故障对照**：

| 症状 | 处置 |
|---|---|
| 手机连不上 8787 | 防火墙放行入站 8787/tcp；或改 adb reverse；先 `adb shell curl …/health` 定位 |
| /ocr_page 503 | OCR 还在加载（启动后 ~10s 内），等 health 里 ocr.ready=true |
| /chat 502 | llama-server 没起 / 8080 被占：status → logs llama |
| 换网络环境后重新部署 | 重跑 fetch 前先 `python -c "import urllib.request;print(urllib.request.getproxies())"` 确认实际路由（§5.3） |

---

## 8. 从零重部署 Runbook（压缩版，按序执行）

> 适用：全新 Windows 机器、有本地代理端口 `P`（无代理场景见 §9 改进建议）。

```bash
# 1. 克隆（P=代理端口）
git -c http.proxy=http://127.0.0.1:P -c https.proxy=http://127.0.0.1:P \
    clone --depth 1 https://github.com/ptkdLLL/comicshelf-android.git
cd comicshelf-android/backend

# 2. 找一个 3.10~3.12 的 Python（排除 WindowsApps stub、排除 3.13+），预建 venv
<python3.11.exe 路径> -m venv venv

# 3. 装依赖（CUDA 机器）
export HTTPS_PROXY=http://127.0.0.1:P
./venv/Scripts/python.exe deploy.py setup --cuda --no-models   # 依赖装完即停

# 4. llama.cpp 二进制（列 pre-release，取最新 b 号；两个 zip 都要）
curl -sL -x http://127.0.0.1:P -o llama-bin.zip  <llama-bNNNN-bin-win-cuda-12.4-x64.zip>
curl -sL -x http://127.0.0.1:P -o cudart.zip    <cudart-llama-bin-win-cuda-12.4-x64.zip>
unzip -o llama-bin.zip -d bin/ && unzip -o cudart.zip -d bin/
bin/llama-server.exe --version                                   # 验证

# 5. 模型（本机结论：官方端点+系统代理最稳；先探测再选端点）
HF_ENDPOINT=https://huggingface.co ./venv/Scripts/python.exe fetch_models.py

# 6. 启动 + 验证
./venv/Scripts/python.exe deploy.py start
curl -s http://127.0.0.1:8787/health          # 期待 device=cuda, ocr.ready=true, llm.ok=true
./venv/Scripts/python.exe deploy.py doctor --full
```

---

## 9. 对官方部署脚本的设计改进建议（摘要）

结合本次全部坑位，一份"真正有效的 Windows 无代理纯国内镜像一键部署"应当：

1. **引导层去 Python 化**：`start.bat → bootstrap.ps1` 负责找/装 3.10~3.12
   （扫描 py launcher 全列表、`%LOCALAPPDATA%\Programs\Python`、`.local/bin`、conda/scoop，
   排除 WindowsApps；都没有则从华为云/npmmirror 的 Python 镜像静默装用户级 Python），
   之后才轮到 deploy.py——消解"跑部署脚本需要先有 Python"的鸡生蛋问题。
2. **网络路由显式化**：direct / system-proxy 两种 profile 显式选择与传递
   （direct 必须 `NO_PROXY=*` + 空 env + `trust_env=False` 三件套），
   **探测与下载同栈**、校验业务特征头（x-repo-commit 等）而非 200，
   探测结果写入 config 记忆。
3. **制品级国内降级链全自动**：pip→清华/阿里/腾讯；torch cu126→阿里/SJTU；
   模型→hf-mirror→ModelScope；llama 二进制→ghproxy 系加速器列表（探活+下载后
   `--version` 验证）→**自动 pip 装 llama-cpp-python（CPU）兜底**而非打印指引退出。
4. **每层验证门 + 幂等 + 文件锁**：venv→`torch.cuda.is_available()`，模型→md5，
   llama→/health 等待，终局 doctor --full；全程可断点重跑；防双开损坏 venv。
5. **运维面**：bat 入口 `chcp 65001`；防火墙 netsh 自动尝试；任务计划自启脚本；
   status/stop/logs 各配独立双击 bat。

---

## 10. 附录：错误信息 ↔ 根因速查

| 报错原文（截取） | 根因 | 本文 |
|---|---|---|
| `git clone … Recv failure: Connection was reset` | 直连 GitHub 被墙 | §3① |
| `die: 找不到 Python 3.10+` | 候选表窄 + Store stub 占位 | §4.1 |
| `FileMetadataError: Distant resource does not seem to be on huggingface.co` | 经代理访问 hf-mirror，HEAD 响应缺 HF 元数据头 | §5.2 |
| `LocalEntryNotFoundError: … check your connection` | 上条的下游包装；或路由与预期不符 | §5.2 |
| `die: 本机找不到可用的 LLM 运行器`（LLAMA_GUIDE） | llama-server.exe 未部署且无自动下载 | §4.2 |
| `{"error":"bad json"}`（/chat） | cp936 控制台打烂 UTF-8 请求体 | §4.3 |
| `/ocr_page` 返回 503 | OCR 模型后台加载中（~10s），非故障 | §6.3 |
| llama-server 相关 `cublas64_12.dll 缺失` | 只下了主 zip 没下 cudart zip | §4.2 |
