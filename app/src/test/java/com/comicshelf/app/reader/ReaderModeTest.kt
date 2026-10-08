package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ReaderMode 纯函数单测（READER_VERTICAL_SCROLL_PLAN P-V10a：缺键回退/映射）。 */
class ReaderModeTest {

    @Test
    fun dirKeyFallsBackToLegacyRtl() {
        // 缺键（-1）→ 旧全局 rtl 决定：false=左→右，true=右→左（迁移语义，P-V9）
        assertEquals(ReaderMode.DIR_L2R, ReaderMode.resolveDirKey(-1, legacyRtl = false))
        assertEquals(ReaderMode.DIR_R2L, ReaderMode.resolveDirKey(-1, legacyRtl = true))
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
        assertEquals(ReaderMode.DIR_L2R, ReaderMode.resolveDirKey(-2, legacyRtl = false))
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
