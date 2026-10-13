package com.comicshelf.app.reader

import android.graphics.Bitmap
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.awaitCancellation

/** 动图页槽（P4）：本页动画 + 身份（槽位复用时绝不画他页——同 DecodedPage 守卫语义）。 */
internal class AnimPageState(val bookId: Long, val raw: Int, val d: AnimatedImageDrawable)

/** [rememberAnimSlot] 结果：state=本页动画（null=位图路径）；tick 在 draw 层读以订阅帧重绘。 */
internal class AnimSlot(val state: AnimPageState?, val tick: State<Int>)

/**
 * 动图页生命周期（READER_ANIM_PLAN P3–P5/P8–P9）：
 *  · 触发：首帧位图落地后（firstFrame!=null）查页级探测标记（标记在解码期写入，无时序竞态）；
 *  · 门槛：仅当前/置顶页（isCurrent）解码并播放；切分开启（splitOn）不启用（P8）；
 *  · 播放：AnimatedImageDrawable 帧回调桥到 Compose 重绘（tick++）；
 *  · 停止：离开当前页/离开组合/切页 → 协程取消 → stop + 弃槽（帧内存随 GC，P5）；
 *  · 回退：解码 null / 非 AnimatedImageDrawable（探测误报样本）→ 槽恒 null → 位图现状路径（P9）。
 */
@Composable
internal fun rememberAnimSlot(
    vm: ReaderViewModel,
    bookId: Long,
    raw: Int,
    capBytes: Long,
    isCurrent: Boolean,
    splitOn: Boolean,
    firstFrame: Bitmap?,
): AnimSlot {
    val tick = remember { mutableIntStateOf(0) }
    val bridge = remember {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { tick.value++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
    }
    val slot = remember(bookId, raw, capBytes) { mutableStateOf<AnimPageState?>(null) }
    LaunchedEffect(bookId, raw, capBytes, isCurrent, splitOn, firstFrame) {
        if (!isCurrent || splitOn || firstFrame == null) return@LaunchedEffect
        if (!vm.pageAnimated(raw)) return@LaunchedEffect
        val d = vm.decodeAnimationItem(raw, capBytes) ?: return@LaunchedEffect
        if (d !is AnimatedImageDrawable) return@LaunchedEffect        // P9：误报/静态 Drawable 回退
        d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        d.callback = bridge
        val st = AnimPageState(bookId, raw, d)
        slot.value = st
        d.start()
        Log.i("Reader", "anim start page=$raw")
        try {
            awaitCancellation()                                        // 保持播放直到离开/取消
        } finally {
            if (slot.value === st) slot.value = null
            d.stop()
            d.callback = null
            Log.i("Reader", "anim stop page=$raw")
        }
    }
    val cur = slot.value
    return AnimSlot(cur?.takeIf { it.bookId == bookId && it.raw == raw }, tick)
}
