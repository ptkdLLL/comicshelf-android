package com.comicshelf.app.ehmeta

import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 设备端 keys.bin 构建（P4：不随包分发 ~100MB 不可压排序数组，导入时现建）。
 *
 * 两步法（内存友好，largeHeap 下峰值 ~50MB）：
 *  1) 流式扫 title 表生成键 → 分块（2M 键）排序（双数组快排，无符号 (hash,gid) 序）→ 落临时块；
 *  2) k 路归并 + 按 hash 去重（保留最小 gid —— 与 Mac 参考 build_keys 语义一致）。
 *
 * 输出：[u64 hash LE][u32 gid LE] × N，按【无符号】hash 升序（铁律2 的存储形态）。
 */
object EhIndexBuilder {

    private const val CHUNK_KEYS = 2_000_000

    class Progress(val titles: Long, val totalTitles: Long, val keys: Long, val stage: String)

    fun build(
        dbFile: File,
        outFile: File,
        chunkDir: File,
        onProgress: (Progress) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        chunkDir.mkdirs()
        val chunks = ArrayList<File>()
        val h = LongArray(CHUNK_KEYS)
        val g = IntArray(CHUNK_KEYS)
        var n = 0
        var titles = 0L
        var keysDone = 0L
        var totalTitles = 0L

        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        db.use { d ->
            totalTitles = DatabaseUtils.longForQuery(d, "SELECT COUNT(*) FROM title", null)
            onProgress(Progress(0, totalTitles, 0, "index"))
            d.rawQuery("SELECT gid, text FROM title", null).use { c ->
                while (c.moveToNext()) {
                    if (cancelled()) throw EhCancelledException()
                    val gid = c.getLong(0)
                    val text = c.getString(1)
                    for (k in EhV3.keysOf(text)) {
                        h[n] = EhV3.fnv64(k)
                        g[n] = gid.toInt()
                        n++
                        if (n == CHUNK_KEYS) {
                            sortPairs(h, g, n)
                            chunks.add(writeChunk(h, g, n, chunkDir, chunks.size))
                            keysDone += n
                            n = 0
                        }
                    }
                    titles++
                    if (titles % 200_000 == 0L) {
                        if (cancelled()) throw EhCancelledException()
                        onProgress(Progress(titles, totalTitles, keysDone + n, "index"))
                    }
                }
            }
        }
        if (n > 0) {
            sortPairs(h, g, n)
            chunks.add(writeChunk(h, g, n, chunkDir, chunks.size))
            keysDone += n
            n = 0
        }
        onProgress(Progress(titles, totalTitles, keysDone, "merge"))
        val written = merge(chunks, outFile, cancelled)
        for (ch in chunks) ch.delete()
        return written
    }

    // ---------------------------------------------------------------- 分块落盘

    private fun writeChunk(h: LongArray, g: IntArray, n: Int, dir: File, idx: Int): File {
        val f = File(dir, "chunk_%04d.bin".format(idx))
        val bb = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            bb.putLong(h[i])
            bb.putInt(g[i])
        }
        FileOutputStream(f).use { it.write(bb.array()) }
        return f
    }

    // ---------------------------------------------------------------- 双数组快排（无符号 (hash,gid) 序）

    private fun less(h1: Long, g1: Int, h2: Long, g2: Int): Boolean {
        val c = java.lang.Long.compareUnsigned(h1, h2)
        return c < 0 || (c == 0 && g1 < g2)
    }

    private fun swap(h: LongArray, g: IntArray, i: Int, j: Int) {
        val th = h[i]; h[i] = h[j]; h[j] = th
        val tg = g[i]; g[i] = g[j]; g[j] = tg
    }

    private fun insertionSort(h: LongArray, g: IntArray, lo: Int, hi: Int) {
        for (i in lo + 1..hi) {
            val ih = h[i]
            val ig = g[i]
            var j = i - 1
            while (j >= lo && less(ih, ig, h[j], g[j])) {
                h[j + 1] = h[j]
                g[j + 1] = g[j]
                j--
            }
            h[j + 1] = ih
            g[j + 1] = ig
        }
    }

    private fun partition(h: LongArray, g: IntArray, lo: Int, hi: Int): Int {
        val mid = lo + ((hi - lo) ushr 1)
        // 三值排序：lo ≤ mid ≤ hi（标准三比较模式）
        if (less(h[mid], g[mid], h[lo], g[lo])) swap(h, g, lo, mid)
        if (less(h[hi], g[hi], h[lo], g[lo])) swap(h, g, lo, hi)
        if (less(h[hi], g[hi], h[mid], g[mid])) swap(h, g, mid, hi)
        // 枢轴 = 中位数 → lo
        swap(h, g, mid, lo)
        val ph = h[lo]
        val pg = g[lo]
        var i = lo
        for (j in lo + 1..hi) {
            if (less(h[j], g[j], ph, pg)) {
                i++
                swap(h, g, i, j)
            }
        }
        swap(h, g, lo, i)
        return i
    }

    private fun siftDown(h: LongArray, g: IntArray, base: Int, start: Int, n: Int) {
        var root = start
        while (true) {
            val child = 2 * root + 1
            if (child >= n) break
            var c = child
            if (child + 1 < n && less(h[base + child], g[base + child],
                                     h[base + child + 1], g[base + child + 1])) c = child + 1
            if (less(h[base + root], g[base + root], h[base + c], g[base + c])) {
                swap(h, g, base + root, base + c)
                root = c
            } else break
        }
    }

    /** 堆排序（原位、栈安全）—— introsort 的病理兜底 */
    private fun heapSort(h: LongArray, g: IntArray, lo: Int, hi: Int) {
        val n = hi - lo + 1
        for (i in n / 2 - 1 downTo 0) siftDown(h, g, lo, i, n)
        for (end in n - 1 downTo 1) {
            swap(h, g, lo, lo + end)
            siftDown(h, g, lo, 0, end)
        }
    }

    /**
     * 就地排序 [0,n)（无符号 (hash,gid) 序）。
     * introsort：快排 + 深度超限转堆排序（抗对抗数据），显式栈 128 条目
     * （实测 2M 元素最大 ~32 条目；k 路归并阶段不再需要更大深度）。
     */
    fun sortPairs(h: LongArray, g: IntArray, n: Int) {
        if (n < 2) return
        val stack = IntArray(256)
        var sp = 0
        stack[sp++] = 0
        stack[sp++] = n - 1
        while (sp > 0) {
            var hi = stack[--sp]
            var lo = stack[--sp]
            var depth = 0
            var done = false
            while (hi - lo >= 16) {
                if (depth++ >= 48) {
                    heapSort(h, g, lo, hi)
                    done = true
                    break
                }
                val p = partition(h, g, lo, hi)
                if (p - lo < hi - p) {
                    if (lo <= p - 1) { stack[sp++] = lo; stack[sp++] = p - 1 }
                    lo = p + 1
                } else {
                    if (p + 1 <= hi) { stack[sp++] = p + 1; stack[sp++] = hi }
                    hi = p - 1
                }
            }
            if (!done) insertionSort(h, g, lo, hi)
        }
    }

    // ---------------------------------------------------------------- 归并 + 去重

    private fun readExact(ins: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < 12) {
            val r = ins.read(buf, off, 12 - off)
            if (r < 0) {
                if (off == 0) return false
                throw IllegalStateException("chunk 记录截断: $off/12")
            }
            off += r
        }
        return true
    }

    private fun merge(chunks: List<File>, out: File, cancelled: () -> Boolean): Long {
        val k = chunks.size
        if (k == 0) {
            FileOutputStream(out).use { }
            return 0
        }
        val streams = arrayOfNulls<InputStream>(k)
        val raw = Array(k) { ByteArray(12) }
        val view = Array(k) { ByteBuffer.wrap(raw[it]).order(ByteOrder.LITTLE_ENDIAN) }
        val hs = LongArray(k)
        val gs = IntArray(k)
        val live = BooleanArray(k)
        var liveCount = 0
        for (i in 0 until k) {
            streams[i] = BufferedInputStream(FileInputStream(chunks[i]), 1 shl 20)
            if (readExact(streams[i]!!, raw[i])) {
                hs[i] = view[i].getLong(0)
                gs[i] = view[i].getInt(8)
                live[i] = true
                liveCount++
            }
        }
        val rec = ByteArray(12)
        val recView = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        var lastH = 0L
        var haveLast = false
        var written = 0L
        var sinceCheck = 0
        BufferedOutputStream(FileOutputStream(out), 1 shl 20).use { os ->
            while (liveCount > 0) {
                // 选最小 (无符号 hash, gid)
                var best = -1
                var bestH = 0L
                var bestG = 0
                for (i in 0 until k) {
                    if (!live[i]) continue
                    if (best < 0) { best = i; bestH = hs[i]; bestG = gs[i]; continue }
                    val cc = java.lang.Long.compareUnsigned(hs[i], bestH)
                    if (cc < 0 || (cc == 0 && gs[i] < bestG)) {
                        best = i; bestH = hs[i]; bestG = gs[i]
                    }
                }
                if (!haveLast || bestH != lastH) {
                    recView.putLong(0, bestH)
                    recView.putInt(8, bestG)
                    os.write(rec, 0, 12)
                    lastH = bestH
                    haveLast = true
                    written++
                }
                val i = best
                if (readExact(streams[i]!!, raw[i])) {
                    hs[i] = view[i].getLong(0)
                    gs[i] = view[i].getInt(8)
                } else {
                    live[i] = false
                    liveCount--
                    streams[i]!!.close()
                }
                if (++sinceCheck >= 500_000) {
                    sinceCheck = 0
                    if (cancelled()) throw EhCancelledException()
                }
            }
        }
        return written
    }
}
