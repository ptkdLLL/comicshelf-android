package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 设置页滚动指示条几何（READER_SETTINGS_ANIM_AND_SCROLL_PLAN B2）：纯函数数值锁定。 */
class ReaderSettingsTest {

    @Test
    fun notOverflow_hidden() {
        // 内容不足一屏 → (0,0) = 不显示（竖屏常态）
        assertEquals(0f to 0f, scrollIndicatorMetrics(600, 600, 1000, 80f))
        assertEquals(0f to 0f, scrollIndicatorMetrics(600, 400, 1000, 80f))
    }

    @Test
    fun overflow_proportionalWithMinThumb() {
        // 内容 2 屏：thumb 高 = 轨高 × (600/1200) = 500
        val (_, h1) = scrollIndicatorMetrics(600, 1200, 1000, 40f)
        assertEquals(500f, h1, 0.001f)
        // 内容极长：thumb 触底到 minThumb
        val (_, h2) = scrollIndicatorMetrics(600, 60000, 1000, 40f)
        assertEquals(40f, h2, 0.001f)
    }

    @Test
    fun invalid_safe() {
        assertEquals(0f to 0f, scrollIndicatorMetrics(0, 100, 1000, 80f))
    }
}
