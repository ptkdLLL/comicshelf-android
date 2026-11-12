package com.comicshelf.app.shelf

import com.comicshelf.app.core.BookCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShelfReadPatchTest {
    private val base = BookCell(id = 7, title = "t", favorite = false,
                                 readState = 0, lastPage = 0, pages = 20)

    @Test fun sameVersionAppliesPatch() {
        val out = patchedCell(base, ReadPatch(pages = 33, lastPage = 3, readState = 1, version = 2), 2)
        assertEquals(33, out.pages)
        assertEquals(3, out.lastPage)
        assertEquals(1, out.readState)
        assertEquals("t", out.title)
    }

    @Test fun differentVersionKeepsOriginal() {
        val out = patchedCell(base, ReadPatch(33, 3, 1, version = 1), 2)
        assertSame(base, out)
    }

    @Test fun nullPatchKeepsOriginal() {
        assertSame(base, patchedCell(base, null, 2))
    }
}
