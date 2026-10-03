package com.comicshelf.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicshelf.app.core.BookmarkRow
import kotlinx.coroutines.delay
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
    val rev by vm.revision.collectAsState()
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
            ReaderPager(vm, open, prefs.rtl, prefs.spread, rotation, rev,
                        onToggleChrome = { chrome = !chrome })
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
            val st = trStates[open.page]
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
    rtl: Boolean,
    spread: Boolean,
    rotation: Int,
    rev: Int,
    onToggleChrome: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LaunchedEffect(constraints.maxWidth, constraints.maxHeight) {
            vm.setScreenHint(constraints.maxWidth, constraints.maxHeight)
        }
        val landscape = maxWidth > maxHeight
        // Two-page spread: pager items become pairs in landscape.
        val step = if (spread && landscape) 2 else 1
        val pageCount = open.pageCount
        val pageCountEff = if (step == 2) (pageCount + 1) / 2 else pageCount
        val pager = rememberPagerState(
            initialPage = open.page / step,
            initialPageOffsetFraction = 0f,
        ) { pageCountEff }

        // Sync external goto -> pager & persist progress.
        LaunchedEffect(pager.currentPage, pager.isScrollInProgress) {
            if (!pager.isScrollInProgress) {
                val page = pager.currentPage * step
                if (page != vm.open.value.page) {
                    vm.goto(page)
                } else {
                    vm.ensurePage(page)
                }
            }
        }
        LaunchedEffect(open.page) {
            val idx = open.page / step
            if (idx != pager.currentPage && !pager.isScrollInProgress) {
                pager.animateScrollToPage(idx)
            }
        }

        HorizontalPager(
            state = pager,
            reverseLayout = rtl,
            beyondViewportPageCount = 2,   // compose neighbors => decode prefetch
            modifier = Modifier.fillMaxSize(),
        ) { idx ->
            val first = idx * step
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                ZoomablePage(vm, open, first, rotation, rev, rtl, onToggleChrome,
                             Modifier.weight(1f))
                if (step == 2 && first + 1 < pageCount) {
                    ZoomablePage(vm, open, first + 1, rotation, rev, rtl, onToggleChrome,
                                 Modifier.weight(1f))
                }
            }
        }
    }
}

/** One zoomable page. Chrome-free; tap zones and gestures only. */
@Composable
private fun ZoomablePage(
    vm: ReaderViewModel,
    open: ReaderOpenState,
    page: Int,
    rotation: Int,
    rev: Int,
    rtl: Boolean,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(page, rev) { vm.ensurePage(page) }

    val original by remember(page, rev) {
        mutableStateOf(vm.pageAt(page))
    }
    // Translated overlay replaces the original when ready.
    val useTranslate = open.translateEnabled
    val translated by produceState<Bitmap?>(initialValue = null, page, rev, useTranslate) {
        if (useTranslate) {
            while (true) {
                val bmp = vm.translatedAt(page)
                if (bmp != null) { value = bmp; return@produceState }
                kotlinx.coroutines.delay(200)
            }
        }
    }
    val bmp = if (useTranslate) translated ?: original else original

    var scale by remember(page) { mutableFloatStateOf(1f) }
    var offset by remember(page) { mutableStateOf(Offset.Zero) }
    // 手势回调持有的是组合时的快照：位图加载完成后尺寸会变，必须取最新值
    // （v0.3.6 限幅修复需要"图片实际显示尺寸"，不能用旧闭包里的 bmp）。
    val bmpState = rememberUpdatedState(bmp)

    Box(
        modifier
            .fillMaxSize()
            // 缩放/平移手势只在“双指”或“已放大”时消费事件：
            // 单指拖动必须留给 HorizontalPager 翻页，否则滑动翻页会失效。
            .pointerInput(page) {
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
                                                      b.width, b.height)
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
            .pointerInput(page, rtl) {
                detectTapGestures(
                    onTap = { pos ->
                        val third = size.width / 3f
                        when {
                            pos.x < third -> if (rtl) vm.goto(open.page + 1) else vm.goto(open.page - 1)
                            pos.x > 2 * third -> if (rtl) vm.goto(open.page - 1) else vm.goto(open.page + 1)
                            else -> onToggleChrome()
                        }
                    },
                    onDoubleTap = {
                        scale = if (scale > 1.5f) 1f else 2.5f
                        val b = bmpState.value
                        offset = if (scale == 1f || b == null) Offset.Zero
                                 else clampPan(offset.x, offset.y, scale,
                                               size.width.toFloat(), size.height.toFloat(),
                                               b.width, b.height)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 ${page + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        rotationZ = rotation.toFloat()
                    },
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp,
                                         color = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Text("第 ${page + 1} 页", fontSize = 11.sp,
                     color = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * v0.3.6 缩放平移限幅（修复"放大后只能往一个方向平移"）：
 * graphicsLayer 默认以【中心】为变换原点 → 可平移范围必须关于 0 **对称**；
 * 且边界要按【图片实际显示尺寸】（ContentScale.Fit 之后的宽高）算，不能拿容器尺寸。
 * 旧实现 `coerceIn(-容器×(ns−1), 0)` 两条都错：单边区间 + 幅值 2×（少除 2），
 * 表现为放大后只能看图片右/下侧，左/上侧永远到不了，且反方向可越界露底。
 */
private fun clampPan(nx: Float, ny: Float, ns: Float,
                     vw: Float, vh: Float, iw: Int, ih: Int): Offset {
    val fit = minOf(vw / iw, vh / ih)                 // 与 ContentScale.Fit 一致
    val halfX = maxOf(0f, (iw * fit * ns - vw) / 2f)  // 图片溢出视口部分的一半
    val halfY = maxOf(0f, (ih * fit * ns - vh) / 2f)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("方向", Modifier.width(64.dp))
                    TextButton(onClick = { vm.setPrefs(prefs.copy(rtl = false)) }) {
                        Text(if (!prefs.rtl) "●左→右" else "左→右")
                    }
                    TextButton(onClick = { vm.setPrefs(prefs.copy(rtl = true)) }) {
                        Text(if (prefs.rtl) "●右→左" else "右→左")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("双页", Modifier.width(64.dp))
                    TextButton(onClick = { vm.setPrefs(prefs.copy(spread = !prefs.spread)) }) {
                        Text(if (prefs.spread) "●开" else "关（横屏生效）")
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
                    vm.addBookmark(open.page, "第 ${open.page + 1} 页")
                    // refresh hack: re-produce
                }) { Text("＋ 为当前页 (${open.page + 1}) 加书签") }
                bookmarks.forEach { bm ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onGoto(bm.page) }) {
                            Text(if (bm.label.isBlank()) "第 ${bm.page + 1} 页" else bm.label)
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
