package com.comicshelf.app.ehmeta

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.Closeable
import java.io.File

/**
 * path ↔ gid 匹配结果存储（独立文件 ehmeta/match.db）。
 *
 * - path 为稳定键（设计定案：不依赖 core 的 book_id）；
 * - gid = NULL 表示"已尝试、未命中"（避免重复计算，P5）；
 * - 只写本文件，绝不写 library.db（与核心无写交叉；WAL 单写者 = 本类）。
 */
class EhMatchStore(dir: File) : Closeable {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(File(dir, "match.db"), null)

    private val ins: SQLiteStatement

    init {
        // 注意：Android 的 execSQL 不能执行【返回行】的 PRAGMA（journal_mode 会返回模式串，
        // 报 "Queries can be performed using query or rawQuery only"）→ 用专用 API/rawQuery。
        db.enableWriteAheadLogging()
        db.rawQuery("PRAGMA synchronous=NORMAL", null).use { it.moveToFirst() }
        // schema 迁移：两种历史形态互斥（见下）；不一致即重建（测试/开发期数据可重匹配）。
        // 实测：rowid 表（path 唯一索引 + gid 索引）与 WITHOUT ROWID 体积相当（二级索引
        // 内嵌 path），但 rowid 顺序插入更快（9.1s vs 12.6s / 48.7 万行）→ 采用 rowid 表。
        val oldSql = db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='matches'", null
        ).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
        if (oldSql.isNotEmpty() && oldSql.contains("WITHOUT ROWID", ignoreCase = true)) {
            db.execSQL("DROP TABLE IF EXISTS matches")
        }
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS matches(" +
                "path TEXT PRIMARY KEY, gid INTEGER, matched_at INTEGER)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_matches_gid ON matches(gid)")
        ins = db.compileStatement(
            "INSERT OR REPLACE INTO matches(path,gid,matched_at) VALUES(?,?,?)"
        )
    }

    fun begin() {
        db.beginTransaction()
    }

    fun commit() {
        db.setTransactionSuccessful()
        db.endTransaction()
    }

    /** gid < 0 → 存 NULL（未命中） */
    fun put(path: String, gid: Long, now: Long) {
        ins.bindString(1, path)
        if (gid >= 0) ins.bindLong(2, gid) else ins.bindNull(2)
        ins.bindLong(3, now)
        ins.execute()
    }

    /** 批量查已存在（分块 IN，避免逐条查询） */
    fun existingPaths(paths: List<String>): HashSet<String> {
        val out = HashSet<String>(paths.size * 2)
        var i = 0
        while (i < paths.size) {
            val end = minOf(i + 400, paths.size)
            val sb = StringBuilder("SELECT path FROM matches WHERE path IN (")
            for (j in i until end) {
                if (j > i) sb.append(',')
                sb.append('?')
            }
            sb.append(')')
            val args = Array(end - i) { paths[i + it] }
            db.rawQuery(sb.toString(), args).use { c ->
                while (c.moveToNext()) out.add(c.getString(0))
            }
            i = end
        }
        return out
    }

    fun countAll(): Long =
        android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM matches", null)

    fun countMatched(): Long =
        android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM matches WHERE gid IS NOT NULL", null)

    /** 导出全部结果（S1 对账用）：path, gid(NULL=-1) */
    fun all(): Cursor = db.rawQuery(
        "SELECT path, IFNULL(gid,-1) FROM matches ORDER BY path", null
    )

    fun clearAll() {
        db.execSQL("DELETE FROM matches")
    }

    /** 全量匹配后压缩（回收随机插入造成的页空洞；实测 220MB → ~90MB） */
    fun vacuum() {
        db.execSQL("VACUUM")
    }

    /**
     * 本地命中统计落盘（gid 升序 + 每 gid 路径数，紧凑二进制）→ 查询层秒开
     * 格式：[magic 'ELS1'][u64 gid LE][u32 cnt LE] × N（N 由文件长度推得）
     */
    fun writeLocalStats(out: File) {
        val tmp = File(out.parentFile, out.name + ".tmp")
        val rec = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        java.io.BufferedOutputStream(java.io.FileOutputStream(tmp), 1 shl 20).use { os ->
            os.write(byteArrayOf(0x45, 0x4C, 0x53, 0x31))   // "ELS1"
            db.rawQuery(
                "SELECT gid, COUNT(*) FROM matches WHERE gid IS NOT NULL GROUP BY gid ORDER BY gid",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    rec.putLong(0, c.getLong(0))
                    rec.putInt(8, c.getInt(1))
                    os.write(rec.array())
                }
            }
        }
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    override fun close() {
        runCatching { ins.close() }
        runCatching { db.close() }
    }
}
