package com.comicshelf.app.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无动画滑动翻页判定（READER_SETTINGS_ANIM_AND_SCROLL_PLAN §10 C3）：阈值语义数值锁定。 */
class ReaderFlipCutTest {

    private val axis = 2400   // 竖屏主轴视口（px）

    @Test
    fun longDrag_overThreshold_flips() {
        assertTrue(shouldFlipByDrag(axis * 0.20f, axis, 800L))   // 慢拖过 15%
    }

    @Test
    fun tinySlowDrag_belowThreshold_noFlip() {
        assertFalse(shouldFlipByDrag(axis * 0.04f, axis, 600L))  // 微拖且慢
    }

    @Test
    fun fastFlick_smallDistance_flips() {
        assertTrue(shouldFlipByDrag(axis * 0.06f, axis, 150L))   // 快滑 ≥5% 且 ≤250ms
    }

    @Test
    fun slowSmallMove_noFlip() {
        assertFalse(shouldFlipByDrag(axis * 0.06f, axis, 400L))  // 6% 但拖了 400ms
    }

    @Test
    fun zero_noFlip() {
        assertFalse(shouldFlipByDrag(0f, axis, 100L))
    }

    @Test
    fun boundary_15pct_flips() {
        assertTrue(shouldFlipByDrag(axis * 0.15f, axis, 900L))   // 含 ">=" 边界
    }
}
