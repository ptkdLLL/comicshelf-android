package com.comicshelf.app.shelf

import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.comicshelf.app.core.BookCell
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.CoverStore
import com.comicshelf.app.core.DirRow
import com.comicshelf.app.core.Json
import com.comicshelf.app.ehmeta.EhBookTagsPanel
import com.comicshelf.app.core.LibraryRow
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.core.parseImageBundle
import com.comicshelf.app.reader.BookTranslateJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import com.comicshelf.app.core.CsSettings
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

internal val coverPlaceholder = Color(0xFF232830)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    vm: ShelfViewModel,
    hasAllFilesAccess: Boolean,
    onOpenAllFilesAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBook: (Long, String, Boolean) -> Unit,
    // S2：E-Hentai 标签检索入口（未载入数据包时传 null → 入口不渲染，G1"不载入=与现在相同"）
    onOpenEhTags: ((Long?, String?) -> Unit)? = null,
) {
    val libs by vm.libraries.collectAsState()
    val query by vm.query.collectAsState()
    val scan by vm.scan.collectAsState()
    val items = vm.books.collectAsLazyPagingItems()
    val gridState = vm.gridState

    // ---- S-4 双通道低清门控（封面风暴止血；两通道隔离）----
    // 通道 A（直接滚格）：手指拖着滚 = 看内容，不抑制；松手后的惯性甩动 = 内容飞掠，抑制。
    // 通道 B（滚动条拖动）：按下即抑制（传送语义，内容只是闪现）。
    // 低清期间格子不发任何封面请求（produceCoverState 以 lowFi 为 key）；退出滞后 300ms，
    // 落定后只给静止视口加载。滚动条拖动状态由 ShelfScrollbar 回调进来。
    var barDragging by remember { mutableStateOf(false) }      // 通道 B
    var gridDragActive by remember { mutableStateOf(false) }   // 手指正在拖网格（通道 A 的豁免位）
    var gridScrolling by remember { mutableStateOf(false) }    // 网格在滚（含甩动）
    LaunchedEffect(gridState) {
        launch {
            gridState.interactionSource.interactions.collect { i ->
                when (i) {
                    is DragInteraction.Start -> gridDragActive = true
                    is DragInteraction.Stop, is DragInteraction.Cancel -> gridDragActive = false
                }
            }
        }
        launch { snapshotFlow { gridState.isScrollInProgress }.collect { gridScrolling = it } }
    }
    val rawFast = barDragging || (gridScrolling && !gridDragActive)
    var lowFi by remember { mutableStateOf(false) }
    LaunchedEffect(rawFast) {
        if (rawFast) lowFi = true
        else { delay(300); lowFi = false }
    }

    // 记录滚动位置（仅在"已恢复"之后记录，避免回来时的空状态把记忆冲掉）
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> if (vm.scrollRestored()) vm.noteScroll(i, o) }
    }
    // 数据就绪后恢复一次：回到你刚才读的那本书所在的那一屏
    LaunchedEffect(items.itemCount) {
        if (items.itemCount > 0 && !vm.scrollRestored()) {
            val saved = vm.savedScroll()
            vm.markScrollRestored()
            if (saved != null && saved.first > 0) {
                gridState.scrollToItem(saved.first.coerceAtMost(items.itemCount - 1), saved.second)
            }
        }
    }

    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var searchEdit by remember { mutableStateOf(query.search) }
    var showAddLib by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Map<Long, BookCell>>(emptyMap()) }
    var contextBook by remember { mutableStateOf<BookCell?>(null) }
    val trJob by BookTranslateJob.state.collectAsState()
    var removeTarget by remember { mutableStateOf<LibraryRow?>(null) }
    var showTagsDialog by remember { mutableStateOf(false) }
    var ehBookTagsFor by remember { mutableStateOf<BookCell?>(null) }   // S2：E-Hentai 标签面板

    // L3：一次性提示（如"刚添加的库首扫 0 本"）——消费后置空，避免重组重复弹。
    val notice by vm.notice.collectAsState()
    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.consumeNotice()
        }
    }

    if (!hasAllFilesAccess) {
        StorageGate(onOpenAllFilesAccess)
        return
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                LibraryDrawerContent(
                    vm, libs, query,
                    onPickLibrary = {
                        vm.setLibrary(it)
                        scope.launch { drawer.close() }
                    },
                    onRescan = { vm.rescan(it) },
                    onRemove = { id -> removeTarget = libs.firstOrNull { it.id == id } },
                    onPickDir = {
                        vm.setDir(it)
                        scope.launch { drawer.close() }
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (query.dirRel.isEmpty()) "全部漫画"
                                else query.dirRel.substringAfterLast('/'),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            libs.firstOrNull { it.id == query.libId }?.let {
                                Text(
                                    "${it.count} 本 · ${it.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.CollectionsBookmark, "书库")
                        }
                    },
                    actions = {
                        var showSearch by remember { mutableStateOf(false) }
                        if (showSearch) {
                            OutlinedTextField(
                                value = searchEdit,
                                onValueChange = {
                                    searchEdit = it
                                    vm.setSearch(it)
                                },
                                singleLine = true,
                                placeholder = { Text("搜索…") },
                                modifier = Modifier.width(200.dp),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        searchEdit = ""
                                        vm.setSearch("")
                                        showSearch = false
                                    }) { Icon(Icons.Filled.Close, null) }
                                },
                            )
                        } else {
                            IconButton(onClick = { showSearch = true }) {
                                Icon(Icons.Filled.Search, "搜索")
                            }
                        }
                        var sortMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Filled.Sort, "排序")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            ShelfSort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        val mark = if (query.sort == s) {
                                            if (query.desc) " ↓" else " ↑"
                                        } else ""
                                        Text((if (query.sort == s) "● " else "○ ") + s.label + mark)
                                    },
                                    onClick = {
                                        val desc = if (query.sort == s) !query.desc else
                                            s == ShelfSort.ADDED || s == ShelfSort.SIZE ||
                                            s == ShelfSort.MTIME || s == ShelfSort.PAGES
                                        vm.setSort(s, desc)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                        var filterMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { filterMenu = true }) {
                            Icon(Icons.Filled.Visibility, "过滤")
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            DropdownMenuItem(
                                text = { Text((if (query.favOnly) "● " else "○ ") + "只看收藏") },
                                onClick = { vm.setFilters(!query.favOnly, query.readState) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(when (query.readState) {
                                        0 -> "● 未读"; 1 -> "● 在读"; 2 -> "● 读完"
                                        else -> "○ 全部状态"
                                    })
                                },
                                onClick = {
                                    val next = query.readState + 1
                                    vm.setFilters(query.favOnly, if (next > 2) -1 else next)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text((if (query.recursive) "● " else "○ ") + "含子目录") },
                                onClick = { vm.setRecursive(!query.recursive) },
                            )
                        }
                        IconButton(onClick = { vm.rescan() }) {
                            Icon(Icons.Filled.Refresh, "重扫")
                        }
                        // 手动重提失败封面：清掉失败标记（含永久失败），可见格立即重新轮询
                        IconButton(onClick = {
                            scope.launch {
                                val n = withContext(CoreDispatcher) {
                                    NativeBridge.retryFailedCovers()
                                }
                                coverRetryGen.value++
                                Toast.makeText(
                                    ctx,
                                    if (n > 0) "已重置 $n 个失败封面，重新提取中…"
                                    else "已重新排队可见封面",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) {
                            Icon(Icons.Filled.Autorenew, "重提失败封面")
                        }
                        // S2：E-Hentai 标签检索入口（仅在数据就绪时出现）
                        if (onOpenEhTags != null) {
                            IconButton(onClick = { onOpenEhTags(null, null) }) {
                                Icon(Icons.Filled.Label, "E-Hentai 标签检索")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, "设置")
                        }
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddLib = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("添加书库") },
                )
            },
        ) { pad ->
            Column(Modifier.padding(pad)) {
                scan?.let { s ->
                    if (s.running || s.added + s.updated + s.removed > 0) ScanProgressStrip(s, vm)
                }
                if (selection.isNotEmpty()) {
                    SelectionBar(
                        n = selection.size,
                        onTranslate = { on ->
                            selection.keys.forEach { vm.setBookTranslate(it, on) }
                            selection = emptyMap()
                        },
                        onMore = if (selection.size == 1) ({
                            contextBook = selection.values.first()
                            selection = emptyMap()
                        }) else null,
                        onFavorite = {
                            selection.values.forEach { vm.toggleFavorite(it) }
                            selection = emptyMap()
                        },
                        onMark = { st ->
                            vm.setReadState(selection.keys.toList(), st)
                            selection = emptyMap()
                        },
                        onCancel = { selection = emptyMap() },
                    )
                }
                Box(Modifier.fillMaxSize()) {
                    if (items.itemCount == 0) {
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("没有漫画", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (libs.isEmpty()) "先添加一个书库文件夹" else "换个搜索词或目录试试",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(110.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            count = items.itemCount,
                            key = items.itemKey { it.id },
                        ) { idx ->
                            val cell = items[idx]
                            if (cell == null) {
                                // 占位格（目标窗未到，S-1/S-3）：骨架底色，跳转落点不空白
                                Box(Modifier.padding(4.dp)) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(0.7f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(coverPlaceholder),
                                    )
                                }
                                return@items
                            }
                            CoverCell(
                                cell = cell,
                                selected = selection.containsKey(cell.id),
                                job = trJob.takeIf { it.bookId == cell.id && it.active },
                                lowFi = lowFi,
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        selection = if (selection.containsKey(cell.id))
                                            selection - cell.id else selection + (cell.id to cell)
                                    } else {
                                        onOpenBook(cell.id, cell.title, false)
                                    }
                                },
                                onLongClick = {
                                    selection = if (selection.containsKey(cell.id))
                                        selection - cell.id else selection + (cell.id to cell)
                                },
                                onToggleFav = { vm.toggleFavorite(cell) },
                            )
                        }
                    }
                    // S-3：全库滚动条（大库快跳）。状态读取/跳转提交全部封装在组件内部；
                    // onDraggingChange 接入 S-4 通道 B（拖动期间低清=不拉封面）。
                    ShelfScrollbar(
                        gridState = gridState,
                        total = items.itemCount,
                        onDraggingChange = { barDragging = it },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }

    removeTarget?.let { lib ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("移除书库？") },
            text = {
                Text("只会把《${lib.name}》（${lib.count} 本）从书库索引里移除，" +
                     "**不会删除 NAS/手机上的任何文件**。之后可以随时重新添加。\n\n" +
                     "要移除的是：${lib.root}")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeLibrary(lib.id)
                    removeTarget = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("取消") } },
        )
    }

    contextBook?.let { cell ->
        BookContextMenu(
            cell = cell,
            onDismiss = { contextBook = null },
            onOpen = { onOpenBook(cell.id, cell.title, false) },
            onOpenTranslated = { onOpenBook(cell.id, cell.title, true) },
            onToggleTranslate = { on ->
                // backend 模式：启用即启动整本后台翻译队列（从阅读进度处开始）
                vm.setBookTranslate(cell.id, on, cell.pages, cell.lastPage)
                contextBook = null
            },
            job = trJob.takeIf { it.bookId == cell.id },
            translateEnabledOf = { vm.bookTranslateEnabled(cell.id) },
            onToggleFav = { vm.toggleFavorite(cell); contextBook = null },
            onMark = { vm.setReadState(listOf(cell.id), it); contextBook = null },
            onTags = { showTagsDialog = true; contextBook = null },
            onRegenerateCover = { vm.regenerateCover(cell.id); contextBook = null },
            onEhTags = onOpenEhTags?.let { _ -> { ehBookTagsFor = cell; contextBook = null } },
            onExportName = {
                contextBook = null
                scope.launch {
                    val name = withContext(Dispatchers.IO) { NativeBridge.bookFileName(cell.id) }
                    if (name.isBlank()) {
                        Toast.makeText(ctx, "导出失败：书名缺失", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.setText(AnnotatedString(name))
                        Log.i("ShelfExport", "书名已复制: $name")
                        Toast.makeText(ctx, "已复制书名：$name", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    // S2：书籍 E-Hentai 标签面板（点击 tag → 进入标签检索并预选）
    ehBookTagsFor?.let { cell ->
        EhBookTagsPanel(
            bookId = cell.id,
            onDismiss = { ehBookTagsFor = null },
            onPickTag = { t ->
                ehBookTagsFor = null
                onOpenEhTags?.invoke(t.rid, t.nameZh ?: t.name)
            },
        )
    }

    if (showAddLib) {
        AddLibraryDialog(
            vm = vm,
            onDismiss = { showAddLib = false },
            onConfirm = { path ->
                vm.addLibrary(path)
                showAddLib = false
            },
        )
    }

    if (showTagsDialog) {
        TagsDialog(onDismiss = { showTagsDialog = false })
    }
}

// ---------------------------------------------------------------- drawer

@Composable
private fun LibraryDrawerContent(
    vm: ShelfViewModel,
    libs: List<LibraryRow>,
    query: ShelfQuery,
    onPickLibrary: (Long) -> Unit,
    onRescan: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onPickDir: (String) -> Unit,
) {
    LazyColumnWithHeader {
        item(key = "hdr-lib") {
            Text(
                "书库",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(libs.size, key = { i -> "lib$i" }) { i ->
            val lib = libs[i]
            var actions by remember { mutableStateOf(false) }
            androidx.compose.material3.NavigationDrawerItem(
                label = { Text(lib.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                badge = { Text(lib.count.toString()) },
                selected = lib.id == query.libId,
                onClick = { onPickLibrary(lib.id) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Row(Modifier.padding(start = 24.dp)) {
                TextButton(onClick = { onRescan(lib.id) }) { Text("重扫", fontSize = 11.sp) }
                TextButton(onClick = { onRemove(lib.id) }) { Text("移除", fontSize = 11.sp) }
            }
        }
        if (libs.isEmpty()) {
            item(key = "no-lib") {
                Text(
                    "还没有书库。点右下角“添加书库”。",
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "hdr-dir") {
            Text(
                "目录",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "dir-root") {
            val total = libs.firstOrNull { it.id == query.libId }?.count ?: 0
            androidx.compose.material3.NavigationDrawerItem(
                label = { Text("<全部>") },
                badge = { Text(total.toString()) },
                selected = query.dirRel.isEmpty(),
                onClick = { onPickDir("") },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        item(key = "dir-tree") {
            DirTree(vm, query, "", 0, onPickDir)
        }
    }
}

/** Lazily-expanding folder tree backed by the `dirs` table. */
@Composable
private fun DirTree(
    vm: ShelfViewModel,
    query: ShelfQuery,
    parentRel: String,
    depth: Int,
    onPickDir: (String) -> Unit,
) {
    var expanded by remember(parentRel) { mutableStateOf(false) }
    var children by remember(parentRel) { mutableStateOf<List<DirRow>?>(null) }

    LaunchedEffect(parentRel, query.libId) { children = null }

    Column(Modifier.padding(start = (depth * 12).dp)) {
        if (children == null) {
            LaunchedEffect(parentRel, query.libId) {
                children = vm.childDirs(parentRel)
            }
        }
        children?.let { dirs ->
            dirs.forEach { d ->
                var open by remember(d.rel) { mutableStateOf(false) }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickDir(d.rel) }
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            if (open) Icons.Filled.ChevronRight else Icons.Filled.Folder,
                            null,
                            Modifier.size(16.dp).clickable { open = !open },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            d.name,
                            Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "(${d.total})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { vm.refreshDir(d.rel) }, Modifier.size(26.dp)) {
                            Icon(Icons.Filled.Refresh, "刷新此目录", Modifier.size(15.dp))
                        }
                    }
                    if (open) {
                        DirTree(vm, query, d.rel, depth + 1, onPickDir)
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyColumnWithHeader(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) { content() }
}

// ---------------------------------------------------------------- misc chrome

@Composable
private fun StorageGate(onOpen: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(Icons.Filled.Folder, null, Modifier.size(64.dp),
                 tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("需要“所有文件”访问权限", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "ComicShelf 需要直接遍历漫画文件夹——百万级书库的扫描性能依赖原生文件系统访问。\n\n" +
                    "授予权限后应用不会修改或删除你的任何文件。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpen) { Text("去授权") }
        }
    }
}

@Composable
private fun ScanProgressStrip(s: ScanProgressRow, vm: ShelfViewModel) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    buildString {
                        if (s.running) append("扫描中 ") else append("上次扫描 ")
                        append("已见 ${s.seen}")
                        append(" · 新增 ${s.added}")
                        append(" · 更新 ${s.updated}")
                        if (s.dirs > 0) append(" · 目录 ${s.dirs}")
                        if (s.removed > 0) append(" · 移除 ${s.removed}")
                        if (s.paused) append("（已暂停）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (s.running) {
                    IconButton(onClick = { vm.pauseScan(!s.paused) }, Modifier.size(28.dp)) {
                        Icon(
                            if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            "暂停", Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = { vm.cancelScan() }, Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "取消", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CoverCell(
    cell: BookCell,
    selected: Boolean,
    job: BookTranslateJob.State?,
    lowFi: Boolean,          // S-4：快滑/拖滚动条期间不拉封面、不转圈
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFav: () -> Unit,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                else Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(6.dp))
                .background(coverPlaceholder),
        ) {
            val slot by produceCoverState(cell.id, lowFi)
            if (slot.bmp != null) {
                Image(
                    bitmap = slot.bmp!!.asImageBitmap(),
                    contentDescription = cell.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (slot.unavailable) {
                // 不可用（不是图片包 / 读取失败冷却中）：占位图标，不再无限转圈
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.ImageNotSupported,
                        contentDescription = "封面暂不可用",
                        modifier = Modifier.size(22.dp),
                        tint = Color.White.copy(alpha = 0.35f),
                    )
                    if (cell.pages > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("${cell.pages}p", fontSize = 10.sp,
                             color = Color.White.copy(alpha = 0.6f))
                    }
                }
            } else if (!lowFi) {
                // S-4：低清期间保持静态骨架（不转圈省帧）；非低清时才显示转圈
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (cell.pages > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("${cell.pages}p", fontSize = 10.sp,
                             color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                if (cell.favorite) {
                    MiniBadge(Icons.Filled.Favorite, "收藏", Color(0xFFFF6B81))
                }
                if (cell.readState == 2) {
                    Spacer(Modifier.width(3.dp))
                    MiniBadge(Icons.Filled.DoneAll, "已读完", Color(0xFF7CE38B))
                } else if (cell.readState == 1) {
                    Spacer(Modifier.width(3.dp))
                    MiniBadge(Icons.Filled.Schedule, "在读", Color(0xFF8AB4F8))
                }
            }
            if (cell.lastPage > 0 && cell.readState != 2 && cell.pages > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                ) {
                    Text(
                        "${cell.lastPage + 1}/${cell.pages}",
                        Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 9.sp, color = Color.White,
                    )
                }
            }
            if (job != null) {
                // 后台整本翻译的进度徽标（暂停态加 ⏸）
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                ) {
                    Text(
                        "译 ${job.done}/${job.total}" + (if (job.paused) " ⏸" else ""),
                        Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 9.sp, color = Color(0xFF8AB4F8),
                    )
                }
            }
        }
        Text(
            cell.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MiniBadge(icon: ImageVector, desc: String, tint: Color) {
    Surface(color = Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(4.dp)) {
        Icon(icon, desc, Modifier.padding(2.dp).size(12.dp), tint = tint)
    }
}

/**
 * 封面槽位：bmp 就绪；或 unavailable = 暂不可用（永久失败 / 瞬态失败冷却中），
 * 此时显示占位图标而非无限转圈，原生侧冷却到点后会自动重试。
 */
internal data class CoverSlot(val bmp: Bitmap? = null, val unavailable: Boolean = false)

/** “重提失败封面”代际：顶部按钮触发 +1，所有可见单元格立即重新轮询。 */
private val coverRetryGen = mutableStateOf(0)

/** 单次原生轮询（工作线程）：status 1=生成中 2=就绪 3=不可用。 */
private suspend fun pollCover(bookId: Long): Pair<Int, Bitmap?> = withContext(CoreDispatcher) {
    // nativeCoverPoll -> Object[3]: int[1] status, int[2] {w,h}, byte[] rgba
    val bundle = NativeBridge.coverPoll(bookId) ?: return@withContext 1 to null
    val status = (bundle[0] as? IntArray)?.get(0) ?: 1
    if (status != 2) return@withContext status to null
    val dims = bundle[1] as? IntArray ?: return@withContext 1 to null
    val px = bundle[2] as? ByteArray ?: return@withContext 1 to null
    val bmp = if (dims.size < 2 || px.isEmpty()) null else CoverStore.rgbaToBitmap(dims, px)
    if (bmp == null) 3 to null else 2 to bmp
}

/**
 * 轮询原生封面管线直到就绪：退避 80ms→1s→5s，不可用时 30s 慢询。
 * 只在前台（lifecycle STARTED）轮询——退到后台即暂停，回前台自动续。
 */
@Composable
internal fun produceCoverState(bookId: Long, lowFi: Boolean): androidx.compose.runtime.State<CoverSlot> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val retryGen = coverRetryGen.value  // 顶部“重提失败封面”触发本轮重启
    return produceState(
        initialValue = CoverSlot(CoverStore.get(bookId)),
        bookId,
        lifecycleOwner,
        retryGen,
        lowFi,   // S-4：低清态翻转即重启本 effect；低清期间体直接返回 → 零封面请求
    ) {
        if (lowFi) return@produceState
        if (value.bmp != null) return@produceState
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var tries = 0
            while (value.bmp == null) {
                val (status, bmp) = pollCover(bookId)
                if (status == 2 && bmp != null) {
                    CoverStore.put(bookId, bmp)
                    value = CoverSlot(bmp)
                    return@repeatOnLifecycle
                }
                if (status == 3) {
                    // 不可用（永久失败，或瞬态失败冷却中）：占位 + 慢询等原生侧重试
                    value = CoverSlot(unavailable = true)
                    delay(30_000)
                } else {
                    value = CoverSlot(unavailable = false)
                    tries++
                    delay(if (tries <= 5) 80L else if (tries <= 20) 1_000L else 5_000L)
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    n: Int,
    onTranslate: (Boolean) -> Unit,
    onMore: (() -> Unit)?,
    onFavorite: () -> Unit,
    onMark: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 $n 本", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            var trMenu by remember { mutableStateOf(false) }
            IconButton(onClick = { trMenu = true }) { Icon(Icons.Filled.Translate, "翻译") }
            DropdownMenu(expanded = trMenu, onDismissRequest = { trMenu = false }) {
                DropdownMenuItem(text = { Text("为选中书籍启用翻译") },
                    onClick = { onTranslate(true); trMenu = false })
                DropdownMenuItem(text = { Text("关闭选中书籍的翻译") },
                    onClick = { onTranslate(false); trMenu = false })
            }
            onMore?.let {
                IconButton(onClick = it) { Icon(Icons.Filled.MoreVert, "更多") }
            }
            IconButton(onClick = onFavorite) { Icon(Icons.Filled.FavoriteBorder, "收藏") }
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.DoneAll, "标记") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("标记未读") }, onClick = { onMark(0); menu = false })
                DropdownMenuItem(text = { Text("标记在读") }, onClick = { onMark(1); menu = false })
                DropdownMenuItem(text = { Text("标记读完") }, onClick = { onMark(2); menu = false })
            }
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "取消") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookContextMenu(
    cell: BookCell,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onOpenTranslated: () -> Unit,
    onToggleTranslate: (Boolean) -> Unit,
    job: BookTranslateJob.State?,
    translateEnabledOf: suspend () -> Boolean,
    onToggleFav: () -> Unit,
    onMark: (Int) -> Unit,
    onTags: () -> Unit,
    onRegenerateCover: () -> Unit,
    onEhTags: (() -> Unit)? = null,      // S2：E-Hentai 标签面板（未载入数据包时为 null → 不渲染）
    onExportName: () -> Unit,
) {
    var markMenu by remember { mutableStateOf(false) }
    val trOn by produceState(initialValue = false, cell.id) { value = translateEnabledOf() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                cell.title,
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            SheetAction(Icons.AutoMirrored.Filled.MenuBook, "打开阅读", onOpen)
            SheetAction(Icons.Filled.Translate, "翻译并打开（本次）", onOpenTranslated)
            SheetAction(
                if (trOn) Icons.Filled.Translate else Icons.Outlined.Translate,
                if (trOn) "关闭本册翻译" else "为本册启用翻译（整本后台）",
            ) { onToggleTranslate(!trOn) }
            job?.let { j ->
                if (j.active) {
                    SheetAction(Icons.Filled.Schedule, "后台翻译 ${j.done}/${j.total}" +
                        (if (j.paused) "（已暂停）" else ""), {})
                    SheetAction(
                        if (j.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        if (j.paused) "继续后台翻译" else "暂停后台翻译",
                    ) {
                        if (j.paused) BookTranslateJob.resume() else BookTranslateJob.pause()
                        onDismiss()
                    }
                }
            }
            SheetAction(
                if (cell.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                if (cell.favorite) "取消收藏" else "加入收藏",
                onToggleFav,
            )
            SheetAction(Icons.Filled.Schedule, "标记为在读", { onMark(1) })
            SheetAction(Icons.Filled.DoneAll, "标记为读完", { onMark(2) })
            SheetAction(Icons.Filled.Label, "标签…", onTags)
            onEhTags?.let { SheetAction(Icons.Filled.Label, "E-Hentai 标签…", it) }
            SheetAction(Icons.Filled.Image, "重新生成封面", onRegenerateCover)
            SheetAction(Icons.Filled.ContentCopy, "导出书名（复制到剪贴板）", onExportName)
        }
    }
    if (markMenu) {
        AlertDialog(onDismissRequest = { markMenu = false },
            title = { Text("阅读状态") },
            confirmButton = {},
            text = {
                Column {
                    TextButton(onClick = { onMark(0); markMenu = false }) { Text("未读") }
                    TextButton(onClick = { onMark(1); markMenu = false }) { Text("在读") }
                    TextButton(onClick = { onMark(2); markMenu = false }) { Text("读完") }
                }
            })
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { action() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp),
             tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AddLibraryDialog(
    vm: ShelfViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var smbMode by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (smbMode) "添加 SMB 书库" else "添加书库") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    FilterChip(selected = !smbMode, onClick = { smbMode = false },
                               label = { Text("本机目录") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = smbMode, onClick = { smbMode = true },
                               label = { Text("SMB 共享") })
                }
                Spacer(Modifier.height(8.dp))
                if (!smbMode) LocalLibraryForm(onConfirm) else SmbLibraryForm(vm, onDismiss)
            }
        },
        confirmButton = {
            if (!smbMode) TextButton(onClick = onDismiss) { Text("取消") }
        },
        dismissButton = {
            if (smbMode) TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * SAF 目录树 URI → 本机真实绝对路径（L1/L2）。
 * DocumentsContract.getTreeDocumentId 形如 "primary:Comics/x"（内置存储）或
 * "1A2B-3C4D:Comics/x"（SD/OTG 卷）→ 映射 /storage/emulated/0/… 与 /storage/<卷>/…。
 * 无法映射的位置（云盘 provider、"home:" 等）返回 null——调用方提示并保留手输通道。
 */
private fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val idx = docId.indexOf(':')
    if (idx <= 0) return null
    val vol = docId.substring(0, idx)
    val sub = docId.substring(idx + 1).trim('/')
    // 卷 ID：primary（内置）或 4-4 十六进制（SD/OTG，如 0000-0000 形）；其余视为不可映射
    val base = when {
        vol == "primary" -> "/storage/emulated/0"
        vol.matches(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")) -> "/storage/$vol"
        else -> return null
    }
    return if (sub.isEmpty()) base else "$base/$sub"
}

@Composable
private fun LocalLibraryForm(onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("/storage/emulated/0/Comics") }
    var err by remember { mutableStateOf("") }   // L3：内联失败反馈（区分 不存在/非目录/不可读）
    // L1：系统文件夹选择器（SAF）。只取"真实路径"交给现有扫描管道，native 零改动。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult   // 用户取消
        val resolved = treeUriToPath(uri)
        if (resolved == null) {
            err = "该位置无法映射为本机目录（可能是云盘/特殊位置），请手输路径"
        } else {
            path = resolved
            err = ""
        }
    }
    Column {
        Text(
            "点「浏览」从手机里选漫画文件夹（推荐）；也可以直接输入文件夹绝对路径" +
                "（在“文件”应用中长按文件夹可复制路径）：",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { runCatching { picker.launch(null) } },
                       modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Folder, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("浏览… 选择文件夹")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = path, onValueChange = { path = it; err = "" },
                          singleLine = true, label = { Text("路径") },
                          isError = err.isNotEmpty())
        if (err.isNotEmpty()) {
            Text(err, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        Text("常用位置：", style = MaterialTheme.typography.labelSmall)
        listOf(
            "/storage/emulated/0/漫画",
            "/storage/emulated/0/Comics",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Pictures",
        ).forEach { p ->
            TextButton(onClick = { path = p; err = "" }) { Text(p, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        // L3：提交前轻校验，失败不再静默（此前路径打错毫无反应）
        Button(onClick = {
            val f = java.io.File(path.trim())
            err = when {
                path.isBlank() -> "请输入或选择文件夹"
                !f.exists() -> "目录不存在：${f.absolutePath}"
                !f.isDirectory -> "这不是文件夹：${f.absolutePath}"
                !f.canRead() -> "目录不可读（请检查「所有文件访问」权限）"
                else -> ""
            }
            if (err.isEmpty()) onConfirm(f.absolutePath)
        }) { Text("添加并扫描") }
    }
}

@Composable
private fun SmbLibraryForm(vm: ShelfViewModel, onAdded: () -> Unit) {
    var address by remember { mutableStateOf("") }   // 可整段粘贴的地址
    var host by remember { mutableStateOf(CsSettings.get("smb_last_host", "")) }
    var share by remember { mutableStateOf("") }
    var sub by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var sharePicker by remember { mutableStateOf(false) }
    var browser by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        Text(
            "直接粘贴 NAS 地址也行：smb://192.168.1.10/share/dir 、 " +
                "192.168.1.10/share/dir 或 Windows 形式 \\\\192.168.1.10\\share\\dir —— " +
                "会自动拆成下面三格（主机 / 共享名 / 子目录）。\n" +
                "不确定共享名就点“列出共享”，不确定目录就点“浏览…”。密码只保存在本机应用私有目录。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            address, {
                address = it
                parseSmbAddress(it)?.let { p ->
                    host = p.first
                    if (p.second.isNotEmpty()) share = p.second
                    if (p.third.isNotEmpty()) sub = p.third
                }
            },
            Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("地址（可整段粘贴）") },
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), singleLine = true,
                          label = { Text("主机 host[:端口]（如 192.168.1.10）") })
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(share, { share = it }, Modifier.weight(1f), singleLine = true,
                              label = { Text("共享名 share（第一段路径）") })
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { sharePicker = true }) { Text("列出共享") }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(sub, { sub = it }, Modifier.weight(1f), singleLine = true,
                              label = { Text("子目录（可留空 = 整个共享）") })
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { browser = true }) { Text("浏览…") }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true,
                          label = { Text("用户名") })
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(domain, { domain = it }, Modifier.fillMaxWidth(), singleLine = true,
                          label = { Text("域（可留空；域账号填 WORKGROUP 或 AD 域名）") })
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
                          label = { Text("密码") },
                          visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall,
                 color = if (msg.startsWith("OK")) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true; msg = ""
                scope.launch {
                    val err = vm.smbProbe(host.trim(), share.trim().trim('/'), user, pass,
                                          domain.trim())
                    msg = if (err.isEmpty()) "OK：连接成功"
                          else "连接失败：" + smbHint(err)
                    busy = false
                }
            }) { Text("测试连接") }
            Spacer(Modifier.width(11.dp))
            Button(enabled = !busy, onClick = {
                busy = true; msg = ""
                scope.launch {
                    val err = vm.addSmbLibrary(host.trim(), share.trim().trim('/'),
                                               sub.trim(), user, pass, domain.trim())
                    if (err.isEmpty()) {
                        CsSettings.set("smb_last_host", host.trim())
                        onAdded()
                    } else {
                        msg = "添加失败：" + smbHint(err)
                    }
                    busy = false
                }
            }) { Text(if (busy) "请稍候…" else "添加并扫描") }
        }
    }

    if (sharePicker) {
        SharePickerDialog(
            host = host.trim(), user = user, pass = pass, domain = domain.trim(),
            onDismiss = { sharePicker = false },
            onPick = { share = it; sharePicker = false },
        )
    }
    if (browser) {
        DirBrowserDialog(
            host = host.trim(), share = share.trim().trim('/'), startSub = sub.trim(),
            user = user, pass = pass, domain = domain.trim(),
            onDismiss = { browser = false },
            onPick = { sub = it; browser = false },
        )
    }
}

/** 把用户粘贴的地址拆成 (主机, 共享名, 子目录)。支持
 *  \\host\share\a\b 、 smb://host:port/share/a/b 、 host/share/a/b 。 */
private fun parseSmbAddress(raw: String): Triple<String, String, String>? {
    var t = raw.trim()
    if (t.isEmpty()) return null
    t = t.removePrefix("smb://").removePrefix("smb:\\")
    t = t.replace('\\', '/')
    while (t.startsWith("/")) t = t.substring(1)
    if (t.isEmpty()) return null
    val parts = t.split('/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val host = parts.getOrElse(0) { "" }
    val share = parts.getOrElse(1) { "" }
    val sub = if (parts.size > 2) parts.drop(2).joinToString("/") else ""
    return Triple(host, share, sub)
}

/** 把 libsmb2 的错误映射成能看懂的中文提示。 */
private fun smbHint(err: String): String = when {
    err.contains("STATUS_BAD_NETWORK_NAME") || err.contains("STATUS_OBJECT_NAME_NOT_FOUND") ->
        err + "\n→ 共享名不对：主机后面第一段路径就是共享名（可用“列出共享”查看）"
    err.contains("STATUS_LOGON_FAILURE") || err.contains("STATUS_ACCESS_DENIED") ->
        err + "\n→ 账号或密码不对（或该账号没有访问权限）。域账号请填“域”；" +
            "群晖/威联通注意账号大小写与是否允许 SMB 访问"
    err.contains("STATUS_ACCOUNT_DISABLED") -> err + "\n→ 账号被禁用"
    err.contains("STATUS_LOGON_TYPE_NOT_GRANTED") -> err + "\n→ 该账号不允许网络登录"
    err.contains("timed out") || err.contains("Timeout") || err.contains("No route") ||
        err.contains("refused") || err.contains("Connection") ->
        err + "\n→ 连不上主机：检查 IP、端口(默认445)、是否与手机同一网段"
    else -> err
}

/** 共享名选择：走 IPC$ 的 ShareEnum。 */
@Composable
private fun SharePickerDialog(host: String, user: String, pass: String, domain: String,
                              onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) {
            runCatching { NativeBridge.smbShares(host, user, pass, domain) }
                .getOrDefault("{\"error\":\"调用失败\"}")
        }
        runCatching {
            val o = JSONObject(r)
            err = o.optString("error")
            val a = o.optJSONArray("shares") ?: JSONArray()
            val l = ArrayList<String>()
            for (i in 0 until a.length()) l.add(a.getString(i))
            shares = l
        }.onFailure { err = it.message ?: "解析失败" }
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择共享") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (busy) Text("查询中…（需要账号能访问 IPC$）")
                if (err.isNotEmpty()) Text(smbHint(err), color = MaterialTheme.colorScheme.error,
                                           style = MaterialTheme.typography.bodySmall)
                shares.forEach { s ->
                    TextButton(onClick = { onPick(s) }) { Text(s) }
                }
                if (!busy && err.isEmpty() && shares.isEmpty()) Text("没有可见的共享")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 目录浏览：浏览共享内的子目录，显示每层的书数量，避免猜路径。 */
@Composable
private fun DirBrowserDialog(host: String, share: String, startSub: String,
                             user: String, pass: String, domain: String,
                             onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var cur by remember { mutableStateOf(startSub.trim('/')) }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var archives by remember { mutableStateOf(0L) }
    var images by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }

    fun load(path: String) {
        cur = path
        busy = true
        err = ""
    }
    LaunchedEffect(cur) {
        val r = withContext(Dispatchers.IO) {
            runCatching { NativeBridge.smbList(host, share, cur, user, pass, domain) }
                .getOrDefault("{\"error\":\"调用失败\"}")
        }
        runCatching {
            val o = JSONObject(r)
            err = o.optString("error")
            archives = o.optLong("archives")
            images = o.optLong("images")
            val a = o.optJSONArray("dirs") ?: JSONArray()
            val l = ArrayList<String>()
            for (i in 0 until a.length()) l.add(a.getString(i))
            dirs = l
        }.onFailure { err = it.message ?: "解析失败" }
        busy = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("浏览目录") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("当前：/ " + (if (cur.isEmpty()) share else "$share/$cur"),
                     style = MaterialTheme.typography.bodySmall)
                Text("此目录直接包含：${archives} 个压缩包、${images} 张图片",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
                if (busy) Text("列出中…")
                if (err.isNotEmpty()) Text(smbHint(err), color = MaterialTheme.colorScheme.error,
                                           style = MaterialTheme.typography.bodySmall)
                if (cur.isNotEmpty()) {
                    TextButton(onClick = {
                        load(cur.substringBeforeLast('/', ""))
                    }) { Text("⬆ 上一级") }
                }
                dirs.sortedWith(compareBy { it.lowercase() }).forEach { d ->
                    TextButton(onClick = {
                        load(if (cur.isEmpty()) d else "$cur/$d")
                    }) { Text("📁 $d", maxLines = 1) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(cur) }) { Text("选定此目录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TagsDialog(onDismiss: () -> Unit) {
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var edit by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        tags = withContext(CoreDispatcher) { Json.strings(NativeBridge.allTags()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标签") },
        text = {
            Column {
                tags.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t, Modifier.weight(1f))
                        TextButton(onClick = {
                            NativeBridge.deleteTag(t)
                            tags = tags - t
                        }) { Text("删除") }
                    }
                }
                if (tags.isEmpty()) {
                    Text("暂无标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(edit, { edit = it }, Modifier.weight(1f),
                                      label = { Text("新标签") }, singleLine = true)
                    TextButton(onClick = {
                        if (edit.isNotBlank()) {
                            tags = tags + edit
                            edit = ""
                        }
                    }) { Text("添加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

// ---------------------------------------------------------------- scrollbar

/** 小于此本数不显示滚动条（小库用不上，避免遮挡）。 */
private const val SCROLLBAR_MIN_TOTAL = 150

/**
 * 全库滚动条（S-3）：粗定位 + 拖动时"第 N / M 本"序号气泡（纯算术，零 IO）。
 *
 * 两条纪律（见 docs/SHELF_SCROLLBAR_PLAN.md）：
 *  · 状态读取只发生在本组件内部（derivedStateOf / 手势闭包、offset 延迟读取）——
 *    gridState 的 firstVisibleItemIndex 等高频值严禁提升到 ShelfScreen 顶层，
 *    否则滚动时整屏重组；
 *  · 跳转提交走"最新目标位"（conflated StateFlow）+ 单条协程循环（≥120ms 间隔），
 *    结构上同一时刻最多一个悬挂的 scrollToItem，消灭拖动时的加载洪峰。
 */
@Composable
private fun ShelfScrollbar(
    gridState: LazyGridState,
    total: Int,
    onDraggingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (total <= SCROLLBAR_MIN_TOTAL) return

    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableIntStateOf(0) }
    var bubbleVisible by remember { mutableStateOf(false) }
    val stripH = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 目标位（conflated）：拖动只写这里；提交协程每次取走最新值执行一次跳转。
    // compareAndSet 清零：取走后若已有更新的值，清旧不动新，不丢最新落点。
    val pending = remember { MutableStateFlow(-1) }
    val totalNow = rememberUpdatedState(total)
    LaunchedEffect(gridState) {
        while (true) {
            val t = pending.first { it >= 0 }
            pending.compareAndSet(t, -1)
            gridState.scrollToItem(t.coerceIn(0, (totalNow.value - 1).coerceAtLeast(0)))
            delay(120)
        }
    }

    // 组件内部读取（滚动每帧只失效本组件的布局/重组，不碰 ShelfScreen 与网格）
    val visibleCount by remember { derivedStateOf {
        gridState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    } }
    val gridProgress by remember { derivedStateOf {
        (gridState.firstVisibleItemIndex.toFloat() /
            (total - visibleCount).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    } }
    val scrollActive by remember { derivedStateOf { gridState.isScrollInProgress } }

    val minThumbPx = with(density) { 72.dp.toPx() }   // 2× 长：36→72dp 的抓握滑块
    val thumbFrac = (visibleCount.toFloat() / total).coerceIn(0.02f, 1f)
    fun thumbHpx(): Float =
        (stripH.value * thumbFrac).coerceAtLeast(minThumbPx)
            .coerceAtMost(stripH.value.coerceAtLeast(0f))   // 几何护栏：滑块永不超过轨道
    fun thumbTopPx(): Float {
        val span = (stripH.value - thumbHpx()).coerceAtLeast(0f)
        return span * (if (dragging) dragFrac else gridProgress)
    }
    fun fracForY(y: Float): Float {
        val span = (stripH.value - thumbHpx()).coerceAtLeast(1f)
        return ((y - thumbHpx() / 2f) / span).coerceIn(0f, 1f)
    }
    fun pushTarget() {
        val t = (dragFrac * (total - visibleCount).coerceAtLeast(1))
            .roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))
        dragTarget = t
        pending.value = t
    }

    val barAlpha by animateFloatAsState(
        targetValue = if (dragging || scrollActive) 0.85f else 0.22f,
        label = "shelfScrollbarAlpha",
    )

    // 外层容器加宽到 250dp：气泡需要水平空间（宽条带 + 气泡偏移后文字仍要单行）；
    // 容器本身无 pointerInput → 触点穿透，命中区为右侧 72dp 条带。
    Box(
        modifier
            .fillMaxHeight()
            .width(250.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        // 命中条带（只有这条带消费拖动）：24dp × 3 = 72dp
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(72.dp)
                .onSizeChanged { stripH.value = it.height.toFloat() }
                .pointerInput(total) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragging = true
                            bubbleVisible = true
                            onDraggingChange(true)                 // S-4 通道 B 进入
                            dragFrac = fracForY(pos.y)
                            pushTarget()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragFrac = fracForY(change.position.y)
                            pushTarget()
                        },
                        onDragEnd = {
                            dragging = false
                            onDraggingChange(false)                // S-4 通道 B 退出（300ms 滞后由外壳处理）
                            pushTarget()                                  // 追投最终落点
                            scope.launch { delay(900); if (!dragging) bubbleVisible = false }
                        },
                        onDragCancel = {
                            dragging = false
                            onDraggingChange(false)
                            scope.launch { delay(900); if (!dragging) bubbleVisible = false }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            // 轨道
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            // 滑块（offset 延迟读取：滚动时只走布局，不触发重组）：6dp→18dp 宽
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, thumbTopPx().roundToInt()) }
                    .width(18.dp)
                    .height(with(density) { thumbHpx().toDp() })
                    .clip(RoundedCornerShape(9.dp))
                    .alpha(barAlpha)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        // 序号气泡（拖动时显示，松手 900ms 后淡出；单行不打折）
        if (bubbleVisible) {
            Surface(
                color = Color.Black.copy(alpha = 0.82f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            -with(density) { 90.dp.toPx() }.toInt(),   // 宽条带左侧留白
                            thumbTopPx().roundToInt(),
                        )
                    },
            ) {
                Text(
                    "第 ${dragTarget + 1} / $total 本",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp, color = Color.White,
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}
