# -*- coding: utf-8 -*-

from __future__ import annotations
"""产物核验与金标准对账（原 build_ehmeta.py [6/6] 段的扩展版）。

金标准 = 现役 v0.5.0 包（eh_work/ehmeta_out/BUILD_REPORT.txt）在**相同源**下的计数。
注意：金标准只在"同一批源"下有约束力；上游换期后计数会合法变化——对账命令显式传入
`--golden` 时才作一致性断言（用于移植对账/回归），日常构建只报告计数。
"""
import sqlite3

GOLDEN_COUNTS = {
    "gallery": 3_488_515,
    "title": 5_235_445,
    "tag": 167_408,
    "gt": 43_881_202,
    "replaced": 809_731,
    "expunged": 309_247,
    "tag_zh": 44_235,
}
GOLDEN_NS = {
    "artist": 67704, "group": 39411, "character": 31940, "temp": 17158,
    "parody": 7605, "cosplayer": 2199, "female": 625, "male": 583,
    "language": 89, "other": 64, "mixed": 22, "location": 8,
}
# 定点核验（M0/S0 用过的哨兵值）
SENTINELS = (9, 1478328)


def verify(db_path: str, *, log=print, limit: int = 0, golden: bool = False) -> dict:
    v = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)

    def one(sql):
        return v.execute(sql).fetchone()[0]

    counts = {
        "gallery": one("SELECT COUNT(*) FROM gallery"),
        "title": one("SELECT COUNT(*) FROM title"),
        "tag": one("SELECT COUNT(*) FROM tag"),
        "gt": one("SELECT COUNT(*) FROM gt"),
        "replaced": one("SELECT COUNT(*) FROM replaced"),
        "expunged": one("SELECT COUNT(*) FROM gallery WHERE expunged=1"),
        "tag_zh": one("SELECT COUNT(*) FROM tag WHERE name_zh IS NOT NULL"),
        "title_distinct_gid": one("SELECT COUNT(DISTINCT gid) FROM title"),
    }
    log("      表计数: " + str(counts))
    ns_dist = dict(v.execute("SELECT ns, COUNT(*) FROM tag GROUP BY ns").fetchall())
    log("      tag 命名空间: " + ", ".join(f"{k}:{ns_dist[k]}" for k in
                                           sorted(ns_dist, key=lambda k: -ns_dist[k])[:15]))

    log("      —— 抽样（5 个随机 gid）——")
    for (gid,) in v.execute("SELECT gid FROM gallery ORDER BY RANDOM() LIMIT 5").fetchall():
        ts = v.execute("SELECT text FROM title WHERE gid=? LIMIT 3", (gid,)).fetchall()
        tgs = v.execute("SELECT t.ns, t.name, t.name_zh FROM gt JOIN tag t ON t.rid=gt.rid "
                        "WHERE gt.gid=? LIMIT 8", (gid,)).fetchall()
        log(f"      gid={gid} | titles={[t[0] for t in ts]}")
        log(f"        tags={[(a, b, c) for a, b, c in tgs]}")

    log("      —— 定点核验 ——")
    for gid in SENTINELS:
        ts = v.execute("SELECT text FROM title WHERE gid=? LIMIT 3", (gid,)).fetchall()
        log(f"      gid={gid}: {[t[0] for t in ts]}")
    zh_chk = v.execute(
        "SELECT ns, name, name_zh FROM tag WHERE ns='language' AND name='chinese'").fetchone()
    log(f"      language/chinese → {zh_chk}")
    v.close()

    diffs = []
    if golden:
        for k, gv in GOLDEN_COUNTS.items():
            if counts.get(k) != gv:
                diffs.append(f"{k}: {counts.get(k)} != 金标准 {gv}")
        for k, gv in GOLDEN_NS.items():
            if ns_dist.get(k) != gv:
                diffs.append(f"ns[{k}]: {ns_dist.get(k)} != 金标准 {gv}")
        if diffs:
            log("      ⚠️ 金标准对账不一致：")
            for d in diffs:
                log("         " + d)
        else:
            log("      ✅ 金标准对账全部一致（8 计数 + 12 命名空间）")
    return {"counts": counts, "ns_dist": ns_dist, "diffs": diffs, "golden_ok": (not diffs) if golden else None}


if __name__ == "__main__":
    import sys
    verify(sys.argv[1], golden="--golden" in sys.argv)
