# -*- coding: utf-8 -*-

from __future__ import annotations
"""exzhawk/ehdb 的 gdata.json（gid 键控大 JSON）→ 轻量 SQLite（对齐现役 eh_lite.db 的 g 表）。

现役管线（build_ehmeta.py:337）只读 `SELECT id,token,title,title_jpn FROM g`；
本转换器产出同形 schema，保证工具产物与原脚本产物可对账。
JSON 结构与 hwei 同构（gid 键控、记录内含 gid/token/title/title_jpn），
用与阶段 1 相同的 mmap + 分词器流式解析（不整载，手机/桌面都安全）。
"""
import mmap
import os
import re
import sqlite3

TOK = re.compile(rb'"(?:[^"\\]|\\.)*"|[{}\[\]]')


def convert(json_path: str, db_path: str, log=print, limit: int = 0) -> int:
    """→ 写入行数。已存在且行数达标时跳过（幂等）。"""
    import json as _json
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
    tmp = db_path + ".tmp"
    for p in (tmp,):
        if os.path.exists(p):
            os.remove(p)

    conn = sqlite3.connect(tmp)
    conn.executescript("""
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
CREATE TABLE g(id INTEGER PRIMARY KEY, token TEXT, title TEXT, title_jpn TEXT);
""")
    rows = []
    n = 0
    t0 = __import__("time").time()

    def flush():
        if rows:
            conn.executemany("INSERT OR REPLACE INTO g(id,token,title,title_jpn) VALUES(?,?,?,?)", rows)
            rows.clear()

    with open(json_path, "rb") as f:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        depth = 0
        start = None
        for m in TOK.finditer(mm):
            s = m.group(0)
            if s == b'{':
                depth += 1
                if depth == 2:
                    start = m.start()
            elif s == b'}':
                if depth == 2 and start is not None:
                    try:
                        g = _json.loads(mm[start:m.end()])
                    except Exception:
                        g = None
                    if g and g.get("gid") is not None:
                        gid = int(g["gid"])
                        # 对账注记（2026-10-06）：现役 eh_lite.db 对 title/title_jpn 做了 strip()
                        # —— 全量逐行对账 828,837 行中 155 行差异全部由此解释（±U+3000 等空白）。
                        rows.append((gid, g.get("token") or "", (g.get("title") or "").strip(),
                                     (g.get("title_jpn") or "").strip()))
                        n += 1
                        if n % 100000 == 0:
                            flush()
                            if n % 400000 == 0:
                                log(f"      ...{n:,} rec  {__import__('time').time() - t0:.0f}s")
                    if limit and n >= limit:
                        break
                depth -= 1
            elif s == b'[':
                depth += 1
            elif s == b']':
                depth -= 1
        mm.close()
    flush()
    conn.commit()
    conn.close()
    os.replace(tmp, db_path)
    log(f"      eh_lite.db 转换完成：{n:,} 行 → {db_path}（{__import__('time').time() - t0:.0f}s）")
    return n


if __name__ == "__main__":  # 手动转换：python -m ehmeta_builder.ehdb2lite <json> <db>
    import sys
    convert(sys.argv[1], sys.argv[2])
