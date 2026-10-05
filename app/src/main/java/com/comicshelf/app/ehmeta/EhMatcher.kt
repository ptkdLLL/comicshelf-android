package com.comicshelf.app.ehmeta

import java.io.File

/**
 * 全库匹配器（P5 生产版）：书架（library.db 只读枚举）→ v3 匹配 → match 存储。
 *
 * 并发纪律（红线）：本类【绝不在 CoreDispatcher 上执行】——由 EhEngine 的独立
 * 单线程低优先级执行器驱动（否则前台书架分页排队卡死，Core.kt:7-13）。
 * 增量语义：match 表已有的 path 跳过（含未命中 NULL 记录）；可取消/续跑。
 */
class EhMatcher {

    class Breakdown(
        var readMs: Long = 0,     // library.db 游标读取
        var existMs: Long = 0,    // 已匹配集合查询
        var matchMs: Long = 0,    // v3 匹配
        var writeMs: Long = 0,    // match.db 写入+提交
    )

    fun run(
        libraryDb: File,
        index: EhKeysIndex,
        store: EhMatchStore,
        onProgress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean,
        bd: Breakdown = Breakdown(),
    ): EhMatchStats {
        val t0 = System.currentTimeMillis()
        val probe: (String) -> Int = { s -> index.searchKey(s) }
        var total = 0L
        var newly = 0L
        var newlyMatched = 0L

        EhBookSource(libraryDb).use { src ->
            total = src.totalBooks()
            onProgress(0, total)
            val now = System.currentTimeMillis() / 1000
            val hasExisting = store.countAll() > 0     // 空库全量匹配时免存在性查询
            src.booksCursor().use { c ->
                val paths = ArrayList<String>(2048)
                val titles = ArrayList<String>(2048)
                var done = 0L

                fun flush() {
                    if (paths.isEmpty()) return
                    var t = System.currentTimeMillis()
                    val existing = if (hasExisting) store.existingPaths(paths) else emptySet<String>()
                    bd.existMs += System.currentTimeMillis() - t
                    val idsToWrite = ArrayList<String>(paths.size)
                    val gidsToWrite = ArrayList<Long>(paths.size)
                    t = System.currentTimeMillis()
                    for (i in paths.indices) {
                        val p = paths[i]
                        if (p in existing) continue
                        idsToWrite.add(p)
                        gidsToWrite.add(EhV3.match(titles[i], probe).toLong())
                    }
                    bd.matchMs += System.currentTimeMillis() - t
                    if (idsToWrite.isNotEmpty()) {
                        t = System.currentTimeMillis()
                        store.begin()
                        try {
                            for (i in idsToWrite.indices) {
                                store.put(idsToWrite[i], gidsToWrite[i], now)
                                newly++
                                if (gidsToWrite[i] >= 0) newlyMatched++
                            }
                            store.commit()
                        } catch (t2: Throwable) {
                            // 提交已完成的部分（保留进度），再上抛
                            runCatching { store.commit() }
                            throw t2
                        }
                        bd.writeMs += System.currentTimeMillis() - t
                    }
                    paths.clear()
                    titles.clear()
                }

                var t = System.currentTimeMillis()
                while (c.moveToNext()) {
                    if (cancelled()) {
                        flush()
                        throw EhCancelledException()
                    }
                    paths.add(c.getString(2))
                    titles.add(c.getString(3))
                    done++
                    if (paths.size >= 2048) {
                        bd.readMs += System.currentTimeMillis() - t
                        flush()
                        onProgress(done, total)
                        t = System.currentTimeMillis()
                    }
                }
                bd.readMs += System.currentTimeMillis() - t
                flush()
                onProgress(done, total)
            }
        }
        val elapsed = System.currentTimeMillis() - t0
        return EhMatchStats(total, store.countMatched(), newlyMatched, elapsed, bd)
    }
}
