package com.comicshelf.app.ehmeta

import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * 书架书籍来源：Kotlin 【只读】打开核心的 library.db 取 (id, lib_id, path, title)。
 *
 * ⚠️ 边界声明：这是全工程 Kotlin 侧此前为零的 library.db 直读（静态分析 B1，
 * 新边界，需动态验证 V4）。使用纪律：
 *  - 仅 OPEN_READONLY + query_only；绝不写、绝不建表、绝不 VACUUM；
 *  - 连接只在本类生命周期内使用；核心（native）仍是唯一写者；
 *  - core 的 journal_mode=WAL（database.cpp:210）允许并发读，读不阻塞写者。
 *
 * 若 V4 不达标 → 回退路径：page() 分窗枚举 + book_id 键（设计为此预留了改动面，
 * 见 EHENTAI_APP_STATIC_ANALYSIS.md §6 V4）。
 */
class EhBookSource(dbFile: File) : Closeable {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)

    init {
        // query_only 防御（PRAGMA 返回值行 → 必须 rawQuery；见 EhMatchStore 注释）
        runCatching { db.rawQuery("PRAGMA query_only=ON", null).use { it.moveToFirst() } }
    }

    fun totalBooks(): Long =
        DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM books", null)

    /** 逐本遍历（id, lib_id, path, title） */
    fun booksCursor(): Cursor =
        db.rawQuery("SELECT id, lib_id, path, title FROM books", null)

    /** 按 path 升序（id, path, title）——供与 match 结果流式归并连接 */
    fun booksCursorByPath(): Cursor =
        db.rawQuery("SELECT id, path, title FROM books ORDER BY path", null)

    override fun close() {
        runCatching { db.close() }
    }
}
