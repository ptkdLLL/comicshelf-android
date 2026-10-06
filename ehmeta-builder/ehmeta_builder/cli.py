# -*- coding: utf-8 -*-

from __future__ import annotations
"""命令行入口：fetch（取源）| build（构建）| update（一键）| verify（对账）| gui。

用法示例：
  python -m ehmeta_builder update --proxy http://127.0.0.1:7897
  python -m ehmeta_builder fetch  --only hwei,ehtag          # 只更新指定源
  python -m ehmeta_builder build  --limit 5000 --nozip       # 冒烟
  python -m ehmeta_builder verify out/ehmeta.db --golden     # 金标准对账

环境变量 EH_PROXY 可作为 --proxy 的默认值；工作目录默认 ~/ComicShelfBuilder。
"""
import argparse
import os
import sys
import time

from . import download as dl
from .sources import BY_KEY, SOURCES


def default_workdir() -> str:
    return os.path.expanduser("~/ComicShelfBuilder")


def _proxy(a) -> str | None:
    p = a.proxy or os.environ.get("EH_PROXY") or None
    if p and not p.startswith("http"):
        p = "http://" + p
    return p


def _logger(quiet=False):
    def log(*a):
        s = " ".join(str(x) for x in a)
        print(s, flush=True)
    return log


def fetch(workdir: str, proxy: str | None, *, log=print, force=False,
          only: list[str] | None = None, skip_optional=False,
          progress=None) -> dict:
    """下载（按需）+ 解压/转换。→ 各源版本 dict。"""
    os.makedirs(workdir, exist_ok=True)
    manifest = dl.load_manifest(workdir)
    log(f"工作目录: {workdir}" + (f" | 代理: {proxy}" if proxy else " | 直连"))
    versions = {}

    for s in SOURCES:
        if only and s.key not in only:
            continue
        local = os.path.join(workdir, s.local_rel)
        try:
            if s.optional and skip_optional and not os.path.isfile(local):
                log(f"  [{s.key}] 跳过（--skip-optional）")
                continue
            ok = dl.ensure_source(s, workdir, proxy, manifest, log=log, progress=progress, force=force)
            if not ok:
                log(f"  [{s.key}] 未就绪（可选源）")
                continue
            versions[s.key] = manifest.get(s.key, {}).get("version", "")
            # 解压/转换
            if s.kind == "github" and s.asset.endswith(".zip"):
                dl.extract_zip(local, "gdata.json", os.path.join(workdir, s.extract_rel), log=log)
            elif s.local_rel.endswith(".gz"):
                dl.extract_gz(local, os.path.join(workdir, s.extract_rel), log=log)
            if s.key == "ehdb":
                from . import ehdb2lite
                lite = os.path.join(workdir, "sources/ehdb/eh_lite.db")
                ehdb2lite.convert(local, lite, log=log)
        except Exception as e:
            if s.optional:
                log(f"  [{s.key}] 可选源失败（继续）：{e}")
            else:
                raise
    dl.save_manifest(workdir, manifest)
    return versions


def main(argv=None):
    """入口：Ctrl-C 友好退出（.part 保留，可重跑续传）。"""
    try:
        return _main(argv)
    except KeyboardInterrupt:
        print("\n已中断（.part 保留；重跑同一命令会自动续传）", flush=True)
        return 130
    except Exception as e:   # 面向用户：干净报错（EH_DEBUG=1 看 traceback）
        import os as _os
        import traceback
        print(f"\n✗ 失败：{type(e).__name__}: {e}", flush=True)
        if _os.environ.get("EH_DEBUG"):
            traceback.print_exc()
        return 1


def _main(argv=None):
    ap = argparse.ArgumentParser(prog="ehmeta-builder", description="E-Hentai 元数据桌面构建器")
    ap.add_argument("cmd", choices=["fetch", "build", "update", "verify", "gui"])
    ap.add_argument("--workdir", default=default_workdir(), help="工作目录（默认 ~/ComicShelfBuilder）")
    ap.add_argument("--proxy", default=None, help="HTTP 代理，如 http://127.0.0.1:7897（Clash Verge 混合端口）")
    ap.add_argument("--limit", type=int, default=0, help="冒烟：各源限 N 条")
    ap.add_argument("--nozip", action="store_true")
    ap.add_argument("--keep-extracted", action="store_true", help="构建后保留 2GB 解压产物")
    ap.add_argument("--skip-ehdb", action="store_true", help="不并入 ehdb 早期快照（别名略少）")
    ap.add_argument("--skip-optional", action="store_true", help="不下载可选源")
    ap.add_argument("--only", default="", help="fetch 限定源（逗号分隔：hwei,ehtag,...）")
    ap.add_argument("--force", action="store_true", help="强制重下")
    ap.add_argument("--db", default="", help="verify 的目标 db")
    ap.add_argument("--golden", action="store_true", help="与金标准（v0.5.0 同源包）对账")
    a = ap.parse_args(argv)
    log = _logger()
    proxy = _proxy(a)
    workdir = a.workdir

    if a.cmd == "gui":
        from . import gui
        gui.run(workdir=workdir, proxy=proxy)
        return 0

    if a.cmd in ("fetch", "update"):
        only = [x for x in a.only.split(",") if x] or None
        versions = fetch(workdir, proxy, log=log, force=a.force, only=only,
                         skip_optional=a.skip_optional)
        log("源就绪：" + ", ".join(f"{k}={v or '✓'}" for k, v in versions.items()))
        if a.cmd == "fetch":
            return 0

    if a.cmd in ("build", "update"):
        from . import builder
        manifest = dl.load_manifest(workdir)
        versions = {s.key: manifest.get(s.key, {}).get("version", "") for s in SOURCES}
        r = builder.build(workdir, log=log, limit=a.limit, nozip=a.nozip,
                          use_ehdb=not a.skip_ehdb, clean_extracted=not a.keep_extracted,
                          versions=versions)
        log(f"产物: {r['zip'] or r['db']}（{time.strftime('%H:%M:%S')}，耗时 {r['elapsed_s']:.0f}s）")
        return 0

    if a.cmd == "verify":
        from . import verify as V
        if not a.db:
            ap.error("verify 需要 --db <path>")
        res = V.verify(a.db, log=log, golden=a.golden)
        return 0 if (res["golden_ok"] is not False) else 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
