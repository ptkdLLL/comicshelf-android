package com.comicshelf.app.ehmeta

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * ehmeta.db 的 gid 存在性索引（只读；与 EhTagQuery/EhBookSource 同规）。
 *
 * 用途（L2 门控下的 EH 档直配）：书名 gidFromName 候选 → 校验 gallery 主键命中。
 * 纪律：只读连接 + query_only；无写、无建表；连接只在本类生命周期内使用。
 */
class EhGidIndex(dbFile: File) : Closeable {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

    private val stmt = db.compileStatement("SELECT 1 FROM gallery WHERE gid = ?")

    init {
        runCatching { db.rawQuery("PRAGMA query_only=ON", null).use { it.moveToFirst() } }
    }

    /** gid 是否在库（gallery 主键命中）。 */
    fun exists(gid: Int): Boolean = runCatching {
        stmt.clearBindings()
        stmt.bindLong(1, gid.toLong())
        stmt.simpleQueryForLong() == 1L
    }.getOrDefault(false)

    override fun close() {
        runCatching { stmt.close(); db.close() }
    }
}
