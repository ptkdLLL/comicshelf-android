package com.comicshelf.app.reader

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 字迹级掩码（BalloonTranslator 规范，见 docs/BALLOONTRANSLATOR_WORKFLOW.md §3）：
 *   逐文字块裁剪（上扩 10px）→ 11×11 局部均值自适应二值（±2，暗墨/亮墨两遍）
 *   → 8 连通域（面积 > 10）→ 每个连通块外接矩形填入掩码 → 膨胀 4px。
 *
 * 意义：擦除只作用于字迹（BT 实测覆盖 ~3.6% 页面像素），气泡的网点/渐变/
 * 插画背景不受影响；矩形整擦既糊字又伤画。
 */
object InkMask {

    /** 一个连通块的外接矩形 + 其像素坐标（用于按局部背景色回填）。 */
    class Cc(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val pix: IntArray)

    class Result(val w: Int, val h: Int, val mask: ByteArray, val ccs: List<Cc>) {
        inline fun on(x: Int, y: Int): Boolean = mask[y * w + x] != 0.toByte()
    }

    private const val BLOCK = 11        // 局部均值窗口（BT adaptiveThreshold blockSize）
    private const val C = 2             // 阈值偏置（BT C=2）
    private const val MIN_AREA = 10     // CC 面积过滤（BT connectedComponents 面积 >10）
    // v2(PLAN §4 D-2/D-3): 原 MAX_AREA=8192 丢弃巨型 CC 的防护已删除——该规则是
    // E7 时代"环带回填"的产物; 现回填改为调和扩散(像素级, 结构上不可能采块外色),
    // 丢弃大域只剩负面(字迹粘连块残留)。噪点过滤仅保留 MIN_AREA。
    // BT inpaint_mask_dilate=4 用的是 np.ones((4,4)) kernel（锚点居中，每边≈2px）；
    // 掩码面积对膨胀半径极敏感（字迹笔画仅 1~2px 宽），实测 4 轮 3×3=每边4px 时
    // 覆盖率 7.33% vs BT 基准 3.61%，改为每边 2px。
    private const val DILATE = 2
    private const val PAD_TOP = 10      // 裁剪上扩（BT adjust_text_line_coordinates y_off=10）
    private const val TEXTURE_FRAC = 0.45f  // C=2 二值覆盖率超此值 = 纹理/网点背景
    private const val CONTRAST_C = 60   // 纹理背景改用块中位灰 ±60 为墨(黑字|10-192|=182 ✓;网点灰 144:48 ✗)
    private const val STRONG_C = 25     // v2.2: 极性无关强边缘(局部均值偏置); 网点块占比高被门控关
    private const val STRONG_FRAC = 0.60f  // strong 全高占比 ≥ 此值 = 网点/纹理 → 关 strong

    /**
     * @param rects 文字框（页面坐标，仅对将被重绘的块调用）
     */
    fun build(page: Bitmap, rects: List<Rect>): Result {
        val w = page.width
        val h = page.height
        val px = IntArray(w * h)
        page.getPixels(px, 0, w, 0, 0, w, h)

        // 灰度 + 全页积分图（11×11 窗口均值 O(1) 查询）
        val gray = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            gray[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 +
                       (p and 0xFF) * 29) shr 8
        }
        val iw = w + 1
        val integ = LongArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integ[(y + 1) * iw + x + 1] = integ[y * iw + x + 1] + rowSum
            }
        }
        fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = integ[y0 * iw + x0]
            val b = integ[y0 * iw + x1]
            val c = integ[y1 * iw + x0]
            val d = integ[y1 * iw + x1]
            val n = (x1 - x0) * (y1 - y0)
            return ((d - b - c + a) / n).toInt()
        }

        val mask = ByteArray(w * h)
        val ccs = ArrayList<Cc>(64)
        val half = BLOCK / 2
        for (r in rects) {
            val rx0 = (r.left).coerceIn(0, w - 1)
            val rx1 = (r.right).coerceIn(1, w)
            val ry0 = (r.top - PAD_TOP).coerceIn(0, h)
            val ry1 = (r.bottom).coerceIn(0, h)
            if (rx1 - rx0 < 4 || ry1 - ry0 < 4) continue
            val bw = rx1 - rx0
            val bh = ry1 - ry0
            // 1) 自适应二值（暗墨 | 亮墨）
            val bin = ByteArray(bw * bh)
            // v2.2: 极性无关的强边缘判据 |v-局部均值|>STRONG_C —— 治"深底块中位偏暗
            // 时黑字不入 |v-bm|>60(极性翻转漏检)"(实测 #梦寐以求页右下气泡残字)。
            val strong = ByteArray(bw * bh)
            var ones = 0
            var strongCnt = 0
            for (y in ry0 until ry1) {
                for (x in rx0 until rx1) {
                    val x0 = (x - half).coerceAtLeast(0)
                    val y0 = (y - half).coerceAtLeast(0)
                    val x1 = (x + half + 1).coerceAtMost(w)
                    val y1 = (y + half + 1).coerceAtMost(h)
                    val m = mean(x0, y0, x1, y1)
                    val v = gray[y * w + x]
                    if (v < m - C || v > m + C) { bin[(y - ry0) * bw + (x - rx0)] = 1; ones++ }
                    if (v < m - STRONG_C || v > m + STRONG_C) {
                        strong[(y - ry0) * bw + (x - rx0)] = 1; strongCnt++
                    }
                }
            }
            // 1b) 纹理/网点背景兜底:±2 局部均值在灰阶网点上"全块皆墨"(实测 95%)。
            // 笔画级 CC 在纹理上**不可分**(要么吞整块,要么字与纹理相连)。改为**行/列条带**
            // 级掩码:对 |g-块中位|>60 的强对比墨做两轴投影切带,条数多者=文字行进轴;
            // **v2(PLAN §3.4): 每条带只把 ink2 墨迹像素本身入掩码**(旧版按条带墨迹外接
            // 矩形±2/±3 整块填 → 整列矩形刷平盖画面)。条带间的画面保留,配合渲染端调和
            // 填充色调自然延续(§E8); 全局 DILATE=2 补足 BT mask dilate 2 的边距。
            if (ones.toFloat() / (bw * bh) > TEXTURE_FRAC) {
                val smp = ArrayList<Int>(4096)
                val step = (bw * bh / 4096).coerceAtLeast(1)
                var si = 0
                while (si < bw * bh) {
                    smp.add(gray[(ry0 + si / bw) * w + (rx0 + si % bw)])
                    si += step
                }
                smp.sort()
                val bm = smp[smp.size / 2]
                fun ink2(i: Int): Boolean {
                    val v = gray[(ry0 + i / bw) * w + (rx0 + i % bw)]
                    return Math.abs(v - bm) > CONTRAST_C
                }
                val projX = IntArray(bw)
                val projY = IntArray(bh)
                for (i in 0 until bw * bh)
                    if (ink2(i)) { projX[i % bw]++; projY[i / bw]++ }
                fun bands(proj: IntArray): ArrayList<IntArray> {
                    val thr = ((proj.maxOrNull() ?: 0) / 4).coerceAtLeast(1)
                    val out2 = ArrayList<IntArray>(8)
                    var i = 0
                    while (i < proj.size) {
                        if (proj[i] <= thr) { i++; continue }
                        var j = i
                        var gap = 0
                        while (j < proj.size && gap < 6) {
                            if (proj[j] <= thr) gap++ else gap = 0
                            j++
                        }
                        val end = (j - gap).coerceAtLeast(i + 2)
                        if (end - i >= 8) out2.add(intArrayOf(i, end))
                        i = end.coerceAtLeast(i + 1)
                    }
                    return out2
                }
                val bx = bands(projX)
                val by = bands(projY)
                val vertical = bx.size > by.size
                val bd = if (vertical) bx else by
                // v2.2 门控: strong 全高占比 ≥STRONG_FRAC(网点/纹理"处处皆边") → 关,
                // 防 E7 网点块被整片擦平; 常规文字块占比 ~0.3-0.5 → 开, 补极性盲区。
                val useStrong = strongCnt.toFloat() / (bw * bh) < STRONG_FRAC
                if (bd.isEmpty()) {
                    // 兜底:投影找不到条带 → 整块 binm∪ink2 入掩码(调和填充以块边界色延续)
                    for (i in 0 until bw * bh)
                        if (ink2(i) || (useStrong && strong[i] == 1.toByte()))
                            mask[(ry0 + i / bw) * w + (rx0 + i % bw)] = 1
                    continue
                }
                for (bnd in bd) {
                    // v2.1(最佳效果修订): 条带只做"定位"(ink2 稳健找文字行进区), 掩码取
                    // **±2 自适应笔画像素 ∪ 强对比墨**(binm 敏感, 细软笔画/淡色字不漏)——
                    // 旧版条带内只取 ink2(|Δ|>60)导致淡笔画残留; 外接矩形方案会盖画面。
                    // 逐像素入掩码 + 全局 DILATE=2 兜边 = BT"字迹掩码+dilate 2"等效。
                    if (vertical) {
                        for (x in bnd[0]..bnd[1].coerceAtMost(bw - 1))
                            for (y in 0 until bh)
                                if (ink2(y * bw + x) || (useStrong && strong[y * bw + x] == 1.toByte()))
                                    mask[(ry0 + y) * w + rx0 + x] = 1
                    } else {
                        for (y in bnd[0]..bnd[1].coerceAtMost(bh - 1))
                            for (x in 0 until bw)
                                if (ink2(y * bw + x) || (useStrong && strong[y * bw + x] == 1.toByte()))
                                    mask[(ry0 + y) * w + rx0 + x] = 1
                    }
                }
                continue   // 纹理路径不走笔画 CC
            }
            // 2) 8 连通域（面积 >10 保留；小噪点不擦）
            val seen = ByteArray(bw * bh)
            val stack = IntArray(bw * bh)
            val comp = ArrayList<Int>()
            for (iy in 0 until bh) {
                for (ix in 0 until bw) {
                    val s0 = iy * bw + ix
                    if (bin[s0] != 1.toByte() || seen[s0] != 0.toByte()) continue
                    var sp = 0
                    stack[sp++] = s0
                    seen[s0] = 1
                    comp.clear()
                    comp.add(s0)
                    while (sp > 0) {
                        val cur = stack[--sp]
                        val cy = cur / bw
                        val cx = cur - cy * bw
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue
                            val ni = ny * bw + nx
                            if (bin[ni] == 1.toByte() && seen[ni] == 0.toByte()) {
                                seen[ni] = 1
                                stack[sp++] = ni
                                comp.add(ni)
                            }
                        }
                    }
                    if (comp.size < MIN_AREA) continue
                    var mnx = bw; var mny = bh; var mxx = 0; var mxy = 0
                    val pix = IntArray(comp.size)
                    for ((k, ci) in comp.withIndex()) {
                        val cy = ci / bw
                        val cx = ci - cy * bw
                        if (cx < mnx) mnx = cx
                        if (cx > mxx) mxx = cx
                        if (cy < mny) mny = cy
                        if (cy > mxy) mxy = cy
                        pix[k] = (ry0 + cy) * w + (rx0 + cx)   // 页面坐标线性下标
                    }
                    ccs.add(Cc(rx0 + mnx, ry0 + mny, rx0 + mxx, ry0 + mxy, pix))
                }
            }
        }
        // 3) 填入掩码
        for (cc in ccs) for (p in cc.pix) mask[p] = 1
        // 4) 膨胀 DILATE 轮 3×3
        repeat(DILATE) {
            val prev = mask.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (prev[i] != 0.toByte()) continue
                    var hit = false
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            if (prev[ny * w + nx] != 0.toByte()) { hit = true; break }
                        }
                        if (hit) break
                    }
                    if (hit) mask[i] = 1
                }
            }
        }
        return Result(w, h, mask, ccs)
    }
}
