package com.comicshelf.app.ehmeta

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comicshelf.app.core.CsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页「E-Hentai 数据」段（S3）：选择/导入/卸载 + 匹配 + 占用 + 自定义目录。
 *
 * 设计（对三原则）：
 *  - 用户选择权：数据包由用户放置（候选目录/自定义目录），App 不内置下载源（G5）；
 *  - 零常驻：本段之外零触点；未载入时书架入口不出现（G1）；
 *  - 导入成功后自动触发一次匹配（后台低优先级、可取消）——S2 实测发现卸载/重导入后
 *    match 数据被清空，未匹配时 tag 视图为空，故导入后必须补匹配。
 */
@Composable
fun EhSettingsSection() {
    val scope = rememberCoroutineScope()
    val phase by EhEngine.phase.collectAsState()

    var customDir by remember { mutableStateOf(CsSettings.get("eh_data_dir", "")) }
    var stats by remember { mutableStateOf<LongArray?>(null) }
    var sizeText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var confirmUninstall by remember { mutableStateOf(false) }
    var pkgDesc by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            val (st, sz, pk) = withContext(Dispatchers.IO) {
                Triple(EhMetaManager.matchStats(), EhMetaManager.sizeBreakdown(),
                       EhMetaManager.locatePackage())
            }
            stats = st
            sizeText = if (sz.isEmpty()) "" else sz.joinToString(" · ") { (k, v) ->
                "$k ${fmtSize(v)}"
            } + " · 合计 ${fmtSize(EhMetaManager.dirSizeBytes())}"
            pkgDesc = pk?.let { "${it.path}（${fmtSize(it.length())}）" } ?: ""
        }
    }
    LaunchedEffect(phase) { refresh() }
    LaunchedEffect(Unit) { refresh() }   // 进入设置页即刷新（用户可能刚放置/删除数据包，phase 不变）

    val busy = phase is EhPhase.Importing || phase is EhPhase.Matching

    Column {
        Text(
            "把 E-Hentai 元数据包（ehmeta.db.zip）放到下列任一位置后点「导入」：\n" +
                "· 本应用外部目录/ehmeta/\n· /sdcard/ComicShelfModels/ehmeta/\n· 自定义目录（见下）\n" +
                "导入为一次性操作（约 2-3 分钟，后台可取消）；数据包完全可选，不载入时应用与现在完全相同。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // ---- 状态 ----
        val statusText = when (val p = phase) {
            is EhPhase.NotLoaded -> "状态：未载入"
            is EhPhase.Importing -> "状态：导入中（${p.stage} ${p.pct}%）"
            is EhPhase.Matching -> "状态：匹配中 ${p.done}/${p.total}"
            is EhPhase.Ready -> "状态：就绪 · 索引 ${p.keys} 键"
            is EhPhase.Broken -> "状态：异常 · ${p.reason}"
        }
        Text(statusText, style = MaterialTheme.typography.bodyMedium)
        stats?.let { s ->
            Text(
                "已匹配 ${"%,d".format(s[1])} 本 · 未命中记录 ${"%,d".format(s[0] - s[1])} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (phase is EhPhase.Ready && stats == null) {
            Text("尚未匹配（导入后会显示 tag 检索；点「立即匹配」补跑）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        (phase as? EhPhase.Importing)?.let {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { it.pct / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        (phase as? EhPhase.Matching)?.let {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (it.total > 0) it.done.toFloat() / it.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            if (pkgDesc.isNotEmpty()) "数据包：$pkgDesc" else "未找到数据包（请按上方路径放置）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 操作 ----
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                // 始终可点：包缺失时由引擎给出可读提示（避免"刚放好包但按钮仍灰"的陈旧状态）
                enabled = !busy,
                onClick = { note = ""; if (EhEngine.startImport { note = it }) note = "导入已开始（后台）" },
            ) { Text("导入数据包") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = !busy && (phase is EhPhase.Ready),
                onClick = { note = ""; if (EhEngine.startMatch { note = it }) note = "匹配已开始（后台）" },
            ) { Text("立即匹配") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = !busy && phase !is EhPhase.NotLoaded,
                onClick = { confirmUninstall = true },
            ) { Text("卸载") }
            if (busy) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { EhEngine.cancel(); note = "已请求取消" }) { Text("取消") }
            }
        }

        // ---- 自定义目录 ----
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            customDir, { customDir = it },
            label = { Text("自定义数据包目录（可空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            CsSettings.set("eh_data_dir", customDir.trim())
            CsSettings.save()
            note = "自定义目录已保存"
            refresh()
        }) { Text("保存目录") }

        // ---- 占用 ----
        if (sizeText.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("存储占用：$sizeText", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(note, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("卸载 E-Hentai 数据？") },
            text = { Text("将删除全部已导入数据（元数据库/索引/匹配结果，约 ${fmtSize(EhMetaManager.dirSizeBytes())}）。\n" +
                "书本与阅读进度不受影响；再次使用需重新导入。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    note = ""
                    EhEngine.startUninstall { note = it }
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("取消") } },
        )
    }
}

private fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.2fGB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0fMB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0fKB".format(b / 1024.0)
    else -> "$b B"
}
