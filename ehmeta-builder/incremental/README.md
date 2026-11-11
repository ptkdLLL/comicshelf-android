# ehmeta 增量同步 · 外带部署包（一键启动）

零依赖（纯 python3 stdlib）+ 断点续 + 防双开 + 自动出包。整个目录拷到任何
macOS / Linux 机器，放好凭据与基准库即可一条命令启动。

## 一键启动

```bash
./run.sh            # 推进一轮：发现(续) → 抓取(消费) → 出包；Ctrl-C 随时停
./run.sh --loop     # 无人值守挂机：软限自动歇 2h；收敛(连续两轮零新增)后自动退出
./run.sh --status   # 查看进度（pending/incremental/游标/批数/最近合并报告）
```

## 外带三件套（放同目录，或用环境变量指路）

| 件 | 默认路径 | 环境变量 | 说明 |
|---|---|---|---|
| cookie | `./cookie.json` | `EHMETA_COOKIE` | 浏览器扩展导出的 cookie JSON。**凭据绝不入库/不回显** |
| 基准库 | `./ehmeta.db` | `EHMETA_DB` | 出包 = 本库副本 + 增量全量重放（幂等） |
| 代理 | `http://127.0.0.1:7897` | `EHMETA_PROXY` | 访问 exhentai 一般需可达代理；外机自备 |

可选：`ehtag_db.raw.json`（EhTagTranslation 导出，新 tag 中文名映射；缺失仅留空）。

启动时自检会在缺件时直接报出缺失路径与解决方式（不会读到一半才失败）。

## 目录内容

| 文件 | 说明 |
|---|---|
| `run.sh` | 一键启动器（本文件所在目录即工作目录，状态文件随目录走）|
| `sync_increment.py` | 主脚本：阶段1 列表游标发现 → 阶段2 gdata 抓取（成熟度策略）|
| `merge_and_pack.py` | 合并打包：基准库副本 + 增量重放 → `out/ehmeta.db(.zip)` |
| `pending.jsonl` | 阶段1 产物 `{gid,token}`（只增，幂等）|
| `incremental.jsonl` | 阶段2 产物：gdata 原始条目（追加，天然断点）|
| `watchlist.jsonl` | tags<5 低成熟度重访清单（上限 2 次）|
| `state.json` | 断点（游标 / seen_max / 上轮段 / 批计数）|
| `sync.log` | 运行日志（含软限/熔断/每批统计）|
| `out/` | 出包目录：`ehmeta.db` + `ehmeta.db.zip` + 合并由 `MERGE_REPORT.txt` 记录 |

## 防 ban 铁律（脚本已内建，勿改）

- 单线程慢速：列表 15–25s/页、gdata 2–3.5s/批（随机）；单次上限 800 批（约 40 分钟）；
- 软限识别：翻页未前进 = EH 降级页 → 60s ×3 相位重试 → 仍卡则 **exit 3（歇 2h+ 再跑）**；
- HTTP 连续异常 = **exit 2**（当日不再跑）；失败**绝不当"不存在"入库**（只追加 `omitted-by-api` 审计行）；
- 只抓元数据（gdata），不触碰图库内容。

## tag 成熟度策略（滞后 8 天）

上传初期的本子社区未补 tag（实测最新 25 本中 64% tags≤4）。因此：只抓
`gid ≤ seen_max − 8×2100` 的新段；上轮新段整段回抓（每 gid 至少两抓）；
tags<5 进 watchlist 重访（上限 2 次）。日常增量一轮约 6–10 分钟。

## smoke（最小真实演练，防 ban 友好）

```bash
python3 sync_increment.py --smoke 1     # 1 页列表 + 1 批 gdata, 产物加 .smoke 后缀
```
smoke 只写 `*.smoke` 文件，不碰正式断点与产物——适合换机/换网后的连通性验证。
