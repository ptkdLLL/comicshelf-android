# -*- coding: utf-8 -*-

from __future__ import annotations
"""ehmeta-builder：E-Hentai 元数据桌面构建器（Mac/PC）。

由 ComicShelfAndroid 的 S0 生产管线（build_ehmeta.py，已在 v0.5.0 包上验证）
移植/产品化：自动取源 → 本地 6 阶段构建 → 产出 App 可导入的 ehmeta.db.zip。

模块：
  sources    源清单（URL/体积/sha256/节奏，全部 2026-10-06 实测）
  download   代理 + 断点续传 + 校验的下载器
  ehdb2lite  exzhawk/ehdb 的 gdata.json → 轻量 SQLite（对齐现役 eh_lite.db schema）
  builder    6 阶段构建（逐段移植自 build_ehmeta.py，语义一字不动）
  verify     计数/抽样/金标准对账
  cli        命令行入口
  gui        Tkinter 图形界面
"""
__version__ = "1.0.0"
