package com.comicshelf.app.reader

import android.app.ActivityManager
import android.content.Context

/*
 * v0.6.0 页位图所有权归位 · 策略常量与预算（冻结源：docs/READER_FLICKER_FIX_PLAN.md v2 §3/§4、
 * docs/READER_FIX_EVAL.md 评估 G1-G8）。命名与口径一一对应，勿散落魔数。
 */

/** 一张 (bookId,page) 每次组合生命的最大解码尝试数（失败/卡死各计 1 次）。 */
internal const val MAX_LOAD_ATTEMPTS = 2

/** 单次解码超过 20s 未返回视为卡死 → 弃该尝试（等价 v0.3.4 的"重排"语义，项内化）。 */
internal const val STUCK_LOAD_MS = 20_000L

/** 两次解码尝试之间的间隔（失败重试用；旧实现是"bump revision 尽快重触发"）。 */
internal const val RETRY_DELAY_MS = 250L

/**
 * 页位图预算：全工程**唯一**内存旋钮（I2）。单页解码上限由此派生（I3），
 * 常驻 = 瞬时最大窗口 × 单页上限 ≤ BUDGET（I4，P-R8 断言）。
 */
object PageBudget {

    /** = 12MP × 4B：v0.3.3 单页保护线（PageDecoder 12MP 注释的字节形态），防超大分配卡死。 */
    const val ABS_CAP_BYTES = 48L shl 20

    /** = 6 页 × 48MB：瞬时最大组合页数 × 单页保护线——与 §1-E1/E3 的 6 页实证同源。 */
    const val DEVICE_BUDGET_CAP = 288L shl 20

    /** 低内存设备保底（cap ≥ 16MB/页；仍满足 I4：6×16=96）。 */
    const val MIN_BUDGET_CAP = 96L shl 20

    /**
     * 瞬时最大组合页数 = (2×beyondViewportPageCount + 2) × (双页 ? 2 : 1)，beyond=2：
     * 静置 5 页（1+2+2）、滚动瞬时 6 页（2+2+2，E1/E3 实证）、横屏双页 ×2 = 12。
     */
    fun windowPages(step: Int): Int = if (step == 2) 12 else 6

    /** 预算 = min(288MB, max(96MB, availMem/16))；快照一次/进入阅读器（评估 G7）。 */
    fun budgetBytes(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return DEVICE_BUDGET_CAP
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return minOf(DEVICE_BUDGET_CAP, maxOf(MIN_BUDGET_CAP, mi.availMem / 16))
    }

    /** 单页解码字节上限 = min(48MB, 预算 ÷ 当前最大窗口页数)。 */
    fun perPageCap(budget: Long, windowPages: Int): Long =
        minOf(ABS_CAP_BYTES, budget / windowPages)
}
