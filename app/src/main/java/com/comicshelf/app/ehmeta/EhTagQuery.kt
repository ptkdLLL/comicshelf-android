package com.comicshelf.app.ehmeta

import android.database.sqlite.SQLiteDatabase
import com.comicshelf.app.core.BookCell
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * EH tag 查询层（S2）——L1（本地命中）优先，按计划 P7 的 Kotlin 侧交集模型实现。
 *
 * 数据面（三连接，全部 OPEN_READONLY）：
 *  - ehmeta.db：tag / gt（tag→gid 倒排，含全站频次 cnt）
 *  - match.db：path↔gid（本子系统自产，可能被匹配作业并发写——WAL/journal 下点查安全）
 *  - library.db：books / book_meta（B1 只读直读，V4 探针已验证）
 *
 * 性能模型（避开跨库 SQL join；mmap/索引点查为主）：
 *  1) posting(rid)：tag→gid 升序数组（(rid,gid) 索引顺序扫描；LRU ≤6 条）
 *  2) 本地统计：一次 GROUP BY gid 扫描得 (gid, 路径数) 平行数组（会话缓存；匹配后失效）
 *  3) 交集 = 全部列表按大小升序 rarest-first 归并（含本地 gid 集）
 *  4) 结果 = 交集 ∩ 本地 → 按 gid 降序（新 gallery 在前）；计数=路径数前缀和（1:N 精确）
 *  5) 分页 = 前缀和二分定位 → 逐 gid 展开路径（ix_matches_gid）→ 逐 path 取行（ix_books_path）
 *
 * 纪律：绝不在 CoreDispatcher 上执行（书架分页红线）；亦不复用匹配执行器；
 * 查询串行于本类专属单线程执行器（毫秒级点查，无长事务）。
 */
class EhTagQuery(
    private val metaFile: File,
    private val matchFile: File,
    private val libFile: File,
) : Closeable {

    data class TagEntry(
        val rid: Long, val ns: String, val name: String,
        val nameZh: String?, val cnt: Long,
    )

    /** 一次 tag 集查询的全部结果（gid 降序 + 每 gid 的本地路径数 + 总路径数） */
    class Result(val gids: LongArray, val counts: IntArray, val total: Int) {
        val isEmpty: Boolean get() = total == 0
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eh-query").apply { priority = Thread.NORM_PRIORITY }
    }
    private val disp = exec.asCoroutineDispatcher()

    private val meta: SQLiteDatabase =
        SQLiteDatabase.openDatabase(metaFile.path, null, SQLiteDatabase.OPEN_READONLY)
    private val match: SQLiteDatabase =
        SQLiteDatabase.openDatabase(matchFile.path, null, SQLiteDatabase.OPEN_READONLY)
    private val lib: SQLiteDatabase =
        SQLiteDatabase.openDatabase(libFile.path, null, SQLiteDatabase.OPEN_READONLY)

    // ---- 会话缓存 ----
    private var localGids: LongArray = LongArray(0)
    private var localCounts: IntArray = IntArray(0)
    private var localVersion = -1
    private val postingCache = LinkedHashMap<Long, LongArray>(8, 0.75f, true)
    private val multiPathCache = HashMap<Long, Array<String>>()   // 多路径 gid 的路径展开（页面级）

    /** 版本容错：旧包（无 cnt 列）→ 频次按 0（列表退化按名称序，功能可用而非报错） */
    private val hasCnt: Boolean = runCatching {
        var found = false
        meta.rawQuery("PRAGMA table_info(tag)", null).use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "cnt") { found = true; break }
            }
        }
        found
    }.getOrDefault(false)
    private val cntCol: String get() = if (hasCnt) "IFNULL(cnt,0)" else "0"
    private val cntColT: String get() = if (hasCnt) "IFNULL(t.cnt,0)" else "0"

    private suspend fun <T> io(block: () -> T): T = withContext(disp) { block() }

    // ---------------------------------------------------------------- tag 字典（picker）

    fun nsLabel(ns: String): String = ehNsLabel(ns)

    /** (ns, 该 ns 的 tag 数)，按显示序 */
    suspend fun namespaces(): List<Pair<String, Long>> = io {
        val cnt = HashMap<String, Long>()
        meta.rawQuery("SELECT ns, COUNT(*) FROM tag GROUP BY ns", null).use { c ->
            while (c.moveToNext()) cnt[c.getString(0)] = c.getLong(1)
        }
        val out = ArrayList<Pair<String, Long>>(cnt.size)
        for ((ns, _) in EH_NS_ORDER) cnt[ns]?.let { out.add(ns to it) }
        for ((ns, n) in cnt) if (EH_NS_ORDER.none { it.first == ns }) out.add(ns to n)
        out
    }

    /** tag 列表（picker）：ns 过滤（null/"" = 全部）+ 名称模糊（原名/中文）+ 频次降序 */
    suspend fun tags(ns: String?, query: String?, limit: Int = 300): List<TagEntry> = io {
        val sb = StringBuilder(
            "SELECT rid, ns, name, IFNULL(name_zh,''), $cntCol FROM tag"
        )
        val args = ArrayList<String>()
        val where = ArrayList<String>()
        val q = query?.trim()
        // 搜索存在时跨命名空间（ns 过滤仅在浏览态生效：避免"在语言 ns 下搜 female tag 得 0"的困惑）
        if (q.isNullOrEmpty() && !ns.isNullOrEmpty()) { where.add("ns=?"); args.add(ns) }
        if (!q.isNullOrEmpty()) {
            where.add("(name LIKE ? OR IFNULL(name_zh,'') LIKE ?)")
            args.add("%$q%"); args.add("%$q%")
        }
        if (where.isNotEmpty()) sb.append(" WHERE ").append(where.joinToString(" AND "))
        // 频次降序（旧包无 cnt → 退化为名称序；注意 "ORDER BY 0" 非法，须换整个子句）
        sb.append(" ORDER BY ").append(if (hasCnt) "$cntCol DESC, name" else "name")
            .append(" LIMIT ").append(limit.coerceIn(1, 2000))
        val out = ArrayList<TagEntry>(minOf(limit, 512))
        meta.rawQuery(sb.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(TagEntry(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3).ifEmpty { null }, c.getLong(4)))
            }
        }
        out
    }

    /** 按 rid 取若干 tag（已选 chips / 面板跳转用） */
    suspend fun tagsByRid(rids: LongArray): List<TagEntry> = io {
        val out = ArrayList<TagEntry>(rids.size)
        for (r in rids) {
            meta.rawQuery(
                "SELECT rid, ns, name, IFNULL(name_zh,''), $cntCol FROM tag WHERE rid=?",
                arrayOf(r.toString()),
            ).use { c ->
                if (c.moveToFirst()) out.add(TagEntry(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3).ifEmpty { null }, c.getLong(4)))
            }
        }
        out
    }

    // ---------------------------------------------------------------- L1 结果集

    /** 匹配代际（EhEngine 在匹配完成后 +1）→ 本地统计失效 */
    fun invalidateLocal() {
        localVersion = -1
        multiPathCache.clear()
    }

    /** 计算 tag 集（AND）的本地结果：交集 → 本地 → gid 降序 + 路径数前缀（1:N 精确） */
    suspend fun result(rids: LongArray, version: Int): Result = io {
        ensureLocal(version)
        if (rids.isEmpty() || localGids.isEmpty()) return@io Result(LongArray(0), IntArray(0), 0)
        // rarest-first：全部列表（各 tag posting + 本地 gid 集）按大小升序归并求交
        val lists = ArrayList<LongArray>(rids.size + 1)
        for (r in rids) lists.add(posting(r))
        lists.add(localGids)
        lists.sortBy { it.size }
        var acc = lists[0]
        for (i in 1 until lists.size) {
            acc = intersect(acc, lists[i])
            if (acc.isEmpty()) return@io Result(LongArray(0), IntArray(0), 0)
        }
        // 计数：本地 gid → 路径数（平行数组二分）
        val gidsAsc = acc
        val countsAsc = IntArray(gidsAsc.size)
        var total = 0
        for (i in gidsAsc.indices) {
            val c = localCountOf(gidsAsc[i])
            countsAsc[i] = c
            total += c
        }
        // 显示序 = gid 降序（新在前）
        val n = gidsAsc.size
        val gids = LongArray(n)
        val counts = IntArray(n)
        for (i in 0 until n) {
            gids[i] = gidsAsc[n - 1 - i]
            counts[i] = countsAsc[n - 1 - i]
        }
        Result(gids, counts, total)
    }

    /** 一页书架行（≤ limit 个路径展开为 BookCell；多路径 gid 全部展开） */
    suspend fun page(res: Result, offset: Int, limit: Int): List<BookCell> = io {
        if (offset >= res.total) return@io emptyList()
        val out = ArrayList<BookCell>(limit + 4)
        // 定位起始 (gid 下标, 该 gid 内已跳过的路径数)
        var acc = 0
        var gi = 0
        while (gi < res.gids.size && acc + res.counts[gi] <= offset) {
            acc += res.counts[gi]
            gi++
        }
        var skip = offset - acc
        while (gi < res.gids.size && out.size < limit) {
            val gid = res.gids[gi]
            val paths = pathsOfGid(gid)          // 索引点查（含多路径）
            var k = skip
            while (k < paths.size && out.size < limit) {
                rowOfPath(paths[k])?.let { out.add(it) }
                k++
            }
            skip = 0
            gi++
        }
        out
    }

    // ---------------------------------------------------------------- 书籍面板

    /** 一本书的 E-Hentai 匹配信息（gid / 图库标题 / 全部 tag） */
    data class BookEhInfo(val gid: Long, val ehTitle: String?, val tags: List<TagEntry>)

    suspend fun pathOfBookId(bookId: Long): String? = io {
        lib.rawQuery("SELECT path FROM books WHERE id=?", arrayOf(bookId.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    /** 一本书（按 path）的匹配 gid 与其全部 EH tag */
    suspend fun bookInfo(path: String): BookEhInfo = io {
        var gid = -1L
        match.rawQuery("SELECT IFNULL(gid,-1) FROM matches WHERE path=?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) gid = c.getLong(0)
        }
        if (gid < 0) return@io BookEhInfo(-1, null, emptyList())
        var ehTitle: String? = null
        meta.rawQuery("SELECT text FROM title WHERE gid=? LIMIT 1", arrayOf(gid.toString())).use { c ->
            if (c.moveToFirst()) ehTitle = c.getString(0)
        }
        val out = ArrayList<TagEntry>(32)
        meta.rawQuery(
            "SELECT t.rid, t.ns, t.name, IFNULL(t.name_zh,''), $cntColT " +
                "FROM gt g JOIN tag t ON t.rid=g.rid WHERE g.gid=?",
            arrayOf(gid.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(TagEntry(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3).ifEmpty { null }, c.getLong(4)))
            }
        }
        // 排序：命名空间显示序 + 频次降序
        val nsIdx = HashMap<String, Int>(EH_NS_ORDER.size * 2)
        EH_NS_ORDER.forEachIndexed { i, p -> nsIdx[p.first] = i }
        out.sortWith(compareBy({ nsIdx[it.ns] ?: 99 }, { -it.cnt }))
        BookEhInfo(gid, ehTitle, out)
    }

    // ---------------------------------------------------------------- 内部

    private fun ensureLocal(version: Int) {
        if (version == localVersion && localGids.isNotEmpty()) return
        // 快路径：localstats.bin（匹配作业尾部落盘；mtime ≥ match.db 才可信）
        tryLoadLocalStats()?.let { (g, c) ->
            localGids = g
            localCounts = c
            localVersion = version
            multiPathCache.clear()
            return
        }
        val g = ArrayList<Long>(512); val c = ArrayList<Int>(512)
        match.rawQuery(
            "SELECT gid, COUNT(*) FROM matches WHERE gid IS NOT NULL GROUP BY gid ORDER BY gid",
            null,
        ).use { cur ->
            while (cur.moveToNext()) { g.add(cur.getLong(0)); c.add(cur.getInt(1)) }
        }
        localGids = g.toLongArray()
        localCounts = c.toIntArray()
        localVersion = version
        multiPathCache.clear()
    }

    /** 读 localstats.bin（[magic 'ELS1'][u64 gid LE][u32 cnt LE] ×N） */
    private fun tryLoadLocalStats(): Pair<LongArray, IntArray>? = runCatching {
        val f = File(matchFile.parentFile, "localstats.bin")
        if (!f.isFile || f.lastModified() < matchFile.lastModified()) return null
        val bytes = f.readBytes()
        if (bytes.size < 4 || bytes[0] != 0x45.toByte()) return null
        val n = (bytes.size - 4) / 12
        if (n == 0) return null
        val g = LongArray(n)
        val c = IntArray(n)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            g[i] = bb.getLong(4 + i * 12)
            c[i] = bb.getInt(4 + i * 12 + 8)
        }
        g to c
    }.getOrNull()

    private fun localCountOf(gid: Long): Int {
        val i = java.util.Arrays.binarySearch(localGids, gid)
        return if (i >= 0) localCounts[i] else 0
    }

    private fun posting(rid: Long): LongArray {
        postingCache[rid]?.let { return it }
        val out = ArrayList<Long>(256)
        meta.rawQuery("SELECT gid FROM gt WHERE rid=? ORDER BY gid", arrayOf(rid.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        val arr = out.toLongArray()
        postingCache[rid] = arr
        if (postingCache.size > 6) {
            val it = postingCache.keys.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
        return arr
    }

    private fun intersect(a: LongArray, b: LongArray): LongArray {
        var i = 0; var j = 0
        val out = ArrayList<Long>(minOf(a.size, b.size))
        while (i < a.size && j < b.size) {
            val x = a[i]; val y = b[j]
            when {
                x < y -> i++
                x > y -> j++
                else -> { out.add(x); i++; j++ }
            }
        }
        return out.toLongArray()
    }

    private fun pathsOfGid(gid: Long): Array<String> {
        multiPathCache[gid]?.let { return it }
        val out = ArrayList<String>(2)
        match.rawQuery("SELECT path FROM matches WHERE gid=?", arrayOf(gid.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        val arr = out.toTypedArray()
        if (multiPathCache.size > 512) multiPathCache.clear()
        multiPathCache[gid] = arr
        return arr
    }

    /** 单行书架数据（含 book_meta 徽标字段） */
    private fun rowOfPath(path: String): BookCell? {
        lib.rawQuery(
            "SELECT b.id, b.title, IFNULL(bm.favorite,0), IFNULL(bm.read_state,0), " +
                "IFNULL(bm.last_page,0), b.pages " +
                "FROM books b LEFT JOIN book_meta bm ON bm.book_id=b.id WHERE b.path=?",
            arrayOf(path),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BookCell(c.getLong(0), c.getString(1), c.getLong(2) != 0L,
                c.getInt(3), c.getInt(4), c.getInt(5))
        }
    }

    override fun close() {
        runCatching { meta.close() }
        runCatching { match.close() }
        runCatching { lib.close() }
        runCatching { exec.shutdownNow() }
    }
}
