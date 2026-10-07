package com.comicshelf.app.reader

/**
 * 切分阅读模式 · 纯函数映射（docs/READER_SPLIT_PLAN.md P-S2）。
 *
 * 开关打开：显示页 v → (raw = v/2, half = v%2)；每张图源在**图宽正中**对半切，
 * half0 = 叙事首半（LTR 左半 / RTL 右半），半开区间 [x, x+w) 无缝无叠（奇数宽左半取 floor）。
 * 开关关闭：恒等映射（v == raw，half = WHOLE）——与既有行为逐位一致（I-S5）。
 */
object SplitMode {

    /** half 值：整页（未切分）。 */
    const val WHOLE = -1

    /** 显示页总数（2N | N）。 */
    fun vCount(rawCount: Int, on: Boolean): Int = if (on) rawCount * 2 else rawCount

    /** 显示页 → 源页（raw）。 */
    fun rawOf(v: Int, on: Boolean): Int = if (on) v / 2 else v

    /** 显示页 → 半号（0 = 叙事首半；WHOLE = 整页）。 */
    fun halfOf(v: Int, on: Boolean): Int = if (on) v and 1 else WHOLE

    /**
     * 半页源矩形 (x, w)：
     *  WHOLE → 整图；half0 且 !rtl → 左半；half0 且 rtl → 右半；half1 取另一半。
     *  w <= 0（位图未就绪）→ 整图占位。
     */
    fun srcXWidth(half: Int, rtl: Boolean, width: Int): Pair<Int, Int> {
        if (half == WHOLE || width <= 0) return 0 to width
        val m = width / 2
        val firstIsLeft = !rtl
        val leftHalf = if (half == 0) firstIsLeft else !firstIsLeft
        return if (leftHalf) 0 to m else m to (width - m)
    }

    /** 有效几何宽（供 fitScale/clampPan：半页宽；WHOLE = 全宽）。 */
    fun effWidth(half: Int, rtl: Boolean, width: Int): Int = srcXWidth(half, rtl, width).second
}
