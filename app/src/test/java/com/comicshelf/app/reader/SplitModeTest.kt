package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** SplitMode 边界单测（READER_SPLIT_PLAN P-S2 判据：RTL/奇偶/WHOLE/无缝无叠）。 */
class SplitModeTest {

    @Test
    fun identityWhenOff() {
        assertEquals(7, SplitMode.vCount(7, on = false))
        assertEquals(5, SplitMode.rawOf(5, on = false))
        assertEquals(SplitMode.WHOLE, SplitMode.halfOf(5, on = false))
        // WHOLE = 整图（含 RTL 也必须是整图）
        assertEquals(0 to 100, SplitMode.srcXWidth(SplitMode.WHOLE, rtl = false, width = 100))
        assertEquals(0 to 100, SplitMode.srcXWidth(SplitMode.WHOLE, rtl = true, width = 100))
        assertEquals(100, SplitMode.effWidth(SplitMode.WHOLE, rtl = true, width = 100))
    }

    @Test
    fun countAndMappingWhenOn() {
        assertEquals(14, SplitMode.vCount(7, on = true))
        assertEquals(0, SplitMode.rawOf(0, true)); assertEquals(0, SplitMode.halfOf(0, true))
        assertEquals(0, SplitMode.rawOf(1, true)); assertEquals(1, SplitMode.halfOf(1, true))
        assertEquals(6, SplitMode.rawOf(13, true)); assertEquals(1, SplitMode.halfOf(13, true))
        assertEquals(1, SplitMode.rawOf(3, true)); assertEquals(1, SplitMode.halfOf(3, true))
    }

    @Test
    fun halfOrderFollowsRtl() {
        // LTR：half0 左半
        assertEquals(0 to 50, SplitMode.srcXWidth(0, rtl = false, width = 100))
        assertEquals(50 to 50, SplitMode.srcXWidth(1, rtl = false, width = 100))
        // RTL（漫画右开本）：half0 右半，先读
        assertEquals(50 to 50, SplitMode.srcXWidth(0, rtl = true, width = 100))
        assertEquals(0 to 50, SplitMode.srcXWidth(1, rtl = true, width = 100))
    }

    @Test
    fun oddWidthNoGapNoOverlap() {
        // LTR：左半 floor，右半补余
        val (x0, w0) = SplitMode.srcXWidth(0, rtl = false, width = 101)
        val (x1, w1) = SplitMode.srcXWidth(1, rtl = false, width = 101)
        assertEquals(0, x0); assertEquals(50, w0)
        assertEquals(50, x1); assertEquals(51, w1)
        assertEquals(101, w0 + w1)
        assertEquals(x0 + w0, x1)                            // 无缝
        // RTL：首半 = 右半（补余 51），次半 = 左半（floor 50），同样无缝
        val (rx0, rw0) = SplitMode.srcXWidth(0, rtl = true, width = 101)
        val (rx1, rw1) = SplitMode.srcXWidth(1, rtl = true, width = 101)
        assertEquals(50, rx0); assertEquals(51, rw0)
        assertEquals(0, rx1); assertEquals(50, rw1)
        assertEquals(101, rw0 + rw1)
        assertEquals(rx1 + rw1, rx0)                          // 无缝（左半右缘 == 右半左缘）
    }

    @Test
    fun tinyOrInvalidWidth() {
        assertEquals(0 to 0, SplitMode.srcXWidth(0, rtl = false, width = 0))
        assertEquals(0 to 1, SplitMode.srcXWidth(0, rtl = false, width = 3))
        assertEquals(1 to 2, SplitMode.srcXWidth(1, rtl = false, width = 3))
    }
}
