package com.comicshelf.app.reader

/**
 * 嵌字渲染 v2 纯函数集（BT 机制移植, 对照向量见 RenderInkV2Test）：
 * 无 Android/无 native 依赖 → JVM 单测可直接跑对照向量（RenderInkV2Test）。
 * 从 OnDeviceTranslator 拆出的原因：其类初始化链触达 native 库，单测无法引用
 * （测试失败即证据：UnsatisfiedLinkError → 架构拆分）。
 */
internal object InkV2 {

    /**
     * Otsu 阈值（灰度 8bit）——cv2 `getThreshVal_Otsu_8u` 同构（直方图概率、
     * 类间方差最大化、q1/q2 边界跳过、FLT_EPSILON 尺度）。
     */
    fun otsuThreshold(gray: IntArray): Int {
        val total = gray.size
        if (total == 0) return 0
        val hist = DoubleArray(256)
        for (v in gray) hist[v and 0xFF] += 1.0
        var mu = 0.0
        for (i in 0 until 256) mu += i * hist[i]
        mu /= total.toDouble()
        var mu1 = 0.0; var q1 = 0.0; var maxSigma = 0.0; var maxVal = 0
        val eps = 1.1920929e-7f  // FLT_EPSILON（cv2 同源常数）
        for (i in 0 until 256) {
            val pI = hist[i] / total
            mu1 *= q1; q1 += pI; val q2 = 1.0 - q1
            if (minOf(q1, q2) < eps || maxOf(q1, q2) > 1.0 - eps) continue
            mu1 = (mu1 + i * pI) / q1
            val mu2 = (mu - q1 * mu1) / q2
            val sigma = q1 * q2 * (mu1 - mu2) * (mu1 - mu2)
            if (sigma > maxSigma) { maxSigma = sigma; maxVal = i }
        }
        return maxVal
    }

    /**
     * BT `_sample_fill_from_mask`（mit48px.py）等效 — 原文文字填充色估计（纯像素）：
     *  Otsu 二值 → 前景=均值较低类（镜像其 swap 校正）→ 前景像素中 30 < Σrgb < 700 者
     *  （排除纯黑/纯白）→ 逐通道 np.median 语义（偶数取中二平均）；着色前景 < 5 → -1。
     *  灰度 = cv2 `COLOR_BGR2GRAY` 作用于 [R,G,B] 数据的**实证**定点等价
     *  `(R*1868 + G*9617 + B*4899 + 8192) >> 14`（2 万随机样本 99.74% 逐位一致,
     *  残余 ±1 SIMD 路径; 三轮实证定稿）。
     *  @return **不透明 ARGB 0xFFRRGGBB**（Int 有符号, 恒 < 0）；无有效估计 = -1。
     *          契约: 成功值恒含 alpha=0xFF（可直接用作 Paint.color）; 判存用 `!= -1`。
     *          ⚠ 边界: (255,255,255) 的 ARGB=0xFFFFFFFF 与哨兵 -1 同形（亮彩密聚块可达,
     *          触发→回退黑, 温和）; 哨兵值域隔离列为亮字支持(R3)的强制前置。
     *  @param px ARGB int 流（Bitmap.getPixels 语义）
     */
    fun estimateFillColorFromPixels(px: IntArray, n: Int): Int {
        if (n < 16) return -1
        val gray = IntArray(n)
        for (i in 0 until n) {
            val p = px[i]
            gray[i] = (((p shr 16) and 0xFF) * 1868 + ((p shr 8) and 0xFF) * 9617 +
                       (p and 0xFF) * 4899 + 8192) shr 14
        }
        val t = otsuThreshold(gray)
        var fgSum = 0L; var fgCnt = 0; var bgSum = 0L; var bgCnt = 0
        for (v in gray) if (v <= t) { fgSum += v; fgCnt++ } else { bgSum += v; bgCnt++ }
        if (fgCnt == 0 || bgCnt == 0) return -1
        val fgIsDark = fgSum * bgCnt <= bgSum * fgCnt   // fg 均值 <= bg 均值 → 前景=暗类
        var cnt = 0
        val rs = IntArray(n); val gs = IntArray(n); val bs = IntArray(n)
        for (i in 0 until n) {
            val isFg = if (fgIsDark) gray[i] <= t else gray[i] > t
            if (!isFg) continue
            val p = px[i]
            val s = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
            if (s > 30 && s < 700) {
                rs[cnt] = (p shr 16) and 0xFF; gs[cnt] = (p shr 8) and 0xFF; bs[cnt] = p and 0xFF
                cnt++
            }
        }
        if (cnt < 5) return -1
        val r = medianOf(rs, cnt); val g = medianOf(gs, cnt); val b = medianOf(bs, cnt)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** np.median 语义：奇数取中；偶数取中二平均。前 n 个元素原地排序。 */
    private fun medianOf(a: IntArray, n: Int): Int {
        java.util.Arrays.sort(a, 0, n)
        return if (n % 2 == 1) a[n / 2] else (a[n / 2 - 1] + a[n / 2]) / 2
    }

    /**
     * BT `imgproc_utils.color_difference` 等效：CIE76（LAB，L 分量 ×0.392）距离。
     * 用于 v2.1 描边/配色决策的量化判据（0.15em 描边极色规则等）。
     */
    fun colorDifference(rgb1: Int, rgb2: Int): Double {
        val a = labOf(rgb1)
        val b = labOf(rgb2)
        // BT 原式在 cv2 **8U LAB 量化值**上运算: L8=round(L*2.55), a8=round(a)+128,
        // b8=round(b)+128(截断 0..255); 再 diff = (ΔL8*0.392, Δa8, Δb8) 求范数。
        // 量化即 BT 所见（非物理连续量）——向量组 40/40 通过（RenderInkV2Test）。
        val dl = (a[0] - b[0]) * 0.392
        val da = a[1] - b[1]
        val db = a[2] - b[2]
        return Math.sqrt(dl * dl + da * da + db * db)
    }

    /** sRGB(ARGB int) → **cv2 8U 量化** LAB（L8=round(L*2.55)、a8/b8=round(±128)+128,
     *  截断 0..255）——与 BT 的 cv2.cvtColor(...) uint8 输出逐值等价。 */
    private fun labOf(rgb: Int): DoubleArray {
        fun lin(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = lin((rgb shr 16) and 0xFF)
        val g = lin((rgb shr 8) and 0xFF)
        val b = lin(rgb and 0xFF)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        fun f(t: Double): Double =
            if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x / 0.95047); val fy = f(y / 1.0); val fz = f(z / 1.08883)
        val l8 = Math.round(116.0 * fy - 16.0).toDouble() * 2.55
        val a8 = Math.round(500.0 * (fx - fy)) + 128.0
        val b8 = Math.round(200.0 * (fy - fz)) + 128.0
        return doubleArrayOf(
            l8.coerceIn(0.0, 255.0), a8.coerceIn(0.0, 255.0), b8.coerceIn(0.0, 255.0))
    }
}
