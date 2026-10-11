#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ehmeta 增量同步器 v2（Mac 侧, 纯 stdlib, 零依赖）

阶段1 发现: 游标翻页列表 → pending.jsonl（gid,token 对; 翻到锚点为止）
阶段2 抓取: todo（回访段 → watchlist → 新段）→ gdata 25/批 → incremental.jsonl

tag 成熟度策略（v2 新增, D=8）:
  · 滞后过滤: 只抓 gid ≤ seen_max − 8×2100 的新段（跳过"约 8 天内"的嫩本, 等社区补 tag）
  · 双抓回访: 每次运行把"上轮新段"整段重抓（每 gid 至少抓两次; incremental 追加 + 合并取最新）
  · watchlist: 抓到 tags < 5 的 gid 进入重访清单（上限 2 次; 仍少则放弃——有些本天生 tag 少）

用法:
  python3 sync_increment.py                  # 续跑: 补发现 + 抓取(受本次上限)
  python3 sync_increment.py --list-only      # 只跑发现
  python3 sync_increment.py --gdata-only     # 只跑抓取
  python3 sync_increment.py --max-batches N  # 本次 gdata 批数上限(默认 800 ≈ 40 分钟)
  python3 sync_increment.py --no-lag         # 停用滞后过滤（首次全量追赶时用）
  python3 sync_increment.py --smoke N        # 冒烟: N 页 + N 批(自动 --no-lag), 产物加 .smoke

软限说明（重要）: EH 对短时高频翻页会返回"降级页"（完整列表外壳但 next 卡回同段）。
脚本识别（未前进）→ 60s 重试一次 → 仍降级则 SoftLimit 退出（exit 3）。
**收到 exit 3 请歇 2 小时以上再跑**（这是站点在提示"慢一点", 不是故障）。
HTTP 级连续异常为 BanError（exit 2）。
"""
import json
import os
import random
import re
import sqlite3
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------- 配置（不含任何凭据内容; 路径可改） ----------------
COOKIE_FILE = os.environ.get('EHMETA_COOKIE') or os.path.join(HERE, 'cookie.json')  # cookie JSON(扩展导出), 不入库
PROXY = os.environ.get('EHMETA_PROXY') or 'http://127.0.0.1:7897'
LIST_URL = 'https://exhentai.org/'
API_URL = 'https://exhentai.org/api.php'
OLD_DB = os.environ.get('EHMETA_DB') or os.path.join(HERE, 'ehmeta.db')

ANCHOR_MARGIN = 10000          # 锚点回退余量（gid）
BATCH = 25                     # gdata 每批本数
SLEEP_LIST = (15.0, 25.0)       # 列表页间隔（随机区间, 秒; 防 EH 降级页软限）
SLEEP_GDATA = (2.0, 3.5)       # gdata 间隔
MAX_BATCHES_DEFAULT = 800      # 本次 gdata 批数上限（≈40 分钟）
SUSPICIOUS_LIMIT = 5           # 连续可疑空响应批数上限 → 熔断

LAG_DAYS = 8                   # 滞后窗口（天）: 只抓"上传 ≥8 天"的新本（tag 成熟）
GID_PER_DAY = 2100             # gid 日增（用于 lag cutoff 外推）
LOW_TAG_THRESHOLD = 5          # 初抓 tags < 5 → 进重访清单
MAX_REVISITS = 2               # watchlist 重访上限

UA = ('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')

SUF = ''                       # smoke 时 = '.smoke'


def path(name):
    return os.path.join(HERE, name + SUF)


PENDING = None
INCR = None
STATE = None
LOG = None
WATCH = None


def log(msg):
    line = time.strftime('[%Y-%m-%d %H:%M:%S] ') + msg
    print(line, flush=True)
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(line + '\n')


class BanError(Exception):
    """硬异常（HTTP 连续失败等）：exit 2。"""


class SoftLimit(Exception):
    """EH 降级页（翻页未前进重试后仍卡）：exit 3，歇 2h+ 再跑。"""


def load_cookie():
    if not os.path.exists(COOKIE_FILE):
        raise SystemExit('缺少 cookie 文件: %s（浏览器扩展导出的 JSON 放此; 或设环境变量 EHMETA_COOKIE）'
                         % COOKIE_FILE)
    d = json.load(open(COOKIE_FILE, encoding='utf-8'))
    parts = []
    if isinstance(d, list):
        for c in d:
            if c.get('name') and c.get('value') is not None:
                parts.append('%s=%s' % (c['name'], c['value']))
    elif isinstance(d, dict):
        for k, v in d.items():
            if isinstance(v, dict) and 'value' in v:
                parts.append('%s=%s' % (k, v['value']))
            elif isinstance(v, str):
                parts.append('%s=%s' % (k, v))
    if not parts:
        raise SystemExit('cookie 文件解析为空: ' + COOKIE_FILE)
    return '; '.join(parts)


def make_opener():
    proxy = urllib.request.ProxyHandler({'https': PROXY, 'http': PROXY})
    return urllib.request.build_opener(proxy)


def http_get(opener, cookie, url, retries=2):
    last = None
    for k in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': UA, 'Cookie': cookie})
            r = opener.open(req, timeout=30)
            return r.read().decode('utf-8', errors='ignore')
        except Exception as e:            # noqa: BLE001
            last = e
            if k < retries:
                wait = 30 * (k + 1)
                log('  GET 异常(%s), %ds 后重试 %d/%d' % (type(e).__name__, wait, k + 1, retries))
                time.sleep(wait)
    raise BanError('GET 连续失败: %s (%s)' % (url, last))


def http_gdata(opener, cookie, gid_token_pairs):
    body = json.dumps({'method': 'gdata',
                       'gidlist': [[g, t] for g, t in gid_token_pairs],
                       'namespace': 1}).encode()
    last = None
    for k in range(2 + 1):
        try:
            req = urllib.request.Request(API_URL, data=body, headers={
                'Content-Type': 'application/json', 'User-Agent': UA, 'Cookie': cookie})
            r = opener.open(req, timeout=40)
            raw = r.read().decode('utf-8', errors='ignore')
            return json.loads(raw)
        except Exception as e:            # noqa: BLE001
            last = e
            if k < 2:
                wait = 30 * (k + 1)
                log('  gdata 异常(%s), %ds 后重试' % (type(e).__name__, wait))
                time.sleep(wait)
    raise BanError('gdata 连续失败: %s' % last)


def anchor_gid():
    con = sqlite3.connect('file:%s?mode=ro' % OLD_DB, uri=True)
    try:
        g = con.execute('SELECT max(gid) FROM gallery').fetchone()[0]
    finally:
        con.close()
    return int(g) - ANCHOR_MARGIN


def load_state():
    if os.path.exists(STATE):
        return json.load(open(STATE, encoding='utf-8'))
    return {}


def save_state(st):
    tmp = STATE + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(st, f, ensure_ascii=False, indent=1)
    os.replace(tmp, STATE)


def load_jsonl_set(fname, key='gid'):
    s = set()
    if os.path.exists(fname):
        for line in open(fname, encoding='utf-8'):
            try:
                s.add(json.loads(line)[key])
            except Exception:             # noqa: BLE001
                pass
    return s


def append_pending(pairs):
    with open(PENDING, 'a', encoding='utf-8') as f:
        for g, t in pairs:
            f.write(json.dumps({'gid': g, 'token': t}) + '\n')


def load_watch():
    w = {}
    if os.path.exists(WATCH):
        for line in open(WATCH, encoding='utf-8'):
            try:
                o = json.loads(line)
                w[o['gid']] = (o.get('token', ''), int(o.get('revisits', 0)))
            except Exception:             # noqa: BLE001
                pass
    return w


def save_watch(w):
    tmp = WATCH + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        for g, (t, rv) in sorted(w.items()):
            f.write(json.dumps({'gid': g, 'token': t, 'revisits': rv}) + '\n')
    os.replace(tmp, WATCH)


# ---------------- 阶段1：发现（列表游标翻页） ----------------
def phase_list(opener, cookie, state, smoke=0):
    anchor = anchor_gid()
    have = load_jsonl_set(PENDING)
    seen_max = state.get('seen_max_gid') or 0
    log('[发现] 锚点 gid = %d; pending 已有 %d 条' % (anchor, len(have)))
    nxt = state.get('list_next', '')
    page_no = state.get('list_pages', 0)
    smoke_pages = 0
    while True:
        url = LIST_URL if not nxt else (LIST_URL + '?next=' + str(nxt))
        html = http_get(opener, cookie, url)
        rows = re.findall(r'/g/(\d+)/([0-9a-f]+)/', html)
        if not rows:
            raise BanError('列表页无条目（可能被拦或页面结构变化）: ' + url)
        min_gid = min(int(g) for g, _ in rows)
        max_gid = max(int(g) for g, _ in rows)
        prev_tail = state.get('page_tail')
        if prev_tail is not None and min_gid >= prev_tail:
            try:
                open('/tmp/eh_page_fail.html', 'w', encoding='utf-8').write(html)
            except Exception:             # noqa: BLE001
                pass
            log('[发现] 警告: 翻页未前进(tail=%d >= prev=%d), 疑 EH 降级页, 60s 重试'
                % (min_gid, prev_tail))
            ok2 = False
            for attempt in range(3):
                log('[发现] 第%d次重试（60s 后, 换相位 tail-1）' % (attempt + 1))
                time.sleep(60)
                url2 = LIST_URL + '?next=' + str(prev_tail - 1)
                html = http_get(opener, cookie, url2)
                try:
                    open('/tmp/eh_page_fail2.html', 'w', encoding='utf-8').write(html)
                except Exception:             # noqa: BLE001
                    pass
                rows = re.findall(r'/g/(\d+)/([0-9a-f]+)/', html)
                if rows and min(int(g) for g, _ in rows) < prev_tail:
                    min_gid = min(int(g) for g, _ in rows)
                    max_gid = max(int(g) for g, _ in rows)
                    ok2 = True
                    break
            if not ok2:
                state['page_tail'] = prev_tail
                save_state(state)
                raise SoftLimit('翻页未前进（多相位重试仍降级）——歇 2h+ 再跑')
        fresh = [(int(g), t) for g, t in rows if int(g) not in have]
        append_pending(fresh)
        have.update(g for g, _ in fresh)
        seen_max = max(seen_max, max_gid)
        state['seen_max_gid'] = seen_max
        page_no += 1
        log('[发现] 第%d页: %d 条(新 %d) 页尾 gid=%d' % (page_no, len(rows), len(fresh), min_gid))
        if min_gid <= anchor:
            state['list_next'] = ''
            state['list_pages'] = page_no
            state['page_tail'] = min_gid
            state['list_done'] = True
            save_state(state)
            log('[发现] 已到达锚点，发现阶段完成（pending 合计 %d 条）' % len(have))
            return
        nx = re.findall(r'\?next=(\d+)', html)
        if not nx:
            state['list_next'] = ''
            state['list_pages'] = page_no
            state['page_tail'] = min_gid
            state['list_done'] = True
            save_state(state)
            log('[发现] 列表到底（无 next），完成')
            return
        state['list_next'] = str(min_gid - 1)
        state['list_pages'] = page_no
        state['page_tail'] = min_gid
        save_state(state)
        smoke_pages += 1
        if smoke and smoke_pages >= smoke:
            log('[发现] smoke 达上限 %d 页，暂停' % smoke)
            return
        time.sleep(random.uniform(*SLEEP_LIST))


# ---------------- 阶段2：抓取（gdata, 断点续传 + 成熟度策略） ----------------
def phase_gdata(opener, cookie, state, max_batches, smoke=0, no_lag=False):
    pairs = []
    if os.path.exists(PENDING):
        for line in open(PENDING, encoding='utf-8'):
            try:
                o = json.loads(line)
                pairs.append((o['gid'], o['token']))
            except Exception:             # noqa: BLE001
                pass
    done = load_jsonl_set(INCR)
    coarse = not smoke                       # smoke 只验机械链路

    # ---- 构造 todo: 回访段 → watchlist → 新段（滞后过滤） ----
    todo = []
    seen_todo = set()

    def add(lst):
        for p in lst:
            if p[0] not in seen_todo:
                todo.append(p)
                seen_todo.add(p[0])

    watch = {}
    if coarse:
        pf, pl = state.get('prev_run_first_gid'), state.get('prev_run_last_gid')
        if pf and pl:
            revisit = [p for p in pairs if pf <= p[0] <= pl]
            log('[抓取] 双抓回访段 [%d..%d]: %d 本（重抓成熟 tag）' % (pf, pl, len(revisit)))
            add(revisit)
        watch = load_watch()
        if watch:
            wl = sorted((g, t) for g, (t, rv) in watch.items() if rv < MAX_REVISITS)
            log('[抓取] watchlist 重访: %d 本' % len(wl))
            add(wl)

    seen_max = state.get('seen_max_gid')
    cutoff = None
    if coarse and (not no_lag) and seen_max:
        cutoff = seen_max - LAG_DAYS * GID_PER_DAY
        held = sum(1 for p in pairs if p[0] > cutoff)
        log('[抓取] 滞后过滤: cutoff=%d（滞后 %d 天）, 嫩段待下次 %d 本' % (cutoff, LAG_DAYS, held))
    new_pairs = [p for p in pairs if p[0] not in done
                 and (cutoff is None or p[0] <= cutoff)]
    new_pairs.sort()
    log('[抓取] 新段: %d 本' % len(new_pairs))
    add(new_pairs)

    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    limit = smoke if smoke else max_batches
    log('[抓取] todo 合计 %d 本 → %d 批（本次上限 %d）' % (len(todo), len(batches), limit))

    done_batches = 0
    suspicious = 0
    for bi, batch in enumerate(batches):
        if done_batches >= limit:
            break
        t0 = time.time()
        data = http_gdata(opener, cookie, batch)
        gm = data.get('gmetadata') if isinstance(data, dict) else None
        if gm is None:
            suspicious += 1
            log('[抓取] 批次 %d 响应异常(无 gmetadata), 可疑 %d/%d' % (bi, suspicious, SUSPICIOUS_LIMIT))
            if suspicious >= SUSPICIOUS_LIMIT:
                raise BanError('连续可疑空响应达上限')
            time.sleep(60)
            continue
        ok_n = sum(1 for g in gm if 'error' not in g)
        err_n = len(gm) - ok_n
        got_ids = {g.get('gid') for g in gm if isinstance(g, dict)}
        omitted = [(g, t2) for g, t2 in batch if g not in got_ids]
        with open(INCR, 'a', encoding='utf-8') as f:
            for g in gm:
                if isinstance(g, dict) and 'gid' in g:
                    f.write(json.dumps(g, ensure_ascii=False) + '\n')
            for gid, _t2 in omitted:
                f.write(json.dumps({'gid': gid, 'error': 'omitted-by-api'},
                                   ensure_ascii=False) + '\n')
        # watchlist 更新（成熟度：tags 数）
        if coarse:
            for g in gm:
                if not isinstance(g, dict) or 'error' in g:
                    continue
                gid = g.get('gid')
                n = len(g.get('tags') or [])
                if n >= LOW_TAG_THRESHOLD:
                    watch.pop(gid, None)
                else:
                    tok = g.get('token', '')
                    rv = watch.get(gid, (tok, 0))[1] + 1
                    if rv <= MAX_REVISITS:
                        watch[gid] = (tok, rv)
            save_watch(watch)
        if ok_n == 0 and err_n == 0:
            suspicious += 1
        else:
            suspicious = 0
        done_batches += 1
        state['gdata_batches'] = state.get('gdata_batches', 0) + 1
        state['last_batch_gids'] = [batch[0][0], batch[-1][0]]
        save_state(state)
        log('[抓取] 批%d: %d 本(ok %d / err %d / 无返回 %d) %.1fs' % (
            bi, len(gm), ok_n, err_n, len(omitted), time.time() - t0))
        time.sleep(random.uniform(*SLEEP_GDATA))

    # 记录"本轮新段"范围 → 供下次双抓回访
    if coarse and new_pairs:
        state['prev_run_first_gid'] = min(g for g, _ in new_pairs)
        state['prev_run_last_gid'] = max(g for g, _ in new_pairs)
        save_state(state)
    log('[抓取] 本次完成 %d 批（累计 %d）' % (done_batches, state.get('gdata_batches', 0)))


def main():
    global SUF, PENDING, INCR, STATE, LOG, WATCH
    argv = sys.argv[1:]
    smoke = 0
    list_only = '--list-only' in argv
    gdata_only = '--gdata-only' in argv
    no_lag = '--no-lag' in argv
    max_batches = MAX_BATCHES_DEFAULT
    for i, a in enumerate(argv):
        if a == '--smoke' and i + 1 < len(argv):
            smoke = int(argv[i + 1])
        if a == '--max-batches' and i + 1 < len(argv):
            max_batches = int(argv[i + 1])
    if smoke:
        SUF = '.smoke'
        no_lag = True
    PENDING = path('pending.jsonl')
    INCR = path('incremental.jsonl')
    STATE = path('state.json')
    LOG = path('sync.log')
    WATCH = path('watchlist.jsonl')

    cookie = load_cookie()               # 只解析, 不回显
    opener = make_opener()
    state = load_state()
    log('== sync_increment v2 启动（smoke=%s, max_batches=%d, no_lag=%s）=='
        % (smoke, max_batches, no_lag))
    try:
        if not gdata_only and not state.get('list_done'):
            phase_list(opener, cookie, state, smoke)
        else:
            log('[发现] list_done=%s, 跳过' % state.get('list_done'))
        if not list_only:
            phase_gdata(opener, cookie, state, max_batches, smoke, no_lag)
        log('== 正常结束 ==')
    except SoftLimit as e:
        log('!! EH 降级页（软限）——请歇 2 小时以上再跑（断点已存）: %s' % e)
        sys.exit(3)
    except BanError as e:
        log('!! 熔断退出（断点已保存, 当日不再跑）: %s' % e)
        sys.exit(2)


if __name__ == '__main__':
    main()
