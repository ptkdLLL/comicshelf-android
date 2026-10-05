package com.comicshelf.app.ehmeta

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comicshelf.app.shelf.CoverCell

/**
 * E-Hentai tag 检索视图（S2 · 纯平行视图 —— B3 决策：不叠加现有搜索框）。
 *
 * 布局：
 *  - 挑选模式：命名空间 chips + 名称搜索 + tag 列表（频次降序）
 *  - 结果模式：已选 chips + 本地命中计数 + BookCell 网格（手动窗口，60/页）
 * 结果网格复用书架 CoverCell（封面走既有并发门控，行为与书架一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EhTagScreen(
    vm: EhTagViewModel,
    preloadRid: Long?,
    onBack: () -> Unit,
    onOpenBook: (Long, String, Boolean) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.activate() }
    LaunchedEffect(preloadRid) { vm.applyPreload(preloadRid) }

    var showPicker by remember { mutableStateOf(true) }
    // 首个 tag 选中后自动切到结果；清空后回到挑选
    LaunchedEffect(ui.selected.size) {
        if (ui.selected.isNotEmpty()) showPicker = false
        if (ui.selected.isEmpty()) showPicker = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                title = {
                    Column {
                        Text("E-Hentai 标签", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !ui.dataReady -> "未载入数据包"
                                ui.selected.isEmpty() -> "选择标签开始检索"
                                else -> "本地命中 ${ui.resultTotal} 本"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (ui.selected.isNotEmpty()) {
                        TextButton(onClick = { vm.clearTags() }) { Text("清除") }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!ui.dataReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "未载入 E-Hentai 数据包\n请先在设置中导入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            // ---- 已选 chips（可删；＋ 回到挑选） ----
            if (ui.selected.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(ui.selected, key = { it.rid }) { t ->
                        InputChip(
                            selected = true,
                            onClick = { vm.toggleTag(t) },
                            label = { Text(t.nameZh ?: t.name, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Filled.Close, "移除") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = false,
                            onClick = { showPicker = true },
                            label = { Text("＋ 添加") },
                            leadingIcon = { Icon(Icons.Filled.Add, null) },
                        )
                    }
                }
            }

            if (showPicker) {
                PickerArea(vm, ui, onShowResults = { showPicker = false })
            } else {
                ResultsArea(vm, ui, onOpenBook = onOpenBook,
                    modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- 挑选区

@Composable
private fun PickerArea(
    vm: EhTagViewModel,
    ui: EhTagViewModel.UiState,
    onShowResults: () -> Unit,
) {
    // 命名空间清单（一次性加载；提在 LazyRow 之外——LazyListScope 内不能起协程）
    var nss by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    LaunchedEffect(Unit) {
        runCatching { EhEngine.tagQuery()?.namespaces() }.getOrNull()?.let { nss = it }
    }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                FilterChip(
                    selected = ui.picker.ns == null,
                    onClick = { vm.setPickerNs(null) },
                    label = { Text("全部") },
                )
            }
            items(nss, key = { it.first }) { (ns, n) ->
                FilterChip(
                    selected = ui.picker.ns == ns,
                    onClick = { vm.setPickerNs(ns) },
                    label = { Text("${ehNsLabel(ns)} $n") },
                )
            }
        }
        OutlinedTextField(
            value = ui.picker.query,
            onValueChange = { vm.setPickerQuery(it) },
            singleLine = true,
            placeholder = { Text("搜索标签（原名/中文）…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        if (ui.picker.loading && ui.picker.tags.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.width(28.dp))
            }
        } else if (ui.picker.tags.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(ui.error ?: "无匹配标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(ui.picker.tags, key = { it.rid }) { t ->
                    TagRow(t, selected = ui.selected.any { it.rid == t.rid }) { vm.toggleTag(t) }
                }
            }
        }
        if (ui.selected.isNotEmpty()) {
            Button(
                onClick = onShowResults,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Text(if (ui.computing) "统计中…" else "查看结果 · 本地 ${ui.resultTotal} 本")
            }
        }
    }
}

@Composable
private fun TagRow(t: EhTagQuery.TagEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = selected, onClick = onClick, label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.nameZh ?: t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.nameZh != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        t.name, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
        })
        Spacer(Modifier.width(10.dp))
        Text(
            formatCnt(t.cnt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatCnt(c: Long): String =
    if (c >= 10000) "${c / 10000}.${(c % 10000) / 1000}万" else c.toString()

// ---------------------------------------------------------------- 结果区

@Composable
private fun ResultsArea(
    vm: EhTagViewModel,
    ui: EhTagViewModel.UiState,
    onOpenBook: (Long, String, Boolean) -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    when {
        ui.computing -> Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.width(28.dp))
                Spacer(Modifier.height(8.dp))
                Text("正在统计本地命中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ui.resultTotal == 0 -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(ui.error ?: "本地没有此标签组合的匹配结果",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val gridState = rememberLazyGridState()
            // ⚠️ 不可在 derivedStateOf 内直接读 ui（参数值会被 remember 捕获为首帧快照）——
            // 用 snapshotFlow 监听「可见末端」与「已载行数」两个可观察量。
            LaunchedEffect(gridState, ui.rows.size, ui.resultTotal) {
                snapshotFlow {
                    val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last to ui.rows.size
                }.collect { (last, n) ->
                    if (last >= 0 && n > 0 && last >= n - 24) vm.loadMore()
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                state = gridState,
                modifier = modifier,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.rows, key = { it.id }) { cell ->
                    CoverCell(
                        cell = cell,
                        selected = false,
                        job = null,
                        lowFi = false,
                        onClick = { onOpenBook(cell.id, cell.title, false) },
                        onLongClick = {},
                        onToggleFav = {},
                    )
                }
                if (ui.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.width(22.dp))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 书籍 EH 标签面板（书架长按菜单入口）

/** 面板行：命名空间头 或 tag 行（预计算，避免 LazyColumn 内的可变头部状态） */
private sealed interface PanelRow {
    data class NsHeader(val label: String) : PanelRow
    data class Tag(val entry: EhTagQuery.TagEntry) : PanelRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EhBookTagsPanel(
    bookId: Long,
    onDismiss: () -> Unit,
    onPickTag: (EhTagQuery.TagEntry) -> Unit,
) {
    var info by remember { mutableStateOf<EhTagQuery.BookEhInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var panelRows by remember { mutableStateOf<List<PanelRow>>(emptyList()) }
    LaunchedEffect(bookId) {
        val q = EhEngine.tagQuery()
        val path = q?.pathOfBookId(bookId)
        val inf = if (q != null && path != null) {
            runCatching { q.bookInfo(path) }.getOrNull()
        } else null
        info = inf
        panelRows = buildList {
            var lastNs = ""
            for (t in inf?.tags.orEmpty()) {
                if (t.ns != lastNs) {
                    lastNs = t.ns
                    add(PanelRow.NsHeader(ehNsLabel(t.ns)))
                }
                add(PanelRow.Tag(t))
            }
        }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("E-Hentai 标签", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val inf = info
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("读取中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inf == null || inf.gid < 0 ->
                    Text("未匹配到 E-Hentai 图库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                inf.tags.isEmpty() ->
                    Text("该 gid 无 tag 记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    inf.ehTitle?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "gid ${inf.gid} · ${inf.tags.size} 个标签（点击可去检索）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (panelRows.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(panelRows) { row ->
                        when (row) {
                            is PanelRow.NsHeader -> Text(
                                row.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            )
                            is PanelRow.Tag -> Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onPickTag(row.entry) }) {
                                    Text(
                                        row.entry.nameZh ?: row.entry.name,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (row.entry.nameZh != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            row.entry.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
