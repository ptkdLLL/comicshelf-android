package com.comicshelf.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfRenderSizeTest {

    @Test
    fun portraitScanFillsPhoneWidth() {
        // 768x1146 scan -> 1600 px long edge, ~1072 px wide (1:1 on a 1080 column).
        assertEquals(1072 to 1600, PdfRenderSize.target(768, 1146, 48L * 1024 * 1024))
    }

    @Test
    fun hugePageIsDownscaledToMaxLongEdge() {
        assertEquals(1600 to 2400, PdfRenderSize.target(4000, 6000, 48L * 1024 * 1024))
    }

    @Test
    fun tinyPageUpscalesAtMostTwice() {
        assertEquals(200 to 300, PdfRenderSize.target(100, 150, 48L * 1024 * 1024))
    }

    @Test
    fun byteBudgetShrinksBothDimensions() {
        assertEquals(500 to 500, PdfRenderSize.target(1000, 1000, 1_000_000))
    }

    @Test
    fun neverReturnsZeroPixels() {
        assertEquals(1 to 1, PdfRenderSize.target(1, 1, 4))
    }
}
