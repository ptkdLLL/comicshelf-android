#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Mac 后端管线: CTBD 检测 → 后处理(与设备端 ctbd_qnn.cpp 同构) → PaddleOCR-VL(MPS) 识别。

对齐来源(三处一致, 改动需三处同步):
  · BT  `ballontranslator/modules/textdetector/detector_ctbd.py` (640 squash / BGR2RGB / orig_target_sizes)
  · 设备 `ComicShelfAndroid/app/src/main/cpp/ocr/ctbd_qnn.cpp` L410-545
    (conf0.3 / 5px / IoU0.7 union / region collapse / containment 0.8 降序 / vertical=h>1.5w)
  · App JSON schema: {"boxes":[{x0,y0,x1,y1,vertical,score,label,bubble}]}, label0=气泡
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path

import numpy as np
import cv2
import onnxruntime as ort

# ---------------------------------------------------------------- 模型路径(可移植)
# 标准布局: <部署目录>/models/{CTBD/detector.onnx, PaddleOCR-VL-For-Manga/, hymt/*.gguf}
# CS_MODELS_DIR 可覆盖; 缺失时用 `python deploy.py fetch-models` 拉取(HF/镜像)。
def _model_root() -> Path:
    env = os.environ.get("CS_MODELS_DIR", "").strip()
    here = Path(__file__).resolve().parent
    for c in ([Path(env)] if env else []) + [here / "models"]:
        if (c / "CTBD" / "detector.onnx").is_file():
            return c
    return Path(env) if env else here / "models"


def _require(path: Path, what: str) -> Path:
    if not path.exists():
        raise FileNotFoundError(
            f"缺少{what}: {path}\n  请在部署目录运行: python deploy.py fetch-models")
    return path


MODELS = _model_root()
CTBD_ONNX = MODELS / "CTBD" / "detector.onnx"
OCR_MODEL = MODELS / "PaddleOCR-VL-For-Manga"
GGUF = MODELS / "hymt" / "Hy-MT2-1.8B-Q4_K_M.gguf"
CACHE_DIR = Path(__file__).parent / "cache"
CACHE_DIR.mkdir(exist_ok=True)


def pick_device() -> str:
    """OCR 设备自动探测: CS_OCR_DEVICE 覆盖 > CUDA > MPS(mac) > CPU。"""
    env = os.environ.get("CS_OCR_DEVICE", "").strip()
    if env:
        return env
    try:
        import torch
    except Exception:
        return "cpu"
    if torch.cuda.is_available():
        return "cuda"
    mps = getattr(torch.backends, "mps", None)
    if mps is not None and mps.is_available():
        return "mps"
    return "cpu"


def _ort_providers():
    """检测器执行后端: CS_DET_PROVIDERS 覆盖 > CUDA(装了 onnxruntime-gpu 时) > CPU。"""
    env = os.environ.get("CS_DET_PROVIDERS", "").strip()
    if env:
        return [p.strip() for p in env.split(",") if p.strip()]
    avail = ort.get_available_providers()
    if "CUDAExecutionProvider" in avail:
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return ["CPUExecutionProvider"]

PIPELINE_TAG = "bt-mac-v1"
CONF_THRESH = 0.3
DET = 640.0
MIN_SIDE = 5.0          # 640 空间
DEDUP_IOU = 0.7
CONTAINMENT = 0.8
KID_W, KID_H = 0.12 * DET, 0.0375 * DET   # region collapse 阈值(640 空间)


# ------------------------------------------------------------------ geometry

def _iou(a, b) -> float:
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def _contained(inner, outer) -> float:
    """inner 落在 outer 内的面积占比(交面积 / inner 面积)。"""
    x1, y1 = max(inner[0], outer[0]), max(inner[1], outer[1])
    x2, y2 = min(inner[2], outer[2]), min(inner[3], outer[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    ia = (inner[2]-inner[0]) * (inner[3]-inner[1])
    return inter / ia if ia > 0 else 0.0


def _five_px(v):
    return [r for r in v if r[2]-r[0] > MIN_SIDE and r[3]-r[1] > MIN_SIDE]


def _merge_union(v):
    """BT merge_duplicate_boxes: IoU>=0.7 连通分量并成 union 框(保留最大分)。"""
    m = len(v)
    adj = [[False]*m for _ in range(m)]
    for i in range(m):
        for j in range(i+1, m):
            if _iou(v[i], v[j]) >= DEDUP_IOU:
                adj[i][j] = adj[j][i] = True
    vis = [False]*m
    out = []
    for s in range(m):
        if vis[s]:
            continue
        q, vis[s] = [s], True
        u = list(v[s])
        h = 0
        while h < len(q):
            for t in range(m):
                if not vis[t] and adj[q[h]][t]:
                    vis[t] = True
                    q.append(t)
                    u[0] = min(u[0], v[t][0]); u[1] = min(u[1], v[t][1])
                    u[2] = max(u[2], v[t][2]); u[3] = max(u[3], v[t][3])
                    if v[t][4] > u[4]:
                        u[4] = v[t][4]
            h += 1
        out.append(u)
    return out


def _region_collapse(v):
    """区域框让位(设备端 E2 修复): 含 >=2 个段落级子框 => 丢父保子。"""
    drop = [False]*len(v)
    for i in range(len(v)):
        sub = 0
        for j in range(len(v)):
            if i == j or _contained(v[j], v[i]) < CONTAINMENT:
                continue
            if (v[j][2]-v[j][0]) >= KID_W and (v[j][3]-v[j][1]) >= KID_H:
                sub += 1
                if sub >= 2:
                    break
        if sub >= 2:
            drop[i] = True
    return [r for i, r in enumerate(v) if not drop[i]]


def _containment(v):
    """同类包含 >=0.8 删除内框; 面积降序 keep 扫描(BT remove_contained_boxes 语义),
    输出顺序 = 面积降序(与设备端一致, OCR 顺序对齐依赖此)。"""
    order = sorted(range(len(v)),
                   key=lambda i: -((v[i][2]-v[i][0]) * (v[i][3]-v[i][1])))
    kept = []
    for idx in order:
        if not any(_contained(v[idx], k) >= CONTAINMENT for k in kept):
            kept.append(v[idx])
    return kept


# ------------------------------------------------------------------ detect

class Detector:
    def __init__(self):
        t0 = time.time()
        self.providers = _ort_providers()
        self.sess = ort.InferenceSession(str(_require(CTBD_ONNX, "检测模型 CTBD/detector.onnx")),
                                         providers=self.providers)
        self.load_ms = (time.time() - t0) * 1000

    def detect(self, img_bgr: np.ndarray):
        """返回 (boxes, det_ms)。boxes 为 dict 列表(页面坐标, 与设备 JSON 同 schema)。"""
        h, w = img_bgr.shape[:2]
        t0 = time.time()
        resized = cv2.resize(img_bgr, (640, 640), interpolation=cv2.INTER_LINEAR)
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)   # BT 原样(其通道约定如此)
        x = np.expand_dims(rgb.transpose(2, 0, 1), 0).astype(np.float32) / 255.0
        sizes = np.array([[w, h]], dtype=np.int64)
        labels, boxes, scores = self.sess.run(None, {"images": x, "orig_target_sizes": sizes})
        # 注意: ONNX 经 orig_target_sizes 已把框回到**页坐标**(设备 QNN 版输出 640 空间,
        # 由 native 乘 sx 回页坐标——两端实现不同!)。后处理阈值是 640 空间量纲
        # (5px / region 12%x3.75%), 故先归一 640 空间跑链, 输出再回页坐标。
        sx, sy = w / DET, h / DET
        lists = [[], []]                                  # [0]=气泡(0), [1]=文字(1/2)
        for box, sc, lab in zip(boxes[0], scores[0], labels[0]):
            if sc < CONF_THRESH:
                continue
            lab = int(lab)
            if lab not in (0, 1, 2):
                continue
            b = [float(box[0]) / sx, float(box[1]) / sy,
                 float(box[2]) / sx, float(box[3]) / sy]
            lists[0 if lab == 0 else 1].append(b + [float(sc), lab])
        out = []
        for c in (0, 1):                                  # 与设备同序: 气泡在前
            v = lists[c]
            v = _five_px(v)
            if v:
                v = _merge_union(v)
                v = _region_collapse(v)
                v = _containment(v)
            for r in v:
                bw, bh = (r[2]-r[0]) * sx, (r[3]-r[1]) * sy
                out.append({
                    "x0": r[0]*sx, "y0": r[1]*sy, "x1": r[2]*sx, "y1": r[3]*sy,
                    "vertical": bh > bw * 1.5,
                    "score": round(r[4], 3),
                    "label": 0 if c == 0 else int(r[5]),
                    "bubble": c == 0,
                })
        return out, (time.time() - t0) * 1000


# ------------------------------------------------------------------ OCR (MPS)

class Ocr:
    def __init__(self, device: str | None = None):
        self.lock = threading.Lock()
        self.ready = False
        self.device = device or pick_device()
        self.model = None
        self.processor = None
        self.load_ms = 0.0

    def load(self):
        t0 = time.time()
        import torch
        from transformers import AutoModelForCausalLM, AutoProcessor
        if self.device == "cuda" and not torch.cuda.is_available():
            print("[pipeline] CUDA 不可用, OCR 回退 CPU", flush=True)
            self.device = "cpu"
        mps = getattr(torch.backends, "mps", None)
        if self.device == "mps" and (mps is None or not mps.is_available()):
            print("[pipeline] MPS 不可用, OCR 回退 CPU", flush=True)
            self.device = "cpu"
        dtype = torch.float16 if self.device in ("mps", "cuda") else torch.float32
        model_dir = _require(OCR_MODEL, "OCR 模型 PaddleOCR-VL-For-Manga/")
        self.model = AutoModelForCausalLM.from_pretrained(
            str(model_dir), trust_remote_code=True, dtype=dtype).to(self.device).eval()
        self.processor = AutoProcessor.from_pretrained(
            str(model_dir), trust_remote_code=True, use_fast=True, add_prefix_space=None)
        if self.model.generation_config.pad_token_id is None:
            self.model.generation_config.pad_token_id = self.processor.tokenizer.eos_token_id
        self.ready = True
        self.load_ms = (time.time() - t0) * 1000
        self._warmup()

    def _warmup(self):
        """MPS 首次 kernel 编译 ~2.8s: 启动时用合成小图预热, 首真页不再吃这个成本。"""
        try:
            dummy = np.full((64, 64, 3), 255, np.uint8)
            self.ocr_crop(dummy)
        except Exception:
            pass

    def ocr_crop(self, crop_bgr: np.ndarray, max_new: int = 512) -> str:
        """单块识别(BT ocr_img 同构: >1024 先缩, 无采样 greedy)。"""
        import torch
        rh, rw = crop_bgr.shape[:2]
        if max(rh, rw) > 1024:
            s = 1024.0 / max(rh, rw)
            crop_bgr = cv2.resize(crop_bgr, (int(rw*s), int(rh*s)),
                                  interpolation=cv2.INTER_AREA)
        messages = [{"role": "user", "content": [
            {"type": "image", "image": crop_bgr}, {"type": "text", "text": "OCR:"}]}]
        text = self.processor.apply_chat_template(messages, tokenize=False,
                                                  add_generation_prompt=True)
        inputs = self.processor(text=[text], images=[crop_bgr], return_tensors="pt")
        inputs = {k: (v.to(self.model.device) if isinstance(v, torch.Tensor) else v)
                  for k, v in inputs.items()}
        with torch.inference_mode():
            gen = self.model.generate(**inputs, max_new_tokens=max_new,
                                      do_sample=False, use_cache=True)
        n_in = inputs["input_ids"].shape[1]
        return self.processor.batch_decode(gen[:, n_in:],
                                           skip_special_tokens=True)[0].strip()


# ------------------------------------------------------------------ page (detect + ocr)

def _hash_bytes(b: bytes) -> str:
    return hashlib.sha1(b).hexdigest()


class Pipeline:
    """整页: PNG bytes -> boxes + texts(与 boxes 等长, 气泡/失败段为空串)。线程安全。"""

    def __init__(self, device: str | None = None):
        self.det = Detector()
        self.ocr = Ocr(device)
        self.lock = threading.Lock()
        self._ram = {}

    def load_ocr(self):
        self.ocr.load()

    def analyze_png(self, png_bytes: bytes, fresh: bool = False):
        key = _hash_bytes(png_bytes)
        if not fresh:
            ent = self._ram.get(key)
            if ent is not None:
                return ent, True
            disk = CACHE_DIR / f"{key}.json"
            if disk.is_file():
                try:
                    ent = json.loads(disk.read_text("utf-8"))
                    self._ram[key] = ent
                    return ent, True
                except Exception:
                    pass
        img = cv2.imdecode(np.frombuffer(png_bytes, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError("无法解码图像(PNG/JPEG)")
        h, w = img.shape[:2]
        t0 = time.time()
        boxes, det_ms = self.det.detect(img)
        # OCR: 只认文字框(label!=0); 气泡条目 texts 留空。逐块顺序识别(批量实测无收益)。
        texts = []
        t_ocr = time.time()
        n_text = 0
        with self.lock:
            for b in boxes:
                if b["label"] == 0:
                    texts.append("")
                    continue
                x0, y0 = int(max(0, b["x0"])), int(max(0, b["y0"]))
                x1, y1 = int(min(w, b["x1"])), int(min(h, b["y1"]))
                if x1 - x0 < 4 or y1 - y0 < 4:
                    texts.append("")
                    continue
                t = self.ocr.ocr_crop(img[y0:y1, x0:x1])
                texts.append(t)
                n_text += 1
        ocr_ms = (time.time() - t_ocr) * 1000
        ent = {
            "w": w, "h": h,
            "pipeline": PIPELINE_TAG,
            "boxes": boxes, "texts": texts,
            "timings": {"det": det_ms, "ocr": ocr_ms, "blocks": n_text,
                        "total": (time.time() - t0) * 1000},
        }
        self._ram[key] = ent
        if len(self._ram) > 64:
            self._ram.pop(next(iter(self._ram)))
        try:
            disk.write_text(json.dumps(ent, ensure_ascii=False), "utf-8")
        except Exception:
            pass
        return ent, False
