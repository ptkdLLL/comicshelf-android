#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ehmeta 合并打包器（第二阶段, Mac 侧, 纯 stdlib）

把 ehmeta_sync/incremental.jsonl 合并进基准 ehmeta.db（复制副本后操作, 不碰原库）,
产出升级版 ehmeta.db + ehmeta.db.zip —— App 现有导入流程直接重载。

合并语义（对齐 build_ehmeta.py P1 的增量版）:
  · gallery: 取最新全字段; expunged = old OR new（删除不可逆）
  · title: title / title_jpn 记为别名多行（INSERT OR IGNORE, 旧别名全保留）
  · tag: (ns,name) 复用既有 rid / 新建; name_zh 由 EhTagTranslation 映射补齐
  · gt: 新增 gid-rid 边; replaced: 不动（增量流无此信息）
  · 全量重放设计: 每次运行从基准库副本重放全部增量行 → 天然幂等（可反复运行）

用法:
  python3 merge_and_pack.py            # 合并 + 打包 zip（ZIP_DEFLATED-6）
  python3 merge_and_pack.py --nozip    # 只合并出 db（快, 用于验证）
"""
import hashlib
import json
import os
import shutil
import sqlite3
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
BASE_DB = os.environ.get('EHMETA_DB') or os.path.join(HERE, 'ehmeta.db')
BASE_ZIP = os.environ.get('EHMETA_DB_ZIP') or os.path.join(HERE, 'ehmeta.db.zip')
INCR = os.path.join(HERE, 'incremental.jsonl')
EHTAG = os.environ.get('EHMETA_EHTAG') or os.path.join(HERE, 'ehtag_db.raw.json')
OUTDIR = os.path.join(HERE, 'out')
OUT_DB = os.path.join(OUTDIR, 'ehmeta.db')
OUT_ZIP = os.path.join(OUTDIR, 'ehmeta.db.zip')
REPORT = os.path.join(HERE, 'MERGE_REPORT.txt')


def log(m):
    print(time.strftime('[%Y-%m-%d %H:%M:%S] ') + m, flush=True)


def load_zh():
    zh = {}
    try:
        data = json.loads(open(EHTAG, encoding='utf-8').read())
        for item in data.get('data', []):
            ns = item.get('namespace')
            if ns == 'rows':
                continue
            for name, v in (item.get('data') or {}).items():
                z = (v.get('name') or '').strip() if isinstance(v, dict) else ''
                if z:
                    zh[(ns, name)] = z
    except Exception as e:                # noqa: BLE001
        log('ehtag 加载失败(新 tag 中文名将留空): %s' % e)
    return zh


def to_int(v, d=0):
    try:
        return int(float(v))
    except Exception:                     # noqa: BLE001
        return d


def to_f(v, d=0.0):
    try:
        return float(v)
    except Exception:                     # noqa: BLE001
        return d


def main():
    nozip = '--nozip' in sys.argv
    os.makedirs(OUTDIR, exist_ok=True)
    lines = open(INCR, encoding='utf-8').readlines() if os.path.exists(INCR) else []
    log('增量行数: %d（全量重放）' % len(lines))

    # 1) 复制基准库（不碰原库）
    if not os.path.exists(BASE_DB):
        raise SystemExit('基准库不存在: ' + BASE_DB)
    shutil.copy2(BASE_DB, OUT_DB)
    con = sqlite3.connect(OUT_DB)
    cur = con.cursor()
    cur.execute('PRAGMA journal_mode=WAL')

    zh = load_zh()
    rid_of = {}
    max_rid = 0
    for rid, ns, name in cur.execute('SELECT rid,ns,name FROM tag'):
        rid_of[(ns, name)] = rid
        if rid > max_rid:
            max_rid = rid
    log('既有 tag: %d 条（max rid %d）· 中文映射: %d' % (len(rid_of), max_rid, len(zh)))

    n_g = n_exp = n_title = n_tag_new = n_gt = n_skip = 0
    cur.execute('BEGIN')
    for ln in lines:
        try:
            o = json.loads(ln)
        except Exception:                 # noqa: BLE001
            n_skip += 1
            continue
        if 'error' in o:                  # omitted-by-api 审计行
            n_skip += 1
            continue
        gid = o.get('gid')
        if not gid:
            n_skip += 1
            continue
        cur.execute(
            """INSERT INTO gallery(gid,token,category,posted,rating,filesize,expunged)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(gid) DO UPDATE SET
                 token=excluded.token, category=excluded.category, posted=excluded.posted,
                 rating=excluded.rating, filesize=excluded.filesize,
                 expunged=max(gallery.expunged, excluded.expunged)""",
            (gid, o.get('token') or '', o.get('category') or '',
             to_int(o.get('posted')), to_f(o.get('rating')),
             to_int(o.get('filesize')), 1 if o.get('expunged') else 0))
        n_g += 1
        if o.get('expunged'):
            n_exp += 1
        for t in (o.get('title'), o.get('title_jpn')):
            if t and str(t).strip():
                cur.execute('INSERT OR IGNORE INTO title(gid,text) VALUES(?,?)',
                            (gid, str(t).strip()))
                n_title += 1
        for tg in (o.get('tags') or []):
            if not isinstance(tg, str) or ':' not in tg:
                continue
            ns, name = tg.split(':', 1)
            key = (ns, name)
            rid = rid_of.get(key)
            if rid is None:
                max_rid += 1
                rid = max_rid
                cur.execute(
                    'INSERT OR IGNORE INTO tag(rid,ns,name,name_zh,cnt) VALUES(?,?,?,?,?)',
                    (rid, ns, name, zh.get(key, ''), 1))
                rid_of[key] = rid
                n_tag_new += 1
            cur.execute('INSERT OR IGNORE INTO gt(gid,rid) VALUES(?,?)', (gid, rid))
            n_gt += 1
    con.commit()

    cnt_g = cur.execute('SELECT COUNT(*) FROM gallery').fetchone()[0]
    cnt_t = cur.execute('SELECT COUNT(*) FROM tag').fetchone()[0]
    cnt_gt = cur.execute('SELECT COUNT(*) FROM gt').fetchone()[0]
    log('库内: gallery=%d tag=%d gt=%d（本次 g=%d 新tag=%d gt=%d 别名=%d exp=%d 跳过=%d）'
        % (cnt_g, cnt_t, cnt_gt, n_g, n_tag_new, n_gt, n_title, n_exp, n_skip))

    try:
        cur.execute('VACUUM')
        log('VACUUM 完成')
    except Exception as e:                # noqa: BLE001
        log('VACUUM 跳过: %s' % e)
    con.commit()
    con.close()

    db_size = os.path.getsize(OUT_DB)
    zip_size = sha = None
    if not nozip:
        # meta.json 以现有包为模板（保真 format/schema/sources 结构）
        meta = None
        try:
            with zipfile.ZipFile(BASE_ZIP) as z:
                meta = json.loads(z.read('meta.json').decode('utf-8'))
        except Exception as e:            # noqa: BLE001
            log('读现有 meta.json 失败(使用最小模板): %s' % e)
        if not isinstance(meta, dict):
            meta = {'format': 'ehmeta/1'}
        meta['built_at'] = time.strftime('%Y-%m-%d %H:%M:%S')
        meta.setdefault('sources', {})['incremental'] = {
            'file': 'ehmeta_sync/incremental.jsonl',
            'records': n_g, 'edges': n_gt, 'new_tags': n_tag_new,
        }
        with zipfile.ZipFile(OUT_ZIP, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            z.write(OUT_DB, 'ehmeta.db')
            z.writestr('meta.json', json.dumps(meta, ensure_ascii=False, indent=1))
        zip_size = os.path.getsize(OUT_ZIP)
        sha = hashlib.sha256(open(OUT_ZIP, 'rb').read()).hexdigest()
        log('打包: %s (%.1f MB)' % (OUT_ZIP, zip_size / 1e6))

    rep = ['==== ehmeta 合并报告 ====',
           '时间: ' + time.strftime('%Y-%m-%d %H:%M:%S'),
           '增量行: %d（gallery %d / title 别名 %d / 新 tag %d / gt 边 %d / expunged %d / 跳过 %d）'
           % (len(lines), n_g, n_title, n_tag_new, n_gt, n_exp, n_skip),
           '库内: gallery=%d tag=%d gt=%d' % (cnt_g, cnt_t, cnt_gt),
           'db: %s (%.1f MB)' % (OUT_DB, db_size / 1e6)]
    if not nozip:
        rep.append('zip: %s (%.1f MB)' % (OUT_ZIP, zip_size / 1e6))
        rep.append('sha256: ' + sha)
    open(REPORT, 'w', encoding='utf-8').write('\n'.join(rep) + '\n')
    log('报告: ' + REPORT)


if __name__ == '__main__':
    main()
