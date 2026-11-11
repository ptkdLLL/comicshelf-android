package com.comicshelf.app.core

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFailurePolicyTest {

    @Test
    fun encryptedIsPermanent() {
        val t = SecurityException("Password required to access document")
        assertTrue(PdfFailurePolicy.permanent(t))
        assertEquals("PDF 已加密，需要密码", PdfFailurePolicy.message(t))
    }

    @Test
    fun malformedPdfIsPermanent() {
        val t = IOException("Unable to load the document!")
        assertTrue(PdfFailurePolicy.permanent(t))
        assertEquals("PDF 文件损坏或格式不受支持", PdfFailurePolicy.message(t))
    }

    @Test
    fun timeoutIsTransient() {
        val t = IOException("timeout expired")
        assertFalse(PdfFailurePolicy.permanent(t))
        assertTrue(PdfFailurePolicy.message(t).contains("timeout expired"))
    }

    @Test
    fun missingFileIsTransientAndVisible() {
        val t = FileNotFoundException("/x.pdf")
        assertFalse(PdfFailurePolicy.permanent(t))
        assertEquals("文件不存在或无法访问", PdfFailurePolicy.message(t))
    }
}
