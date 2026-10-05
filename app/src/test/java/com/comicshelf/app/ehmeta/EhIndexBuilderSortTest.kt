package com.comicshelf.app.ehmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * S1 引擎单元自测 · 分块排序（introsort：无符号 (hash,gid) 序，栈安全）。
 * 背景：初版快排显式栈 64 int 在真机 2M 键上溢出（实测需求 ~32 条目），
 * 本测试固化"任意分布（含对抗序）都不溢出且全序正确"。
 */
class EhIndexBuilderSortTest {

    private fun verifySorted(h: LongArray, g: IntArray, n: Int) {
        for (i in 0 until n - 1) {
            val c = java.lang.Long.compareUnsigned(h[i], h[i + 1])
            assertTrue("乱序 @$i: h=${java.lang.Long.toUnsignedString(h[i])} > ${java.lang.Long.toUnsignedString(h[i + 1])}",
                c < 0 || (c == 0 && g[i] <= g[i + 1]))
        }
    }

    @Test
    fun random2m() {
        val n = 2_000_000
        val rnd = Random(42)
        val h = LongArray(n) { rnd.nextLong() }
        val g = IntArray(n) { rnd.nextInt(4_000_000) + 1 }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun unsignedHalfSpace() {
        // 全部落在 hash >= 2^63 的无符号半空间（带符号比较会全错）
        val n = 100_000
        val rnd = Random(7)
        val h = LongArray(n) { Long.MIN_VALUE or rnd.nextLong().and(Long.MAX_VALUE) }
        val g = IntArray(n) { rnd.nextInt(1_000_000) }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun allEqual() {
        val n = 50_000
        val h = LongArray(n) { 12345L }
        val g = IntArray(n) { 999 }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun alreadySorted() {
        val n = 200_000
        val h = LongArray(n) { it.toLong() }
        val g = IntArray(n) { it }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun reverseSorted() {
        val n = 200_000
        val h = LongArray(n) { (n - it).toLong() }
        val g = IntArray(n) { n - it }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun heavyDuplicates() {
        // 大量重复 hash + 同 hash 多 gid（真实数据的典型形态）
        val n = 1_000_000
        val rnd = Random(11)
        val pool = LongArray(5000) { rnd.nextLong() }
        val h = LongArray(n) { pool[rnd.nextInt(pool.size)] }
        val g = IntArray(n) { rnd.nextInt(4_000_000) + 1 }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun sawtoothAdversarial() {
        // 锯齿 + 小池（诱发病理分区）
        val n = 400_000
        val h = LongArray(n) { (it % 977).toLong() }
        val g = IntArray(n) { it }
        EhIndexBuilder.sortPairs(h, g, n)
        verifySorted(h, g, n)
    }

    @Test
    fun tiny() {
        for (sizes in intArrayOf(0, 1, 2, 3, 15, 16, 17, 100)) {
            val rnd = Random(sizes.toLong())
            val h = LongArray(sizes) { rnd.nextLong() }
            val g = IntArray(sizes) { rnd.nextInt(50) }
            EhIndexBuilder.sortPairs(h, g, sizes)
            verifySorted(h, g, sizes)
            assertEquals(sizes, h.size)
        }
    }
}
