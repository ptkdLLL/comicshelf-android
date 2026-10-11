package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 嵌字渲染 v2 对照测试（真值向量由 Python/cv2 生成）：
 *  - otsuThreshold 与 cv2 getThreshVal_Otsu_8u 真值逐位一致（24 组随机灰度向量）；
 *  - estimateFillColorFromPixels 与 BT `_sample_fill_from_mask`（cv2 跑出的真值）
 *    在 6 个真实页面块上逐通道一致 —— 灰度定点式 + Otsu + 前景/中位链完整校验。
 * 真值由 Python cv2 生成；夹具 = 真页裁剪的**原始 ARGB 小端 int 流**
 * （与 Bitmap.getPixels 语义逐位一致；Android 单测无 AWT，故不经图片解码）。
 */
class RenderInkV2Test {

    private fun loadText(name: String): List<String> =
        javaClass.classLoader!!.getResourceAsStream("v2/$name")!!
            .bufferedReader().readLines().filter { it.isNotBlank() }

    @Test
    fun otsuMatchesCv2Vectors() {
        val lines = loadText("otsu_vectors.txt")
        assertEquals("向量组数", 24, lines.size)
        for ((idx, line) in lines.withIndex()) {
            val t = line.substringBefore(' ').toInt()
            val rest = line.substringAfter(' ')
            val n = rest.substringBefore(' ').toInt()
            val vals = rest.substringAfter(' ').split(' ').map { v -> v.toInt() }
            assertEquals("n[$idx]", n, vals.size)
            assertEquals("otsu[$idx]", t, InkV2.otsuThreshold(vals.toIntArray()))
        }
    }

    private fun loadArgb(name: String, w: Int, h: Int): IntArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("v2/$name")!!.readBytes()
        assertEquals("bytes[$name]", w * h * 4, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(w * h) { buf.getInt() }
    }

    /** estimateFillColorFromPixels 在 6 个真实页面块上与 BT 真值逐通道一致。 */
    @Test
    fun fillColorMatchesBtOnRealBlocks() {
        // 真页像素夹具(blk*.argb)含原书画面内容, 不入公开仓库(脱敏); 本地存在时完整对照
        org.junit.Assume.assumeTrue("像素夹具未入库(脱敏), 跳过",
            javaClass.classLoader!!.getResourceAsStream("v2/blk0.argb") != null)
        val lines = loadText("fill_vectors.txt")
        assertEquals("块数", 6, lines.size)
        for (line in lines) {
            val p = line.split(' ')
            val k = p[0]; val w = p[1].toInt(); val h = p[2].toInt()
            val exp = p.drop(3).map { v -> v.toInt() }
            val px = loadArgb("blk$k.argb", w, h)
            val est = InkV2.estimateFillColorFromPixels(px, px.size)
            if (exp.size == 1 && exp[0] == -1) {
                assertEquals("blk$k 应为无估计", -1, est)
            } else {
                assertTrue("blk$k est=$est expect=$exp", est != -1)
                val r = (est shr 16) and 0xFF; val g = (est shr 8) and 0xFF; val b = est and 0xFF
                assertEquals("blk$k R", exp[0], r)
                assertEquals("blk$k G", exp[1], g)
                assertEquals("blk$k B", exp[2], b)
                assertEquals("blk$k A(不透明契约)", 0xFF, (est ushr 24) and 0xFF)
            }
        }
    }

    /** 边界: 全白块 → s=765 全被排除 → -1（黑兜底; 亮字缺失为 R3 已知项）。 */
    @Test
    fun pureWhiteBlockEstimatesNone() {
        val px = IntArray(256) { 0xFFFFFFFF.toInt() }
        assertEquals(-1, InkV2.estimateFillColorFromPixels(px, px.size))
    }

    /** 边界: 全黑块 → s=0 全被排除 → -1（黑兜底, 结果恰为黑字）。 */
    @Test
    fun pureBlackBlockEstimatesNone() {
        val px = IntArray(256) { 0xFF000000.toInt() }
        assertEquals(-1, InkV2.estimateFillColorFromPixels(px, px.size))
    }

    /** 边界探针: 三型密聚亮彩块 → (255,255,255)=0xFFFFFFFF 与哨兵同形（已知边界, S2 下→黑）。
     *  若未来做哨兵值域隔离(亮字支持前置), 更新本用例为可区分断言。 */
    @Test
    fun triToneSaturatedBlockHitsSentinelBoundary() {
        val px = IntArray(900) { 0xFFFFFFFF.toInt() }
        var i = 600
        repeat(60) { px[i++] = 0xFFFFFFBD.toInt() }   // rg (255,255,189)
        repeat(120) { px[i++] = 0xFFFFBDFF.toInt() }  // rb (255,189,255)
        repeat(120) { px[i++] = 0xFF00FFFF.toInt() }  // gb (0,255,255)
        assertEquals(-1, InkV2.estimateFillColorFromPixels(px, px.size))
    }

    @Test
    fun colorDifferenceMatchesBt() {
        val lines = loadText("coldiff_vectors.txt")
        assertEquals("向量组数", 40, lines.size)
        for ((idx, line) in lines.withIndex()) {
            val v = line.split(' ').map { it.toDouble() }
            val fg = (0xFF shl 24) or (v[0].toInt() shl 16) or (v[1].toInt() shl 8) or v[2].toInt()
            val bg = (0xFF shl 24) or (v[3].toInt() shl 16) or (v[4].toInt() shl 8) or v[5].toInt()
            val d = InkV2.colorDifference(fg, bg)
            // 容差 1.2: cv2 8U LAB 的定点/截断内部路径与浮点复现残差(实测 max≈1.0);
            // 决策阈值在 ≥15 处, 该量级残差不可影响判定。
            assertEquals("coldiff[$idx]", v[6], d, 1.2)
        }
    }
}
