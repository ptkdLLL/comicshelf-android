package com.comicshelf.app.ehmeta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.core.Json
import com.comicshelf.app.util.AppContextHolder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S1 调试驱动（仅 debug 构建注册；release/sidestest 无此类）。
 *
 * 用法（adb）：
 *   adb shell am broadcast -a com.comicshelf.app.DEBUG_EH -p com.comicshelf.app --es cmd status
 *   cmd ∈ { status | import | match | matchnames | dump | keysinfo | v4probe | uninstall | cancel }
 * 输出：logcat 标签 EhDebug + 文件 <externalFiles>/ehdump/debug.log（含时间戳）
 */
class EhDebugReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "EhDebug"
        const val ACTION = "com.comicshelf.app.DEBUG_EH"
        private val TS = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
    }

    private fun dumpDir(): File =
        File(AppContextHolder.app.getExternalFilesDir(null), "ehdump").apply { mkdirs() }

    private fun log(msg: String) {
        val line = "${TS.format(Date())} $msg"
        Log.i(TAG, line)
        runCatching { File(dumpDir(), "debug.log").appendText(line + "\n") }
    }

    /** 进程累计 CPU 时间（ms；USER_HZ=100）——用于区分"CPU 忙"与"低优先级让路" */
    private fun cpuTimeMs(): Long = runCatching {
        val s = File("/proc/self/stat").readText()
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        (f[11].toLong() + f[12].toLong()) * 10L
    }.getOrDefault(-1L)

    override fun onReceive(ctx: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: "status"
        log("cmd=$cmd")
        when (cmd) {
            "status" -> {
                log(EhMetaManager.statusText())
                log("phase=${EhEngine.phase.value} busy=${EhEngine.isBusy}")
            }
            "cancel" -> {
                EhEngine.cancel()
                log("cancel requested")
            }
            else -> {
                if (!EhEngine.submit(cmd) { run(cmd) }) log("busy — 拒绝 $cmd")
            }
        }
    }

    private fun run(cmd: String) {
        when (cmd) {
            "import" -> {
                val t0 = System.currentTimeMillis()
                var lastLog = 0L
                EhEngine.setPhase(EhPhase.Importing("start", 0))
                val r = EhEngine.importAndIndex { stage, pct ->
                    EhEngine.setPhase(EhPhase.Importing(stage, pct))
                    val now = System.currentTimeMillis()
                    if (now - lastLog > 2000 || pct >= 99) {
                        lastLog = now
                        log("import $stage $pct% (${(now - t0) / 1000}s)")
                    }
                }
                log("$r（耗时 ${(System.currentTimeMillis() - t0) / 1000}s）")
            }

            "match" -> {
                var lastLog = 0L
                val cpu0 = cpuTimeMs()
                val st = EhEngine.matchLibrary { done, total ->
                    EhEngine.setPhase(EhPhase.Matching(done, total))
                    val now = System.currentTimeMillis()
                    if (now - lastLog > 2000 || done == total) {
                        lastLog = now
                        log("match $done/$total")
                    }
                }
                val cpu = cpuTimeMs() - cpu0
                val bd = st.breakdown
                log("match 完成: total=${st.total} matched=${st.matched} newly=${st.newlyMatched} " +
                    "wall=${st.elapsedMs}ms cpu=${cpu}ms" +
                    (if (bd != null) " [读${bd.readMs} 检查${bd.existMs} 匹配${bd.matchMs} 写${bd.writeMs}]" else ""))
            }

            "matchreset" -> {
                EhMatchStore(EhMetaManager.ehDir()).use { it.clearAll() }
                log("matchreset: match.db 已清空")
            }

            "matchnames" -> matchNames()

            "dump" -> dumpResults()

            "keysinfo" -> keysInfo()

            "v4probe" -> v4Probe()

            "uninstall" -> {
                EhMetaManager.uninstall()
                log("uninstall 完成: ${EhMetaManager.statusText()}")
            }
        }
    }

    // ---------------------------------------------------------------- 子命令

    /** 对 ehDir/names.txt（每行一个书名）跑匹配 → ehdump/matchnames.tsv（Mac 对账用） */
    private fun matchNames() {
        val dir = EhMetaManager.ehDir()
        val names = File(dir, "names.txt")
        if (!names.isFile) { log("缺 $names"); return }
        val keysFile = File(dir, EhMetaManager.KEYS_NAME)
        val t0 = System.currentTimeMillis()
        var hits = 0
        var n = 0
        val outFile = File(dumpDir(), "matchnames.tsv")
        outFile.writeText("")
        EhKeysIndex(keysFile).use { idx ->
            val out = StringBuilder(1 shl 20)
            names.bufferedReader().useLines { lines ->
                for (nm in lines) {
                    if (nm.isBlank()) continue
                    val gid = EhV3.match(nm) { s -> idx.searchKey(s) }
                    out.append(nm).append('\t').append(gid).append('\n')
                    n++
                    if (gid >= 0) hits++
                    if (out.length > (1 shl 20)) {
                        outFile.appendText(out.toString())
                        out.setLength(0)
                    }
                }
            }
            outFile.appendText(out.toString())
        }
        log("matchnames: $hits/$n = ${"%.1f".format(hits * 100.0 / n)}% 耗时=${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /** 匹配结果导出（与 library.db 按 path 归并连接，流式零内存）：ehdump/matches.tsv */
    private fun dumpResults() {
        val dir = EhMetaManager.ehDir()
        val libDb = File(AppContextHolder.app.filesDir, "data/library.db")
        val outFile = File(dumpDir(), "matches.tsv")
        var n = 0L
        var hit = 0L
        outFile.writeText("")
        EhBookSource(libDb).use { src ->
            EhMatchStore(dir).use { store ->
                src.booksCursorByPath().use { b ->
                    store.all().use { m ->
                        var bHas = b.moveToFirst()
                        var mHas = m.moveToFirst()
                        while (bHas && mHas) {
                            val bp = b.getString(1)
                            val mp = m.getString(0)
                            val cmp = cmpUtf8(bp, mp)   // SQLite BINARY = UTF-8 memcmp 序
                            when {
                                cmp < 0 -> bHas = b.moveToNext()
                                cmp > 0 -> mHas = m.moveToNext()
                                else -> {
                                    val gid = m.getLong(1)
                                    outFile.appendText("$bp\t${b.getLong(0)}\t${b.getString(2)}\t$gid\n")
                                    n++
                                    if (gid >= 0) hit++
                                    bHas = b.moveToNext()
                                    mHas = m.moveToNext()
                                }
                            }
                        }
                    }
                }
            }
        }
        log("dump: $n 行（其中命中 $hit）→ ${outFile.path}")
    }

    /** UTF-8 字节序比较（与 SQLite BINARY/memcmp 一致；String.compareTo 是 UTF-16 序，增补字符会不一致） */
    private fun cmpUtf8(a: String, b: String): Int {
        val ba = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        val n = minOf(ba.size, bb.size)
        var i = 0
        while (i < n) {
            val x = ba[i].toInt() and 0xFF
            val y = bb[i].toInt() and 0xFF
            if (x != y) return x - y
            i++
        }
        return ba.size - bb.size
    }

    /** keys.bin 完整性自检：记录数、长度、全量无符号升序验证 + 抽样打印 */
    private fun keysInfo() {
        val keysFile = File(EhMetaManager.ehDir(), EhMetaManager.KEYS_NAME)
        val t0 = System.currentTimeMillis()
        EhKeysIndex(keysFile).use { idx ->
            val n = idx.count
            var prev = 0L
            var bad = -1L
            var i = 0
            while (i < n) {
                val h = idx.hashAt(i)
                if (i > 0 && java.lang.Long.compareUnsigned(h, prev) < 0) { bad = i.toLong(); break }
                prev = h
                i++
            }
            val sizeFile = keysFile.length()
            log("keysinfo: count=$n bytes=$sizeFile (期望 ${n * 12}) 排序检查=${if (bad >= 0) "坏@$bad" else "OK"} 耗时=${(System.currentTimeMillis() - t0) / 1000}s")
            // 前 5 / 后 5 条抽样
            for (k in 0 until minOf(5, n)) log("  head[$k] h=${java.lang.Long.toUnsignedString(idx.hashAt(k))} gid=${idx.gidAt(k)}")
            for (k in maxOf(0, n - 5) until n) log("  tail[$k-${n}] h=${java.lang.Long.toUnsignedString(idx.hashAt(k))} gid=${idx.gidAt(k)}")
        }
    }

    /**
     * V4 探针（B1 边界动态验证）：库内小目录 refreshDir（核心写）期间持续读 library.db（Kotlin 读）。
     * 通过标准：无 SQLiteException/锁错误；计数读一致。
     */
    private fun v4Probe() {
        val libDb = File(AppContextHolder.app.filesDir, "data/library.db")
        val src = EhBookSource(libDb)
        var refreshStarted = false
        try {
            val libs = runCatching {
                runBlocking { withContext(CoreDispatcher) { Json.libraries(NativeBridge.libraries()) } }
            }.getOrNull() ?: emptyList()
            if (libs.isNotEmpty()) {
                val lib = libs[0]
                val rel = runCatching {
                    runBlocking {
                        withContext(CoreDispatcher) {
                            Json.dirs(NativeBridge.childDirs(lib.id, "")).minByOrNull { it.count }?.rel ?: ""
                        }
                    }
                }.getOrNull() ?: ""
                log("v4probe: 对 lib=${lib.name} rel='$rel' 发起子树刷新（写）同时读…")
                runCatching {
                    runBlocking { withContext(CoreDispatcher) { NativeBridge.refreshDir(lib.id, rel) } }
                }
                refreshStarted = true
            } else {
                log("v4probe: 无库，仅做读基线")
            }
            val t0 = System.currentTimeMillis()
            var reads = 0L
            var errors = 0L
            val errMsgs = ArrayList<String>()
            while (System.currentTimeMillis() - t0 < 20_000) {
                try {
                    var cnt = 0L
                    src.booksCursor().use { c -> cnt = c.count.toLong() }
                    reads++
                    if (reads == 1L) log("v4probe: 首读 books=$cnt")
                } catch (t: Throwable) {
                    errors++
                    if (errMsgs.size < 5) errMsgs.add("${t.javaClass.simpleName}: ${t.message}")
                }
                Thread.sleep(120)
            }
            log("v4probe 结束: 20s 内读 $reads 次，错误 $errors（refreshStarted=$refreshStarted）" +
                if (errMsgs.isNotEmpty()) " 样例=${errMsgs}" else " ✓ 无锁错误")
        } finally {
            src.close()
        }
    }
}
