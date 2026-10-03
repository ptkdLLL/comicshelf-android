package com.comicshelf.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.CsSettings
import com.comicshelf.app.core.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selftestReport by remember { mutableStateOf<String?>(null) }
    var selftestScale by remember { mutableStateOf("200000") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsSection("离线翻译（本机 NPU）") {
                OfflineOcrSection()
            }
            SettingsSection("本机翻译模型（内置, 离线）") {
                LocalLlmSection()
            }
            SettingsSection("网络书库（SMB / NAS）") {
                SmbSection()
            }
            SettingsSection("翻译服务（旁车模式）") {
                TranslateSection()
            }
            SettingsSection("性能自检") {
                OutlinedTextField(
                    selftestScale, { selftestScale = it },
                    label = { Text("规模（行数）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        selftestReport = try {
                            NativeBridge.selftest(
                                selftestScale.toIntOrNull() ?: 200000,
                                File(contextCacheDir(), "").absolutePath)
                        } catch (t: Throwable) {
                            "selftest crashed: ${t.message}"
                        }
                    }
                }) { Text("运行数据库自检") }
                selftestReport?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            SettingsSection("关于") {
                Text("ComicShelf for Android\n核心与 Windows 版同源（SQLite WAL · 增量扫描 · 三级封面缓存 · miniz/UnRAR · stb/WebP/AVIF 解码链）。",
                     style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 离线翻译说明与模型包状态。翻译默认关闭：在阅读器点“翻译”按钮或书架
 * 长按选择“为本册启用翻译”后，才会为该本书启动（每本书单独记忆）。
 */
@Composable
private fun LocalLlmSection() {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(CsSettings.bool("llm_local_enabled", false)) }
    var path by remember {
        mutableStateOf(CsSettings.get("llm_local_path",
            com.comicshelf.app.reader.OnDeviceTranslator.DEFAULT_LOCAL_MODEL))
    }
    var threads by remember { mutableStateOf(CsSettings.int("llm_local_threads", 4).toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var sample by remember { mutableStateOf("") }

    fun refreshStatus() {
        scope.launch {
            status = withContext(CoreDispatcher) {
                val exists = java.io.File(path).isFile()
                val info = NativeBridge.llmLocalInfo()
                val size = if (exists) "%.2f GB".format(java.io.File(path).length() / 1073741824.0)
                           else "文件不存在"
                "$info\n模型: $path ($size)"
            }
        }
    }
    LaunchedEffect(Unit) { refreshStatus() }

    Column {
        Text(
            "内置 llama.cpp 推理（Hy-MT2-1.8B GGUF，约 1.1GB，放 /sdcard/ComicShelfModels/hymt/）。\n" +
                "开启后翻译完全离线；未开启或加载失败时自动回退到上面的旁车服务。\n" +
                "速度参考（骁龙8Gen2，4 线程）：一页 6 句台词约 5 秒。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                CsSettings.setBool("llm_local_enabled", it)
                CsSettings.save()
            })
            Spacer(Modifier.width(8.dp))
            Text(if (enabled) "本机模型优先" else "使用旁车服务")
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), singleLine = true,
                          label = { Text("GGUF 路径") })
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(threads, { threads = it }, Modifier.width(120.dp), singleLine = true,
                              label = { Text("线程数") })
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !busy, onClick = {
                CsSettings.set("llm_local_path", path)
                CsSettings.setInt("llm_local_threads", threads.toIntOrNull() ?: 4)
                CsSettings.save()
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        NativeBridge.llmLocalLoad(path, threads.toIntOrNull() ?: 4, 4096)
                    }
                    status = if (ok) "加载成功" else "加载失败（检查路径/存储空间）"
                    refreshStatus()
                    busy = false
                }
            }) { Text(if (busy) "加载中…" else "加载模型") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) { NativeBridge.llmLocalUnload() }
                    refreshStatus()
                    busy = false
                }
            }) { Text("卸载") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(enabled = !busy && enabled, onClick = {
            busy = true
            sample = ""
            scope.launch {
                val t0 = System.nanoTime()
                val r = withContext(Dispatchers.IO) {
                    if (!NativeBridge.llmLocalLoaded() &&
                        !NativeBridge.llmLocalLoad(path, threads.toIntOrNull() ?: 4, 4096)) {
                        return@withContext "（模型未加载）"
                    }
                    NativeBridge.llmLocalChat(
                        com.comicshelf.app.reader.OnDeviceTranslator.DEFAULT_LOCAL_SYS,
                        "1. あっ、先輩！おはようございます！\n2. だって今日は特別な日ですから！",
                        200, 0.7f, 0.8f, 20)
                }
                val ms = (System.nanoTime() - t0) / 1e6
                sample = r + "\n（${"%.0f".format(ms)} ms）"
                refreshStatus()
                busy = false
            }
        }) { Text("试翻一句") }
        if (sample.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(sample, style = MaterialTheme.typography.bodySmall)
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmbSection() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        status = withContext(CoreDispatcher) { NativeBridge.autoSyncStatus(false) }
    }
    Column {
        Text(
            "SMB 书库支持：\n" +
                "· 目录浏览/扫描/读压缩包 全部按需随机读，不会整档下载（RAR 例外，首次会缓存）\n" +
                "· 实时同步依赖服务器的 CHANGE_NOTIFY；威联通/群晖(Samba) 支持，未支持时请用“重扫”",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    status = withContext(CoreDispatcher) { NativeBridge.autoSyncStatus(true) }
                    busy = false
                }
            }) { Text(if (busy) "检测中…" else "重新检测") }
            Spacer(Modifier.width(12.dp))
            Text(status, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OfflineOcrSection() {
    val ctx = com.comicshelf.app.util.AppContextHolder.app
    var probe by remember { mutableStateOf<Pair<String, Long>?>(null) }
    fun runProbe() {
        val ext = ctx.getExternalFilesDir(null)
        val cands = buildList {
            val custom = runCatching { CsSettings.get("vl_model_dir", "") }.getOrDefault("")
            if (custom.isNotBlank()) add(File(custom))
            if (ext != null) add(File(ext, "vlmodel"))
            add(File("/sdcard/ComicShelfModels/vlmodel"))
        }
        probe = cands.firstOrNull { File(it, "vl_ctx.bin").isFile }
            ?.let { dir ->
                val size = File(dir, "vl_ctx.bin").length() + File(dir, "vl_embed_f16.bin").length()
                dir.absolutePath to size
            }
    }
    LaunchedEffect(Unit) { runProbe() }
    Column {
        Text(
            "默认不翻译。在阅读器点“翻译”按钮（或书架长按书籍）可为本册启用；" +
            "启用后识别与翻译都在本机运行，结果按页缓存。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        val p = probe
        if (p != null) {
            Text("VL 模型包：已就绪（%.1f GB）".format(p.second / 1e9),
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.primary)
            Text(p.first, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("VL 模型包：未找到", style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.error)
            Text(
                "将 PaddleOCR-VL-For-Manga 模型包放到以下任一位置即可：\n" +
                "/sdcard/ComicShelfModels/vlmodel/（含 vl_ctx.bin、vl_embed_f16.bin、" +
                "vl_vocab.tsv、vl_prompt_ids.i64、vl_mrope.i32、libQnnHtpV73Skel.so）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { runProbe() }) { Text("重新检测") }
    }
}

private fun contextCacheDir(): File =
    com.comicshelf.app.util.AppContextHolder.cacheDir

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall,
         color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
private fun TranslateSection() {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(CsSettings.get("translate_service_url", "")) }
    var src by remember { mutableStateOf(CsSettings.get("source_lang", "日本語")) }
    var dst by remember { mutableStateOf(CsSettings.get("target_lang", "简体中文")) }
    var enabled by remember { mutableStateOf(CsSettings.bool("translate_enabled", false)) }
    var health by remember { mutableStateOf<String?>(null) }

    // ---- engine mode -------------------------------------------------------
    var engine by remember { mutableStateOf(CsSettings.get("translate_engine", "sidecar")) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("引擎", Modifier.width(64.dp))
        listOf("sidecar" to "旁车 (PC)", "ondevice" to "端侧 GPU",
               "backend" to "本机后端").forEach { (k, label) ->
            TextButton(onClick = {
                engine = k
                CsSettings.set("translate_engine", k)
                CsSettings.save()
            }) {
                Text(if (engine == k) "●$label" else label,
                     color = if (engine == k) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.onSurface)
            }
        }
    }

    if (engine == "backend") {
        var bUrl by remember { mutableStateOf(CsSettings.get("backend_url",
            com.comicshelf.app.reader.OnDeviceTranslator.DEFAULT_BACKEND)) }
        var bHealth by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(bUrl, { bUrl = it },
                          label = { Text("后端地址 http://Mac-IP:8787 (adb reverse 可用 127.0.0.1)") },
                          placeholder = { Text("未配置") },
                          singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                CsSettings.set("backend_url", bUrl.trim().trimEnd('/'))
                CsSettings.save()
            }) { Text("保存后端地址") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    bHealth = withContext(Dispatchers.IO) {
                        val u = bUrl.trim().trimEnd('/')
                        if (u.isEmpty()) {
                            "请先填写后端地址（Mac 的局域网 IP:8787；USB 可用 adb reverse tcp:8787 后填 http://127.0.0.1:8787）"
                        } else try {
                            val conn = java.net.URL(u + "/health")
                                .openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 3000
                            conn.readTimeout = 3000
                            val t = conn.inputStream.use {
                                it.readBytes().toString(Charsets.UTF_8)
                            }
                            conn.disconnect()
                            t.take(220)
                        } catch (e: Throwable) { "连接失败: ${e.message}" }
                    }
                }
            }) { Text("测试") }
        }
        bHealth?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "本机后端：Mac 侧 cs-backend（CTBD 检测 + PaddleOCR-VL 识别, MPS 加速）" +
                "与 llama-server（Hy-MT2, Metal）承担 OCR 与翻译；端侧只做墨迹擦除/排版。" +
                "手机需与 Mac 同网（或 adb reverse tcp:8787）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    if (engine == "ondevice") {
        var gpu by remember { mutableStateOf(CsSettings.bool("ocr_gpu", true)) }
        SettingRow("OCR 用 GPU (Adreno/Vulkan)") {
            Switch(checked = gpu, onCheckedChange = {
                gpu = it
                CsSettings.setBool("ocr_gpu", it)
                CsSettings.save()
            })
        }
        var llmUrl by remember { mutableStateOf(CsSettings.get("llm_url", "")) }
        var llmKey by remember { mutableStateOf(CsSettings.get("llm_key", "")) }
        var llmModel by remember { mutableStateOf(CsSettings.get("llm_model", "")) }
        OutlinedTextField(llmUrl, { llmUrl = it },
                          label = { Text("LLM 地址 http://IP:PORT/v1/chat/completions") },
                          singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(llmKey, { llmKey = it }, label = { Text("API Key") },
                              singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(llmModel, { llmModel = it }, label = { Text("模型") },
                              singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "端侧模式：文本检测（NPU）+ PaddleOCR-VL-For-Manga 识别（NPU，与 Windows CUDA 同权重），" +
                "LLM 走上面配置的 OpenAI 兼容接口。模型目录：cacheDir/models 或 " +
                "/sdcard/ComicShelfModels。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            CsSettings.set("llm_url", llmUrl)
            CsSettings.set("llm_key", llmKey)
            CsSettings.set("llm_model", llmModel)
            CsSettings.save()
        }) { Text("保存 LLM 配置") }
        Spacer(Modifier.height(8.dp))
    }

    SettingRow("启用翻译") {
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            CsSettings.setBool("translate_enabled", it)
            NativeBridge.translateConfigure(null, null, null, -1, -1, -1, it)
        })
    }
    if (engine != "ondevice") {
        OutlinedTextField(url, { url = it }, label = { Text("旁车地址 http://IP:8674") },
                          singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(src, { src = it }, label = { Text("源语言") },
                              singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(dst, { dst = it }, label = { Text("目标语言") },
                              singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = {
                CsSettings.set("translate_service_url", url)
                CsSettings.set("source_lang", src)
                CsSettings.set("target_lang", dst)
                NativeBridge.translateConfigure(url, src, dst, -1, -1, -1, enabled)
                CsSettings.save()
            }) { Text("保存") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    health = try {
                        NativeBridge.translateHealth(4000)
                    } catch (t: Throwable) {
                        "{\"ok\":false,\"error\":\"${t.message}\"}"
                    }
                }
            }) { Text("测试连接") }
        }
        health?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = if (it.contains("\"ok\":true")) MaterialTheme.colorScheme.primary
                 else MaterialTheme.colorScheme.error)
        }
    }
    Spacer(Modifier.height(8.dp))
    // ---- 存储占用 + 一键释放（翻译档案 + 封面缓存；均为无上限的本地缓存）---------
    var storageTick by remember { mutableStateOf(0) }
    var confirmRelease by remember { mutableStateOf(false) }
    var releaseResult by remember { mutableStateOf<String?>(null) }
    val coversDir = remember {
        java.io.File(com.comicshelf.app.util.AppContextHolder.app.filesDir, "data/covers")
    }
    val archiveKb = remember(storageTick) { NativeBridge.translateArchiveBytes() / 1024 }
    val coversKb = remember(storageTick) {
        (coversDir.listFiles()?.sumOf { it.length() } ?: 0L) / 1024
    }
    Text("翻译文本档案 $archiveKb KB · 封面缓存 $coversKb KB",
         style = MaterialTheme.typography.bodySmall)
    releaseResult?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.primary)
    }
    Spacer(Modifier.height(6.dp))
    Button(onClick = { confirmRelease = true }) { Text("释放全部翻译与封面") }
    if (confirmRelease) {
        AlertDialog(
            onDismissRequest = { confirmRelease = false },
            title = { Text("释放全部翻译与封面？") },
            text = {
                Column {
                    Text("将删除：", style = MaterialTheme.typography.bodyMedium)
                    Text("· 翻译文本档案（$archiveKb KB，所有书的译文）",
                         style = MaterialTheme.typography.bodySmall)
                    Text("· 封面缓存（$coversKb KB）",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("书籍原文不受影响；已翻译的书再次打开/翻页时会按需重新翻译" +
                         "（后端模式约数秒/页），封面会自动重新生成。",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        com.comicshelf.app.reader.BookTranslateJob.cancel()  // 释放后队列重扫
                        val rows = NativeBridge.clearAllTextArchive()
                        NativeBridge.clearAllCovers()
                        com.comicshelf.app.core.CoverStore.clear()
                        withContext(Dispatchers.Main) {
                            storageTick++
                            releaseResult = "已释放：翻译档案 $rows 本 · " +
                                "封面 $coversKb KB（页面内位图缓存将在离开后失效）"
                            confirmRelease = false
                        }
                    }
                }) { Text("释放", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRelease = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingRow(label: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        trailing()
    }
}
