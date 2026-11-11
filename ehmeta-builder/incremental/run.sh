#!/bin/bash
# ehmeta 增量同步 · 一键启动器 v3（外带版: macOS / Linux 通用, 纯 python3 stdlib）
#   ./run.sh          推进一轮: 「发现(续) → 抓取(消费囤积) → 出包」; 断点续, Ctrl-C 随时停
#   ./run.sh --loop   无人值守: 软限自动歇 2h; 收敛(连续两轮零新增)后退出
#   ./run.sh --status 查看进度
#
# 外带三件套（与脚本同目录, 或用环境变量指路）:
#   ① cookie.json    浏览器扩展导出的 cookie（EHMETA_COOKIE 可覆盖; 绝不入库/不回显）
#   ② ehmeta.db      基准库（EHMETA_DB 可覆盖; 出包=基准库副本+增量重放）
#   ③ 代理            默认 http://127.0.0.1:7897（EHMETA_PROXY 可覆盖; 外机需自备可达代理）
# 可选: ehtag_db.raw.json（新 tag 中文名映射, 缺失仅留空）
set -u
D="$(cd "$(dirname "$0")" && pwd)"
cd "$D" || exit 1

state_get() {
  python3 -c "import json,os;d=json.load(open('state.json')) if os.path.exists('state.json') else {};print(d.get('$1',''))" 2>/dev/null
}
count_lines() { [ -f "$1" ] && wc -l < "$1" || echo 0; }

case "${1:-}" in
--status)
  echo "==== ehmeta 增量同步 · 进度 ===="
  grep '\[发现\] 第' sync.log 2>/dev/null | tail -2
  echo "pending:      $(count_lines pending.jsonl) 行"
  echo "incremental:  $(count_lines incremental.jsonl) 行"
  echo "list_done: $(state_get list_done) | pages: $(state_get list_pages) | tail_gid: $(state_get page_tail) | batches: $(state_get gdata_batches)"
  [ -f MERGE_REPORT.txt ] && tail -3 MERGE_REPORT.txt
  exit 0 ;;
esac

# ---- 外带自检：缺件给出明确指引（只查存在性/路径, 不读内容）----
COOKIE="${EHMETA_COOKIE:-$D/cookie.json}"
DB="${EHMETA_DB:-$D/ehmeta.db}"
PROXY="${EHMETA_PROXY:-http://127.0.0.1:7897}"
if [ ! -f "$COOKIE" ]; then
  echo "缺少 cookie 文件: $COOKIE"
  echo "  → 把浏览器扩展导出的 cookie JSON 放到该路径, 或 export EHMETA_COOKIE=<路径>"
  exit 1
fi
if [ ! -f "$DB" ]; then
  echo "缺少基准库: $DB"
  echo "  → 放置 ehmeta.db 到该路径, 或 export EHMETA_DB=<路径>"
  exit 1
fi
export EHMETA_COOKIE="$COOKIE" EHMETA_DB="$DB" EHMETA_PROXY="$PROXY"
echo "自检: cookie OK | db $(basename "$DB") | 代理 $PROXY | python3 $(python3 -V 2>&1 | cut -d' ' -f2)"

# caffeinate（防休眠）仅 macOS 有, 其他平台自动降级
if command -v caffeinate >/dev/null 2>&1; then CF=(caffeinate -i); else CF=(); fi

LOOP=0
[ "${1:-}" = "--loop" ] && LOOP=1

# ---- 防双开（进程 + 锁双检）----
LOCK="$D/.run.lock"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  echo "已有 run.sh 实例在跑（pid $(cat "$LOCK")）——禁止双开"; exit 1
fi
if pgrep -f 'sync_increment.py' >/dev/null 2>&1; then
  echo "检测到 sync_increment.py 进程在跑（可能是手动启动的）——禁止双开"; exit 1
fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"' EXIT

CONSUMED_NEW=0     # consume 本轮新增行数(全局)
consume() {        # 抓取一批 + 有新增则出包; 返回 sync rc
  local pre post rc
  pre=$(count_lines incremental.jsonl)
  echo ">> 阶段: 抓取（消费囤积 pending, 本轮 ≤800 批）"
  "${CF[@]}" python3 sync_increment.py --gdata-only --max-batches 800; rc=$?
  echo ">> 抓取 rc=$rc"
  if [ $rc -ne 0 ]; then CONSUMED_NEW=0; return $rc; fi
  post=$(count_lines incremental.jsonl)
  CONSUMED_NEW=$((post - pre))
  if [ "$CONSUMED_NEW" -gt 0 ]; then
    echo ">> 合并打包（新增 $CONSUMED_NEW 行）"
    "${CF[@]}" python3 merge_and_pack.py && echo ">> 新包: out/ehmeta.db.zip"
  else
    echo ">> 本轮零新增"
  fi
  return 0
}
wait_or_exit() {   # 参数: rc
  case "$1" in
    3) echo ">> EH 软限——歇 2 小时再继续"
       if [ $LOOP -eq 1 ]; then sleep 7200; return 0;
       else echo ">> 歇够后重跑 ./run.sh（断点已存）"; exit 3; fi ;;
    2) echo ">> 硬异常——当日退出（看 sync.log; 多为网络抖动, 恢复后重跑 ./run.sh）"; exit 2 ;;
    *) exit "$1" ;;
  esac
}

echo "==== ehmeta 增量同步 v3 · $(date '+%F %T') ===="
zero_rounds=0
while true; do
  # ---- 阶段A: 发现（未完成则续; 中断也先消费）----
  if [ "$(state_get list_done)" != "True" ]; then
    echo ">> 阶段: 发现（列表翻页, 断点续）"
    "${CF[@]}" python3 sync_increment.py --list-only; rc=$?
    echo ">> 发现 rc=$rc ($(date '+%F %T'))"
    if [ $rc -ne 0 ]; then
      echo ">> 发现中断——先消费已囤数据"
      consume || true
      wait_or_exit $rc
    fi
  fi
  # ---- 阶段B: 抓取（消费囤积）----
  consume; rc=$?
  if [ $rc -ne 0 ]; then wait_or_exit $rc; fi
  # ---- 收敛判定（仅当发现已到底）----
  if [ "$(state_get list_done)" = "True" ]; then
    if [ "$CONSUMED_NEW" -gt 0 ]; then
      zero_rounds=0
    else
      zero_rounds=$((zero_rounds + 1))
      echo ">> 抓取零新增（$zero_rounds/2）"
      if [ $zero_rounds -ge 2 ]; then
        echo ">> 已收敛（嫩段待滞后窗口到期自动可抓）——退出"
        break
      fi
    fi
  else
    zero_rounds=0
  fi
  if [ $LOOP -eq 0 ]; then
    echo ">> 本轮完成。再跑 ./run.sh 继续（或 ./run.sh --loop 挂机）"
    break
  fi
done
