package com.comicshelf.app.ehmeta

import android.util.Log
import com.comicshelf.app.util.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * EH 子系统运行时（S1 无 UI：调试驱动 / S3 设置页消费）。
 * - 独立单线程 MIN_PRIORITY 执行器（结构化保障"零性能损坏"）；
 * - 单实例串行（防与扫描/用户操作叠加）；
 * - StateFlow 状态暴露（S3 UI 直接消费）；
 * - 零启动触点：本 object 不引用任何启动路径；首次使用才初始化。
 */
object EhEngine {

    private const val TAG = "EhEngine"

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eh-meta").apply { priority = Thread.MIN_PRIORITY }
    }

    private val _phase = MutableStateFlow<EhPhase>(EhPhase.NotLoaded)
    val phase: StateFlow<EhPhase> = _phase

    @Volatile
    var cancelRequested = false
        private set

    /** 最近一次作业失败原因（UI 诚实展示；不清空旧数据的可用性） */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    fun clearLastError() { _lastError.value = null }

    @Volatile
    private var busy = false

    val isBusy: Boolean get() = busy

    fun setPhase(p: EhPhase) {
        _phase.value = p
    }

    fun refresh() {
        if (!busy) _phase.value = EhMetaManager.status()
    }

    fun cancel() {
        cancelRequested = true
    }

    /** 提交单实例作业；已有作业在跑时拒绝（返回 false） */
    fun submit(name: String, block: () -> Unit): Boolean {
        if (busy) return false
        busy = true
        cancelRequested = false
        _lastError.value = null
        exec.execute {
            try {
                block()
            } catch (e: EhCancelledException) {
                Log.i(TAG, "$name 已取消")
            } catch (t: Throwable) {
                Log.e(TAG, "$name 失败", t)
                // M12 失败语义：错误经 lastError 诚实展示，但不吞掉可用性
                _lastError.value = "$name 失败: ${t.message}"
            } finally {
                busy = false
                // 作业终结统一回落到磁盘真实状态（成功/取消/失败一致；
                // 旧数据完好时一次失败不会让入口消失）
                _phase.value = EhMetaManager.status()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- S2: tag 查询（懒开、可重置）

    /** 匹配代际：每次匹配完成后 +1 → 查询层本地统计按此失效重载 */
    @Volatile
    var matchVersion = 0
        private set

    @Volatile
    private var query: EhTagQuery? = null

    /** 数据就绪（db + keys + match.db 齐备）时返回查询单例（三只读连接懒开）；否则 null */
    fun tagQuery(): EhTagQuery? {
        val dir = EhMetaManager.ehDir()
        if (!File(dir, EhMetaManager.DB_NAME).isFile || !File(dir, "match.db").isFile) return null
        query?.let { return it }
        synchronized(this) {
            query?.let { return it }
            val q = runCatching {
                EhTagQuery(
                    File(dir, EhMetaManager.DB_NAME),
                    File(dir, "match.db"),
                    File(AppContextHolder.app.filesDir, "data/library.db"),
                )
            }.getOrNull()
            query = q
            return q
        }
    }

    /** 数据包变化（导入/卸载）后重置查询单例 */
    fun resetTagQuery() {
        synchronized(this) {
            runCatching { query?.close() }
            query = null
        }
    }

    // ---------------------------------------------------------------- 便捷作业（设置段/调试驱动共用）

    /** 导入数据包 + 建索引；成功后**自动触发一次匹配**（S2 发现：卸载/重导入会清空 match.db）。
     *  @return 启动是否被接受（false = 已有作业在跑） */
    fun startImport(onLog: (String) -> Unit = {}): Boolean = submit("import") {
        setPhase(EhPhase.Importing("start", 0))
        val pkg = EhMetaManager.locatePackage()
        if (pkg == null) {
            setPhase(EhPhase.NotLoaded)
            onLog("未找到数据包（候选目录: ${EhMetaManager.candidateDirs().joinToString { it.path }}）")
            return@submit
        }
        val keys = EhMetaManager.import(pkg, { stage, pct ->
            setPhase(EhPhase.Importing(stage, pct))
            onLog("import $stage $pct%")
        }) { cancelRequested }
        resetTagQuery()
        onLog("导入完成: ${pkg.name} → keys=$keys")
        refresh()
        // 导入后自动匹配（后台低优先级；可取消）：无匹配数据时 tag 视图/面板为空
        if (!cancelRequested) {
            runCatching { runMatchInner(onLog) }
        }
    }

    /** 全库匹配（增量）；@return 启动是否被接受 */
    fun startMatch(onLog: (String) -> Unit = {}): Boolean = submit("match") {
        runMatchInner(onLog)
    }

    /** 卸载（删除 ehmeta 目录） */
    fun startUninstall(onLog: (String) -> Unit = {}): Boolean = submit("uninstall") {
        EhMetaManager.uninstall()
        resetTagQuery()
        onLog("已卸载")
    }

    private fun runMatchInner(onLog: (String) -> Unit) {
        val dir = EhMetaManager.ehDir()
        val keysFile = File(dir, EhMetaManager.KEYS_NAME)
        if (!keysFile.isFile) throw IllegalStateException("keys.bin 不存在，请先导入")
        EhKeysIndex(keysFile).use { idx ->
            EhMatchStore(dir).use { store ->
                val libDb = File(AppContextHolder.app.filesDir, "data/library.db")
                val st = EhMatcher().run(libDb, idx, store, { done, total ->
                    setPhase(EhPhase.Matching(done, total))
                }, { cancelRequested })
                if (st.newlyMatched > 20_000) {
                    val t0 = System.currentTimeMillis()
                    runCatching { store.vacuum() }
                    Log.i(TAG, "match.db vacuum ${System.currentTimeMillis() - t0}ms")
                }
                runCatching {
                    store.writeLocalStats(File(dir, "localstats.bin"))
                }.onFailure { Log.w(TAG, "localstats 写入失败: ${it.message}") }
                matchVersion++      // 本地统计失效（tag 查询层下轮自载）
                onLog("match 完成: total=${st.total} matched=${st.matched} newly=${st.newlyMatched} " +
                    "wall=${st.elapsedMs}ms")
            }
        }
    }

    /** 定位并导入数据包（S1 调试驱动的同步版；不含自动匹配） */
    fun importAndIndex(onProgress: (String, Int) -> Unit): String {
        val pkg = EhMetaManager.locatePackage()
            ?: return "未找到数据包（候选目录: ${EhMetaManager.candidateDirs().joinToString { it.path }}）"
        val keys = EhMetaManager.import(pkg, onProgress) { cancelRequested }
        resetTagQuery()
        return "导入完成: ${pkg.name} → keys=$keys"
    }

    /** 全库匹配（S1 调试驱动的同步版） */
    fun matchLibrary(onProgress: (Long, Long) -> Unit): EhMatchStats {
        val dir = EhMetaManager.ehDir()
        val keysFile = File(dir, EhMetaManager.KEYS_NAME)
        if (!keysFile.isFile) throw IllegalStateException("keys.bin 不存在，请先导入")
        EhKeysIndex(keysFile).use { idx ->
            EhMatchStore(dir).use { store ->
                val libDb = File(AppContextHolder.app.filesDir, "data/library.db")
                val st = EhMatcher().run(libDb, idx, store, onProgress, { cancelRequested })
                if (st.newlyMatched > 20_000) {
                    runCatching { store.vacuum() }
                }
                runCatching { store.writeLocalStats(File(dir, "localstats.bin")) }
                matchVersion++
                return st
            }
        }
    }
}
