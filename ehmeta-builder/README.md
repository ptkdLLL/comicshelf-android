# ehmeta-builder · E-Hentai 元数据桌面构建器

在 **Mac / PC** 上运行的图形工具：手动运行 → 自动从已验证的公开来源抽取元数据 → 在本机构建
**最新的 E-Hentai 元数据数据库** → 产出可被 ComicShelf（Android）导入的 `ehmeta.db.zip`。

- 构建过程**完全本地**（不调用 E-Hentai 官方 API，只从公开数据源下载文件）；
- 网络受限时填入**代理地址**（如 Clash Verge 的混合端口 `http://127.0.0.1:7897`）即可自动下载；
- 每期只需下载变化的源（通常仅 hwei 快照 ~565MB + 中文名 ~1.8MB）。

## 快速开始

### 方式 A：源码运行（Mac/PC，需 Python 3.10+）

```bash
cd ehmeta-builder
python3 -m ehmeta_builder gui                      # 图形界面
# 或命令行：
python3 -m ehmeta_builder update --proxy http://127.0.0.1:7897
```

### 方式 B：打包二进制

```bash
bash packaging/build_mac.sh     # Mac → dist/ehmeta-builder.app + dist/ehmeta-builder-cli
packaging\build_win.bat         # Windows（需在 Windows 机器上执行，未在开发机验证）
```

> Mac 未签名 .app 首次打开：右键 → 打开。

## 使用（GUI）

1. **工作目录**：默认 `~/ComicShelfBuilder`（缓存与产物都放这里）；
2. **代理**：填 `127.0.0.1:7897` 或 `http://127.0.0.1:7897` → 点「测试连接」看到 GitHub ✓ / HuggingFace ✓；
   - Clash Verge 请在设置里确认「混合端口」（默认 7897，同时支持 HTTP/HTTPS）；
   - 本工具只支持 **HTTP 代理**（纯标准库实现）；SOCKS-only 端口请改用混合端口；
3. 点 **「一键构建最新数据库」**：自动查上游版本 → 只下载有变化的源 → 解压/转换 → 6 阶段构建 → 产出；
4. 完成后点「打开产物文件夹」，把 `out/ehmeta.db.zip` 传到手机，在 ComicShelf 设置页导入。

## 数据来源（均为公开来源，全部在 v0.5.0 生产包上验证过）

| 源 | 内容 | 体积 | 更新节奏 | 说明 |
|---|---|---|---|---|
| hwei115j/exhentai_metadata | 元数据主快照（349 万 gid） | ~565MB zip | 2-3 月/期 | GitHub Releases |
| EhTagTranslation/Database | tag 中文名 | ~1.8MB | 一天多次 | **CC BY-NC-SA 4.0**（署名见产物 `meta.json.licenses`） |
| bogeyturn dumps（HF） | 2025-01 全量补缺 + expunged/replaced | ~2.36GB | 冻结 | 仅首次下载 |
| bogeyturn CSV（HF） | 2023-07 中期标题别名 | ~1.45GB | 冻结 | 仅首次下载 |
| exzhawk/ehdb | 2019-07 早期标题别名 | ~616MB | 冻结 | **可选源**（GitHub LFS 有带宽配额，失败会自动跳过） |

## 磁盘与流量预算

- 首次全量：下载 ~5.1GB（其中 ehdb 616MB 可选），峰值磁盘 ~9GB，构建 ~5-10 分钟；
- 之后每期：下载仅 ~567MB，重建 ~5-10 分钟（缓存源复用）；
- 勾选「构建后清理解压的大文件」可回收 ~2GB（zip 保留，重建时自动再解压）。

## 命令行

```bash
python3 -m ehmeta_builder fetch  --proxy http://127.0.0.1:7897      # 只取源
python3 -m ehmeta_builder fetch  --only hwei,ehtag                  # 只更新指定源
python3 -m ehmeta_builder build  --limit 5000 --nozip               # 冒烟
python3 -m ehmeta_builder update --proxy http://127.0.0.1:7897      # 一键（取源+构建）
python3 -m ehmeta_builder verify out/ehmeta.db --golden             # 与金标准对账
```

环境变量 `EH_PROXY` 可作默认代理；`--workdir` 指定工作目录。

## 可信度说明

本工具是 ComicShelf 的 **S0 生产管线**（`build_ehmeta.py`）的移植产品化版本，合并语义/SQL/索引顺序
**逐段一致**（对齐表见 `docs/EHENTAI_S5_BUILDER_PLAN.md` §4），并通过两层对账验收：

- **L1 冒烟**：相同源 `--limit 5000`，工具产物 vs 原脚本产物计数全等；
- **L2 全量**：工具产物 vs 现役 v0.5.0 包——8 项计数 + 12 个命名空间分布一致，且
  `s1_mac.py keys` 对两库导出键索引**逐字节一致**。

## 常见问题

- **下载失败/超时**：先点「测试连接」；中国内地直连 GitHub/HuggingFace 通常不可达，请填代理；
- **ehdb 源 402/403**：GitHub LFS 带宽配额被耗尽，取消勾选「包含 ehdb 早期快照」即可继续（别名略少，其余不受影响）；
- **Windows**：源码与打包脚本已提供；二进制**未在开发机验证**，如遇编码/路径问题请反馈；
- **产物导入不了**：确认文件是 `out/ehmeta.db.zip`（不要手动解压），且手机 App 为 v0.5.0+。

## 许可

本工具代码随 ComicShelfAndroid 仓库（见仓库 LICENSE/NOTICE）。下载的数据归各上游所有，
其中 EhTagTranslation（CC BY-NC-SA 4.0）署名已写入产物 `meta.json.licenses` 段。
