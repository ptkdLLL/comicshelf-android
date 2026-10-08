package com.comicshelf.app.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.paging.cachedIn
import com.comicshelf.app.core.BookCell
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.CsSettings
import com.comicshelf.app.core.DirRow
import com.comicshelf.app.reader.BookTranslateJob
import com.comicshelf.app.core.Json
import com.comicshelf.app.core.LibraryRow
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.core.SmbCreds
import com.comicshelf.app.core.parsePageBundle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sort order mirrors cs::SortKey on the native side. */
enum class ShelfSort(val key: Int, val label: String) {
    ADDED(0, "添加时间"), TITLE(1, "标题"), PATH(2, "路径"),
    SIZE(3, "大小"), MTIME(4, "修改时间"), PAGES(5, "页数"),
    LAST_READ(6, "最近阅读");
}

data class ShelfQuery(
    val libId: Long,
    val search: String,
    val sort: ShelfSort,
    val desc: Boolean,
    val dirRel: String,     // "" = whole library
    val recursive: Boolean,
    val favOnly: Boolean,
    val readState: Int,     // -1 any, 0 unread, 1 reading, 2 finished, -2 读过（浏览历史哨兵）
    val history: Boolean = false,   // v0.5.3 浏览历史模式（跨库/50 帽/退出还原；见 BROWSE_HISTORY_PLAN.md）
)

/** One cell of the virtualized cover wall. */
data class ScanProgressRow(
    val running: Boolean, val paused: Boolean, val libId: Long,
    val seen: Long, val added: Long, val updated: Long, val removed: Long,
    val dirs: Long = 0,
)

/**
 * The sliding-window pager: Paging 3 asks for windows around the scroll
 * position, each load is one indexed SQL `page_books` call — the same
 * 2048-row-window architecture that kept the Windows app flat at a million
 * books, now feeding LazyVerticalGrid.
 */
/** v0.5.3 浏览历史上限（计划 P-H7；UI 文案同源引用）。 */
internal const val HISTORY_MAX = 50L

class ShelfPagingSource(private val q: ShelfQuery) : PagingSource<Long, BookCell>() {

    // Offset-based keys are directly jumpable (jumpingSupported) — this is what
    // lets the scrollbar jump into a million-row library without full loads.
    override val jumpingSupported: Boolean get() = true

    override fun getRefreshKey(state: PagingState<Long, BookCell>): Long? {
        val anchor = state.anchorPosition ?: return 0L
        return maxOf(0L, anchor - state.config.initialLoadSize / 2L)
    }

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, BookCell> =
        withContext(CoreDispatcher) {
            try {
                val offset = params.key ?: 0L
                val rawLimit = params.loadSize
                val totalRaw = NativeBridge.count(q.libId, q.search, q.dirRel, q.recursive,
                                                  q.favOnly, q.readState)
                // v0.5.3 浏览历史：50 上限必须**同时**夹 total（placeholders 的 itemCount 源，
                // 决定滚动条/占位）与单页 limit（P-H7 / G-H1），否则第 51 本会从占位里露出来。
                val total = if (q.history) minOf(totalRaw, HISTORY_MAX) else totalRaw
                val limit = if (q.history)
                    minOf(rawLimit.toLong(), (HISTORY_MAX - offset).coerceAtLeast(0L)).toInt()
                else rawLimit
                val rows = if (limit <= 0) emptyList<BookCell>() else parsePageBundle(
                    NativeBridge.page(q.libId, q.search, q.sort.key, q.desc,
                                      offset, limit, q.dirRel, q.recursive, q.favOnly, q.readState))
                android.util.Log.i("ShelfPaging",
                    "load lib=${q.libId} off=$offset lim=$limit total=$total " +
                    "hist=${q.history} rows=${rows.size}")
                val next = if (offset + rows.size < total) offset + rows.size else null
                val prev = if (offset > 0) maxOf(0L, offset - limit) else null
                // S-2：喂给 Paging 的前后计数（placeholders 的 itemCount 推导），
                // 配合 jumpingThreshold 让深跳只加载目标窗；Long→Int 按纪律夹紧。
                val itemsBefore = offset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val itemsAfter = (total - offset - rows.size)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                LoadResult.Page(rows, prev, next, itemsBefore, itemsAfter)
            } catch (t: Throwable) {
                android.util.Log.e("ShelfPaging", "load failed", t)
                LoadResult.Error(t)
            }
        }
}

class ShelfViewModel : ViewModel() {

    // ---- libraries ---------------------------------------------------------
    private val _libraries = MutableStateFlow<List<LibraryRow>>(emptyList())
    val libraries: StateFlow<List<LibraryRow>> = _libraries.asStateFlow()

    // ---- query state -------------------------------------------------------
    val query = MutableStateFlow(
        ShelfQuery(0, "", ShelfSort.ADDED, true, "", true, false, -1))
    val queryVersion = MutableStateFlow(0)   // bumped to invalidate the pager

    val books: Flow<PagingData<BookCell>> = query.flatMapLatest { q ->
        Pager(PagingConfig(
            pageSize = 120,
            initialLoadSize = 240,
            prefetchDistance = 48,
            // 滚动条跳转（S-1/S-2）：placeholders 让 itemCount=全库数（LoadResult.Page
            // 携带 itemsBefore/After），配合 jumpingSupported/jumpThreshold 实现
            // "丢弃中间页、直载目标窗"；maxSize 上界防跳来跳去时已加载页无限积累。
            enablePlaceholders = true,
            maxSize = 2048,
            jumpThreshold = 240,
        )) { ShelfPagingSource(q) }.flow
    }.cachedIn(viewModelScope)

    // ---- scan progress -----------------------------------------------------
    private val _scan = MutableStateFlow<ScanProgressRow?>(null)
    val scan: StateFlow<ScanProgressRow?> = _scan.asStateFlow()

    /** L3：一次性用户提示（Screen 弹 Toast 后调 consumeNotice 置空）。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun consumeNotice() { _notice.value = null }

    /** L3：最近一次「添加书库」的库 id——首扫完成时用于判断是否 0 本（跨线程读写）。 */
    @Volatile private var firstScanLibId = 0L

    /**
     * 书架网格的滚动状态提升到 ViewModel：进阅读器时书架会被移出组合，
     * 若状态留在 composable 里就会丢，回来时回到目录开头。放在 VM 里
     * 就能原样恢复（同一个 state 对象跨越屏幕切换）。
     */
    val gridState = LazyGridState()

    /** 每个"库+目录+搜索+排序/过滤"各记一份位置；切目录再切回来也能回到原处。 */
    private val scrollMemory = HashMap<String, Pair<Int, Int>>()
    private val scrollRestoredKeys = HashSet<String>()

    private fun queryKey(): String {
        val q = query.value
        return buildString {
            append(q.libId).append('|').append(q.dirRel).append('|').append(q.search)
            append('|').append(q.sort).append('|').append(q.desc)
            append('|').append(q.recursive).append('|').append(q.favOnly)
            append('|').append(q.readState).append('|').append(q.history)
        }
    }

    fun savedScroll(): Pair<Int, Int>? = scrollMemory[queryKey()]
    fun noteScroll(index: Int, offset: Int) { scrollMemory[queryKey()] = index to offset }
    fun scrollRestored(): Boolean = queryKey() in scrollRestoredKeys
    fun markScrollRestored() { scrollRestoredKeys.add(queryKey()) }

    /** 读完书回到书架：刷新一下阅读徽标/页数（滚动位置由 gridState 保持）。 */
    fun refreshAfterRead() { queryVersion.value++ }

    init {
        refreshLibraries()
        pollScan()
    }

    fun refreshLibraries() {
        viewModelScope.launch(CoreDispatcher) {
            val libs = Json.libraries(NativeBridge.libraries())
            _libraries.value = libs
            // Default to the library that actually holds books.
            if (query.value.libId == 0L && libs.isNotEmpty()) {
                val best = libs.maxByOrNull { it.count } ?: libs.first()
                setLibrary(best.id)
            }
        }
    }

    fun setLibrary(id: Long) {
        leaveHistoryRestoring()
        query.value = query.value.copy(libId = id, dirRel = "")
        queryVersion.value++
    }

    fun setSearch(s: String) {
        leaveHistoryRestoring()
        query.value = query.value.copy(search = s)
        queryVersion.value++
    }

    fun setSort(sort: ShelfSort, desc: Boolean) {
        leaveHistoryRestoring()
        query.value = query.value.copy(sort = sort, desc = desc)
        queryVersion.value++
    }

    fun setDir(rel: String) {
        leaveHistoryRestoring()
        query.value = query.value.copy(dirRel = rel)
        queryVersion.value++
    }

    fun setRecursive(r: Boolean) {
        leaveHistoryRestoring()
        query.value = query.value.copy(recursive = r)
        queryVersion.value++
    }

    fun setFilters(favOnly: Boolean, readState: Int) {
        leaveHistoryRestoring()
        query.value = query.value.copy(favOnly = favOnly, readState = readState)
        queryVersion.value++
    }

    // ---- v0.5.3 浏览历史（进入即快照、退出即原样还原；任何其它参数变更自动退出）----
    // 修复（docs/BROWSE_HISTORY_FIX_PLAN.md）：原 P-H6 伪代码只在 setter 里清 history 标志、
    // 未还原 setHistory 覆盖的哨兵字段（readState=-2 / sort=LAST_READ）→ 点书库后"只读过的"过滤
    // 泄漏到普通视图且需冷启动才消。现改为统一"先还原快照、再应用本次变更"。

    private var preHistory: ShelfQuery? = null

    /**
     * 退出历史并原样还原进入前的查询（幂等；六条 setter 与 exitHistory 共用）。
     * 消费后 preHistory 置空——避免"泄漏态再进历史"时把被污染的查询当快照（二次陷阱）。
     * @return 是否发生了一次真实退出（供 exitHistory 决定是否 bump queryVersion）
     */
    private fun leaveHistoryRestoring(): Boolean {
        if (!query.value.history) return false
        query.value = preHistory ?: query.value.copy(
            history = false, libId = 0, sort = ShelfSort.ADDED, desc = true,
            readState = -1, dirRel = "", search = "")
        preHistory = null
        return true
    }

    /** 进入跨库"浏览历史"：lib=-1（全部库）/按 last_read_at 倒序/仅读过/50 帽（P-H4/H7）。
     *  recursive 必须 true：dirRel 空 + recursive=false 会被 make_filter 解释为"仅库根"，
     *  子目录里的书会整批消失（动态阶段 G-H5 实测抓到：bb/ 下 4 本被滤）。 */
    fun setHistory() {
        if (query.value.history) return
        preHistory = query.value
        query.value = ShelfQuery(
            libId = -1, search = "", sort = ShelfSort.LAST_READ, desc = true,
            dirRel = "", recursive = true, favOnly = false, readState = -2, history = true)
        queryVersion.value++
    }

    /** 退出浏览历史：原样还原进入前的查询（滚动位分账——qKey 含 history）。 */
    fun exitHistory() {
        if (leaveHistoryRestoring()) queryVersion.value++
    }

    /** 翻译是手动、按书启用的（默认全关）。backend 模式：启用即启动整本后台翻译队列；
     *  pageCount<=0（多选批量启用）只写标记，打开书时再起队列。 */
    fun setBookTranslate(bookId: Long, on: Boolean, pageCount: Int = 0, startPage: Int = 0) =
        viewModelScope.launch(CoreDispatcher) {
            NativeBridge.setBookTranslateEnabled(bookId, on)
            if (CsSettings.get("translate_engine", "sidecar") == "backend") {
                if (on && pageCount > 0) {
                    BookTranslateJob.ensureStarted(bookId, pageCount, startPage.coerceAtLeast(0))
                } else if (!on) {
                    if (BookTranslateJob.state.value.bookId == bookId) BookTranslateJob.cancel()
                }
            }
        }

    suspend fun bookTranslateEnabled(bookId: Long): Boolean = withContext(CoreDispatcher) {
        NativeBridge.getBookTranslateEnabled(bookId)
    }

    suspend fun childDirs(parentRel: String): List<DirRow> = withContext(CoreDispatcher) {
        Json.dirs(NativeBridge.childDirs(query.value.libId, parentRel))
    }

    fun addLibrary(path: String) {
        viewModelScope.launch(CoreDispatcher) {
            val id = NativeBridge.addLibrary(path, scan = true)
            if (id > 0) {
                firstScanLibId = id                       // L3：等这次首扫结果
                refreshLibraries()
                setLibrary(id)
            }
        }
    }

    /** "" = 成功；否则返回错误文本（表单内展示）。 */
    suspend fun smbProbe(host: String, share: String, user: String, pass: String,
                         domain: String): String = withContext(CoreDispatcher) {
        NativeBridge.smbProbe(host, share, user, pass, domain)
    }

    /**
     * 添加 SMB 书库：先注册凭据 → 探测连接 → 入库并开始扫描。
     * 参数都来自“添加 SMB 书库”对话框，root 形如
     * smb://host[:port]/share[/子目录]。
     */
    suspend fun addSmbLibrary(host: String, share: String, sub: String, user: String,
                              pass: String, domain: String): String {
        val h = host.trim()
        val sh = share.trim().trim('/')
        if (h.isEmpty() || sh.isEmpty()) return "请填写主机和共享名"
        val err = withContext(CoreDispatcher) { NativeBridge.smbProbe(h, sh, user, pass, domain) }
        if (err.isNotEmpty()) return err
        val root = buildString {
            append("smb://").append(h).append('/').append(sh)
            val s = sub.trim().trim('/')
            if (s.isNotEmpty()) append('/').append(s)
        }
        SmbCreds.add(SmbCreds.Cfg(h, sh, user, pass, domain))
        val id = withContext(CoreDispatcher) { NativeBridge.addLibrary(root, scan = true) }
        if (id <= 0) return "书库写入失败"
        // 新库也要纳入实时同步（重新为所有 SMB 库建立监视）
        withContext(CoreDispatcher) { runCatching { NativeBridge.autoSync(true) } }
        refreshLibraries()
        setLibrary(id)
        return ""
    }

    /** 只重扫一棵子树（“刷新此目录”）。 */
    fun refreshDir(rel: String, libId: Long = query.value.libId) {
        if (libId <= 0) return
        viewModelScope.launch(CoreDispatcher) { NativeBridge.refreshDir(libId, rel) }
    }

    fun removeLibrary(id: Long) {
        viewModelScope.launch(CoreDispatcher) {
            android.util.Log.i("ComicShelf", "user action: removeLibrary($id)")
            NativeBridge.removeLibrary(id)
            if (query.value.libId == id) query.value = query.value.copy(libId = 0, dirRel = "")
            refreshLibraries()
        }
    }

    fun renameLibrary(id: Long, name: String) {
        viewModelScope.launch(CoreDispatcher) {
            NativeBridge.renameLibrary(id, name)
            refreshLibraries()
        }
    }

    fun rescan(id: Long = query.value.libId) {
        if (id <= 0) return
        viewModelScope.launch(CoreDispatcher) { NativeBridge.scanStart(id) }
    }

    fun cancelScan() = viewModelScope.launch(CoreDispatcher) { NativeBridge.scanCancel() }

    fun pauseScan(paused: Boolean) =
        viewModelScope.launch(CoreDispatcher) { NativeBridge.scanPause(paused) }

    private fun pollScan() {
        viewModelScope.launch {
            // serial 是 native 侧每次扫描自增的代号：即便一次扫描（SMB 增量可能只要
            // 几十毫秒）完全落在两次轮询之间，也能靠 serial 变化发现“扫完了”。
            var lastSerial = -1L
            while (true) {
                val p = withContext(CoreDispatcher) { NativeBridge.scanProgress() }
                if (p != null && p.size >= 9) {
                    val running = p[0] != 0L
                    val serial = if (p.size >= 10) p[9] else 0L
                    val row = ScanProgressRow(running, p[2] != 0L, p[3], p[4], p[5], p[6], p[7],
                                              p[8])
                    _scan.value = row
                    if (serial != lastSerial) {
                        if (!running) {
                            // 新代号且已结束 → 这一次扫描真的完成了，刷新界面数据。
                            if (lastSerial >= 0) {
                                refreshLibraries()
                                queryVersion.value++
                            }
                            lastSerial = serial
                            // L3：刚添加的库首扫 0 本（空目录/无图片）→ 一次性提示。
                            // 三重判据排除误报：仅刚添加的库 + 本扫 0 变更 + 该库 0 本书
                            //（重复添加已有书的库、手动重扫大库都不会命中）。
                            val fid = firstScanLibId
                            if (fid > 0 && row.libId == fid) {
                                firstScanLibId = 0
                                if (row.added == 0L && row.updated == 0L && row.removed == 0L) {
                                    val st = withContext(CoreDispatcher) {
                                        NativeBridge.libraryStats(fid)
                                    }
                                    if (st != null && st.isNotEmpty() && st[0] == 0L) {
                                        _notice.value = "该目录未发现可入库的漫画；放入文件后可手动重扫"
                                    }
                                }
                            }
                        }
                        // running == true 时先不动 lastSerial，等它结束再刷新
                    } else if (!running && row.added + row.updated + row.removed > 0) {
                        // 兜底：代号没变但计数有变化（理论到不了这里）
                        refreshLibraries()
                        queryVersion.value++
                    }
                }
                delay(if (p?.get(0) != 0L) 500 else 1200)
            }
        }
    }

    // ---- book commands -----------------------------------------------------
    fun toggleFavorite(b: BookCell) = viewModelScope.launch(CoreDispatcher) {
        NativeBridge.setFavorite(b.id, !b.favorite)
        queryVersion.value++
    }

    fun setReadState(ids: List<Long>, state: Int) = viewModelScope.launch(CoreDispatcher) {
        ids.forEach { NativeBridge.setReadState(it, state) }
        queryVersion.value++
    }

    fun deleteBooks(ids: List<Long>) = viewModelScope.launch(CoreDispatcher) {
        ids.forEach { NativeBridge.deleteBook(it) }
        queryVersion.value++
        refreshLibraries()
    }

    fun regenerateCover(id: Long) = viewModelScope.launch(CoreDispatcher) {
        NativeBridge.forgetCover(id)
    }

    fun refreshMeta(ids: List<Long>) = viewModelScope.launch(CoreDispatcher) {
        // Pages recount lazily on next open; cover regen is the visible part.
        ids.forEach { NativeBridge.forgetCover(it) }
    }

    override fun onCleared() {
        super.onCleared()
        // The app process owns the core; the scanner keeps running across
        // configuration changes because the core outlives this ViewModel.
    }
}
