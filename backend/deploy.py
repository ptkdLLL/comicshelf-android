#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cs-backend 跨平台部署器 (Windows / Linux / macOS), 仅用 Python 标准库引导。

用法:
  python deploy.py                一键: 缺环境自动 setup(装依赖+下模型), 然后 start
  python deploy.py setup [--cpu|--cuda] [--no-models] [--mirror]
  python deploy.py fetch-models [--mirror]
  python deploy.py start | stop | restart | status | logs [llama|server]
  python deploy.py doctor [--full]

配置: config.env(首次自动生成; 命令行 > 环境变量 > config.env > 默认), 见 DEFAULTS。
LLM 三种来源(自动选择): 本机 llama-server 二进制 → llama-cpp-python(venv) →
外部 OpenAI 兼容端点(config.env 里设 LLAMA_URL, 如 ollama / LM Studio / Sakura)。

手机 App 端零改动: 同一端口(默认 8787)、同一 HTTP 契约; App 设置里填本机局域网 IP。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):          # Windows cp936 控制台防 UnicodeEncodeError
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

ROOT = Path(__file__).resolve().parent
VENV = ROOT / "venv"
PIDS = ROOT / "pids"
CONFIG = ROOT / "config.env"
IS_WIN = os.name == "nt"
PY_EXE = VENV / ("Scripts" if IS_WIN else "bin") / ("python.exe" if IS_WIN else "python")
GGUF_NAME = "Hy-MT2-1.8B-Q4_K_M.gguf"
GGUF_MD5 = "436f3ec23b236b2ac1d05dd7a713f8ae"
HF_MIRROR = "https://hf-mirror.com"
# 国内自动镜像候选(顺序=优先级); 官方可达时一律走官方。
PIP_MIRRORS = ("https://pypi.tuna.tsinghua.edu.cn/simple",
               "https://mirrors.aliyun.com/pypi/simple/")
TORCH_MIRRORS = ("https://mirrors.aliyun.com/pytorch-wheels",
                 "https://mirror.sjtu.edu.cn/pytorch-wheels")

DEFAULTS = {
    "CS_BACKEND_PORT": "8787",
    "LLAMA_PORT": "8080",
    "LLAMA_URL": "",           # 空=本机托管 llama; 填外部端点则跳过本地 llama 启动
    "CS_OCR_DEVICE": "",       # 空=自动探测 CUDA > MPS > CPU
    "CS_MODELS_DIR": "",       # 空=./models
    "CS_LLAMA_BIN": "",        # 空=自动(PATH -> ./bin/ -> llama-cpp-python)
    "CS_LLAMA_CACHE_MB": "1024",  # llama-server prompt cache 上限(MiB); 新版默认 8192 太占内存
    "HF_ENDPOINT": "",         # 空=自动探测(官方不通时走 hf-mirror.com); 也可显式指定
    "PIP_INDEX_URL": "",       # 空=自动探测(官方 PyPI 不通自动切清华/阿里镜像); 也可显式指定
    "CS_TORCH_INDEX": "",      # 空=自动探测(官方 pytorch 源不通自动切阿里/SJTU 镜像)
    "EXTRA_LLAMA_ARGS": "",
}


def info(msg: str) -> None:
    print(f"[deploy] {msg}", flush=True)


def warn(msg: str) -> None:
    print(f"[deploy] [!] {msg}", flush=True)


def die(msg: str) -> None:
    print(f"[deploy] [x] {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


# --------------------------------------------------------------------- config

def load_config() -> dict:
    cfg = dict(DEFAULTS)
    if CONFIG.is_file():
        for line in CONFIG.read_text("utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            if k.strip() in cfg:
                cfg[k.strip()] = v.strip()
    for k in cfg:                                   # 环境变量优先于 config.env
        if os.environ.get(k, "") != "":
            cfg[k] = os.environ[k]
    # 网络/镜像三项: 同步进环境变量, 让子进程(pip / huggingface_hub)也拿到; 真实环境变量优先
    for k in ("HF_ENDPOINT", "PIP_INDEX_URL", "CS_TORCH_INDEX"):
        if cfg.get(k):
            os.environ.setdefault(k, cfg[k])
    return cfg


def write_default_config() -> None:
    if CONFIG.exists():
        # 升级场景: 只补齐新增键(不动已有值)
        text = CONFIG.read_text("utf-8")
        have = {ln.split("=", 1)[0].strip()
                for ln in text.splitlines() if "=" in ln and not ln.strip().startswith("#")}
        missing = [k for k in DEFAULTS if k not in have]
        if missing:
            CONFIG.write_text(text.rstrip("\n") + "\n# --- 新增(升级补齐) ---\n" +
                              "\n".join(f"{k}={DEFAULTS[k]}" for k in missing) + "\n", "utf-8")
            info(f"config.env 补齐新增配置: {', '.join(missing)}")
        return
    lines = ["# cs-backend 部署配置(修改后 restart 生效; 也可用环境变量临时覆盖)",
             "# LLAMA_URL 留空=本机托管 llama-server; 填 http://host:port 则用外部 OpenAI 兼容服务",
             "# 网络/镜像(HF_ENDPOINT / PIP_INDEX_URL / CS_TORCH_INDEX)留空=自动探测:",
             "#   官方不通时自动切国内镜像(hf-mirror.com / 清华·阿里 PyPI / 阿里·SJTU torch)",
             "#   要固定走某个源就填这里(或同名环境变量), 填了就不再自动探测/切换",
             "# CS_OCR_DEVICE 留空自动: cuda > mps > cpu",
             "# CS_LLAMA_CACHE_MB llama-server prompt cache 上限(MiB), 0=关; 新版默认 8192 太占内存"]
    lines += [f"{k}={v}" for k, v in DEFAULTS.items()]
    CONFIG.write_text("\n".join(lines) + "\n", "utf-8")
    info(f"已生成默认配置 {CONFIG.name}")


def models_dir(cfg: dict) -> Path:
    return Path(cfg["CS_MODELS_DIR"]).expanduser() if cfg["CS_MODELS_DIR"] else ROOT / "models"


def models_missing(cfg: dict) -> list[str]:
    md = models_dir(cfg)
    missing = []
    if not (md / "CTBD" / "detector.onnx").is_file():
        missing.append("CTBD/detector.onnx")
    if not (md / "PaddleOCR-VL-For-Manga" / "model.safetensors").is_file():
        missing.append("PaddleOCR-VL-For-Manga/")
    if not (md / "hymt" / GGUF_NAME).is_file():
        missing.append(f"hymt/{GGUF_NAME}")
    return missing


# ------------------------------------------------------------------ processes

def pid_file(name: str) -> Path:
    return PIDS / f"{name}.pid"


def read_pid(name: str) -> int | None:
    try:
        return int(pid_file(name).read_text().strip())
    except Exception:  # noqa: BLE001
        return None


def pid_alive(pid: int) -> bool:
    if IS_WIN:
        try:
            out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"],
                                 capture_output=True, text=True, timeout=15).stdout
            return str(pid) in out
        except Exception:  # noqa: BLE001
            return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def kill_pid(pid: int, hard: bool = False) -> None:
    if IS_WIN:
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"],
                       capture_output=True)
        return
    try:
        os.kill(pid, signal.SIGKILL if hard else signal.SIGTERM)
    except ProcessLookupError:
        pass


def stop_one(name: str, label: str) -> bool:
    """pidfile 优先; 无 pidfile 时按命令行特征兜底(仅 POSIX, 兼容早期 run.sh 起的进程)。"""
    pid = read_pid(name)
    pid_file(name).unlink(missing_ok=True)
    if pid and pid_alive(pid):
        kill_pid(pid)
        for _ in range(12):                          # 最多等 6s 再强杀
            if not pid_alive(pid):
                break
            time.sleep(0.5)
        if pid_alive(pid):
            warn(f"{label} 未响应 SIGTERM, 强杀 pid={pid}")
            kill_pid(pid, hard=True)
        info(f"{label} 已停止 (pid={pid})")
        return True
    if not IS_WIN:
        pat = {"llama": f"llama-server.*{models_dir(load_config())}", "server": "uvicorn server:app"}[name]
        try:
            r = subprocess.run(["pgrep", "-f", pat], capture_output=True, text=True)
            pids = [int(x) for x in r.stdout.split()]
        except Exception:  # noqa: BLE001
            pids = []
        if pids:
            for p in pids:
                kill_pid(p)
            info(f"{label} 已停止 (兜底匹配 pid={pids})")
            return True
    info(f"{label} 未运行")
    return False


def truncate_log(path: Path, max_mb: int = 32) -> None:
    try:
        if path.is_file() and path.stat().st_size > max_mb * 1_000_000:
            path.write_bytes(b"")
    except Exception:  # noqa: BLE001
        pass


def spawn(name: str, cmd: list[str], env: dict, log_name: str) -> int:
    PIDS.mkdir(exist_ok=True)
    log_path = ROOT / log_name
    truncate_log(log_path)
    log = open(log_path, "ab")                       # noqa: SIM115
    kwargs: dict = {}
    if IS_WIN:
        kwargs["creationflags"] = 0x00000008 | 0x00000200   # DETACHED_PROCESS | NEW_GROUP
    else:
        kwargs["start_new_session"] = True
    p = subprocess.Popen(cmd, cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT,
                         env=env, **kwargs)
    pid_file(name).write_text(str(p.pid))
    return p.pid


# ------------------------------------------------------------------- net/util

def http_get(url: str, timeout: float = 2.0):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return None, str(e)


def wait_health(url: str, timeout: float, what: str) -> bool:
    t0 = time.time()
    while time.time() - t0 < timeout:
        st, _ = http_get(url, 2.0)
        if st == 200:
            info(f"{what} 就绪 ({time.time()-t0:.1f}s)")
            return True
        time.sleep(0.5)
    warn(f"{what} 在 {timeout:.0f}s 内未就绪: {url}")
    return False


def port_busy(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def lan_ips() -> list[str]:
    ips: set[str] = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except Exception:  # noqa: BLE001
        pass
    try:
        for res in socket.getaddrinfo(socket.gethostname(), None):
            ip = res[4][0]
            if not ip.startswith("127.") and ":" not in ip:
                ips.add(ip)
    except Exception:  # noqa: BLE001
        pass
    return sorted(ips)


_PROBE_PATH = "/ogkalu/comic-text-and-bubble-detector/resolve/main/config.json"


def probe_hf_endpoint() -> str:
    """探测用官方还是镜像: 拿真实下载路径(resolve)去试, 官方 8s 不通、镜像通 -> 镜像。
    注意: 必须用与 huggingface_hub 相同的网络栈(Python)探测, curl 的连通性不代表它。"""
    for ep in ("https://huggingface.co", HF_MIRROR):
        st, body = http_get(ep + _PROBE_PATH, 8.0)
        if st == 200 and '"model_type"' in body:
            return ep
    return HF_MIRROR                                # 都不通也给镜像(至少不更差)


def http_status(url: str, timeout: float = 4.0) -> int | None:
    """返回 HTTP 状态码(含 4xx/5xx); 网络层失败(超时/DNS/拒连)返回 None。"""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:  # noqa: BLE001
        return None


def probe_first_ok(urls, timeout: float = 4.0) -> str | None:
    """返回第一个"可达"的 URL(4xx<500 也算: 部分镜像禁目录列表但文件可下)。"""
    for u in urls:
        st = http_status(u, timeout)
        if st is not None and st < 500:
            return u
    return None


def ensure_pip_index() -> None:
    """PyPI 自动镜像: 官方可达不动(用默认); 不通则自动切国内镜像(清华→阿里)。
    尊重用户已设的 PIP_INDEX_URL / PIP_EXTRA_INDEX_URL(不覆盖)。"""
    if os.environ.get("PIP_INDEX_URL") or os.environ.get("PIP_EXTRA_INDEX_URL"):
        info(f"pip 索引: 用指定的 {os.environ.get('PIP_INDEX_URL') or '(PIP_EXTRA_INDEX_URL)'}")
        return
    if http_status("https://pypi.org/simple/", 6.0) == 200:
        return                                       # 官方可达: 用默认
    m = probe_first_ok(PIP_MIRRORS)
    if m:
        os.environ["PIP_INDEX_URL"] = m              # 之后所有 pip 子进程继承
        info(f"pip 索引: 官方 PyPI 不通 → 自动使用镜像 {m}")
    else:
        warn("pip 索引: 官方与国内镜像均不可达(走代理可设 HTTPS_PROXY 后重跑)")


def pick_torch_index(tag: str) -> str:
    """torch 轮子源: CS_TORCH_INDEX 优先; 否则 官方 → 阿里 → SJTU 探测。"""
    if os.environ.get("CS_TORCH_INDEX"):
        return os.environ["CS_TORCH_INDEX"]
    official = f"https://download.pytorch.org/whl/{tag}"
    if http_status(official + "/", 6.0) == 200:
        return official
    m = probe_first_ok([f"{b}/{tag}" for b in TORCH_MIRRORS])
    if m:
        info(f"torch 轮子源: 官方不通 → 自动使用镜像 {m}")
        return m
    warn("torch 轮子源: 官方与镜像均不可达, 先按官方尝试")
    return official


def torch_index_candidates(tag: str) -> list[str]:
    """首选索引 + 其余候选(安装失败自动换源用)。"""
    chosen = pick_torch_index(tag)
    others = [f"https://download.pytorch.org/whl/{tag}"] + [f"{b}/{tag}" for b in TORCH_MIRRORS]
    return [chosen] + [u for u in others if u != chosen]


# ------------------------------------------------------------------ venv/setup

def _py_ver(exe: list[str]) -> tuple[int, int] | None:
    try:
        r = subprocess.run(exe + ["-c", "import sys;print('%d %d' % sys.version_info[:2])"],
                           capture_output=True, text=True, timeout=30)
        if r.returncode == 0:
            a, b = r.stdout.split()[:2]
            return int(a), int(b)
    except Exception:  # noqa: BLE001
        pass
    return None


def find_system_python() -> list[str]:
    cands = [["python3.12"], ["python3.11"], ["python3.10"], ["python3"], ["python"]]
    if IS_WIN:
        cands = [["py", "-3.12"], ["py", "-3"], ["python"], ["python3"]]
    fallback = None
    for c in cands:
        exe = shutil.which(c[0])
        if not exe:
            continue
        v = _py_ver(c)
        if not v:
            continue
        if (3, 10) <= v <= (3, 12):
            return c
        if v >= (3, 13) and fallback is None:
            fallback = c
    if fallback:
        warn(f"系统只有 Python {'.'.join(map(str, _py_ver(fallback)))}; "
             f"推荐 3.10~3.12(与 PaddleOCR-VL 自定义代码兼容性最好), 继续尝试安装")
        return fallback
    die("找不到 Python 3.10+(Windows 安装时勾选 Add to PATH; "
        "macOS: brew install python@3.12; Linux: apt install python3 python3-venv)")
    return []  # unreachable


def ensure_venv() -> None:
    if PY_EXE.is_file():
        ver = subprocess.run([str(PY_EXE), "-c",
                              "import sys;print('%d.%d.%d' % sys.version_info[:3])"],
                             capture_output=True, text=True).stdout.strip()
        info(f"venv 已存在 (Python {ver})")
        return
    py = find_system_python()
    info(f"创建 venv: {' '.join(py)} -m venv {VENV.name}")
    r = subprocess.run(py + ["-m", "venv", str(VENV)])
    if r.returncode != 0 or not PY_EXE.is_file():
        die("venv 创建失败(Linux 可能需要: apt install python3-venv)")
    info("venv 创建完成")


def pip(args: list[str], extra: list[str] | None = None) -> int:
    """跑一次 pip install, 返回退出码(不 die——便于上层换源重试)。"""
    allargs = [*args, *(extra or [])]
    shown = " ".join(a for a in allargs if not a.startswith("http"))
    info(f"pip install {shown}")
    return subprocess.run([str(PY_EXE), "-m", "pip", "install", *allargs],
                          cwd=str(ROOT)).returncode


def pip_or_die(args: list[str]) -> None:
    if pip(args) != 0:
        die(f"pip 安装失败: {' '.join(args)}\n"
            f"  官方 PyPI 不通会自动切清华/阿里镜像; 仍失败可手动设 "
            f"PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple 重跑(或设 HTTPS_PROXY)")


def pip_indexed(args: list[str], indexes: list[str | None]) -> None:
    """按候选索引逐个尝试(首个=探测结果), 全失败才 die。"""
    for i, idx in enumerate(indexes):
        if i:
            info(f"换源重试: {idx or '默认 PyPI'}")
        if pip(args, ["--index-url", idx] if idx else None) == 0:
            return
    die(f"pip 安装失败(已自动换源): {' '.join(args)}\n"
        f"  可手动指定 CS_TORCH_INDEX=<镜像>/<tag> 或 PIP_INDEX_URL=... 后重跑")


def detect_accel() -> str:
    if sys.platform == "darwin":
        return "mps"
    if shutil.which("nvidia-smi"):
        return "cuda"
    return "cpu"


def install_deps(accel: str) -> None:
    ensure_pip_index()
    pip_or_die(["-U", "pip", "wheel"])
    if accel == "cuda":
        pip_indexed(["torch", "torchvision"], torch_index_candidates("cu126"))
        pip_or_die(["onnxruntime-gpu"])
    elif sys.platform == "darwin":
        pip_or_die(["torch", "torchvision"])       # macOS(MPS) 轮子在 PyPI
        pip_or_die(["onnxruntime"])
    else:
        pip_indexed(["torch", "torchvision"], torch_index_candidates("cpu"))
        pip_or_die(["onnxruntime"])
    pip_or_die(["-r", str(ROOT / "requirements.txt")])


def run_fetch_models(cfg: dict, mirror: bool | None) -> None:
    if subprocess.run([str(PY_EXE), "-c", "import huggingface_hub"],
                      capture_output=True).returncode != 0:
        die("venv 里缺 huggingface_hub → 先运行: python deploy.py setup (或 setup --no-models)")
    env = os.environ.copy()
    explicit = bool(env.get("HF_ENDPOINT") or cfg["HF_ENDPOINT"] or mirror)
    if mirror is True:
        env["HF_ENDPOINT"] = env.get("HF_ENDPOINT") or HF_MIRROR
    elif explicit:
        env["HF_ENDPOINT"] = env.get("HF_ENDPOINT") or cfg["HF_ENDPOINT"]
    else:
        env["HF_ENDPOINT"] = probe_hf_endpoint()
        info(f"HF 端点自动选择: {env['HF_ENDPOINT']}")
    if cfg["CS_MODELS_DIR"]:
        env["CS_MODELS_DIR"] = cfg["CS_MODELS_DIR"]

    # 自动选择时给两个端点都留机会: 首个失败(如中途被墙/抖动)自动换另一个再试,
    # 文件级断点续传, 已下部分不浪费; 显式指定(--mirror/配置)则不擅自换。
    endpoints = [env["HF_ENDPOINT"]]
    if not explicit:
        endpoints.append(HF_MIRROR if env["HF_ENDPOINT"] != HF_MIRROR else "https://huggingface.co")
    for i, ep in enumerate(endpoints):
        if i:
            warn(f"模型下载失败 → 自动换端点重试: {ep}")
        env["HF_ENDPOINT"] = ep
        if subprocess.run([str(PY_EXE), str(ROOT / "fetch_models.py")],
                          env=env, cwd=str(ROOT)).returncode == 0:
            if i:
                info(f"换端点后下载成功: {ep}")
            return
    die("模型下载失败(重跑可断点续传; 国内建议 --mirror 或 config.env 设 HF_ENDPOINT)")


def cmd_setup(args) -> None:
    write_default_config()
    cfg = load_config()          # 先加载: 把 HF/PIP/TORCH 镜像配置同步进环境变量(供 pip 子进程用)
    ensure_venv()
    accel = "cuda" if args.cuda else ("cpu" if args.cpu else detect_accel())
    info(f"目标加速: {accel}")
    install_deps(accel)
    if args.no_models:
        info("跳过模型下载 (--no-models); 之后可运行: python deploy.py fetch-models")
    else:
        run_fetch_models(cfg, mirror=True if args.mirror else None)


# ----------------------------------------------------------------------- llama

def find_llama_binary(cfg: dict) -> str | None:
    if cfg["CS_LLAMA_BIN"]:
        p = Path(cfg["CS_LLAMA_BIN"]).expanduser()
        if p.is_file():
            return str(p)
        warn(f"CS_LLAMA_BIN 不存在: {p}")
    local = ROOT / "bin" / ("llama-server.exe" if IS_WIN else "llama-server")
    if local.is_file():
        return str(local)
    return shutil.which("llama-server.exe" if IS_WIN else "llama-server")


def llama_cpp_available() -> bool:
    return subprocess.run([str(PY_EXE), "-c", "import llama_cpp"],
                          capture_output=True).returncode == 0


LLAMA_GUIDE = f"""本机找不到可用的 LLM 运行器, 三选一:
  a) 装 llama.cpp 后重试 (推荐, 性能最好):
       macOS:   brew install llama.cpp
       Windows: 从 llama.cpp 官方 release 下载 llama-server.exe, 放到 {ROOT / 'bin'}
       Linux:   包管理器 / 官方 release / 源码构建
  b) pip 版(免装二进制): {PY_EXE} -m pip install llama-cpp-python
  c) 已有外部 OpenAI 兼容服务(ollama / LM Studio / Sakura):
       在 {CONFIG.name} 里设 LLAMA_URL=http://<host>:<port> 后重跑 start"""


def _llama_supports(binary: str, flag: str) -> bool:
    try:
        r = subprocess.run([binary, "--help"], capture_output=True, text=True, timeout=20)
        return flag in (r.stdout + r.stderr)
    except Exception:  # noqa: BLE001
        return False


def start_llama(cfg: dict) -> str:
    """返回 LLAMA_URL。external 模式直接返回配置值。"""
    if cfg["LLAMA_URL"]:
        info(f"LLM 使用外部端点: {cfg['LLAMA_URL']} (跳过本地 llama)")
        return cfg["LLAMA_URL"]
    url = f"http://127.0.0.1:{cfg['LLAMA_PORT']}"
    pid = read_pid("llama")
    if pid and pid_alive(pid):
        info(f"llama 已在运行 (pid={pid})")
        return url
    gguf = models_dir(cfg) / "hymt" / GGUF_NAME
    if not gguf.is_file():
        die(f"缺少 GGUF 模型: {gguf}\n  运行: python deploy.py fetch-models")
    accel = detect_accel()
    ngl = "99" if accel in ("mps", "cuda") else "0"
    extra = shlex.split(cfg["EXTRA_LLAMA_ARGS"]) if cfg["EXTRA_LLAMA_ARGS"] else []
    binary = find_llama_binary(cfg)
    if binary:
        cmd = [binary, "-m", str(gguf), "--host", "127.0.0.1", "--port", cfg["LLAMA_PORT"],
               "-c", "4096", "-ngl", ngl, "-t", "8"]
        # 新版 llama.cpp 的 prompt cache 默认吃 8GB(实测 RSS 8.9GB→cap 后 ~2.6GB);
        # 保留 1GB 足够复用 BT 系统提示词前缀。老版本没有该 flag, 探测后再加。
        cram = cfg.get("CS_LLAMA_CACHE_MB", "")
        if cram and _llama_supports(binary, "--cache-ram"):
            cmd += ["--cache-ram", cram]
        cmd += extra
        mode = f"llama-server ({binary}, prompt cache {cram or '-'}MiB)"
    elif llama_cpp_available():
        cmd = [str(PY_EXE), "-m", "llama_cpp.server", "--model", str(gguf),
               "--host", "127.0.0.1", "--port", cfg["LLAMA_PORT"], "--n_ctx", "4096",
               "--n_gpu_layers", ngl, *extra]
        mode = "llama-cpp-python (仓内服务器, CPU 或已编译加速)"
    else:
        die(LLAMA_GUIDE)
        return url  # unreachable
    info(f"启动 {mode} :{cfg['LLAMA_PORT']} (ngl={ngl}, 模型={gguf.name})")
    pid = spawn("llama", cmd, os.environ.copy(), "llama_server.log")
    wait_health(url + "/health", 180, "llama-server")   # 首次载入 1.1GB 约 5~30s
    return url


# ---------------------------------------------------------------------- start

def cmd_start(args) -> None:
    if not PY_EXE.is_file():
        die("venv 不存在: 先运行  python deploy.py setup")
    write_default_config()
    cfg = load_config()
    if args.port:
        cfg["CS_BACKEND_PORT"] = str(args.port)
    port = int(cfg["CS_BACKEND_PORT"])
    missing = models_missing(cfg)
    if missing and not cfg["LLAMA_URL"]:
        die("模型缺失: " + ", ".join(missing) + "\n  运行: python deploy.py fetch-models")

    llama_url = start_llama(cfg)

    pid = read_pid("server")
    if pid and pid_alive(pid):
        info(f"cs-backend 已在运行 (pid={pid}) -> http://127.0.0.1:{port}")
    else:
        if port_busy(port):
            warn(f"端口 {port} 已被占用(可能是早期 run.sh 起的进程): 先 python deploy.py stop")
        env = os.environ.copy()
        env["PYTORCH_ENABLE_MPS_FALLBACK"] = "1"     # MPS 未实现算子回退 CPU(而非报错)
        env["LLAMA_URL"] = llama_url
        if cfg["CS_OCR_DEVICE"]:
            env["CS_OCR_DEVICE"] = cfg["CS_OCR_DEVICE"]
        if cfg["CS_MODELS_DIR"]:
            env["CS_MODELS_DIR"] = cfg["CS_MODELS_DIR"]
        cmd = [str(PY_EXE), "-m", "uvicorn", "server:app", "--host", "0.0.0.0", "--port", str(port)]
        info(f"启动 cs-backend :{port} (OCR 设备={cfg['CS_OCR_DEVICE'] or '自动'})")
        spawn("server", cmd, env, "server.log")
        wait_health(f"http://127.0.0.1:{port}/health", 60, "cs-backend(HTTP)")

    st, body = http_get(f"http://127.0.0.1:{port}/health", 5.0)
    if st != 200:
        warn("健康检查未通过, 看日志: python deploy.py logs")
    else:
        info("health: " + body[:400])
    print()
    for ip in lan_ips():
        info(f"手机 App 后端地址: http://{ip}:{port}  (设置 -> 翻译 -> 后端)")
    info("OCR 首次加载约 5~20s, 期间 /ocr_page 返回 503 属正常; 查看: python deploy.py logs server")


def cmd_stop(args) -> None:
    stop_one("server", "cs-backend")
    stop_one("llama", "llama-server")


def cmd_restart(args) -> None:
    cmd_stop(args)
    time.sleep(1.0)
    cmd_start(args)


# --------------------------------------------------------------------- status

def cmd_status(args) -> None:
    cfg = load_config()
    port = int(cfg["CS_BACKEND_PORT"])
    lp = int(cfg["LLAMA_PORT"])
    for name, label, url in (("llama", "llama-server", f"http://127.0.0.1:{lp}/health"),
                             ("server", "cs-backend", f"http://127.0.0.1:{port}/health")):
        pid = read_pid(name)
        alive = bool(pid) and pid_alive(pid)
        st, body = http_get(url, 3.0)
        state = "运行中" if alive else ("端口有响应(非本部署管理)" if st else "未运行")
        print(f"  {label:<12} pid={pid or '-':<7} {state}")
        if st == 200:
            print(f"    {url} -> {body[:220]}")
    if cfg["LLAMA_URL"]:
        print(f"  LLM 模式     external: {cfg['LLAMA_URL']}")


def _tail(path: Path, n: int) -> str:
    try:
        data = path.read_bytes()
        lines = data.decode("utf-8", "replace").splitlines()
        return "\n".join(lines[-n:])
    except Exception as e:  # noqa: BLE001
        return f"(无法读取 {path.name}: {e})"


def cmd_logs(args) -> None:
    which = getattr(args, "which", None)
    files = {"llama": ["llama_server.log"], "server": ["server.log"]}.get(
        which or "", ["llama_server.log", "server.log"])
    for f in files:
        print(f"----- {f} (tail 40) -----")
        print(_tail(ROOT / f, 40))


# --------------------------------------------------------------------- doctor

def cmd_doctor(args) -> None:
    cfg = load_config()
    print("== 环境 ==")
    print(f"  OS: {sys.platform} {os.uname().machine if hasattr(os, 'uname') else ''}"
          f"  Python(引导): {sys.version.split()[0]}  CPU: {os.cpu_count()}")
    print(f"  目标加速(探测): {detect_accel()}")
    print(f"  config.env: {'存在' if CONFIG.exists() else '未生成(运行 setup 生成)'}")

    print("== 网络/镜像(与 setup 同源的自动探测) ==")
    hf_auto = probe_hf_endpoint()
    official_hf = "官方可达" if hf_auto == "https://huggingface.co" else "官方不通→镜像"
    print(f"  HF 模型端点: {hf_auto}  ({official_hf})")
    st = http_status("https://pypi.org/simple/", 6.0)
    if st == 200:
        print("  PyPI: 官方可达")
    else:
        m = probe_first_ok(PIP_MIRRORS)
        print(f"  PyPI: 官方不通 → {'自动用 ' + m if m else '镜像也不可达!'}")
    accel_now = detect_accel()
    if accel_now in ("cuda", "cpu"):
        tag = "cu126" if accel_now == "cuda" else "cpu"
        print(f"  torch 轮子源: {pick_torch_index(tag)}")

    print("== venv ==")
    if PY_EXE.is_file():
        ver = subprocess.run([str(PY_EXE), "-c", "import sys;print(sys.version.split()[0])"],
                             capture_output=True, text=True).stdout.strip()
        print(f"  Python: {ver}")
        probe = (
            "import json;import torch;"
            "print(json.dumps({'torch':torch.__version__,"
            "'cuda':torch.cuda.is_available(),"
            "'mps':bool(getattr(torch.backends,'mps',None) and torch.backends.mps.is_available())}))"
        )
        r = subprocess.run([str(PY_EXE), "-c", probe], capture_output=True, text=True)
        print(f"  torch: {r.stdout.strip() or r.stderr.strip()[:120]}")
        r = subprocess.run([str(PY_EXE), "-c",
                            "import transformers,onnxruntime as o,cv2;"
                            "print(transformers.__version__, '| ort:', o.get_available_providers(), '| cv2', cv2.__version__)"],
                           capture_output=True, text=True)
        print(f"  依赖: {r.stdout.strip() or r.stderr.strip()[:200]}")
    else:
        print("  venv 不存在 -> python deploy.py setup")

    print("== 模型 ==")
    md = models_dir(cfg)
    for rel, name in (("CTBD/detector.onnx", "CTBD 检测"),
                      ("PaddleOCR-VL-For-Manga/model.safetensors", "PaddleOCR-VL"),
                      (f"hymt/{GGUF_NAME}", "Hy-MT2 GGUF")):
        p = md / rel
        if p.is_file():
            print(f"  [ok] {name:<14} {p.stat().st_size/1e6:.1f}MB  {p}")
        else:
            print(f"  [--] {name:<14} 缺失 -> python deploy.py fetch-models")
    g = md / "hymt" / GGUF_NAME
    if g.is_file() and getattr(args, "full", False):
        h = hashlib.md5()
        with g.open("rb") as f:
            for b in iter(lambda: f.read(8 << 20), b""):
                h.update(b)
        print(f"  GGUF md5: {h.hexdigest()} ({'与基准一致' if h.hexdigest() == GGUF_MD5 else '与基准不一致!'})")

    print("== LLM ==")
    if cfg["LLAMA_URL"]:
        print(f"  external: {cfg['LLAMA_URL']}")
    else:
        b = find_llama_binary(cfg)
        print(f"  llama-server: {b or '未找到'}")
        print(f"  llama-cpp-python: {'已装' if PY_EXE.is_file() and llama_cpp_available() else '未装(可选)'}")

    print("== 运行 ==")
    cmd_status(args)
    for p in (int(cfg["CS_BACKEND_PORT"]), int(cfg["LLAMA_PORT"])):
        if port_busy(p):
            print(f"  端口 {p}: 监听中")

    if getattr(args, "full", False):
        print("== 管线自测(--full: 合成图 detect+OCR, 首次会加载模型) ==")
        code = (
            "import json,numpy as np,cv2,pipeline,time;"
            "img=np.full((720,480,3),255,np.uint8);"
            "cv2.putText(img,'TEST 123',(30,100),cv2.FONT_HERSHEY_SIMPLEX,2,(0,0,0),4);"
            "p=pipeline.Pipeline();"
            "p.load_ocr();"
            "t=time.time();ent,_=p.analyze_png(cv2.imencode('.png',img)[1].tobytes(),fresh=True);"
            "print(json.dumps({'device':p.ocr.device,'det+ocr_ms':round((time.time()-t)*1000,1),"
            "'boxes':len(ent['boxes']),'texts':[t for t in ent['texts'] if t][:3]}))"
        )
        r = subprocess.run([str(PY_EXE), "-c", code], cwd=str(ROOT), capture_output=True, text=True)
        print("  " + (r.stdout.strip() or r.stderr.strip()[-400:]))


# -------------------------------------------------------------------- 一键 up

def cmd_up(args) -> None:
    need_setup = not PY_EXE.is_file()
    if not need_setup:
        miss = models_missing(load_config())
        if miss and not load_config()["LLAMA_URL"]:
            info(f"模型缺失({', '.join(miss)}), 先执行 setup")
            need_setup = True
    if need_setup:
        info("首次运行, 自动 setup(装依赖 + 下模型, 国内自动走镜像; 可 Ctrl+C 中断, 重跑续传)…")
        cmd_setup(args)
    cmd_start(args)


# ------------------------------------------------------------------------ main

def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="deploy.py", description="cs-backend 跨平台部署器",
                                 formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog=__doc__.split("配置:")[0])
    sub = ap.add_subparsers(dest="cmd")

    p = sub.add_parser("setup", help="建 venv + 装依赖 + 下模型")
    p.add_argument("--cpu", action="store_true", help="强制 CPU 版 torch")
    p.add_argument("--cuda", action="store_true", help="强制 CUDA 版 torch/onnxruntime")
    p.add_argument("--no-models", action="store_true", help="只装依赖不下模型")
    p.add_argument("--mirror", action="store_true", help="模型走 hf-mirror.com")
    p.set_defaults(func=cmd_setup)

    p = sub.add_parser("fetch-models", help="下载三个模型到 ./models(官方不通自动走镜像)")
    p.add_argument("--mirror", action="store_true", help="走 hf-mirror.com")
    p.set_defaults(func=lambda a: (ensure_venv(), run_fetch_models(load_config(), True if a.mirror else None)))

    p = sub.add_parser("start", help="启动 llama-server + cs-backend")
    p.add_argument("--port", type=int, help="cs-backend 端口(默认 config.env 的 8787)")
    p.set_defaults(func=cmd_start)

    p = sub.add_parser("stop", help="停止两个服务")
    p.set_defaults(func=cmd_stop)

    p = sub.add_parser("restart", help="stop + start")
    p.add_argument("--port", type=int)
    p.set_defaults(func=cmd_restart)

    p = sub.add_parser("status", help="运行状态 + health")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("logs", aliases=["log"], help="看日志(默认两个都看)")
    p.add_argument("which", nargs="?", choices=["llama", "server"])
    p.set_defaults(func=cmd_logs)

    p = sub.add_parser("doctor", help="环境体检(含网络/镜像探测; --full 再加管线自测)")
    p.add_argument("--full", action="store_true")
    p.set_defaults(func=cmd_doctor)

    p = sub.add_parser("up", help="一键: 缺环境自动 setup, 然后 start(默认命令)")
    p.add_argument("--cpu", action="store_true")
    p.add_argument("--cuda", action="store_true")
    p.add_argument("--no-models", action="store_true")
    p.add_argument("--mirror", action="store_true")
    p.add_argument("--port", type=int)
    p.set_defaults(func=cmd_up)
    return ap


def main() -> None:
    ap = build_parser()
    args = ap.parse_args()
    if not getattr(args, "func", None):
        args = ap.parse_args(["up"])
    try:
        args.func(args)
    except KeyboardInterrupt:
        print("\n[deploy] 已中断", flush=True)
        sys.exit(130)


if __name__ == "__main__":
    main()
