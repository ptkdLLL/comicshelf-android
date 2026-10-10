package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReaderMode 纯函数单测（READER_VERTICAL_SCROLL_PLAN P-V10a：缺键回退/映射）。 */
class ReaderModeTest {

    @Test
    fun defaultVerticalExceptLegacyRtl() {
        // 缺键（-1）：旧全局 rtl=true → 右→左（迁移保留）；否则 → 上→下（新默认，2026-10-11）
        assertEquals(ReaderMode.DIR_T2B, ReaderMode.resolveDirKey(-1, legacyRtl = false))
        assertEquals(ReaderMode.DIR_R2L, ReaderMode.resolveDirKey(-1, legacyRtl = true))
    }

    @Test
    fun defaultTruthTableExhaustive() {
        // P-D5：默认真值表穷举（把"缺省"收口为单点；未来再改默认时这里必红）
        for (rtl in booleanArrayOf(false, true)) {
            for (k in -2..3) {
                val expect = when {
                    k in ReaderMode.DIR_L2R..ReaderMode.DIR_T2B -> k
                    rtl -> ReaderMode.DIR_R2L
                    else -> ReaderMode.DIR_T2B
                }
                assertEquals("k=$k rtl=$rtl", expect, ReaderMode.resolveDirKey(k, rtl))
            }
        }
    }

    @Test
    fun dirKeyTakesPrecedenceOverLegacy() {
        for (d in 0..2) {
            assertEquals(d, ReaderMode.resolveDirKey(d, legacyRtl = true))
            assertEquals(d, ReaderMode.resolveDirKey(d, legacyRtl = false))
        }
    }

    @Test
    fun outOfRangeKeyFallsBack() {
        assertEquals(ReaderMode.DIR_R2L, ReaderMode.resolveDirKey(3, legacyRtl = true))
        assertEquals(ReaderMode.DIR_T2B, ReaderMode.resolveDirKey(-2, legacyRtl = false))
    }

    @Test
    fun axisAndRtlMapping() {
        assertFalse(ReaderMode(ReaderMode.DIR_L2R, true).vertical)
        assertFalse(ReaderMode(ReaderMode.DIR_L2R, true).rtl)
        assertTrue(ReaderMode(ReaderMode.DIR_R2L, true).rtl)
        assertFalse(ReaderMode(ReaderMode.DIR_R2L, true).vertical)
        assertTrue(ReaderMode(ReaderMode.DIR_T2B, true).vertical)
        assertFalse(ReaderMode(ReaderMode.DIR_T2B, true).rtl)
    }
}
