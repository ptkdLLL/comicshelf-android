# -*- coding: utf-8 -*-

from __future__ import annotations
"""下载器：HTTP 代理（Clash 混合端口等）+ 断点续传（Range）+ 体积/sha256 校验 + 清单管理。

设计（对齐 S0/M0 实测教训）：
  - 一律走 Python 网络栈（urllib）；curl 探测不通不代表不通（M0 记录）；
  - 先落 .part，校验通过才改名 —— 中断/损坏永不污染已缓存的好文件；
  - 已缓存文件在"版本未变"时零网络开销跳过（manifest 记录版本/体积/sha）。
"""
import hashlib
import json
import os
import time
import urllib.error
import urllib.request

from .sources import UA, Source

CHUNK = 1 << 20
MANIFEST = "cache_manifest.json"


# ---------------------------------------------------------------- 基础

def _opener(proxy: str | None):
    if proxy:
        h = urllib.request.ProxyHandler({"http": proxy, "https": proxy})
        return urllib.request.build_opener(h)
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))  # 显式直连


def http_json(url: str, proxy: str | None, timeout: int = 30) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/vnd.github+json"})
    with _opener(proxy).open(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def sha256_file(path: str, progress=None) -> str:
    h = hashlib.sha256()
    done = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 22)
            if not b:
                break
            h.update(b)
            done += len(b)
            if progress:
                progress(done)
    return h.hexdigest()


# ---------------------------------------------------------------- 清单

def load_manifest(workdir: str) -> dict:
    p = os.path.join(workdir, MANIFEST)
    if os.path.isfile(p):
        try:
            with open(p, encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_manifest(workdir: str, m: dict):
    os.makedirs(workdir, exist_ok=True)
    tmp = os.path.join(workdir, MANIFEST + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
    os.replace(tmp, os.path.join(workdir, MANIFEST))


# ---------------------------------------------------------------- GitHub 源

def github_latest(repo: str, proxy: str | None) -> tuple[str, dict]:
    """→ (tag, {asset_name: {size, url}})。"""
    data = http_json(f"https://api.github.com/repos/{repo}/releases/latest", proxy)
    tag = data.get("tag_name") or ""
    assets = {}
    for a in data.get("assets") or []:
        assets[a["name"]] = {"size": int(a.get("size") or 0), "url": a.get("browser_download_url")}
    return tag, assets


# ---------------------------------------------------------------- 下载

def fetch_url(url: str, dest: str, *, proxy=None, expected_size=0,
              progress=None, resume=True, timeout=60, log=None) -> tuple[int, str]:
    """下载到 dest（.part 中转 + 校验后改名）。progress(done, total)。→ (size, sha256)。"""
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    part = dest + ".part"
    done = 0
    if resume and os.path.isfile(part):
        done = os.path.getsize(part)
    if os.path.isfile(dest) and expected_size and os.path.getsize(dest) == expected_size:
        return os.path.getsize(dest), sha256_file(dest)   # 已就位（调用方另有版本判断）

    headers = {"User-Agent": UA}
    mode = "wb"
    if done > 0:
        headers["Range"] = f"bytes={done}-"
        mode = "ab"
        if log:
            log(f"  续传：从 {done:,}B 继续（HTTP Range）")
    req = urllib.request.Request(url, headers=headers)
    with _opener(proxy).open(req, timeout=timeout) as r:
        total = expected_size or (int(r.headers.get("Content-Length") or 0) + (done if r.status == 206 else 0))
        if r.status != 206 and done > 0:
            # 服务端不支持续传 → 重新开始
            if log:
                log("  服务端未响应 Range（非 206）→ 重新开始")
            done = 0
            mode = "wb"
        if progress:
            progress(done, total)
        with open(part, mode) as f:
            while True:
                b = r.read(CHUNK)
                if not b:
                    break
                f.write(b)
                done += len(b)
                if progress:
                    progress(done, total)
    size = os.path.getsize(part)
    if expected_size and size != expected_size:
        raise IOError(f"体积不符：{size} != {expected_size}（{os.path.basename(dest)}，.part 保留可续传）")
    sha = sha256_file(part)
    os.replace(part, dest)
    return size, sha



def _retry(fn, *, times=3, delay=3, log=None, what="网络请求"):
    """代理网络抖动是常态：统一 3 次重试（自包含，不依赖调用方）。"""
    last = None
    for i in range(1, times + 1):
        try:
            return fn()
        except Exception as e:
            last = e
            if log:
                log(f"  {what}第 {i}/{times} 次失败：{type(e).__name__}: {e}")
            if i < times:
                time.sleep(delay)
    raise last


def check_source(s: Source, workdir: str, proxy: str | None, manifest: dict, log=None) -> dict:
    """查源的最新版本信息（零/极小网络开销）。
    → {"changed": bool, "version": str, "url": str, "size": int, "cached": bool}
    """
    cached = manifest.get(s.key, {})
    local = os.path.join(workdir, s.local_rel)
    has_file = os.path.isfile(local)

    if s.kind == "github":
        tag, assets = _retry(lambda: github_latest(s.repo, proxy), log=log,
                             what=f"[{s.key}] 检查上游版本")
        a = assets.get(s.asset)
        if not a:
            raise IOError(f"{s.repo} 最新 release（{tag}）里没有资产 {s.asset}；"
                          f"实际资产：{', '.join(assets) or '（空）'}")
        changed = cached.get("version") != tag
        return {"changed": changed or not has_file, "version": tag,
                "url": a["url"], "size": a["size"], "cached": has_file and not changed}
    # http 固定源：版本 = sha 前 12 位
    changed = (cached.get("sha256") != s.sha256) or not has_file
    if has_file and not changed:  # 体积抽查（防被截断）
        if s.size and os.path.getsize(local) != s.size:
            changed = True
    return {"changed": changed, "version": (s.sha256 or "static")[:12],
            "url": s.url, "size": s.size, "cached": has_file and not changed}


def ensure_source(s: Source, workdir: str, proxy: str | None, manifest: dict,
                  log=print, progress=None, force=False) -> bool:
    """确保源已缓存且校验通过。→ True=已就位（含新下载），False=可选源跳过。"""
    local = os.path.join(workdir, s.local_rel)
    if not force and os.path.isfile(local):
        if s.size and os.path.getsize(local) == s.size:
            log(f"  [{s.key}] 已缓存（{os.path.getsize(local):,}B）")
            return True
        if not s.size:   # 动态源：靠 manifest 版本判定在 check_source 里做
            log(f"  [{s.key}] 已缓存（{os.path.getsize(local):,}B）")
            return True
    if s.optional and not os.path.isfile(local):
        log(f"  [{s.key}] 可选源未缓存 —— 跳过（{s.label}）")
        return False

    st = check_source(s, workdir, proxy, manifest, log=log)
    if not st["changed"] and os.path.isfile(local):
        log(f"  [{s.key}] 已是最新（{st['version']}）")
        return True

    log(f"  [{s.key}] 下载 {st['version']} → {s.local_rel}（{st['size'] or '?'}B）")
    t0 = time.time()
    size, sha, last_err = None, None, None
    for attempt in range(1, 4):   # 代理网络抖动是常态：3 次重试 + 自动续传（.part）
        try:
            size, sha = fetch_url(st["url"], local, proxy=proxy,
                                  expected_size=st["size"] if s.size else 0,
                                  progress=(lambda d, t: progress(s.key, d, t)) if progress else None,
                                  log=log)
            if s.sha256 and sha != s.sha256:
                raise IOError(f"[{s.key}] sha256 不符：{sha} != {s.sha256}")
            break
        except Exception as e:
            last_err = e
            size, sha = None, None
            if "sha256 不符" in str(e) and os.path.isfile(local):
                os.remove(local)      # 同尺寸损坏（罕见）：清掉坏文件重下
            log(f"  [{s.key}] 第 {attempt}/3 次失败：{type(e).__name__}: {e}")
            if attempt < 3:
                log("  3s 后重试（自动续传）…")
                time.sleep(3)
    if size is None:
        raise IOError(f"[{s.key}] 重试 3 次仍失败：{last_err}")
    manifest[s.key] = {"version": st["version"], "size": size, "sha256": sha,
                       "fetched_at": time.strftime("%Y-%m-%d %H:%M:%S")}
    save_manifest(workdir, manifest)
    log(f"  [{s.key}] 完成 {size:,}B sha256={sha[:16]}… {time.time() - t0:.0f}s")
    return True


def extract_zip(zip_path: str, member: str, out_path: str, log=print):
    """从 zip 解出 member → out_path（若已存在且体积一致则跳过）。"""
    import zipfile
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        info = z.getinfo(member)
        if os.path.isfile(out_path) and os.path.getsize(out_path) == info.file_size:
            log(f"  解压已存在：{os.path.basename(out_path)}")
            return
        log(f"  解压 {member} → {os.path.basename(out_path)}（{info.file_size:,}B）…")
        t0 = time.time()
        tmp = out_path + ".part"
        with z.open(member) as src, open(tmp, "wb") as dst:
            while True:
                b = src.read(1 << 22)
                if not b:
                    break
                dst.write(b)
        os.replace(tmp, out_path)
        log(f"  解压完成 {time.time() - t0:.0f}s")


def extract_gz(gz_path: str, out_path: str, log=print):
    import gzip
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    if os.path.isfile(out_path):
        log(f"  解压已存在：{os.path.basename(out_path)}")
        return
    log(f"  解压 {os.path.basename(gz_path)} → {os.path.basename(out_path)} …")
    tmp = out_path + ".part"
    with gzip.open(gz_path, "rb") as src, open(tmp, "wb") as dst:
        while True:
            b = src.read(1 << 22)
            if not b:
                break
            dst.write(b)
    os.replace(tmp, out_path)


def test_connection(proxy: str | None, timeout: int = 20) -> str:
    """连通性自检（GitHub + HuggingFace 各一发最小请求）。→ 可读报告。"""
    msgs = []
    try:
        tag, _ = github_latest("hwei115j/exhentai_metadata", proxy)
        msgs.append(f"GitHub ✓（hwei 最新 release: {tag}）")
    except Exception as e:
        msgs.append(f"GitHub ✗（{type(e).__name__}: {e}）")
    try:
        req = urllib.request.Request(
            "https://huggingface.co/datasets/bogeyturn/exhentai-api-dump-raw/resolve/main/merge",
            headers={"User-Agent": UA, "Range": "bytes=0-0"})
        with _opener(proxy).open(req, timeout=timeout) as r:
            msgs.append(f"HuggingFace ✓（HTTP {r.status}）")
    except Exception as e:
        msgs.append(f"HuggingFace ✗（{type(e).__name__}: {e}）")
    return " · ".join(msgs)
