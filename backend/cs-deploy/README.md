# cs-deploy — ComicShelf 后端 Windows 一键部署

> 双击 `deploy.bat`，其余全自动。适配所有带 NVIDIA 显卡的 Windows 10 (1803+) / 11 机器，
> 无任何前置要求（Python / 依赖 / 模型 / llama 二进制全部自动获取），国内网络直连可用。

## 用法

| 场景 | 操作 |
|---|---|
| 全新机器部署 | 把 `cs-deploy\` 整个文件夹（连同 `comicshelf-android\` 仓库，见下）拷过去，双击 `deploy.bat` |
| 日常启停 | 部署完成后用 `backend\` 里生成的 `start-backend.bat` / `stop-backend.bat` / `status-backend.bat` / `logs-backend.bat` |
| 中断后续跑 | 再次双击 `deploy.bat`——已完成的阶段自动跳过，下载断点续传 |
| 防火墙 | **默认自动放行入站 8787**（幂等：已有规则不重复加；非管理员时弹一次 UAC，拒绝则打印手动命令）。手机/内网设备即可直连 |

可选参数（命令行 `deploy.bat -参数`）：

- `-Cpu` 强制 CPU 模式（无 N 卡 / 驱动过老时自动生效）
- `-NoStart` 只部署不启动
- `-NoFirewall` 不动防火墙（内网设备将无法连入，需自行放行）

部署完成时会在结果框里**优先打印 192.168.\* 物理网段地址**作为手机 App 的推荐后端地址（其他网卡地址列为备选）。注意服务**无鉴权**，放行即对整个局域网开放，仅限可信网络使用。

## 仓库代码来源

脚本按以下顺序找仓库（认 `deploy.py` 所在目录）：

1. 脚本同目录 / 父目录下的 `comicshelf-android\backend` 或 `backend`
2. 都没有 → 自动经 ghproxy 加速链下载 GitHub 仓库 zip 并解压

**注意**：上游仓库若为私有/已删除（2026-10-03 实测 `ptkdLLL/comicshelf-android` 已
无法公开访问），自动下载会失败并提示——此时把已有的 `comicshelf-android\` 文件夹
放在 `deploy.bat` 旁边即可，其余阶段照常。

## 七个阶段（全部幂等）

| 阶段 | 内容 | 自动降级链 |
|---|---|---|
| 0 | 预检：curl 可用性、系统代理探测、并发锁 | — |
| 1 | 定位/下载仓库代码 | ghfast.top → gh-proxy.com → mirror.ghproxy.com → 直连 GitHub；直连→系统代理双路由 |
| 2 | 找 Python 3.10~3.12（py 启动器全列表/PATH/固定安装位，排除 Store stub） | 找不到 → npmmirror/华为云镜像下载 3.11.9 静默装到 `%LOCALAPPDATA%\ComicShelf\Python311`（无 UAC、不污染 PATH） |
| 3 | 建 venv（已存在则跳过） | — |
| 4 | 依赖：torch cu126 / onnxruntime-gpu / requirements（transformers pin 4.57.6） | pip 镜像：清华→阿里→腾讯→SJTU；torch 轮子：阿里→SJTU；驱动 <528 或无 N 卡自动转 CPU |
| 5 | llama.cpp CUDA 二进制（固定 b11368 + cudart）到 `backend\bin\` | 加速器链×双路由全失败 → 自动 `pip install llama-cpp-python`（CPU，翻译 10~30 tok/s） |
| 6 | 三模型 ~3.1GB（CTBD / PaddleOCR-VL / Hy-MT2） | 端点×路由逐一探测（校验 HF `x-repo-commit` 头）：hf-mirror 直连→镜像走系统代理→官方走系统代理→官方直连；下载失败自动换下一个组合 |
| 7 | 启动 + /health 轮询 + 打印手机可填地址（过滤虚拟网卡） | — |

网络路由原则（踩坑沉淀，详见部署归档 §5）：

- **探测与下载同栈**：全部用 `curl.exe`，与实际下载行为一致；
- **"直连"是显式动作**：`--noproxy '*'` + `NO_PROXY=*` 三件套，防止 Windows 注册表
  系统代理（Clash 类工具设置）被 Python/curl 隐式读取；
- **校验业务特征而非 HTTP 200**：HF 探测要求 `x-repo-commit` 响应头存在。

## 升级 llama.cpp

编辑 `deploy.ps1` 顶部：

```powershell
$script:LlamaTag  = 'b11368'          # 改成目标 b 号
$script:LlamaZips = @("llama-<tag>-bin-win-cuda-12.4-x64.zip", "cudart-llama-bin-win-cuda-12.4-x64.zip")
```

删除 `backend\bin\` 后重跑 `deploy.bat`。

## 常见问题

| 症状 | 处置 |
|---|---|
| 双击后窗口一闪而过 | 用 cmd 手动跑 `deploy.bat` 看报错；或直接 `powershell -File deploy.ps1` |
| 手机连不上 | 管理员 cmd：`netsh advfirewall firewall add rule name="ComicShelf backend 8787" dir=in action=allow protocol=TCP localport=8787`；或 USB `adb reverse tcp:8787 tcp:8787` |
| 阶段 6 全部端点失败 | 检查是否能手动打开 hf-mirror.com；公司网络可能需要 `-Firewall` 同款人工配置代理 |
| 想彻底重来 | 删除 `backend\venv` `backend\models` `backend\bin` 后重跑（约 6.5GB 重新下载） |
| OCR 报 OOM（显存 <6GB） | 重跑加 `-Cpu`，或换小显存兼容配置 |

## 文件清单

```
cs-deploy\
├── deploy.bat    ← 双击这个
├── deploy.ps1    ← 全部逻辑（纯英文注释，防 PS 5.1 ANSI 解析乱码）
└── README.md     ← 本文件
运行时产物（自动生成）:
  cs-deploy\.deploy.lock            并发锁（正常退出自动删除；被中断遗留的锁下次运行
                                    会按 PID 存活检测自动清掉，无需手动删）
  backend\venv\ models\ bin\        venv(4.7G) / 模型(3.1G) / llama 二进制(1.2G)
  backend\{start|stop|status|logs}-backend.bat   日常运维双击入口
```

## 实测记录（2026-10-03, RTX 3090 / 64GB / Win10 19045）

- 全新部署（有网缓存）：约 40 分钟，其中 95% 是下载（torch 2.6G + 模型 3.1G + llama 0.65G）
- Python 自动安装链：npmmirror 直连失败 → 系统代理成功 → 静默安装 ✅
- 幂等重跑（全部就绪）：**7 秒**完成 7 阶段检查并确认服务健康
- 部署后指标：检测 362ms/页（CUDA）、OCR 814ms/页、翻译 422ms/句
