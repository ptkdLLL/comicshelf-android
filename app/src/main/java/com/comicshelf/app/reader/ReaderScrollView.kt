package com.comicshelf.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** 组合项持有的页位图（守卫语义同 ZoomablePage：槽位复用绝不显示他页）。 */
private class ScrollDecoded(val bookId: Long, val page: Int, val bmp: Bitmap)

/**
 * 连续滚动容器（READER_VERTICAL_SCROLL_PLAN P-V4/P-V5）：
 * 竖向 = LazyColumn（上→下瀑布）；横向 = LazyRow（左→右 / 右→左连续，reverseLayout）。
 * 进入即定位 open.page；落定回写 / 外部跳转 = 双守卫（结构照抄 ReaderPager，防环）。
 * 页项 = ScrollPageItem：解码/overlay/手势/绘制语义逐条对齐 ZoomablePage；布局策略不同（7.3-D）。
 */
@Composable
internal fun ReaderScrollView(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    mode: ReaderMode,
    splitOn: Boolean,
    fit: Int,
    rotation: Int,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaunchedEffect(constraints.maxWidth, constraints.maxHeight) {
            vm.setScreenHint(constraints.maxWidth, constraints.maxHeight)
        }
        val vw = constraints.maxWidth
        val vh = constraints.maxHeight
        val vertical = mode.vertical
        val rtl = mode.rtl
        val context = LocalContext.current
        val budget = remember(open.bookId) { PageBudget.budgetBytes(context) }
        LaunchedEffect(budget) { vm.updateBudget(budget) }   // P-R8 断言口径
        // cap 沿用按页单页口径（windowPages(1)=6；组合窗口 ≤ pager，见计划 §7.2-4）
        val capBytes = remember(budget) { PageBudget.perPageCap(budget, PageBudget.windowPages(1)) }

        val list = rememberLazyListState(initialFirstVisibleItemIndex = open.page)

        // 落定回写（守卫结构与 pager 同构）：settle 才 goto（persist），防环
        LaunchedEffect(list.firstVisibleItemIndex, list.isScrollInProgress) {
            if (!list.isScrollInProgress) {
                val v = list.firstVisibleItemIndex
                if (v != vm.open.value.page) vm.goto(v)
            }
        }
        // 外部跳转（滑块/书签/模式切换锚定）→ 列表（守卫防环）
        LaunchedEffect(open.page) {
            if (open.page != list.firstVisibleItemIndex && !list.isScrollInProgress) {
                list.animateScrollToItem(open.page)
            }
        }

        val itemContent: @Composable (Int) -> Unit = { v ->
            ScrollPageItem(
                vm, open, v, splitOn, rtl, vertical, fit, rotation, capBytes,
                isTopmost = list.firstVisibleItemIndex == v,   // P-R5：仅置顶项转圈
                vw = vw, vh = vh,
            )
        }
        // 点按分区（M-V2 动态修正）：按**视口**三分为准（连续流中页项可远高于视口——
        // 按项盒分区会让高页的可见区全落"上一页"格）；中 1/3=工具栏，起点侧=上一页。
        val tapMod = Modifier.pointerInput(vertical, rtl) {
            detectTapGestures(onTap = { pos ->
                val v0 = vm.open.value.page
                if (vertical) {
                    val third = vh / 3f
                    when {
                        pos.y < third -> vm.goto(v0 - 1)
                        pos.y > 2 * third -> vm.goto(v0 + 1)
                        else -> onToggleChrome()
                    }
                } else {
                    val third = vw / 3f
                    when {
                        pos.x < third -> if (rtl) vm.goto(v0 + 1) else vm.goto(v0 - 1)
                        pos.x > 2 * third -> if (rtl) vm.goto(v0 - 1) else vm.goto(v0 + 1)
                        else -> onToggleChrome()
                    }
                }
            })
        }
        if (vertical) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().then(tapMod)) {
                items(open.pageCount) { v -> itemContent(v) }
            }
        } else {
            LazyRow(state = list, reverseLayout = rtl,
                    modifier = Modifier.fillMaxSize().then(tapMod)) {
                items(open.pageCount) { v -> itemContent(v) }
            }
        }
    }
}

/**
 * 连续流页项（P-V5）。几何（7.3-B）：渲染矩形 = 视觉几何 × fitScale；
 * 主轴方向取全尺寸（滚动可达）、横截轴 min(渲染, 视口) 由外层 clipToBounds 居中裁切。
 * 占位（D-V9）：dims 缓存命中 → 精确主轴尺寸；未命中 → 一屏长（视口），到达后生长一次。
 */
@Composable
private fun ScrollPageItem(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    v: Int,
    splitOn: Boolean,
    rtl: Boolean,
    vertical: Boolean,
    fit: Int,
    rotation: Int,
    capBytes: Long,
    isTopmost: Boolean,
    vw: Int,
    vh: Int,
) {
    val bookId = open.bookId
    val raw = SplitMode.rawOf(v, splitOn)
    val half = SplitMode.halfOf(v, splitOn)

    // 解码（与 ZoomablePage 逐条同构）：键不含 isTopmost——滚动中置顶翻转不得取消/重启解码
    val decoded by produceState<ScrollDecoded?>(null, bookId, raw, capBytes) {
        if (value?.let { it.bookId == bookId && it.page == raw } == true) {
            return@produceState                    // 本页已解码（key 变化时保留位图）
        }
        var attempt = 0
        while (attempt < MAX_LOAD_ATTEMPTS) {      // 取消经挂起点传播（awaitAdmission 起）
            vm.awaitAdmission(raw)                 // P-R6：大页模式挂起等待放行（raw 域）
            val job = async { vm.decodePageItem(bookId, raw, half, capBytes) }
            val b = withTimeoutOrNull(STUCK_LOAD_MS) { job.await() }
            if (b == null) {
                job.cancel()
            } else {
                value = ScrollDecoded(bookId, raw, b)
                vm.onItemDecoded(raw, b)
                return@produceState
            }
            attempt++
            if (attempt < MAX_LOAD_ATTEMPTS) delay(RETRY_DELAY_MS)
        }
    }
    // 7.3-A：身份判定注销（滚动"离屏→重组合→重解码"是常态，不污染"在位重解"检查）
    DisposableEffect(bookId, raw) { onDispose { vm.unregisterBorrowed(raw, decoded?.bmp) } }
    val original = decoded?.takeIf { it.bookId == bookId && it.page == raw }?.bmp
    // 翻译 overlay（照抄 ZoomablePage：raw 键 + 200ms 身份轮询）
    val useTranslate = open.translateEnabled
    val translated by produceState<Bitmap?>(initialValue = null, raw, useTranslate) {
        if (useTranslate) {
            while (true) {
                val b = vm.translatedAt(raw)
                if (b != null && b !== value) value = b
                delay(200)
            }
        }
    }
    val bmp = if (useTranslate) translated ?: original else original

    // 缩放/平移（D-V4：消费规则与 ZoomablePage 同款——未放大时单指拖动留给容器滚动）
    var scale by remember(v, splitOn, fit, rotation) { mutableFloatStateOf(1f) }
    var offset by remember(v, splitOn, fit, rotation) { mutableStateOf(Offset.Zero) }
    val bmpState = rememberUpdatedState(bmp)

    // 主轴尺寸（px）：真实位图 → dims 缓存 → 一屏占位
    val dim = if (bmp != null) (bmp.width to bmp.height) else vm.pageDimsOf(raw)
    val mainPx: Int = if (dim != null && dim.first > 0 && dim.second > 0) {
        val eW = SplitMode.effWidth(half, rtl, dim.first)
        val (wV, hV) = visualDims(eW.toFloat(), dim.second.toFloat(), rotation)
        val s = fitScale(fit, rotation, vw.toFloat(), vh.toFloat(), eW.toFloat(), dim.second.toFloat())
        (if (vertical) hV * s else wV * s).roundToInt().coerceAtLeast(1)
    } else {
        if (vertical) vh else vw
    }
    val density = LocalDensity.current
    val boxMod = if (vertical) Modifier.fillMaxWidth().height(with(density) { mainPx.toDp() })
                 else Modifier.fillMaxHeight().width(with(density) { mainPx.toDp() })

    Box(
        modifier = boxMod
            .clipToBounds()   // 横截溢出 = 居中裁切（7.3-B）
            .pointerInput(v, splitOn, fit, rotation) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val ns = (scale * zoom).coerceIn(1f, 6f)
                            if (ns != 1f) {
                                val b = bmpState.value
                                if (b != null) {
                                    offset = clampPan(offset.x + pan.x, offset.y + pan.y, ns,
                                                      size.width.toFloat(), size.height.toFloat(),
                                                      SplitMode.effWidth(half, rtl, b.width), b.height,
                                                      fit, rotation)
                                }
                            } else {
                                offset = Offset.Zero
                            }
                            scale = ns
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "第 ${v + 1} 页" }
                    .graphicsLayer { rotationZ = rotation.toFloat() },   // 旋转以 canvas 中心为原点
            ) {
                val bb = bmpState.value ?: return@Canvas
                val (sx, sw) = SplitMode.srcXWidth(half, rtl, bb.width)
                val eW = SplitMode.effWidth(half, rtl, bb.width)
                val s = fitScale(fit, rotation, vw.toFloat(), vh.toFloat(),
                                 eW.toFloat(), bb.height.toFloat())
                val dW = (eW * s).roundToInt().coerceAtLeast(1)
                val dH = (bb.height * s).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = bb.asImageBitmap(),
                    srcOffset = IntOffset(sx, 0),
                    srcSize = IntSize(sw, bb.height),
                    dstOffset = IntOffset(((size.width - dW) / 2f).roundToInt(),
                                          ((size.height - dH) / 2f).roundToInt()),
                    dstSize = IntSize(dW, dH),
                    filterQuality = FilterQuality.Low,
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isTopmost) {   // P-R5：仅可见页允许转圈（静置 ≤5fps 口径）
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                                              color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                }
                Text("第 ${v + 1} 页", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}
