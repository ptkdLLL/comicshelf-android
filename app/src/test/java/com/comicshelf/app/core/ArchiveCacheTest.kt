package com.comicshelf.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ArchiveCache 释放逻辑（JVM）；固化"只删 smbcache、其他全部不动"的契约。 */
class ArchiveCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cacheDir(): File = File(tmp.root, "data/smbcache")

    private fun write(p: File, text: String) {
        p.parentFile?.mkdirs()
        p.writeText(text)
    }

    @Test
    fun statsAndReleaseIdempotent() {
        write(File(cacheDir(), "a.rar"), "A".repeat(100))
        write(File(cacheDir(), "b.rar"), "B".repeat(200))
        write(File(cacheDir(), "sub/c.rar"), "C".repeat(50))
        write(File(cacheDir(), "x.part"), "P".repeat(10))

        val s = ArchiveCache.stats(tmp.root)
        assertEquals("files", 4, s.files)
        assertEquals("bytes", 360L, s.bytes)

        val r = ArchiveCache.release(tmp.root)
        assertEquals("r.files", 4, r.files)
        assertEquals("r.bytes", 360L, r.bytes)
        assertTrue("r.ok", r.ok)
        assertTrue("目录保留", cacheDir().isDirectory)
        assertEquals("目录已清空", 0, cacheDir().listFiles()!!.size)

        val r2 = ArchiveCache.release(tmp.root)
        assertEquals(ArchiveCache.ReleaseResult(0, 0L, true), r2)
    }

    @Test
    fun releaseTouchesNothingElse() {
        write(File(cacheDir(), "a.rar"), "A".repeat(100))
        val keep1 = File(tmp.root, "data/covers/keep.txt").also { write(it, "KEEP") }
        val keep2 = File(tmp.root, "data/config.ini").also { write(it, "CFG") }
        val keep3 = File(tmp.root, "data/library.db").also { write(it, "DB") }
        val keep4 = File(tmp.root, "other.txt").also { write(it, "OUT") }

        ArchiveCache.release(tmp.root)

        assertEquals("KEEP", keep1.readText())
        assertEquals("CFG", keep2.readText())
        assertEquals("DB", keep3.readText())
        assertEquals("OUT", keep4.readText())
    }

    @Test
    fun missingDirIsNoop() {
        assertEquals(ArchiveCache.ReleaseResult(0, 0L, true), ArchiveCache.release(tmp.root))
    }
}
