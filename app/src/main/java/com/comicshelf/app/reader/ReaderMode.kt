package com.comicshelf.app.reader

import com.comicshelf.app.core.CsSettings

/**
 * 连续滚动模式 · 方向与模式解析（docs/READER_VERTICAL_SCROLL_PLAN.md P-V1）。
 *
 * 方向 dir = 0 左→右 / 1 右→左 / 2 上→下；连续 scroll = 关/开。
 * 每册记忆：`reader_dir_<bookId>` / `reader_scroll_<bookId>`（方向缺键回退旧全局
 * reader_rtl——迁移语义：旧库打开行为与 v0.5.6 逐位一致；连续默认关）。
 * 写键只在用户于设置中改动时发生（P-V9）；本文件只读。
 * resolveDirKey / vertical / rtl 为纯函数（单测覆盖，P-V10a）。
 */
data class ReaderMode(
    val dir: Int = DIR_L2R,
    val scroll: Boolean = false,
) {
    /** 主轴竖直（上→下）。 */
    val vertical: Boolean get() = dir == DIR_T2B

    /** 仅「右→左」具有左右反向语义（切分半序 / 点按分区 / 横向流反向）；上→下 无左右。 */
    val rtl: Boolean get() = dir == DIR_R2L

    companion object {
        const val DIR_L2R = 0
        const val DIR_R2L = 1
        const val DIR_T2B = 2

        /** 纯函数：本册键值 → 生效方向（缺键/越界回退旧全局 rtl）。 */
        fun resolveDirKey(key: Int, legacyRtl: Boolean): Int =
            if (key in DIR_L2R..DIR_T2B) key else if (legacyRtl) DIR_R2L else DIR_L2R

        /** 读键解析本册模式（只读，不改任何存储）。 */
        fun load(bookId: Long, legacyRtl: Boolean): ReaderMode = ReaderMode(
            dir = resolveDirKey(CsSettings.int("reader_dir_$bookId", -1), legacyRtl),
            scroll = CsSettings.bool("reader_scroll_$bookId", false),
        )
    }
}
