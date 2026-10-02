#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""可行性门/回归: CTBD(onnx) + PaddleOCR-VL 的速度实测(单页与多页计时 + 输出目检)。

用法:
    python timing_test.py <页面图片目录>     # 目录内放若干 *.jpg 页图

模型从标准布局 ./models 读取(与 server.py 同一套解析逻辑, 见 pipeline.py);
CTBD 预处理/后处理与参考实现 detector_ctbd.py + 设备端 ctbd_qnn.cpp 后处理链同构
(5px / IoU0.7 union / region collapse / containment 0.8 降序)。
"""
import os, sys, time, glob
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
import numpy as np
import cv2
import onnxruntime as ort
import torch

import pipeline

CTBD_ONNX = str(pipeline.CTBD_ONNX)
OCR_MODEL = str(pipeline.OCR_MODEL)
PAGES = sorted(glob.glob(os.path.join(
    sys.argv[1] if len(sys.argv) > 1 else "pages", "*.jpg")))
if not PAGES:
    sys.exit("用法: python timing_test.py <页面图片目录>（目录内需含 *.jpg）")

# ---------------- CTBD (BT preprocessing + device postproc chain) ----------------
def iou(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    ua = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter
    return inter / ua if ua > 0 else 0.0

def contained(inner, outer):
    x1, y1 = max(inner[0], outer[0]), max(inner[1], outer[1])
    x2, y2 = min(inner[2], outer[2]), min(inner[3], outer[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    ia = (inner[2]-inner[0])*(inner[3]-inner[1])
    return inter / ia if ia > 0 else 0.0

def postproc(texts, bubbles, page_w, page_h):
    """设备端后处理链(ctbd_qnn.cpp L410-530 的 python 移植)。boxes 在 640 空间,
    region 阈值按页比例映射到 640(squash 空间 x/y 各向 640/W, 640/H)。"""
    def five_px(v):  return [r for r in v if r[2]-r[0] > 5 and r[3]-r[1] > 5]
    def merge(v):
        m = len(v); adj = [[False]*m for _ in range(m)]
        for i in range(m):
            for j in range(i+1, m):
                if iou(v[i][:4], v[j][:4]) >= 0.7: adj[i][j] = adj[j][i] = True
        vis = [False]*m; out = []
        for s in range(m):
            if vis[s]: continue
            q = [s]; vis[s] = True; u = list(v[s])
            h = 0
            while h < len(q):
                for t in range(m):
                    if not vis[t] and adj[q[h]][t]:
                        vis[t] = True; q.append(t)
                        u[0] = min(u[0], v[t][0]); u[1] = min(u[1], v[t][1])
                        u[2] = max(u[2], v[t][2]); u[3] = max(u[3], v[t][3])
                h += 1
            out.append(u)
        return out
    def region_collapse(v, sx, sy):
        kidw, kidh = 0.12*640.0, 0.0375*640.0   # 640 空间阈值
        drop = [False]*len(v)
        for i in range(len(v)):
            sub = 0
            for j in range(len(v)):
                if i == j or contained(v[j][:4], v[i][:4]) < 0.8: continue
                if (v[j][2]-v[j][0]) >= kidw and (v[j][3]-v[j][1]) >= kidh:
                    sub += 1
                    if sub >= 2: break
            if sub >= 2: drop[i] = True
        return [r for i, r in enumerate(v) if not drop[i]]
    def containment(v):
        order = sorted(range(len(v)), key=lambda i: -(v[i][2]-v[i][0])*(v[i][3]-v[i][1]))
        kept = []  # keep-scan: 面积降序, 已被包含的删除
        keep = [True]*len(v)
        for oi, i in enumerate(order):
            if not keep[i]: continue
            for j in order[oi+1:]:
                if keep[j] and contained(v[j][:4], v[i][:4]) >= 0.8:
                    keep[j] = False
        return [v[i] for i in range(len(v)) if keep[i]]
    out = []
    for cat in (texts, bubbles):
        v = five_px(cat)
        if not v: continue
        v = merge(v)
        v = region_collapse(v, 640.0/page_w, 640.0/page_h)
        v = containment(v)
        out.extend(v)
    return out

def detect(sess, img, conf=0.3):
    h, w = img.shape[:2]
    resized = cv2.resize(img, (640, 640), interpolation=cv2.INTER_LINEAR)
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)        # BT 原样(含其通道约定)
    x = np.expand_dims(rgb.transpose(2, 0, 1), 0).astype(np.float32) / 255.0
    sizes = np.array([[w, h]], dtype=np.int64)
    labels, boxes, scores = sess.run(None, {"images": x, "orig_target_sizes": sizes})
    t, b = [], []
    for box, sc, lab in zip(boxes[0], scores[0], labels[0]):
        if sc < conf: continue
        r = [float(v) for v in box]
        if lab == 0: b.append(r)
        elif lab in (1, 2): t.append(r)
    return postproc(t, b, w, h)

# ---------------- PaddleOCR-VL on MPS ----------------
def load_ocr(device):
    from transformers import AutoModelForCausalLM, AutoProcessor
    dtype = torch.float16 if device in ("mps", "cuda") else torch.float32
    t0 = time.time()
    model = AutoModelForCausalLM.from_pretrained(
        OCR_MODEL, trust_remote_code=True, dtype=dtype).to(device).eval()
    processor = AutoProcessor.from_pretrained(
        OCR_MODEL, trust_remote_code=True, use_fast=True, add_prefix_space=None)
    if model.generation_config.pad_token_id is None:
        model.generation_config.pad_token_id = processor.tokenizer.eos_token_id
    print(f"[ocr] load {time.time()-t0:.1f}s device={device} dtype={dtype}", flush=True)
    return model, processor

def ocr_crop(model, processor, img, box, max_new=512):
    x1, y1, x2, y2 = [int(round(v)) for v in box]
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(img.shape[1], x2), min(img.shape[0], y2)
    if x2 - x1 < 4 or y2 - y1 < 4: return ""
    crop = img[y1:y2, x1:x2]
    rh, rw = crop.shape[:2]
    if max(rh, rw) > 1024:
        s = 1024.0 / max(rh, rw)
        crop = cv2.resize(crop, (int(rw*s), int(rh*s)), interpolation=cv2.INTER_AREA)
    messages = [{"role": "user", "content": [
        {"type": "image", "image": crop}, {"type": "text", "text": "OCR:"}]}]
    text = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = processor(text=[text], images=[crop], return_tensors="pt")
    inputs = {k: (v.to(model.device) if isinstance(v, torch.Tensor) else v)
              for k, v in inputs.items()}
    with torch.inference_mode():
        gen = model.generate(**inputs, max_new_tokens=max_new, do_sample=False, use_cache=True)
    n_in = inputs["input_ids"].shape[1]
    return processor.batch_decode(gen[:, n_in:], skip_special_tokens=True)[0].strip()

def main():
    dev = sys.argv[1] if len(sys.argv) > 1 else "mps"
    page = cv2.imread(PAGES[0])
    print(f"[page] {PAGES[0]} {page.shape}", flush=True)
    # ---- CTBD
    t0 = time.time()
    sess = ort.InferenceSession(CTBD_ONNX, providers=["CPUExecutionProvider"])
    t_load = time.time() - t0
    t0 = time.time(); boxes = detect(sess, page); t_det = time.time() - t0
    t0 = time.time(); _ = detect(sess, page); t_det2 = time.time() - t0
    print(f"[ctbd] load {t_load:.1f}s | infer {t_det*1000:.0f}ms (warm {t_det2*1000:.0f}ms) "
          f"| {len(boxes)} boxes", flush=True)
    # ---- OCR on detected boxes (first page)
    model, processor = load_ocr(dev)
    boxes_sorted = sorted(boxes, key=lambda r: (r[1], r[0]))
    n = min(len(boxes_sorted), int(os.environ.get("OCR_N", "6")))
    t0 = time.time(); first = None
    for i, b in enumerate(boxes_sorted[:n]):
        ts = time.time(); txt = ocr_crop(model, processor, page, b[:4])
        if first is None: first = time.time() - ts
        print(f"  crop{i}: {(time.time()-ts)*1000:.0f}ms -> {txt[:36]!r}", flush=True)
    tot = time.time() - t0
    print(f"[ocr] {n} crops: first {first*1000:.0f}ms, total {tot:.1f}s "
          f"(avg {tot/max(1,n)*1000:.0f}ms/crop)", flush=True)

if __name__ == "__main__":
    main()

# ---------------- 批量 OCR 测试(左 padding 一次 generate) ----------------
def ocr_batch(model, processor, img, boxes, max_new=512):
    crops, metas = [], []
    for b in boxes:
        x1, y1, x2, y2 = [int(round(v)) for v in b[:4]]
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(img.shape[1], x2), min(img.shape[0], y2)
        if x2-x1 < 4 or y2-y1 < 4: continue
        c = img[y1:y2, x1:x2]
        rh, rw = c.shape[:2]
        if max(rh, rw) > 1024:
            s = 1024.0/max(rh, rw)
            c = cv2.resize(c, (int(rw*s), int(rh*s)), interpolation=cv2.INTER_AREA)
        crops.append(c)
    if not crops: return []
    msgs = [[{"role": "user", "content": [
        {"type": "image", "image": c}, {"type": "text", "text": "OCR:"}]}] for c in crops]
    texts = [processor.apply_chat_template(m, tokenize=False, add_generation_prompt=True)
             for m in msgs]
    processor.tokenizer.padding_side = "left"
    inputs = processor(text=texts, images=crops, return_tensors="pt", padding=True)
    inputs = {k: (v.to(model.device) if isinstance(v, torch.Tensor) else v)
              for k, v in inputs.items()}
    with torch.inference_mode():
        gen = model.generate(**inputs, max_new_tokens=max_new, do_sample=False, use_cache=True)
    n_in = inputs["input_ids"].shape[1]
    return [t.strip() for t in processor.batch_decode(gen[:, n_in:], skip_special_tokens=True)]
