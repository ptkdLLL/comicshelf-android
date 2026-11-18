package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderModeTest {

    @Test
    fun keepsUpstreamTopToBottomDefaultUnlessMigratingLegacyRtl() {
        assertEquals(ReaderMode.DIR_T2B, ReaderMode.resolveDefaultDir(-1, legacyRtl = false))
        assertEquals(ReaderMode.DIR_R2L, ReaderMode.resolveDefaultDir(-1, legacyRtl = true))
        assertEquals(ReaderMode.DIR_T2B, ReaderMode.resolveDefaultDir(3, legacyRtl = false))
    }

    @Test
    fun explicitGlobalDefaultWinsOverLegacyRtl() {
        for (dir in ReaderMode.DIR_L2R..ReaderMode.DIR_T2B) {
            assertEquals(dir, ReaderMode.resolveDefaultDir(dir, legacyRtl = true))
            assertEquals(dir, ReaderMode.resolveDefaultDir(dir, legacyRtl = false))
        }
    }

    @Test
    fun reopenedBooksKeepTheirOwnDirectionAfterGlobalDefaultChanges() {
        for (saved in ReaderMode.DIR_L2R..ReaderMode.DIR_T2B) {
            for (defaultDir in ReaderMode.DIR_L2R..ReaderMode.DIR_T2B) {
                val reopened = ReaderMode.resolveMode(saved, defaultDir, scroll = true)
                assertEquals(saved, reopened.dir)
                assertFalse(reopened.followsDefaultDirection)
                assertTrue(reopened.scroll)
            }
        }
    }

    @Test
    fun newOrResetBooksFollowGlobalDefault() {
        for (key in listOf(-1, -2, 3)) {
            val mode = ReaderMode.resolveMode(key, ReaderMode.DIR_R2L, scroll = false)
            assertEquals(ReaderMode.DIR_R2L, mode.dir)
            assertTrue(mode.followsDefaultDirection)
            assertFalse(mode.scroll)
        }
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
