package com.comicshelf.app.reader

import com.comicshelf.app.core.CsSettings

/**
 * 连续滚动模式 · 方向与模式解析（docs/READER_VERTICAL_SCROLL_PLAN.md P-V1）。
 *
 * 方向 dir = 0 左→右 / 1 右→左 / 2 上→下；连续 scroll = 关/开。
 * 按册记忆：`reader_dir_<bookId>`；连续滚动为全局键 `reader_scroll`（v0.5.13 起）。
 * 方向优先级：本册设置 → 全局默认方向 → 旧全局 RTL → 上→下。连续缺省仍为开。
 */
data class ReaderMode(
    val dir: Int = DIR_L2R,
    val scroll: Boolean = false,
    val followsDefaultDirection: Boolean = true,
) {
    /** 主轴竖直（上→下）。 */
    val vertical: Boolean get() = dir == DIR_T2B

    /** 仅「右→左」具有左右反向语义（切分半序 / 点按分区 / 横向流反向）；上→下 无左右。 */
    val rtl: Boolean get() = dir == DIR_R2L

    companion object {
        const val DIR_L2R = 0
        const val DIR_R2L = 1
        const val DIR_T2B = 2

        private const val DEFAULT_DIR_KEY = "reader_default_dir"
        private const val UNSPECIFIED_DIR = -1

        internal val directionOptions = listOf(DIR_L2R to "左→右", DIR_R2L to "右→左", DIR_T2B to "上→下")

        private fun isDirection(value: Int) = value in DIR_L2R..DIR_T2B

        internal fun resolveDefaultDir(key: Int, legacyRtl: Boolean): Int =
            if (isDirection(key)) key else if (legacyRtl) DIR_R2L else DIR_T2B

        fun defaultDirection(): Int = resolveDefaultDir(
            CsSettings.int(DEFAULT_DIR_KEY, UNSPECIFIED_DIR),
            CsSettings.bool("reader_rtl", false),
        )

        fun saveDefaultDirection(dir: Int) {
            require(isDirection(dir))
            CsSettings.setInt(DEFAULT_DIR_KEY, dir)
            CsSettings.save()
        }

        internal fun resolveMode(bookDir: Int, defaultDir: Int, scroll: Boolean): ReaderMode =
            if (isDirection(bookDir)) ReaderMode(bookDir, scroll, followsDefaultDirection = false)
            else ReaderMode(defaultDir, scroll)

        fun load(bookId: Long): ReaderMode = resolveMode(
            CsSettings.int("reader_dir_$bookId", UNSPECIFIED_DIR),
            defaultDirection(),
            CsSettings.bool("reader_scroll", true),
        )

        fun saveBookDirection(bookId: Long, dir: Int?) {
            require(dir == null || isDirection(dir))
            CsSettings.setInt("reader_dir_$bookId", dir ?: UNSPECIFIED_DIR)
            CsSettings.save()
        }
    }
}
