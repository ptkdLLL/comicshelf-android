package com.comicshelf.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubBookSourceTest {

    @Test fun resolve_parent_and_fragment() {
        assertEquals("Images/a.jpg", EpubBookSource.resolve("OEBPS", "../Images/a.jpg#frag"))
    }

    @Test fun resolve_percent_and_space() {
        assertEquals("Images/a b.jpg", EpubBookSource.resolve("OEBPS", "../Images/a%20b.jpg?x=1"))
    }

    @Test fun resolve_absolute_zip_path() {
        assertEquals("Images/a.jpg", EpubBookSource.resolve("OEBPS", "/Images/a.jpg"))
    }

    @Test fun sanitize_unescaped_lt_and_bad_comment() {
        val bad = "<html><!--x <h1--><p>a < b & c</p></html>"
        val fixed = EpubBookSource.sanitizeXml(bad)
        assertTrue(fixed.contains("a &lt; b &amp; c"))
        assertFalse(fixed.contains("<!--"))
    }

    @Test fun raster_extensions() {
        assertTrue(EpubBookSource.isRaster("OEBPS/Images/x.JPG"))
        assertTrue(EpubBookSource.isRaster("a.webp"))
        assertFalse(EpubBookSource.isRaster("a.svg"))
        assertFalse(EpubBookSource.isRaster("a.xhtml"))
    }
}
