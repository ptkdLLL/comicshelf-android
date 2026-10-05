package com.comicshelf.app.ehmeta

import com.comicshelf.app.core.CsSettings
import com.comicshelf.app.util.AppContextHolder
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * ehmeta 数据包管理器（P2/P3）：定位 / 校验 / 导入 / 卸载。
 *
 * 目录约定（B2 决策建议默认）：
 *  - 数据包候选目录：设置项 `eh_data_dir`（可空）→ getExternalFilesDir()/ehmeta →
 *    /sdcard/ComicShelfModels/ehmeta（沿用 vl_model 先例）
 *  - 导入产物目录：getExternalFilesDir()/ehmeta/（ehmeta.db + keys.bin + meta.json）
 *
 * 导入原子性（R3）：
 *  解压 → tmp/ehmeta.db（边解压边算 sha256）→ 校验 → 从 tmp 建 keys.bin →
 *  写 import.state 标记 → 两次原子改名（db、keys）→ 删标记。
 *  任一环节失败/中断：正式文件不被破坏；import.state 标记使半成品可被识别。
 */
object EhMetaManager {

    const val FORMAT = "ehmeta/1"
    const val ZIP_NAME = "ehmeta.db.zip"
    const val DB_NAME = "ehmeta.db"
    const val KEYS_NAME = "keys.bin"
    private const val STATE_NAME = "import.state"

    // ---------------------------------------------------------------- 目录

    fun ehDir(): File {
        val ext = AppContextHolder.app.getExternalFilesDir(null)
        return if (ext != null) File(ext, "ehmeta") else File(AppContextHolder.app.filesDir, "ehmeta")
    }

    fun candidateDirs(): List<File> {
        val out = ArrayList<File>(3)
        val custom = runCatching { CsSettings.get("eh_data_dir", "") }.getOrDefault("")
        if (custom.isNotBlank()) out.add(File(custom))
        out.add(ehDir())
        out.add(File("/sdcard/ComicShelfModels/ehmeta"))
        return out
    }

    fun locatePackage(): File? {
        for (d in candidateDirs()) {
            val f = File(d, ZIP_NAME)
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    fun importStateFile(): File = File(ehDir(), STATE_NAME)

    // ---------------------------------------------------------------- 状态

    fun status(): EhPhase {
        val dir = ehDir()
        val state = importStateFile()
        if (state.exists()) {
            return EhPhase.Broken("上次导入未完成（${state.readText().trim()}），请重新导入")
        }
        val db = File(dir, DB_NAME)
        val keys = File(dir, KEYS_NAME)
        return if (db.isFile && keys.isFile) EhPhase.Ready(keys.length() / 12)
        else EhPhase.NotLoaded
    }

    fun statusText(): String {
        val dir = ehDir()
        val pkg = locatePackage()
        return "dir=${dir.path} exists=${dir.exists()} " +
            "db=${File(dir, DB_NAME).exists()} keys=${File(dir, KEYS_NAME).exists()} " +
            "pkg=${pkg?.path ?: "无"} status=${status()}"
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 全流程导入：解压+校验+原子改名+键索引构建。
     * @return keys.bin 写入的键数
     */
    fun import(pkg: File, onProgress: (stage: String, pct: Int) -> Unit, cancelled: () -> Boolean): Long {
        val dir = ehDir()
        dir.mkdirs()
        val tmp = File(dir, "tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpDb = File(tmp, DB_NAME)
        val tmpKeys = File(tmp, KEYS_NAME)

        var dbSize = 0L
        var keys = 0L
        try {
            // ---- 1) 读 meta + 解压（边解压边 sha256）
            var expectedSha = ""
            ZipFile(pkg).use { zf ->
                val metaEntry = zf.getEntry("meta.json")
                    ?: throw IllegalStateException("包内缺 meta.json")
                val meta = JSONObject(
                    zf.getInputStream(metaEntry).readBytes().decodeToString()
                )
                if (meta.optString("format") != FORMAT) {
                    throw IllegalStateException("包格式不支持: ${meta.optString("format")}（需要 $FORMAT）")
                }
                expectedSha = meta.optString("db_sha256", "")
                val dbEntry = zf.getEntry(DB_NAME)
                    ?: throw IllegalStateException("包内缺 $DB_NAME")
                val total = dbEntry.size
                var done = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                zf.getInputStream(dbEntry).use { raw ->
                    BufferedInputStream(raw, 1 shl 20).use { ins ->
                        FileOutputStream(tmpDb).use { out ->
                            val buf = ByteArray(1 shl 20)
                            var lastPct = -1
                            while (true) {
                                if (cancelled()) throw EhCancelledException()
                                val r = ins.read(buf)
                                if (r < 0) break
                                digest.update(buf, 0, r)
                                out.write(buf, 0, r)
                                done += r
                                if (total > 0) {
                                    val pct = (done * 40 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress("unzip", pct)
                                    }
                                }
                            }
                        }
                    }
                }
                dbSize = done
                if (total in 1 until done) throw IllegalStateException("解压尺寸异常: $done != $total")
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha.isNotEmpty() && !actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw IllegalStateException("校验失败: sha256 不匹配（包损坏或版本不符）")
                }
                onProgress("verify", 40)
            }

            // ---- 2) 从 tmp db 构建 keys.bin（此时正式目录还未被触碰）
            keys = EhIndexBuilder.build(
                tmpDb, tmpKeys, File(tmp, "chunks"),
                { p ->
                    val pct = if (p.totalTitles > 0L) {
                        45 + (p.titles * 55 / p.totalTitles).toInt()
                    } else 45
                    onProgress(if (p.stage == "merge") "index-merge" else "index", minOf(pct, 99))
                },
                cancelled,
            )

            // ---- 3) 原子替换（标记窗口：两次 rename 之间被杀 → status 报未完成）
            onProgress("install", 99)
            importStateFile().writeText("installing ${System.currentTimeMillis()}")
            val finalDb = File(dir, DB_NAME)
            val finalKeys = File(dir, KEYS_NAME)
            if (!tmpDb.renameTo(finalDb)) {
                tmpDb.copyTo(finalDb, overwrite = true)
                tmpDb.delete()
            }
            if (!tmpKeys.renameTo(finalKeys)) {
                tmpKeys.copyTo(finalKeys, overwrite = true)
                tmpKeys.delete()
            }
            // 包内 meta 落地（版本/校验和/计数留档）
            runCatching {
                ZipFile(pkg).use { zf ->
                    zf.getEntry("meta.json")?.let { e ->
                        File(dir, "meta.json").writeBytes(zf.getInputStream(e).readBytes())
                    }
                }
            }
            importStateFile().delete()
            // 生命周期：导入成功 → 功能启用（卸载时置 false）
            runCatching {
                CsSettings.setBool("eh_enabled", true)
                CsSettings.save()
            }
            onProgress("done", 100)
            return keys
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 卸载 = 删除整个 ehmeta 目录（对既有系统零残留） */
    fun uninstall() {
        ehDir().deleteRecursively()
        runCatching {
            CsSettings.setBool("eh_enabled", false)
            CsSettings.save()
        }
    }

    /** 匹配统计（读 match.db）：[匹配行数, gid 去重数]；无 match.db 返回 null */
    fun matchStats(): LongArray? {
        val f = File(ehDir(), "match.db")
        if (!f.isFile) return null
        return runCatching {
            android.database.sqlite.SQLiteDatabase
                .openDatabase(f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                .use { db ->
                    val rows = android.database.DatabaseUtils.longForQuery(db,
                        "SELECT COUNT(*) FROM matches", null)
                    val matched = android.database.DatabaseUtils.longForQuery(db,
                        "SELECT COUNT(*) FROM matches WHERE gid IS NOT NULL", null)
                    longArrayOf(rows, matched)
                }
        }.getOrNull()
    }

    /** ehmeta 目录占用（字节） */
    fun dirSizeBytes(): Long {
        val dir = ehDir()
        if (!dir.isDirectory) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    /** 各文件占用（展示用）：db / zip / keys / match / 其他 */
    fun sizeBreakdown(): List<Pair<String, Long>> {
        val dir = ehDir()
        val db = File(dir, DB_NAME); val zip = File(dir, ZIP_NAME)
        val keys = File(dir, KEYS_NAME); val match = File(dir, "match.db")
        val local = File(dir, "localstats.bin")
        var other = 0L
        dir.walkTopDown().forEach { f ->
            if (f.isFile && f != db && f != zip && f != keys && f != match && f != local) other += f.length()
        }
        return listOf(
            "数据包(zip)" to (if (zip.isFile) zip.length() else 0L),
            "元数据库" to (if (db.isFile) db.length() else 0L),
            "匹配索引(keys)" to (if (keys.isFile) keys.length() else 0L),
            "匹配结果" to (if (match.isFile) match.length() else 0L),
            "其他" to (other + if (local.isFile) local.length() else 0L),
        ).filter { it.second > 0 }
    }
}
