#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载 cs-backend 所需三个模型到标准布局(在 venv 里运行, 依赖 huggingface_hub)。

  models/CTBD/detector.onnx                  ~161MB  漫画气泡/文字检测 (ogkalu/comic-text-and-bubble-detector)
  models/PaddleOCR-VL-For-Manga/             ~1.8GB  日漫 OCR (jzhang533/PaddleOCR-VL-For-Manga)
  models/hymt/Hy-MT2-1.8B-Q4_K_M.gguf        ~1.1GB  翻译 LLM (tencent/Hy-MT2-1.8B-GGUF)

镜像: huggingface_hub 自动读 HF_ENDPOINT, 国内用 https://hf-mirror.com
      (deploy.py fetch-models --mirror 会代设)。下载可断点续传, 重跑即续。
"""
from __future__ import annotations

import hashlib
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODELS = Path(os.environ.get("CS_MODELS_DIR") or (ROOT / "models"))

CTBD_REPO, CTBD_FILE = "ogkalu/comic-text-and-bubble-detector", "detector.onnx"
OCR_REPO = "jzhang533/PaddleOCR-VL-For-Manga"
GGUF_REPO, GGUF_FILE = "tencent/Hy-MT2-1.8B-GGUF", "Hy-MT2-1.8B-Q4_K_M.gguf"
GGUF_MD5 = "436f3ec23b236b2ac1d05dd7a713f8ae"   # 实测部署同款, 防半包/换量化


def _hf():
    try:
        from huggingface_hub import hf_hub_download, snapshot_download
    except ImportError:
        sys.exit("[fetch] 缺少 huggingface_hub: 先跑 python deploy.py setup")
    return hf_hub_download, snapshot_download


def _md5(path: Path, chunk: int = 8 << 20) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def _size(p: Path) -> str:
    n = p.stat().st_size if p.is_file() else sum(
        f.stat().st_size for f in p.rglob("*") if f.is_file())
    return f"{n / 1e9:.2f}GB"


def main() -> int:
    hf_hub_download, snapshot_download = _hf()
    end = os.environ.get("HF_ENDPOINT", "https://huggingface.co")
    print(f"[fetch] 模型目录: {MODELS}\n[fetch] HF 端点: {end}\n", flush=True)
    MODELS.mkdir(parents=True, exist_ok=True)

    # 1) CTBD 检测器
    f = MODELS / "CTBD" / CTBD_FILE
    if f.is_file() and f.stat().st_size > 100e6:
        print(f"[fetch] CTBD 已存在 ({_size(f)}), 跳过")
    else:
        target = MODELS / "CTBD"
        target.mkdir(parents=True, exist_ok=True)
        print(f"[fetch] 下载 CTBD detector.onnx …", flush=True)
        hf_hub_download(CTBD_REPO, CTBD_FILE, local_dir=target)
        print(f"[fetch] CTBD 完成 {_size(f)}")

    # 2) PaddleOCR-VL-For-Manga (整仓快照: 权重 + trust_remote_code 的 .py)
    d = MODELS / "PaddleOCR-VL-For-Manga"
    if (d / "model.safetensors").is_file():
        print(f"[fetch] PaddleOCR-VL 已存在 ({_size(d)}), 跳过")
    else:
        print(f"[fetch] 下载 PaddleOCR-VL-For-Manga (约1.8GB, 可断点续传) …", flush=True)
        snapshot_download(OCR_REPO, local_dir=d,
                          allow_patterns=["*.json", "*.py", "*.model", "*.safetensors",
                                          "*.jinja", "*.txt"])
        print(f"[fetch] PaddleOCR-VL 完成 {_size(d)}")

    # 3) Hy-MT2 GGUF
    g = MODELS / "hymt" / GGUF_FILE
    if g.is_file() and _md5(g) == GGUF_MD5:
        print(f"[fetch] Hy-MT2 GGUF 已存在且校验通过 ({_size(g)}), 跳过")
        return 0
    (MODELS / "hymt").mkdir(parents=True, exist_ok=True)
    print(f"[fetch] 下载 {GGUF_FILE} (约1.1GB) …", flush=True)
    hf_hub_download(GGUF_REPO, GGUF_FILE, local_dir=MODELS / "hymt")
    got = _md5(g)
    if got != GGUF_MD5:
        print(f"[fetch] ⚠ GGUF md5={got} 与实测基准 {GGUF_MD5} 不一致"
              f"(上游可能更新)。仍可用, 但翻译行为有变时先怀疑这里。")
    else:
        print(f"[fetch] Hy-MT2 GGUF 校验通过 {_size(g)}")

    print("\n[fetch] 全部就绪。启动: python deploy.py start")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[fetch] 已中断(重跑续传)")
        sys.exit(130)
