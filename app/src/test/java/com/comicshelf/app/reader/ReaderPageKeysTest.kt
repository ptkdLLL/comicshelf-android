package com.comicshelf.app.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPageKeysTest {
    @Test
    fun onlyActiveReaderReceivesOneTurnPerAction() {
        val keys = ReaderPageKeys()
        val turns = mutableListOf<Int>()
        assertFalse(keys.onKeyDown(KeyEvent.KEYCODE_F9, 0))

        val detach = keys.attach { turns.add(it) }
        assertTrue(keys.onKeyDown(KeyEvent.KEYCODE_F9, 0))
        assertTrue(keys.onKeyDown(KeyEvent.KEYCODE_F9, 1))
        assertTrue(keys.onKeyDown(KeyEvent.KEYCODE_F10, 0))
        assertFalse(keys.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        detach()
        assertFalse(keys.onKeyDown(KeyEvent.KEYCODE_F10, 0))
        assertEquals(listOf(1, -1), turns)
    }

    @Test
    fun remotePageTurnsDoNotWrapOrLeaveTheCurrentBook() {
        assertEquals(1, pageTurnTarget(0, 3, 1))
        assertEquals(1, pageTurnTarget(2, 3, -1))
        assertNull(pageTurnTarget(0, 3, -1))
        assertNull(pageTurnTarget(2, 3, 1))
        assertNull(pageTurnTarget(0, 0, 1))
    }
}
