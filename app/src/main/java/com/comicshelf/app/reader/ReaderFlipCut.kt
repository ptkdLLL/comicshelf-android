package com.comicshelf.app.reader

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

// v3（READER_SETTINGS_ANIM_AND_SCROLL_PLAN §10）：翻页动画=关 时滑动翻页的「cut」语义——
// 拖动过程画面不跟手、不移动；越过阈值瞬时切页；不过阈值原地不动（连回弹吸附也没有）。
// 翻页动作复用 vm.goto（与点按分区同一路径，瞬时由 A4 分支保证——单一真相源）。

internal const val FLIP_DIST_FRAC = 0.15f    // 距离阈值：主轴视口 15%
internal const val FLIP_FLICK_FRAC = 0.05f   // 快滑距离阈值：5%…
internal const val FLIP_FLICK_MS = 250L      // …且时长 ≤250ms

/** 无动画滑动翻页判定（纯函数，单测锁定）：主轴累计位移 + 时长 → 是否翻页。 */
internal fun shouldFlipByDrag(movedPx: Float, axisPx: Int, durMs: Long): Boolean {
    val d = abs(movedPx)
    return d >= axisPx * FLIP_DIST_FRAC ||
           (d >= axisPx * FLIP_FLICK_FRAC && durMs <= FLIP_FLICK_MS)
}

/** 仅 anim=关 激活的拖动识别层；anim=开 直接早退（pager 走内建跟手/惯性/吸附）。
 *  slop 由 detectDragGestures 内建：点按与微抖不触达 onDragStart → 点按路径零干扰。 */
internal fun Modifier.cutFlipWhenOff(
    pageAnim: Boolean,
    vertical: Boolean,
    rtl: Boolean,
    vm: ReaderViewModel,
): Modifier = this.pointerInput(pageAnim, vertical, rtl) {
    if (pageAnim) return@pointerInput
    var total = 0f
    var t0 = 0L
    detectDragGestures(
        onDragStart = {
            total = 0f
            t0 = System.currentTimeMillis()
        },
        onDrag = { change, drag ->
            total += if (vertical) drag.y else drag.x
            change.consume()
        },
        onDragEnd = {
            val dur = System.currentTimeMillis() - t0
            val axisPx = if (vertical) size.height else size.width
            if (shouldFlipByDrag(total, axisPx, dur)) {
                val advance = if (vertical) total < 0f else if (rtl) total > 0f else total < 0f
                // 实时读页号（pointerInput 键不含 open，防陈旧闭包）
                vm.goto(vm.open.value.page + if (advance) 1 else -1)
            }
            // 未过阈值：画面完全不动（cut 语义）
        },
        onDragCancel = { },
    )
}
