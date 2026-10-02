#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""cs-backend: 漫画翻译的局域网后端(OCR 在本机; 翻译转发给 llama-server)。

端点:
  GET  /health          服务/模型状态
  POST /ocr_page        整页图(PNG/JPEG bytes) -> {w,h,pipeline,boxes[],texts[],timings}
  POST /chat            {system,user,max_tokens,temperature,top_p,frequency_penalty}
                        -> 转发 llama-server /v1/chat/completions -> {content}

启动:
  ./venv/bin/uvicorn server:app --host 0.0.0.0 --port 8787
(llama-server 另起: llama-server -m models/Hy-MT2-1.8B-Q4_K_M.gguf --port 8080 -ngl 99)
"""
from __future__ import annotations

import json
import os
import threading
import time

import requests
from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse

import pipeline

LLAMA_URL = os.environ.get("LLAMA_URL", "http://127.0.0.1:8080")
PORT = int(os.environ.get("CS_BACKEND_PORT", "8787"))

app = FastAPI(title="cs-backend", version=pipeline.PIPELINE_TAG)
PIPE = pipeline.Pipeline(device=os.environ.get("CS_OCR_DEVICE") or None)  # 空=自动(cuda>mps>cpu)
_load_err = ""


def _load_bg():
    global _load_err
    t0 = time.time()
    try:
        PIPE.load_ocr()
        print(f"[cs-backend] OCR ready in {time.time()-t0:.1f}s (device={PIPE.ocr.device})",
              flush=True)
    except Exception as e:  # noqa: BLE001
        _load_err = f"{type(e).__name__}: {e}"
        print(f"[cs-backend] OCR load FAILED: {_load_err}", flush=True)


@app.on_event("startup")
def _startup():
    print(f"[cs-backend] det load {PIPE.det.load_ms:.0f}ms; OCR loading in background…",
          flush=True)
    threading.Thread(target=_load_bg, daemon=True).start()


@app.get("/health")
def health():
    llm = {"ok": False}
    try:
        r = requests.get(LLAMA_URL + "/health", timeout=1.5)
        llm = {"ok": r.status_code == 200, "url": LLAMA_URL}
    except Exception as e:  # noqa: BLE001
        llm = {"ok": False, "url": LLAMA_URL, "error": str(e)[:120]}
    return {
        "ok": True, "pipeline": pipeline.PIPELINE_TAG, "device": PIPE.ocr.device,
        "det_providers": PIPE.det.providers,
        "det_ms": round(PIPE.det.load_ms, 1),
        "ocr": {"ready": PIPE.ocr.ready, "load_ms": round(PIPE.ocr.load_ms, 1),
                "error": _load_err},
        "llm": llm,
    }


@app.post("/ocr_page")
async def ocr_page(request: Request):
    body = await request.body()
    if not body:
        return JSONResponse({"error": "empty body"}, status_code=400)
    if not PIPE.ocr.ready:
        return JSONResponse({"error": f"OCR 未就绪: {_load_err or 'loading'}"},
                            status_code=503)
    fresh = request.query_params.get("fresh") in ("1", "true", "yes")
    t0 = time.time()
    try:
        ent, cached = PIPE.analyze_png(body, fresh=fresh)
    except Exception as e:  # noqa: BLE001
        return JSONResponse({"error": f"{type(e).__name__}: {e}"}, status_code=500)
    out = dict(ent)
    out["cached"] = cached
    out["ms"] = round((time.time() - t0) * 1000, 1)
    return out


@app.post("/chat")
async def chat(request: Request):
    try:
        req = json.loads(await request.body())
    except Exception:  # noqa: BLE001
        return JSONResponse({"error": "bad json"}, status_code=400)
    payload = {
        "model": "local",
        "messages": [
            {"role": "system", "content": req.get("system", "")},
            {"role": "user", "content": req.get("user", "")},
        ],
        "temperature": req.get("temperature", 0.1),
        "top_p": req.get("top_p", 0.3),
        "max_tokens": req.get("max_tokens", 1024),
        "frequency_penalty": req.get("frequency_penalty", 0.05),
        "seed": -1,
    }
    t0 = time.time()
    try:
        r = requests.post(LLAMA_URL + "/v1/chat/completions", json=payload,
                          timeout=180)
        if r.status_code != 200:
            return JSONResponse({"error": f"llama-server {r.status_code}: {r.text[:200]}"},
                                status_code=502)
        d = r.json()
        return {"content": d["choices"][0]["message"]["content"],
                "ms": round((time.time() - t0) * 1000, 1),
                "usage": d.get("usage")}
    except Exception as e:  # noqa: BLE001
        return JSONResponse({"error": f"{type(e).__name__}: {e}"}, status_code=502)
