package com.comicshelf.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicshelf.app.core.BookmarkRow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Full-bleed manga reader:
 *  * HorizontalPager supplies virtualized pages + neighbor composition
 *    (which drives the decode prefetch, like the C++ preload chain);
 *  * pinch-zoom/pan per page with double-tap toggle;
 *  * tap left/right thirds to flip (honoring RTL), middle toggles chrome;
 *  * translated overlay swaps in when the engine reports READY.
 */
@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    var showTrEnable by remember { mutableStateOf(false) }
    var showTrMenu by remember { mutableStateOf(false) }
    val open by vm.open.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val splitOn by vm.splitOn.collectAsState()
    val mode by vm.mode.collectAsState()
    var chrome by remember { mutableStateOf(true) }
    var rotation by remember { mutableIntStateOf(0) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val bg = when (prefs.bg) {
        1 -> Color.White
        2 -> Color(0xFF3A3F47)
        else -> Color.Black
    }

    Box(Modifier.fillMaxSize().background(bg)) {
        if (open.pageCount > 0) {
            // 四容器分派（P-V3）：方向 × 连续——独立成项；页项/同步语义与 pager 同构
            if (mode.scroll) {
                ReaderScrollView(vm, open, mode, splitOn, prefs.fit, rotation,
                                 onToggleChrome = { chrome = !chrome })
            } else {
                ReaderPager(vm, open, mode, splitOn, prefs.fit, rotation, prefs.spread,
                            onToggleChrome = { chrome = !chrome })
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("打开中…", color = Color.White.copy(alpha = 0.7f))
            }
        }

        if (chrome) {
            ReaderTopBar(
                title = open.title, onBack = onBack, prefs = prefs, vm = vm,
                rotation = rotation,
                onRotate = { rotation = (rotation + 90) % 360 },
                onBookmarks = { showBookmarks = true },
                onSettings = { showSettings = true },
                onTranslate = {
                    if (open.translateEnabled) showTrMenu = true else showTrEnable = true
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ReaderBottomBar(open, vm,
                            onGoto = { vm.goto(it) },
                            modifier = Modifier.align(Alignment.BottomCenter))
        }

        if (showTrEnable) TranslateEnableDialog(vm) { showTrEnable = false }
        if (showTrMenu) TranslateMenuDialog(vm) { showTrMenu = false }

        // Translate status chip while pages are being processed.
        if (open.translateEnabled) {
            val trStates by vm.trStates.collectAsState()
            val st = trStates[vm.rawOfPage(open.page)]   // trStates 键 = raw（P-S8）
            if (st == TranslatePageState.QUEUED || st == TranslatePageState.BUSY) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("翻译中…", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }

    if (showBookmarks) {
        BookmarksDialog(vm, onGoto = { vm.goto(it); showBookmarks = false },
                        onDismiss = { showBookmarks = false })
    }
    if (showSettings) {
        ReaderSettingsDialog(vm, onDismiss = { showSettings = false })
    }
}

@Composable
private fun ReaderPager(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    mode: ReaderMode,
    splitOn: Boolean,
    fit: Int,
    rotation: Int,
    spread: Boolean,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaunchedEffect(constraints.maxWidth, constraints.maxHeight) {
            vm.setScreenHint(constraints.maxWidth, constraints.maxHeight)
        }
        val rtl = mode.rtl
        val vertical = mode.vertical
        val landscape = maxWidth > maxHeight
        // Two-page spread: pager items become pairs in landscape（连续=关时本容器才生效，D-V7）。
        val step = if (spread && landscape) 2 else 1
        val pageCount = open.pageCount
        val pageCountEff = if (step == 2) (pageCount + 1) / 2 else pageCount
        val pager = rememberPagerState(
            initialPage = open.page / step,
            initialPageOffsetFraction = 0f,
        ) { pageCountEff }

        // v0.6.0：预算快照一次/进入阅读器（availMem 漂移不重启解码，评估 G7）；
        // cap 只随 step（双页/旋转）变化（P-R3 v2：min(48MB, BUDGET÷瞬时最大窗口页数)）。
        val context = LocalContext.current
        val budget = remember(open.bookId) { PageBudget.budgetBytes(context) }
        LaunchedEffect(budget) { vm.updateBudget(budget) }   // P-R8 断言口径
        val capBytes = remember(budget, step) {
            PageBudget.perPageCap(budget, PageBudget.windowPages(step))
        }

        // Sync external goto -> pager & persist progress.
        // v0.3.4 的"落定必加载"真空修复由"组合项在=解码协程在"结构性接管（评估 G4/G8），
        // 落定分支不再补发 ensurePage。
        LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
            if (!pager.isScrollInProgress) {
                val page = pager.currentPage * step
                if (page != vm.open.value.page) {
                    vm.goto(page)
                }
            }
        }
        LaunchedEffect(open.page) {
            val idx = open.page / step
            if (idx != pager.currentPage && !pager.isScrollInProgress) {
                pager.animateScrollToPage(idx)
            }
        }

        // 页内容（两种轴的 pager 共享；轴只换容器——P-V6/D-V1）
        val pageContent: @Composable (Int) -> Unit = { idx ->
            val first = idx * step
            val isCurrentItem = pager.currentPage == idx   // P-R5：项作用域读取，只重组该项
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                ZoomablePage(vm, open, first, splitOn, fit, rotation, rtl, vertical, capBytes,
                             isCurrentItem, onToggleChrome, Modifier.weight(1f))
                if (step == 2 && first + 1 < pageCount) {
                    ZoomablePage(vm, open, first + 1, splitOn, fit, rotation, rtl, vertical, capBytes,
                                 isCurrentItem, onToggleChrome, Modifier.weight(1f))
                }
            }
        }
        if (vertical) {
            VerticalPager(
                state = pager,
                beyondViewportPageCount = 2,   // 同上：组合即预取
                modifier = Modifier.fillMaxSize(),
            ) { idx -> pageContent(idx) }
        } else {
            HorizontalPager(
                state = pager,
                reverseLayout = rtl,
                beyondViewportPageCount = 2,   // compose neighbors => decode prefetch（大页模式由准入收紧，组合仍保留）
                modifier = Modifier.fillMaxSize(),
            ) { idx -> pageContent(idx) }
        }
    }
}

/** 组合项持有的页位图（书号/页号随值走——槽位复用时绝不显示他页，评估 R-E6 防御）。 */
private class DecodedPage(val bookId: Long, val page: Int, val bmp: Bitmap)

/** One zoomable page. Chrome-free; tap zones and gestures only. */
@Composable
private fun ZoomablePage(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    page: Int,
    splitOn: Boolean,
    fit: Int,
    rotation: Int,
    rtl: Boolean,
    vertical: Boolean,
    capBytes: Long,
    isCurrent: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // P-R1：位图由本项持有——进入窗口即解码，离开窗口协程取消 → 位图自然释放（I1）。
    // 无 LRU、无逐出：本页在组合中 ⇔ 本页位图在（P-R2 借用表只存弱引用供 VM 查询）。
    // 切分模式（READER_SPLIT_PLAN P-S3）：项身份 = (raw, half)；位图仍是整页，
    // 同 raw 的两个半页项各解码一次（语义与现状"翻到该页即解码"同构）。
    val bookId = open.bookId
    val raw = SplitMode.rawOf(page, splitOn)
    val half = SplitMode.halfOf(page, splitOn)
    val decoded by produceState<DecodedPage?>(null, bookId, raw, capBytes, isCurrent) {
        if (value?.let { it.bookId == bookId && it.page == raw } == true) {
            return@produceState                    // 本页已解码（key 变化时保留位图）
        }
        var attempt = 0
        while (attempt < MAX_LOAD_ATTEMPTS) {      // 取消经挂起点传播（awaitAdmission 起）
            vm.awaitAdmission(raw)                 // P-R6：大页模式挂起等待放行（raw 域）
            val job = async { vm.decodePageItem(bookId, raw, half, capBytes) }
            val b = withTimeoutOrNull(STUCK_LOAD_MS) { job.await() }
            if (b == null) {
                job.cancel()                       // 卡死/失败尝试弃引用（JNI 跑完即被 GC）
            } else {
                value = DecodedPage(bookId, raw, b)
                vm.onItemDecoded(raw, b)
                return@produceState
            }
            attempt++
            if (attempt < MAX_LOAD_ATTEMPTS) delay(RETRY_DELAY_MS)
        }
    }
    DisposableEffect(bookId, raw) { onDispose { vm.unregisterBorrowed(raw, decoded?.bmp) } }
    val original = decoded?.takeIf { it.bookId == bookId && it.page == raw }?.bmp
    // Translated overlay replaces the original when ready（raw 键；overlay 与底图用同一
    // 半页矩形绘制，sidecar/ondevice/backend 三引擎天然对齐，P-S4/I-S3）。
    val useTranslate = open.translateEnabled
    val translated by produceState<Bitmap?>(initialValue = null, raw, useTranslate) {
        if (useTranslate) {
            // 评估 G5：200ms 身份轮询——常规落地/重译替换/失败恢复三态全覆盖（替代旧 revision 重启）。
            while (true) {
                val bmp = vm.translatedAt(raw)
                if (bmp != null && bmp !== value) value = bmp
                kotlinx.coroutines.delay(200)
            }
        }
    }
    val bmp = if (useTranslate) translated ?: original else original

    // v0.4.3：适配模式/旋转也是几何输入——任一变化即重建（统一重置缩放平移）。
    // 注意：下方两处 pointerInput 的 key 必须同步含 fit/rotation（+ splitOn 同理），否则手势闭包
    // 会握旧 delegate（写旧 state、渲染读新 state 的分裂）。
    var scale by remember(page, splitOn, fit, rotation) { mutableFloatStateOf(1f) }
    var offset by remember(page, splitOn, fit, rotation) { mutableStateOf(Offset.Zero) }
    // 手势回调持有的是组合时的快照：位图加载完成后尺寸会变，必须取最新值
    // （v0.3.6 限幅修复需要"图片实际显示尺寸"，不能用旧闭包里的 bmp）。
    val bmpState = rememberUpdatedState(bmp)

    Box(
        modifier
            .fillMaxSize()
            // 缩放/平移手势只在“双指”或“已放大”时消费事件：
            // 单指拖动必须留给 HorizontalPager 翻页，否则滑动翻页会失效。
            .pointerInput(page, splitOn, fit, rotation) {
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
            }
            .pointerInput(page, splitOn, rtl, vertical, fit, rotation) {
                detectTapGestures(
                    onTap = { pos ->
                        if (vertical) {
                            // 竖直轴：上/下 1/3 = 上/下一页（起点侧=上一页，P-V5）
                            val third = size.height / 3f
                            when {
                                pos.y < third -> vm.goto(open.page - 1)
                                pos.y > 2 * third -> vm.goto(open.page + 1)
                                else -> onToggleChrome()
                            }
                        } else {
                            val third = size.width / 3f
                            when {
                                pos.x < third -> if (rtl) vm.goto(open.page + 1) else vm.goto(open.page - 1)
                                pos.x > 2 * third -> if (rtl) vm.goto(open.page - 1) else vm.goto(open.page + 1)
                                else -> onToggleChrome()
                            }
                        }
                    },
                    onDoubleTap = {
                        scale = if (scale > 1.5f) 1f else 2.5f
                        val b = bmpState.value
                        offset = if (scale == 1f || b == null) Offset.Zero
                                 else clampPan(offset.x, offset.y, scale,
                                               size.width.toFloat(), size.height.toFloat(),
                                               SplitMode.effWidth(half, rtl, b.width), b.height,
                                               fit, rotation)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            // 切分显示（P-S4）：绘制层取半页子矩形（drawImage 四整型矩形重载）——
            // 位图仍是整页（解码/所有权/预算不受影响）；layout 尺寸 ≡ 半页 × s（等比，无失真）。
            Canvas(
                modifier = Modifier
                    .semantics { contentDescription = "第 ${page + 1} 页" }
                    // v0.4.3 自算布局（方案乙）：适配模式 × 旋转语义在此统一计算
                    //（唯一公式源 fitScale）——组件尺寸即目标绘制尺寸，与 graphicsLayer 旋转无交互歧义。
                    .layout { measurable, constraints ->
                        val bb = bmpState.value
                        val vw = constraints.maxWidth.toFloat()
                        val vh = constraints.maxHeight.toFloat()
                        var w = constraints.maxWidth
                        var h = constraints.maxHeight
                        if (bb != null && bb.width > 0 && bb.height > 0) {
                            val effW = SplitMode.effWidth(half, rtl, bb.width)
                            val s = fitScale(fit, rotation, vw, vh,
                                             effW.toFloat(), bb.height.toFloat())
                            w = (effW * s).roundToInt().coerceAtLeast(1)
                            h = (bb.height * s).roundToInt().coerceAtLeast(1)
                        }
                        val p = measurable.measure(Constraints.fixed(w, h))
                        layout(w, h) { p.place(0, 0) }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        rotationZ = rotation.toFloat()
                    }
                    .clipToBounds(),
            ) {
                val bb = bmpState.value ?: return@Canvas
                val (sx, sw) = SplitMode.srcXWidth(half, rtl, bb.width)
                drawImage(
                    image = bb.asImageBitmap(),
                    srcOffset = IntOffset(sx, 0),
                    srcSize = IntSize(sw, bb.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1),
                                      size.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low,
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // P-R5：只有可见页允许转圈（动效永不来自离屏组合页——M2 静置 ≤5fps 的直接开关）。
                if (isCurrent) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                                             color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                }
                Text("第 ${page + 1} 页", fontSize = 11.sp,
                     color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * v0.4.3 适配模式（ReaderPrefs.fit）：0 整页 / 1 宽度 / 2 高度 / 3 原始。
 * 视觉坐标系：rotation∈{90,270} 时页面"视觉宽高"与位图宽高互换——
 * "宽度适配"在旋转后 = 视觉宽度撑满（渲染布局与 clampPan 共用本公式，单一真相源，
 * 杜绝两处数学漂移）。
 */
internal fun visualDims(iw: Float, ih: Float, rot: Int): Pair<Float, Float> =
    if (rot % 180 != 0) ih to iw else iw to ih

internal fun fitScale(mode: Int, rot: Int, vw: Float, vh: Float, iw: Float, ih: Float): Float {
    if (mode == 3 || iw <= 0f || ih <= 0f) return 1f      // 原始 1:1
    val (sw, sh) = visualDims(iw, ih, rot)
    if (sw <= 0f || sh <= 0f) return 1f
    return when (mode) {
        1 -> vw / sw                                      // 宽度：视觉宽撑满
        2 -> vh / sh                                      // 高度：视觉高撑满
        else -> minOf(vw / sw, vh / sh)                   // 整页（旋转时按视觉几何）
    }
}

/**
 * v0.3.6 缩放平移限幅（修复"放大后只能往一个方向平移"）：
 * graphicsLayer 默认以【中心】为变换原点 → 可平移范围必须关于 0 **对称**；
 * 且边界要按【图片实际显示尺寸】算——v0.4.3 起 = fitScale(模式, 旋转) 后的视觉尺寸
 *（平移在 graphicsLayer 外层作用于屏幕坐标，故按视觉几何限幅）。
 * 旧实现 `coerceIn(-容器×(ns−1), 0)` 两条都错：单边区间 + 幅值 2×（少除 2），
 * 表现为放大后只能看图片右/下侧，左/上侧永远到不了，且反方向可越界露底。
 */
internal fun clampPan(nx: Float, ny: Float, ns: Float,
                     vw: Float, vh: Float, iw: Int, ih: Int,
                     mode: Int, rot: Int): Offset {
    val f = fitScale(mode, rot, vw, vh, iw.toFloat(), ih.toFloat())
    val (sw, sh) = visualDims(iw.toFloat(), ih.toFloat(), rot)
    val halfX = maxOf(0f, (sw * f * ns - vw) / 2f)   // 图片溢出视口部分的一半
    val halfY = maxOf(0f, (sh * f * ns - vh) / 2f)
    return Offset(nx.coerceIn(-halfX, halfX), ny.coerceIn(-halfY, halfY))
}

// ---------------------------------------------------------------- chrome

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    prefs: ReaderPrefs,
    vm: ReaderViewModel,
    rotation: Int,
    onRotate: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
    onTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTranslate) {
                Icon(
                    Icons.Filled.Translate, "翻译",
                    tint = if (vm.open.value.translateEnabled) MaterialTheme.colorScheme.primary
                    else Color.White,
                )
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.Filled.RotateRight, "旋转", tint = Color.White)
            }
            IconButton(onClick = onBookmarks) {
                Icon(Icons.Filled.BookmarkBorder, "书签", tint = Color.White)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "阅读设置", tint = Color.White)
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(open: ReaderOpenState, vm: ReaderViewModel, onGoto: (Int) -> Unit,
                            modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (open.pageCount > 1) {
                Slider(
                    value = open.page.toFloat(),
                    onValueChange = { onGoto(it.toInt()) },
                    valueRange = 0f..(open.pageCount - 1).toFloat(),
                )
            }
            Text(
                "${open.page + 1} / ${open.pageCount}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun ReaderSettingsDialog(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val prefs by vm.prefs.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("适配", Modifier.width(64.dp))
                    listOf(0 to "整页", 1 to "宽度", 2 to "高度", 3 to "原始").forEach { (k, label) ->
                        TextButton(onClick = { vm.setPrefs(prefs.copy(fit = k)) }) {
                            Text(if (prefs.fit == k) "●$label" else label,
                                 color = if (prefs.fit == k) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("背景", Modifier.width(64.dp))
                    listOf(0 to "黑", 1 to "白", 2 to "灰").forEach { (k, label) ->
                        TextButton(onClick = { vm.setPrefs(prefs.copy(bg = k)) }) {
                            Text(if (prefs.bg == k) "●$label" else label)
                        }
                    }
                }
                // 方向（P-V2）：三档按册记忆；只管"阅读推进方向"，朝向由旋转按钮决定、与设置无关。
                val mode by vm.mode.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("方向", Modifier.width(64.dp))
                    listOf(0 to "左→右", 1 to "右→左", 2 to "上→下").forEach { (k, label) ->
                        TextButton(onClick = { vm.setDir(k) }) {
                            Text(if (mode.dir == k) "●$label" else label,
                                 color = if (mode.dir == k) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("双页", Modifier.width(64.dp))
                    TextButton(onClick = { vm.setPrefs(prefs.copy(spread = !prefs.spread)) }) {
                        Text(if (prefs.spread) "●开" else "关（横屏生效）")
                    }
                }
                // 切分阅读（P-S7）：每册记忆的手动开关；开 = 每页按正中切成两半逐半显示。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("切分阅读", Modifier.width(64.dp))
                    val splitOn by vm.splitOn.collectAsState()
                    TextButton(onClick = { vm.setSplit(false) }) {
                        Text(if (!splitOn) "●关" else "关",
                             color = if (!splitOn) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { vm.setSplit(true) }) {
                        Text(if (splitOn) "●开" else "开",
                             color = if (splitOn) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurface)
                    }
                }
                // 连续滚动（P-V2）：每册记忆的手动开关；开 = 该"方向"变为连续滚动（瀑布流）。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("连续滚动", Modifier.width(64.dp))
                    TextButton(onClick = { vm.setScroll(false) }) {
                        Text(if (!mode.scroll) "●关" else "关",
                             color = if (!mode.scroll) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurface)
                    }
                    TextButton(onClick = { vm.setScroll(true) }) {
                        Text(if (mode.scroll) "●开" else "开",
                             color = if (mode.scroll) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurface)
                    }
                }
                val open by vm.open.collectAsState()
                if (open.translateEnabled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("翻译", Modifier.width(64.dp))
                        TextButton(onClick = { vm.retranslateCurrent() }) { Text("重译当前页") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

/**
 * 翻译是"手动、按书"的显式动作：默认不运行任何识别/翻译。
 * backend 模式：启用 = 整本后台翻译（可见页优先，随时暂停/继续，进度=已落档案页）。
 * ondevice 模式保持原语义（本书长期启用 / 仅本页）。
 */
@Composable
private fun TranslateEnableDialog(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val title by vm.open.collectAsState()
    val backend = vm.engineMode() == "backend"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("为《${title.title}》启用翻译？") },
        text = {
            Column {
                if (backend) {
                    Text("整本后台翻译：从当前页开始按顺序翻译全书，正在阅读的页面优先；",
                         style = MaterialTheme.typography.bodyMedium)
                    Text("可随时暂停/继续，进度保存在本机，再次打开自动续传。",
                         style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("· 识别与翻译由局域网后端（Mac）完成，端侧只做排版",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("· 关掉本册翻译只是停止队列，已翻译的页面仍然显示",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("识别与翻译都在本机完成（NPU），首次阅读每页约数秒；",
                         style = MaterialTheme.typography.bodyMedium)
                    Text("结果会缓存，再次阅读同一页秒开。",
                         style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("· 启用本书：记住《${title.title}》，以后打开自动翻译",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("· 仅本页：本次阅读临时启用，不改动书的设置",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.setTranslateEnabled(true, persist = true); onDismiss() }) {
                Text(if (backend) "开启整本翻译" else "启用本书")
            }
        },
        dismissButton = {
            Row {
                if (!backend) {
                    TextButton(onClick = {
                        vm.setTranslateEnabled(true, persist = false); onDismiss()
                    }) { Text("仅本页") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun TranslateMenuDialog(vm: ReaderViewModel, onDismiss: () -> Unit) {
    val open by vm.open.collectAsState()
    val backend = vm.engineMode() == "backend"
    if (backend) {
        val job by BookTranslateJob.state.collectAsState()
        val mine = job.bookId != 0L && job.bookId == open.bookId
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("翻译") },
            text = {
                Column {
                    if (mine) {
                        val eta = job.etaMs()
                        val status = when {
                            job.error.isNotEmpty() -> job.error
                            job.paused -> "已暂停"
                            job.running -> "后台翻译中"
                            job.done >= job.total && job.total > 0 -> "已完成"
                            else -> "已停止"
                        }
                        Text("$status · ${job.done}/${job.total} 页" +
                            (if (eta > 0) " · 约剩 ${(eta / 60000).coerceAtLeast(1)} 分钟" else ""),
                            style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (mine && (job.running || job.paused)) {
                        TextButton(onClick = {
                            if (job.paused) BookTranslateJob.resume() else BookTranslateJob.pause()
                            onDismiss()
                        }) { Text(if (job.paused) "继续后台翻译" else "暂停后台翻译") }
                    }
                    TextButton(onClick = { vm.retranslateCurrent(); onDismiss() }) {
                        Text("重译本页")
                    }
                    TextButton(onClick = {
                        vm.setTranslateEnabled(false, persist = true); onDismiss()
                    }) { Text("关闭本册翻译（停止后台队列）") }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("翻译") },
        text = {
            Column {
                TextButton(onClick = { vm.retranslateCurrent(); onDismiss() }) {
                    Text("重译当前页")
                }
                TextButton(onClick = { vm.setTranslateEnabled(false, persist = true); onDismiss() }) {
                    Text("关闭本册翻译")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BookmarksDialog(vm: ReaderViewModel, onGoto: (Int) -> Unit, onDismiss: () -> Unit) {
    val open by vm.open.collectAsState()
    val bookmarks by produceState<List<BookmarkRow>>(emptyList(), open.bookId) {
        value = vm.bookmarks()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签") },
        text = {
            Column {
                TextButton(onClick = {
                    vm.addBookmark(vm.rawOfPage(open.page), "第 ${open.page + 1} 页")  // 存 raw，标签显示页号
                    // refresh hack: re-produce
                }) { Text("＋ 为当前页 (${open.page + 1}) 加书签") }
                bookmarks.forEach { bm ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onGoto(vm.pageOfRaw(bm.page)) }) {
                            Text(if (bm.label.isBlank()) "第 ${vm.pageOfRaw(bm.page) + 1} 页" else bm.label)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.removeBookmark(bm.id) }, Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
