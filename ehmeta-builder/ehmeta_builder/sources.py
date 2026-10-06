# -*- coding: utf-8 -*-

from __future__ import annotations
"""源清单：五个已验证数据源的直链 / 体积 / sha256 / 更新方式。

常量来源：EHENTAI_M0_REPORT.md §7（bogey 两源的前缀/后缀 sha 交叉验证）+ 2026-10-06 本机全量 sha256 实测。
纪律：构建设有零 EH API 调用（裸 tag 解析为纯本地规则，见 builder.resolve_bare_tags）。
"""
from dataclasses import dataclass, field

UA = "ehmeta-builder/1.0 (+https://github.com/ptkdLLL/comicshelf-android)"


@dataclass
class Source:
    key: str              # 短名（缓存目录名 / manifest 键）
    label: str            # 界面显示
    kind: str             # "github"（releases/latest 资产）| "http"（固定直链）
    local_rel: str        # 缓存文件相对路径（workdir 下）
    extract_rel: str = "" # 需要解压出的文件（zip/gz → 该路径）；空=无需解压
    repo: str = ""        # kind=github
    asset: str = ""       # kind=github（资产名；实际名以 releases/latest 为准）
    url: str = ""         # kind=http
    size: int = 0         # 期望字节数（固定源；动态源为 0=每次记录）
    sha256: str = ""      # 期望 sha256（固定源；动态源为 ""=每次记录实测值）
    optional: bool = False  # 可选源（缺失时构建降级并显式提示）
    batch: str = "update"   # "once"（一次性源，缓存后不再查）| "update"（每期检查）
    note: str = ""
    # 运行时填充
    meta: dict = field(default_factory=dict)


SOURCES = [
    Source(
        key="hwei",
        label="hwei 元数据快照（gdata.zip）",
        kind="github",
        repo="hwei115j/exhentai_metadata",
        asset="gdata.zip",
        local_rel="sources/hwei/gdata.zip",
        extract_rel="sources/hwei/gdata.json",
        size=564_577_051,
        sha256="7eddd1dffd6d4ee9c9c51a7a4afa816b9d463c270b44eda7487ec4470f5130c1",
        batch="update",
        note="主源（约 2-3 月一期）；sha 为本机实测值（上游未发布 sha），重下载时以体积+sha 双重校验",
    ),
    Source(
        key="bogey",
        label="bogey 官方 API 全量 dump（merge）",
        kind="http",
        url="https://huggingface.co/datasets/bogeyturn/exhentai-api-dump-raw/resolve/main/merge",
        local_rel="sources/bogey/merge.jsonl",
        size=2_361_534_443,
        sha256="0152dbe67a6e7a5dd47417d1864477cc69757d70a4a257e707c6e40738661200",
        batch="once",
        note="2025-01 冻结快照：补缺 + expunged + replaced(current_gid)",
    ),
    Source(
        key="bogey_csv",
        label="bogey 中期 CSV（gallery.csv）",
        kind="http",
        url="https://huggingface.co/datasets/bogeyturn/exhentai-api-dump/resolve/main/gallery.csv",
        local_rel="sources/bogey_csv/gallery.csv",
        size=1_453_421_004,
        sha256="51a80808413520823d305d6f3ad64bef0bd8e1c96ee258ecd23447c7e8b42e32",
        batch="once",
        note="2023-07 冻结快照：中期标题别名",
    ),
    Source(
        key="ehdb",
        label="ehdb 早期快照（gdata.json → 轻量库）",
        kind="http",
        url="https://media.githubusercontent.com/media/exzhawk/ehdb/master/gdata.json",
        local_rel="sources/ehdb/gdata.json",
        extract_rel="sources/ehdb/eh_lite.db",   # 由 ehdb2lite.convert 生成
        size=616_742_672,
        sha256="67ffeb5bf888edadfab412d64a97fb867d83166dc9e9525d810ed49cd49a9b68",
        optional=True,
        batch="once",
        note="2019-07 冻结快照（仅历史标题别名，边际贡献）；走 GitHub LFS 媒体端点（有月度带宽配额，故设为可选源）",
    ),
    Source(
        key="ehtag",
        label="EhTagTranslation 中文 tag 名",
        kind="github",
        repo="EhTagTranslation/Database",
        asset="db.raw.json.gz",
        local_rel="sources/ehtag/db.raw.json.gz",
        extract_rel="sources/ehtag/db.raw.json",
        batch="update",
        note="一天多次发布的活跃源；CC BY-NC-SA 4.0（署名写入 meta.json.licenses 与 README）",
    ),
]

BY_KEY = {s.key: s for s in SOURCES}


def workdir_paths(workdir: str) -> dict:
    """返回 {key: 缓存绝对路径} + 产物目录。"""
    import os
    out = {"out": os.path.join(workdir, "out")}
    for s in SOURCES:
        out[s.key] = os.path.join(workdir, s.local_rel)
        if s.extract_rel:
            out[s.key + "_extract"] = os.path.join(workdir, s.extract_rel)
    return out


LICENSES = {
    "EhTagTranslation": {
        "license": "CC BY-NC-SA 4.0",
        "source": "https://github.com/EhTagTranslation/Database",
        "usage": "tag 中文名（写入 tag.name_zh）",
    },
    "hwei115j/exhentai_metadata": {
        "license": "见上游仓库",
        "source": "https://github.com/hwei115j/exhentai_metadata",
    },
    "bogeyturn dumps": {
        "license": "见上游数据集页",
        "source": "https://huggingface.co/datasets/bogeyturn/exhentai-api-dump-raw",
    },
    "exzhawk/ehdb": {
        "license": "见上游仓库",
        "source": "https://github.com/exzhawk/ehdb",
    },
}
