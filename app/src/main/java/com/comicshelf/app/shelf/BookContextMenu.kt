package com.comicshelf.app.shelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comicshelf.app.core.BookCell
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.Json
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.reader.BookTranslateJob
import kotlinx.coroutines.withContext

/*
 * v0.5.2 共享书籍操作面板（原 ShelfScreen 私有实现**原样搬家**，零逻辑变更——
 * docs/EH_TAG_LONGPRESS_PLAN.md P-T1；图谱核实消费面各恰 1：书架）。
 * 现由书架（多选→更多）与标签检索页（长按）共宿主；动作通路均为薄 NativeBridge/全局队列，
 * 不依赖任何页面私有状态（评估 E3）。
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookContextMenu(
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
        // v0.5.2 动态认证修（G-T3）：11 行 × 130px 在 2880 屏上超出 → 末项"导出书名"
        // 零布局不可达（实测 bounds=[0,0]）。加垂直滚动 → 全部动作可达（书库同修）。
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
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

/** 全局标签管理器（列举/增删标签；与本册无关）——书架与标签页共用。 */
@Composable
internal fun TagsDialog(onDismiss: () -> Unit) {
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
