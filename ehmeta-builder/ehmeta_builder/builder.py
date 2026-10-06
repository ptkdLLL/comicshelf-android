# -*- coding: utf-8 -*-

from __future__ import annotations
"""六阶段构建 —— 逐段移植自 S0 生产管线 `eh_work/build_ehmeta.py`（487 行，已在 v0.5.0 包上验证）。

移植纪律（对齐表见 docs/EHENTAI_S5_BUILDER_PLAN.md §4）：
  - 合并语义 / SQL / 索引顺序 / 计数口径 **一字不动**（G_UPS 的 expunged 只升、cnt 必须在 ix_gt_rid_gid 之后）；
  - 仅两处改造：① 源路径参数化到 workdir；② P() 输出改为 log 回调（供 CLI/GUI）；
  - 新增：meta.json 记录源版本 + licenses；构建成功后可选删除 2GB 解压产物。
"""
import collections
import csv
import hashlib
import json
import mmap
import os
import re
import sqlite3
import time
import zipfile

from .sources import LICENSES

TOK = re.compile(rb'"(?:[^"\\]|\\.)*"|[{}\[\]]')
_MD_IMG = re.compile(r'!\[[^\]]*\]\([^)]*\)')
_MD_LINK = re.compile(r'\[([^\]]*)\]\([^)]*\)')


def hsize(n):
    return f"{n / 1e6:.1f}MB" if n < 1e9 else f"{n / 1e9:.2f}GB"


def sha256_file(path, buf=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(buf)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def clean_zh(s):
    """EhTagTranslation 的 name 字段偶尔混入 Markdown 图标/链接（68 例）——清洗为纯文本。"""
    s = _MD_IMG.sub('', s)
    s = _MD_LINK.sub(r'\1', s)
    s = re.sub(r'\s+', ' ', s).strip()
    return s


def load_zh(path, log):
    """→ (zh, mixed_names, other_names, sha)。（原 build_ehmeta.py:73-89）"""
    data = json.loads(open(path, encoding="utf-8").read())
    zh = {}
    mixed_names, other_names = set(), set()
    for item in data["data"]:
        ns = item["namespace"]
        if ns == "rows":
            continue
        for name, v in (item.get("data") or {}).items():
            z = clean_zh(v.get("name") or "") if isinstance(v, dict) else ""
            if z:
                zh[(ns, name)] = z
            if ns == "mixed":
                mixed_names.add(name)
            elif ns == "other":
                other_names.add(name)
    return zh, mixed_names, other_names, data.get("head", {}).get("sha", "")


def to_int(v, d=0):
    try:
        return int(v)
    except (TypeError, ValueError):
        return d


def to_float(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def parse_tags(tags):
    """['ns:name', ...] → [(ns,name)]；裸名以 ns='' 占位（原 :171-185）。"""
    out = []
    for tg in tags or []:
        if isinstance(tg, str):
            if ":" in tg:
                ns, nm = tg.split(":", 1)
                if nm:
                    out.append((ns, nm))
            elif tg:
                out.append(("", tg))
        elif isinstance(tg, dict):
            ns, nm = tg.get("namespace") or "", tg.get("name") or ""
            if nm:
                out.append((ns, nm))
    return out


class Batch:
    """按源分批：records 级缓冲，flush 时 executemany（原 :152-169）。"""

    def __init__(self, conn, gallery_sql):
        self.conn = conn
        self.gallery_sql = gallery_sql
        self.g, self.t, self.e, self.r = [], [], [], []
        self.cnt_g = self.cnt_t = self.cnt_e = self.cnt_r = 0

    def add_gallery(self, row):
        self.g.append(row); self.cnt_g += 1

    def add_title(self, gid, text):
        if text:
            self.t.append((gid, text)); self.cnt_t += 1

    def add_edge(self, gid, rid):
        self.e.append((gid, rid)); self.cnt_e += 1

    def add_replaced(self, old, cur):
        if old != cur:
            self.r.append((old, cur)); self.cnt_r += 1

    def flush(self):
        if self.g:
            self.conn.executemany(self.gallery_sql, self.g); self.g.clear()
        if self.t:
            self.conn.executemany("INSERT OR IGNORE INTO title(gid,text) VALUES(?,?)", self.t); self.t.clear()
        if self.e:
            self.conn.executemany("INSERT OR IGNORE INTO gt(gid,rid) VALUES(?,?)", self.e); self.e.clear()
        if self.r:
            self.conn.executemany("INSERT OR IGNORE INTO replaced(old_gid,current_gid) VALUES(?,?)", self.r); self.r.clear()
        self.conn.commit()


def resolve_bare_tags(conn, zh, ehtag_mixed, ehtag_other, log):
    """裸 tag 四级解析（原 :187-230；纯本地规则，零 EH API 调用）。"""
    rows = conn.execute("SELECT rid, name FROM tag WHERE ns=''").fetchall()
    if not rows:
        log("      裸 tag 解析: 无")
        return
    conn.execute("CREATE INDEX tmp_gt_rid ON gt(rid)")
    conn.commit()
    uniq = {}
    for name, ns, c in conn.execute(
            "SELECT name, MIN(ns), COUNT(DISTINCT ns) FROM tag WHERE ns<>'' GROUP BY name"):
        if c == 1:
            uniq[name] = ns
    target = {}
    for rid, ns, name in conn.execute("SELECT rid, ns, name FROM tag WHERE ns<>''"):
        target[(ns, name)] = rid
    st = collections.Counter()
    for rid, name in rows:
        if name in ehtag_mixed:
            ns, tag = 'mixed', 'mixed'
        elif name in ehtag_other:
            ns, tag = 'other', 'other'
        elif name in uniq:
            ns, tag = uniq[name], 'dataset'
        else:
            ns, tag = 'temp', 'temp'
        st[tag] += 1
        t = target.get((ns, name))
        if t is not None and t != rid:
            conn.execute("UPDATE OR IGNORE gt SET rid=? WHERE rid=?", (t, rid))
            conn.execute("DELETE FROM gt WHERE rid=?", (rid,))
            conn.execute("DELETE FROM tag WHERE rid=?", (rid,))
            st['merged'] += 1
        else:
            z = zh.get((ns, name))
            if z:
                conn.execute("UPDATE tag SET ns=?, name_zh=COALESCE(name_zh, ?) WHERE rid=?", (ns, z, rid))
            else:
                conn.execute("UPDATE tag SET ns=? WHERE rid=?", (ns, rid))
            target[(ns, name)] = rid
    conn.execute("DROP INDEX tmp_gt_rid")
    conn.commit()
    log(f"      裸 tag 解析: 共 {len(rows):,} 名 → mixed {st['mixed']:,} / other {st['other']:,} / "
        f"数据集唯一 {st['dataset']:,} / temp {st['temp']:,}（并入已有行 {st['merged']:,}）")


def build(workdir: str, *, log=print, limit: int = 0, nozip: bool = False,
          use_ehdb: bool = True, clean_extracted: bool = True,
          versions: dict | None = None) -> dict:
    """全量构建。→ 结果 dict（counts/体积/sha/产物路径）。"""
    S = lambda rel: os.path.join(workdir, rel)   # noqa: E731
    OUT = os.path.join(workdir, "out")
    suf = ".smoke" if limit else ""
    DB = os.path.join(OUT, f"ehmeta{suf}.db")
    TMP = os.path.join(OUT, f"ehmeta{suf}.db.tmp")
    ZIP = os.path.join(OUT, f"ehmeta{suf}.db.zip")
    REPORT = os.path.join(OUT, f"BUILD_REPORT{suf}.txt")
    versions = versions or {}

    t_all = time.time()
    lines = []

    def P(*a):
        s = " ".join(str(x) for x in a)
        log(s)
        lines.append(s)

    P("=" * 78)
    P("ehmeta-builder · 并包构建" + (f"（冒烟 limit={limit}）" if limit else ""))
    P("=" * 78)
    os.makedirs(OUT, exist_ok=True)
    for p in (TMP, DB):
        if os.path.exists(p):
            os.remove(p)

    # ---------------- [0/6] 中文名 ----------------
    P("[0/6] 载入 EhTagTranslation 中文名 …")
    zh, et_mixed, et_other, ehtag_sha = load_zh(S("sources/ehtag/db.raw.json"), log)
    P(f"      中文名 {len(zh):,} 条（sha {ehtag_sha[:12]}）| mixed {len(et_mixed)} / other {len(et_other)}")

    conn = sqlite3.connect(TMP)
    conn.executescript("""
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
PRAGMA temp_store=MEMORY;
PRAGMA cache_size=-786432;
CREATE TABLE gallery(gid INTEGER PRIMARY KEY, token TEXT, category TEXT,
                     posted INTEGER, rating REAL, filesize INTEGER, expunged INTEGER DEFAULT 0);
CREATE TABLE title(gid INTEGER, text TEXT, PRIMARY KEY(gid,text)) WITHOUT ROWID;
CREATE TABLE tag(rid INTEGER PRIMARY KEY, ns TEXT, name TEXT, name_zh TEXT, cnt INTEGER);
CREATE TABLE gt(gid INTEGER, rid INTEGER, PRIMARY KEY(gid,rid)) WITHOUT ROWID;
CREATE TABLE replaced(old_gid INTEGER PRIMARY KEY, current_gid INTEGER);
""")
    conn.commit()

    rid_of = {}
    tag_pending = []
    G_INS = ("INSERT OR REPLACE INTO gallery(gid,token,category,posted,rating,filesize,expunged) "
             "VALUES(?,?,?,?,?,?,?)")
    G_UPS = ("INSERT INTO gallery(gid,token,category,posted,rating,filesize,expunged) VALUES(?,?,?,?,?,?,?) "
             "ON CONFLICT(gid) DO UPDATE SET expunged=1 WHERE excluded.expunged=1 AND gallery.expunged=0")

    def flush_tags():
        if tag_pending:
            conn.executemany("INSERT OR IGNORE INTO tag(rid,ns,name,name_zh) VALUES(?,?,?,?)", tag_pending)
            tag_pending.clear()

    def rid_for(ns, name):
        key = (ns, name)
        r = rid_of.get(key)
        if r is None:
            r = len(rid_of)
            rid_of[key] = r
            tag_pending.append((r, ns, name, zh.get(key)))
            if len(tag_pending) >= 50000:
                flush_tags()
        return r

    # ---------------- [1/6] hwei（最新抓取优先） ----------------
    hwei_json = S("sources/hwei/gdata.json")
    if not os.path.isfile(hwei_json):
        raise FileNotFoundError(f"缺少 hwei 解压产物：{hwei_json}（请先 fetch，会从 gdata.zip 解压）")
    P(f"[1/6] hwei{(' ' + versions['hwei']) if versions.get('hwei') else ''}（最新抓取优先）…")
    st_h = dict(rec=0, err=0, edges=0, titles=0, exp=0)
    b = Batch(conn, G_INS)
    n = 0
    t0 = time.time()
    with open(hwei_json, "rb") as f:
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
                        g = json.loads(mm[start:m.end()])
                    except Exception:
                        g = None
                    if g and g.get("gid") is not None:
                        gid = int(g["gid"])
                        exp = 1 if g.get("expunged") else 0
                        b.add_gallery((gid, g.get("token") or "", g.get("category") or "",
                                       to_int(g.get("posted")), to_float(g.get("rating")),
                                       to_int(g.get("filesize")), exp))
                        b.add_title(gid, g.get("title") or "")
                        b.add_title(gid, g.get("title_jpn") or "")
                        for ns, nm in parse_tags(g.get("tags")):
                            b.add_edge(gid, rid_for(ns, nm))
                        st_h["rec"] += 1; st_h["exp"] += exp
                        st_h["edges"] += len(g.get("tags") or [])
                        n += 1
                        if n % 100000 == 0:
                            b.flush()
                            if n % 500000 == 0:
                                P(f"      ...{n:,} rec  {time.time() - t0:.0f}s  rid={len(rid_of):,}")
                    else:
                        st_h["err"] += 1
                depth -= 1
            elif s == b'[':
                depth += 1
            elif s == b']':
                depth -= 1
            if limit and st_h["rec"] + st_h["err"] >= limit:
                break
        mm.close()
    b.flush()
    st_h["titles"] = b.cnt_t
    P(f"      记录 {st_h['rec']:,}（err {st_h['err']}）| 标题行 {st_h['titles']:,} | tag 边 {st_h['edges']:,} "
      f"| expunged {st_h['exp']:,} | {time.time() - t0:.0f}s")

    # ---------------- [2/6] bogey（补缺 + expunged OR + current_gid） ----------------
    P("[2/6] bogey 2025-01（补缺 + expunged OR + current_gid）…")
    st_b = dict(rec=0, err=0, edges=0, titles=0, ups=0, rep=0)
    b = Batch(conn, G_UPS)
    n = 0
    t0 = time.time()
    with open(S("sources/bogey/merge.jsonl"), "rb") as f:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        depth = 0
        obj_start = None
        for m in TOK.finditer(mm):
            s = m.group(0)
            if s == b'{':
                depth += 1
                if depth == 3:
                    obj_start = m.start()
            elif s == b'}':
                if depth == 3 and obj_start is not None:
                    try:
                        g = json.loads(mm[obj_start:m.end()])
                    except Exception:
                        g = None
                    if g and "error" not in g and g.get("gid") is not None:
                        gid = int(g["gid"])
                        exp = 1 if g.get("expunged") else 0
                        b.add_gallery((gid, g.get("token") or "", g.get("category") or "",
                                       to_int(g.get("posted")), to_float(g.get("rating")),
                                       to_int(g.get("filesize")), exp))
                        b.add_title(gid, g.get("title") or "")
                        b.add_title(gid, g.get("title_jpn") or "")
                        for ns, nm in parse_tags(g.get("tags")):
                            b.add_edge(gid, rid_for(ns, nm))
                        cg = g.get("current_gid")
                        if cg and str(cg).isdigit():
                            b.add_replaced(gid, int(cg))
                        st_b["rec"] += 1; st_b["ups"] += exp
                        st_b["edges"] += len(g.get("tags") or [])
                        n += 1
                        if n % 100000 == 0:
                            b.flush()
                            if n % 500000 == 0:
                                P(f"      ...{n:,} rec  {time.time() - t0:.0f}s  rid={len(rid_of):,}")
                    else:
                        st_b["err"] += 1
                depth -= 1
            elif s == b'[':
                depth += 1
            elif s == b']':
                depth -= 1
            if limit and st_b["rec"] + st_b["err"] >= limit:
                break
        mm.close()
    b.flush()
    st_b["titles"] = b.cnt_t
    st_b["rep"] = b.cnt_r
    P(f"      记录 {st_b['rec']:,}（err {st_b['err']}）| 标题行 {st_b['titles']:,} | tag 边 {st_b['edges']:,} "
      f"| expunged {st_b['ups']:,} | replaced {st_b['rep']:,} | {time.time() - t0:.0f}s")
    flush_tags()
    P(f"      tag 字典 {len(rid_of):,}（含中文 {sum(1 for k in rid_of if k in zh):,}）")

    # ---------------- [3/6] eh_lite.db（历史标题别名） ----------------
    st_e = dict(rec=0, titles=0)
    if use_ehdb and os.path.isfile(S("sources/ehdb/eh_lite.db")):
        P("[3/6] eh_lite.db 2019 快照（历史标题别名）…")
        t0 = time.time()
        src = sqlite3.connect(f"file:{S('sources/ehdb/eh_lite.db')}?mode=ro", uri=True)
        b = Batch(conn, "INSERT OR IGNORE INTO gallery(gid,token,category,posted,rating,filesize,expunged) "
                        "VALUES(?,?,?,?,?,?,0)")
        lim = f" LIMIT {limit}" if limit else ""
        for gid, token, t, tj in src.execute(f"SELECT id,token,title,title_jpn FROM g{lim}"):
            b.add_gallery((gid, token or "", None, 0, None, 0))
            b.add_title(gid, t or "")
            b.add_title(gid, tj or "")
            st_e["rec"] += 1
        b.flush()
        src.close()
        st_e["titles"] = b.cnt_t
        P(f"      记录 {st_e['rec']:,} | 标题行 {st_e['titles']:,} | {time.time() - t0:.0f}s")
    else:
        P("[3/6] eh_lite.db 跳过（可选源缺失）——标题别名将略少")

    # ---------------- [4/6] bogey_csv（历史标题别名） ----------------
    P("[4/6] bogey_csv 2023-07 快照（历史标题别名）…")
    st_c = dict(rec=0, titles=0)
    t0 = time.time()
    b = Batch(conn, "INSERT OR IGNORE INTO gallery(gid,token,category,posted,rating,filesize,expunged) "
                    "VALUES(?,?,?,?,?,?,?)")
    with open(S("sources/bogey_csv/gallery.csv"), encoding="utf-8", newline="") as f:
        rd = csv.reader(f)
        next(rd)
        for r in rd:
            if len(r) < 3 or not r[0].isdigit():
                continue
            gid = int(r[0])
            b.add_gallery((gid, r[12] if len(r) > 12 else "", r[3] if len(r) > 3 else "",
                           to_int(r[5]) if len(r) > 5 else 0, to_float(r[13]) if len(r) > 13 else None,
                           to_int(r[7]) if len(r) > 7 else 0,
                           to_int(r[9]) if len(r) > 9 else 0))
            b.add_title(gid, r[1])
            b.add_title(gid, r[2])
            st_c["rec"] += 1
            if limit and st_c["rec"] >= limit:
                break
            if st_c["rec"] % 500000 == 0:
                b.flush()
    b.flush()
    st_c["titles"] = b.cnt_t
    P(f"      记录 {st_c['rec']:,} | 标题行 {st_c['titles']:,} | {time.time() - t0:.0f}s")

    # ---------------- [4.5/6] 裸 tag 解析 ----------------
    P("[4.5/6] 裸 tag 解析（mixed/other/数据集唯一/temp）…")
    flush_tags()
    resolve_bare_tags(conn, zh, et_mixed, et_other, log)

    # ---------------- [5/6] 索引 + cnt + VACUUM ----------------
    P("[5/6] 二级索引 + 频次 cnt + ANALYZE + VACUUM …")
    t0 = time.time()
    conn.executescript("""
CREATE INDEX ix_gt_rid_gid ON gt(rid,gid);
CREATE INDEX ix_tag_ns_name ON tag(ns,name);
""")
    conn.commit()
    P(f"      索引 {time.time() - t0:.0f}s；计算 tag 全站频次…")
    # 注：cnt 必须在 ix_gt_rid_gid 之后（相关子查询走索引；否则 167K × 全表 44M 行）
    t0 = time.time()
    conn.execute("UPDATE tag SET cnt=(SELECT COUNT(*) FROM gt WHERE gt.rid=tag.rid)")
    conn.commit()
    nz = conn.execute("SELECT COUNT(*), SUM(cnt) FROM tag").fetchone()
    P(f"      cnt 完成: {nz[0]:,} 条 tag（边合计 {nz[1]:,}）{time.time() - t0:.0f}s")
    conn.execute("ANALYZE")
    conn.commit()
    P("      索引完成，开始 VACUUM INTO …")
    t0 = time.time()
    conn.execute(f"VACUUM INTO '{DB}'")
    conn.close()
    P(f"      VACUUM {time.time() - t0:.0f}s → {DB}")

    # ---------------- [6/6] 计数 + 抽样核验 ----------------
    from .verify import verify as verify_db
    result = verify_db(DB, log=log, limit=limit)

    # ---------------- 打包 + 报告 ----------------
    db_size = os.path.getsize(DB)
    sha_db = sha256_file(DB)
    meta = {
        "format": "ehmeta/1",
        "built_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "builder": "ehmeta-builder/1.0.0",
        "schema": {
            "gallery": "gid INTEGER PRIMARY KEY, token TEXT, category TEXT, posted INTEGER, rating REAL, filesize INTEGER, expunged INTEGER",
            "title": "gid, text  (PK(gid,text), 别名多行)",
            "tag": "rid PK, ns, name, name_zh",
            "gt": "gid, rid  (PK(gid,rid) WITHOUT ROWID; ix(rid,gid))",
            "replaced": "old_gid PK, current_gid",
        },
        "sources": {
            "hwei": {"version": versions.get("hwei", ""), "records": st_h["rec"], "edges": st_h["edges"]},
            "bogey_raw": {"records": st_b["rec"], "edges": st_b["edges"]},
            "eh_lite": {"records": st_e["rec"], "role": "alias-only" if use_ehdb else "skipped"},
            "bogey_csv": {"records": st_c["rec"], "role": "alias-only"},
            "ehtagtranslation": {"sha": ehtag_sha, "version": versions.get("ehtag", ""), "zh": len(zh)},
        },
        "counts": result["counts"],
        "db_size": db_size,
        "db_sha256": sha_db,
        "licenses": LICENSES,
    }
    zip_size = 0
    sha_zip = ""
    if not nozip:
        P("      打包 zip（deflate-6）…")
        t0 = time.time()
        with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            z.write(DB, "ehmeta.db")
            z.writestr("meta.json", json.dumps(meta, ensure_ascii=False, indent=1))
        zip_size = os.path.getsize(ZIP)
        sha_zip = sha256_file(ZIP)
        P(f"      zip {hsize(zip_size)}（db 的 {zip_size / db_size * 100:.0f}%）耗时 {time.time() - t0:.0f}s")

    P("")
    P("=" * 78)
    P("结果汇总")
    P("=" * 78)
    c = result["counts"]
    P(f"  gallery {c['gallery']:,} | title {c['title']:,} | tag {c['tag']:,}"
      f"（中文 {c['tag_zh']:,} = {c['tag_zh'] / max(c['tag'], 1) * 100:.1f}%）")
    P(f"  gt {c['gt']:,} | replaced {c['replaced']:,} | expunged {c['expunged']:,}")
    P(f"  db  {hsize(db_size)}  sha256 {sha_db[:16]}…")
    if not nozip:
        P(f"  zip {hsize(zip_size)}  sha256 {sha_zip[:16]}…")
    P(f"  总耗时 {time.time() - t_all:.0f}s")

    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    P(f"\n报告已写: {REPORT}")

    # 构建成功后清理解压的大中间物（zip 保留可再解压；仅全量构建时）
    if clean_extracted and not limit and os.path.isfile(hwei_json) and os.path.isfile(S("sources/hwei/gdata.zip")):
        try:
            os.remove(hwei_json)
            P(f"  已清理解压产物 {hwei_json}（省 {hsize(os.path.getsize(ZIP) if False else 2070949463)}；"
              f"zip 保留，重建时会自动再解压）")
        except OSError as e:
            P(f"  清理失败（忽略）：{e}")

    return {
        "db": DB, "zip": ZIP, "report": REPORT, "counts": c,
        "db_size": db_size, "db_sha256": sha_db, "zip_size": zip_size, "zip_sha256": sha_zip,
        "elapsed_s": time.time() - t_all,
    }
