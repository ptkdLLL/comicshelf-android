package com.comicshelf.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * All short native metadata calls run here, one at a time — the same
 * confinement the Windows app got for free from its single UI thread.
 */
val CoreDispatcher = Executors.newSingleThreadExecutor { r ->
    Thread(r, "cs-core").apply { priority = Thread.NORM_PRIORITY + 1 }
}.asCoroutineDispatcher()

/** Long-running native work (page reads, decode) may use plain IO threads. */
val PageDispatcher = Dispatchers.IO

// ---------------------------------------------------------------- dtos

data class LibraryRow(val id: Long, val root: String, val name: String,
                      val count: Long, val lastScan: Long) {
    override fun toString(): String = name
}

data class DirRow(val id: Long, val name: String, val rel: String, val parent: String,
                  val count: Long, val total: Long, val depth: Int)

data class BookCell(val id: Long, val title: String, val favorite: Boolean,
                    val readState: Int, val lastPage: Int, val pages: Int)

data class BookmarkRow(val id: Long, val bookId: Long, val title: String,
                       val page: Int, val label: String)

object Json {
    fun libraries(s: String): List<LibraryRow> {
        val out = ArrayList<LibraryRow>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(LibraryRow(o.getLong("id"), o.getString("root"), o.getString("name"),
                               o.getLong("count"), o.optLong("lastScan")))
        }
        return out
    }

    fun dirs(s: String): List<DirRow> {
        val out = ArrayList<DirRow>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(DirRow(o.getLong("id"), o.getString("name"), o.getString("rel"),
                           o.optString("parent"), o.getLong("count"), o.getLong("total"),
                           o.optInt("depth")))
        }
        return out
    }

    fun bookmarks(s: String): List<BookmarkRow> {
        val out = ArrayList<BookmarkRow>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            out.add(BookmarkRow(o.getLong("id"), o.getLong("bookId"), o.optString("title"),
                                o.optInt("page"), o.optString("label")))
        }
        return out
    }

    fun strings(s: String): List<String> {
        val out = ArrayList<String>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }
}

fun parsePageBundle(b: Array<Any?>?): List<BookCell> {
    if (b == null || b.size < 6) return emptyList()
    val ids = b[0] as LongArray
    val titles = b[1] as Array<String>
    val fav = b[2] as IntArray
    val rs = b[3] as IntArray
    val lp = b[4] as IntArray
    val pg = b[5] as IntArray
    val out = ArrayList<BookCell>(ids.size)
    for (i in ids.indices) {
        out.add(BookCell(ids[i], titles[i], fav[i] != 0, rs[i], lp[i], pg[i]))
    }
    return out
}

fun parseImageBundle(b: Array<Any?>?): Pair<IntArray, ByteArray>? {
    if (b == null || b.size < 2) return null
    val dims = b[0] as? IntArray ?: return null
    val px = b[1] as? ByteArray ?: return null
    return dims to px
}

// ---------------------------------------------------------------- settings facade

/** Mirrors config.ini on the native side so both sides agree on one store. */
object CsSettings {
    // Direct JNI calls: the native settings map is tiny and the bridge mutex
    // makes them thread-safe. (Never wrap these in runBlocking(CoreDispatcher)
    // — calling that from inside CoreDispatcher would deadlock the single
    // thread by waiting on itself.)
    operator fun get(key: String, def: String): String = NativeBridge.settingsGet(key, def)

    fun int(key: String, def: Int) = get(key, def.toString()).toIntOrNull() ?: def
    fun bool(key: String, def: Boolean) = get(key, def.toString()).toBoolean()
    fun float(key: String, def: Float) = get(key, def.toString()).toFloatOrNull() ?: def

    fun set(key: String, v: String) = NativeBridge.settingsSet(key, v)
    fun setInt(key: String, v: Int) = set(key, v.toString())
    fun setBool(key: String, v: Boolean) = set(key, v.toString())
    fun save() = NativeBridge.settingsSave()
}
