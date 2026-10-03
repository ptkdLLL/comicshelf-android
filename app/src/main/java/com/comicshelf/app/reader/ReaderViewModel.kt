package com.comicshelf.app.reader

import android.graphics.Bitmap
import android.util.LruCache
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comicshelf.app.core.BookmarkRow
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.CsSettings
import com.comicshelf.app.core.Json
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.core.PageDecoder
import com.comicshelf.app.core.parseImageBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reader chrome state persisted via config.ini, like the Windows app. */
data class ReaderPrefs(
    val fit: Int = 0,          // 0 page, 1 width, 2 height, 3 original
    val rtl: Boolean = false,
    val spread: Boolean = false,
    val bg: Int = 0,           // 0 black, 1 white, 2 gray
)

data class ReaderOpenState(
    val bookId: Long = 0,
    val title: String = "",
    val pageCount: Int = 0,
    val page: Int = 0,
    val translateEnabled: Boolean = false,
)

enum class TranslatePageState { NONE, QUEUED, BUSY, READY, FAILED }

// ---- v0.3.2 自适应页加载门控（阈值见 docs/PROJECT_RETROSPECTIVE.md §2.8）----
/** 单页解码耗时 > 1.0s 计一次"慢"（大页包/慢网）。 */
private const val SLOW_LOAD_MS = 1000.0
/** 单页解码耗时 < 0.4s 计一次"快"；0.4~1.0s 为死区，不改变判定。 */
private const val FAST_LOAD_MS = 400.0
/** 一张 (bookId,page) 最多自动重试次数（加载失败时 bump revision 重触发）。 */
private const val MAX_LOAD_ATTEMPTS = 2
/** 失败计数窗口：超过 30s 未再失败则重新计数（避免长会话里旧失败永久封死该页）。 */
private const val RETRY_WINDOW_MS = 30_000L
/** v0.3.3：单次加载超过 20s 未返回视为卡死——允许重新排队（旧任务跑完自行清理）。 */
private const val STUCK_LOAD_MS = 20_000L

class ReaderViewModel : ViewModel() {

    val open = MutableStateFlow(ReaderOpenState())
    val prefs = MutableStateFlow(ReaderPrefs())
    /** page -> decoded original page。v0.3.3：按**字节数**封顶（160MB）。
     *  之前是"24 张"计数封顶——巨页 28.6MB/张时可吃 690MB，实测导致整机内存紧缩。
     *  160MB 恰好容纳大页模式的整个 Compose 窗口（±2 = 5 张 × 28.6MB）。 */
    private val pageCache = object : LruCache<Int, Bitmap>(160 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)   // 防御：sizeOf 必须 >0（极小位图）
    }
    /** page -> translated overlay bitmap (sidecar engine, RAM only)，同样按字节封顶（64MB）。 */
    private val trCache = object : LruCache<Int, Bitmap>(64 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    val trStates = MutableStateFlow<Map<Int, TranslatePageState>>(emptyMap())

    /** backend 可见页渲染的去重（同一页并发触发只渲染一次）。 */
    private val trInflight = HashSet<Int>()

    /** Bumped whenever a bitmap lands so subscribers re-check the caches. */
    val revision = MutableStateFlow(0)

    private var pollJob: Job? = null
    private var targetDim = 2048

    var lastFlipMs = 0.0

    // ---- v0.3.2 页加载去重 / 自适应门控状态 ----
    private class LoadAttempt(var n: Int, var at: Long)
    /** (bookId,page) -> 本次加载的启动时刻——杜绝同一页被重组风暴重复入队（自激队列根因）。
     *  v0.3.3：改为记时间戳，>STUCK_LOAD_MS 判卡死、允许重排（幂等去重 + 自愈兼顾）。 */
    private val loadsInFlight = HashMap<Pair<Long, Int>, Long>()
    /** (bookId,page) -> 失败次数（仅在解码返回 null 时累计）。 */
    private val loadAttempts = HashMap<Pair<Long, Int>, LoadAttempt>()
    /** 连续慢页达到 2 页 → 进入大页模式：只为当前页加载，不再预取邻居。 */
    private var bigPageMode = false
    private var slowStreak = 0
    private var fastStreak = 0
    /** 本次开书以来已完成的加载数；前 2 页不计入门控（首开含 SMB 建连开销，不代表稳态）。 */
    private var loadsDone = 0
    /** v0.3.3：大页模式"读一页预取下一页"的定时任务（只保留最后一个）。 */
    private var prefetchJob: Job? = null

    init {
        // backend 模式：后台队列每落档一页 → 若正是可见页则立即渲染显示
        viewModelScope.launch {
            BookTranslateJob.pageDone.collect { p ->
                if (engineMode() != "backend") return@collect
                val st = open.value
                if (st.translateEnabled && p == st.page) refreshBackendPage(p)
            }
        }
        // 后台队列进度 → 可见页状态（驱动"翻译中"指示器）
        viewModelScope.launch {
            BookTranslateJob.state.collect { s ->
                if (engineMode() != "backend") return@collect
                val st = open.value
                if (st.bookId == 0L || s.bookId != st.bookId) return@collect
                val vis = st.page
                val cur = trStates.value[vis]
                if (cur == TranslatePageState.READY || cur == TranslatePageState.FAILED) return@collect
                val new: TranslatePageState? = when {
                    s.error.isNotEmpty() && s.current == vis -> TranslatePageState.FAILED
                    s.current == vis -> TranslatePageState.BUSY
                    else -> cur
                }
                if (new != null && new != cur) trStates.value = trStates.value + (vis to new)
            }
        }
        // v0.3.4 安全网：每 5s 巡检"可见页既无缓存、又无在途加载"的真空态
        //（= 没有任何路径会再加载它，转圈将永不结束）。根因已在 goto() 修复；
        // 此网兜住未来任何新增"当前页变更"入口的遗漏。
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(5000)
                val st = open.value
                if (st.bookId == 0L || st.pageCount <= 0) continue
                val vis = st.page
                if (pageAt(vis) != null) continue
                val infl = synchronized(loadsInFlight) {
                    loadsInFlight.entries.map { (k, v) ->
                        "p${k.second}:${(System.currentTimeMillis() - v) / 1000}s"
                    }
                }
                if (infl.isEmpty()) {
                    Log.w("Reader", "真空自愈：page=$vis 无缓存且无在途（big=$bigPageMode）→ 补发 ensurePage")
                    ensurePage(vis)
                }
            }
        }
    }

    // ---------------------------------------------------------------- open

    fun openBook(bookId: Long, title: String, forceTranslate: Boolean) {
        viewModelScope.launch(CoreDispatcher) {
            val info = NativeBridge.readerOpen(bookId) ?: return@launch
            resetLoadGating()
            val pageCount = info[0]
            val lastPage = info[1]
            prefs.value = ReaderPrefs(
                fit = CsSettings.int("reader_fit", 0),
                rtl = CsSettings.bool("reader_rtl", false),
                spread = CsSettings.bool("reader_spread", false),
                bg = CsSettings.int("reader_bg", 0),
            )
            // 默认不翻译：只有本册被手动启用（或本次强制打开）才启动引擎。
            val wantTranslate = forceTranslate ||
                withContext(CoreDispatcher) { NativeBridge.getBookTranslateEnabled(bookId) }
            if (wantTranslate && engineMode() == "sidecar") {
                NativeBridge.translateConfigure(null, null, null, -1, -1, -1, true)
                NativeBridge.translateOpenBook(bookId)
            }
            open.value = ReaderOpenState(bookId, title, pageCount,
                                         if (lastPage in 0 until pageCount) lastPage else 0,
                                         wantTranslate)
            if (wantTranslate) {
                when (engineMode()) {
                    "sidecar" -> {
                        NativeBridge.translateFocus(open.value.page)
                        startTranslatePolling()
                    }
                    "backend" -> {
                        // 整本后台翻译：打开即续传/启动（可见页在 goto 时插队）
                        BookTranslateJob.ensureStarted(bookId, pageCount, open.value.page)
                        refreshBackendPage(open.value.page)
                    }
                    else -> viewModelScope.launch(Dispatchers.Default) {
                        OnDeviceTranslator.ensureInit()
                    }
                }
            }
        }
    }

    fun closeBook() {
        val id = open.value.bookId
        if (id != 0L) {
            viewModelScope.launch(CoreDispatcher) {
                NativeBridge.saveProgress(id, open.value.page, open.value.pageCount)
                NativeBridge.readerClose(id)
                NativeBridge.translateCloseBook()
                NativeBridge.translateConfigure(null, null, null, -1, -1, -1,
                                                CsSettings.bool("translate_enabled", false))
            }
        }
        pollJob?.cancel()
        synchronized(pageCache) { pageCache.evictAll() }
        synchronized(trCache) { trCache.evictAll() }
        resetLoadGating()
        open.value = ReaderOpenState()
    }

    /** v0.3.2：清空门控/重试状态（loadsInFlight 不清理——在途任务自行移除，避免重复触发去重空洞）。
     *  v0.3.3：一并取消待执行的邻页预取。 */
    private fun resetLoadGating() {
        bigPageMode = false
        slowStreak = 0
        fastStreak = 0
        loadsDone = 0
        prefetchJob?.cancel()
        synchronized(loadAttempts) { loadAttempts.clear() }
    }

    override fun onCleared() {
        closeBook()
        super.onCleared()
    }

    // ---------------------------------------------------------------- pages

    fun setScreenHint(width: Int, height: Int) {
        targetDim = maxOf(width, height) * 2   // 2x for crisp pinch zoom
    }

    fun pageAt(page: Int): Bitmap? = synchronized(pageCache) { pageCache.get(page) }

    /**
     * Loads [page] (and neighbors) if missing. Called from LaunchedEffect of
     * every composed reader item — Compose's beyond-bounds composition acts
     * as the prefetch window, exactly like the C++ preload of the Windows UI.
     *
     * v0.3.2 修复（翻页冻结根因：重组风暴 → 重复入队 → 自激队列）：
     *  1) (bookId,page) 去重：已在途的页直接跳过，不再重复 launch；
     *  2) 大页模式：连续 2 页 > 1.0s 后只加载当前页，不再预取邻居
     *     （巨页包下预取会把单会话 SMB 队列堵死；连续 3 页 < 0.4s 自动退出）；
     *  3) 失败重试上限：解码返回 null 最多 bump revision 重试 MAX_LOAD_ATTEMPTS 次，
     *     30s 窗口后计数重置；超出则放弃并留日志（防止无限重试队列）。
     *
     * v0.3.3：
     *  4) 卡死自愈：同一页在途超过 STUCK_LOAD_MS(20s) 视为卡死 → 允许重新排队
     *     （实测巨页 114MB 分配在内存紧缩下会卡死 60s+；旧任务跑完用时间戳守卫
     *      自行清理，不会误删新任务的在途标记）；
     *  5) [allowAhead]：仅供"读一页预取下一页"内部通道使用（大页模式下预取 +1 页）。
     */
    fun ensurePage(page: Int) = ensurePageInner(page, allowAhead = false)

    private fun ensurePageInner(page: Int, allowAhead: Boolean) {
        val st = open.value
        if (page !in 0 until st.pageCount) return
        val key = st.bookId to page
        if (pageAt(page) == null) {
            // 大页模式下不给窗口外的页加载（allowAhead 通道除外：仅 +1 预取）。
            if (bigPageMode && page != st.page && !allowAhead) {
                maybeTranslateOnDevice(page, prefetchOnly = true)
                return
            }
            val now = System.currentTimeMillis()
            val fail = synchronized(loadAttempts) {
                val fa = loadAttempts[key]
                if (fa != null && now - fa.at > RETRY_WINDOW_MS) { loadAttempts.remove(key); null } else fa
            }
            if (fail != null && fail.n >= MAX_LOAD_ATTEMPTS) {
                maybeTranslateOnDevice(page, prefetchOnly = page != st.page)
                return
            }
            val start = synchronized(loadsInFlight) {
                val started = loadsInFlight[key]
                if (started != null && now - started < STUCK_LOAD_MS) {
                    maybeTranslateOnDevice(page, prefetchOnly = page != st.page)
                    return
                }
                if (started != null) {
                    Log.w("Reader", "page $page 上一次加载 ${(now - started) / 1000}s 未返回，" +
                        "视为卡死，重新排队（旧任务跑完自行清理）")
                }
                loadsInFlight[key] = now
                now
            }
            Log.i("Reader", "load start page=$page" + (if (allowAhead) " (prefetch)" else ""))
            viewModelScope.launch(Dispatchers.IO) {
                val t0 = System.nanoTime()
                var ok = false
                try {
                    val bmp = PageDecoder.decode(st.bookId, page, targetDim)
                    if (bmp != null) {
                        if (open.value.bookId == st.bookId) {      // 已切书则丢弃，避免串页
                            synchronized(pageCache) { pageCache.put(page, bmp) }
                            revision.value++
                            ok = true
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("Reader", "page $page load failed: $e")
                } finally {
                    synchronized(loadsInFlight) {
                        // 时间戳守卫：卡死重排后旧任务的 finally 不能误删新任务的在途标记
                        if (loadsInFlight[key] == start) loadsInFlight.remove(key)
                    }
                    val ms = (System.nanoTime() - t0) / 1e6
                    lastFlipMs = ms
                    if (ok) {
                        Log.i("Reader", "page $page loaded in $ms ms")
                        onLoadTiming(ms)
                        // v0.3.3：大页模式下，用户若停在这一页读，预取下一页
                        if (bigPageMode) scheduleNeighborPrefetch(st.bookId, page)
                    } else {
                        val n = synchronized(loadAttempts) {
                            val fa = loadAttempts.getOrPut(key) { LoadAttempt(0, now) }
                            if (now - fa.at > RETRY_WINDOW_MS) { fa.n = 0; fa.at = now }
                            fa.n++; fa.at = System.currentTimeMillis(); fa.n
                        }
                        Log.w("Reader", "page $page load failed (attempt $n/$MAX_LOAD_ATTEMPTS) in $ms ms")
                        if (n < MAX_LOAD_ATTEMPTS &&
                            open.value.bookId == st.bookId && pageAt(page) == null) {
                            revision.value++   // 触发订阅者重试（有上限）
                        }
                    }
                }
            }
        }
        maybeTranslateOnDevice(page, prefetchOnly = page != st.page)
    }

    /**
     * v0.3.3 大页模式"读一页预取下一页"：当前页落地 600ms 后，若用户仍停在它上面
     * （= 在读，而非连翻走）→ 预取 +1 页（仅一页，不链式扩散——只有 +1 页真正成为
     * 当前页时，它的落地才会再触发下一次预取）。
     */
    private fun scheduleNeighborPrefetch(bookId: Long, page: Int) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            val cur = open.value
            if (cur.bookId != bookId || cur.page != page) return@launch
            val next = page + 1
            if (next >= cur.pageCount || pageAt(next) != null) return@launch
            ensurePageInner(next, allowAhead = true)
        }
    }

    /** 自适应门控：慢 → 进大页模式（只保当前页）；快 → 退出门控恢复预取。 */
    private fun onLoadTiming(ms: Double) {
        if (loadsDone < 2) { loadsDone++; return }   // 前两页含建连开销，不计入
        loadsDone++
        when {
            ms > SLOW_LOAD_MS -> {
                slowStreak++; fastStreak = 0
                if (!bigPageMode && slowStreak >= 2) {
                    bigPageMode = true
                    Log.i("Reader", "big-page mode ON (slowStreak=$slowStreak, last ${ms}ms): " +
                        "loading current page only, prefetch paused")
                }
            }
            ms < FAST_LOAD_MS -> {
                fastStreak++; slowStreak = 0
                if (bigPageMode && fastStreak >= 3) {
                    bigPageMode = false
                    Log.i("Reader", "big-page mode OFF (fastStreak=$fastStreak, last ${ms}ms): prefetch resumed")
                }
            }
            else -> { slowStreak = 0; fastStreak = 0 }   // 死区：不动
        }
    }

    /** Engine selection: "sidecar" (LAN service) / "ondevice" (GPU OCR + LLM) /
     *  "backend" (局域网后端: 检测+OCR 在 Mac, 翻译走 llama-server, 端侧只渲染). */
    fun engineMode(): String = CsSettings.get("translate_engine", "sidecar")

    /**
     * backend 模式：整本翻译由 [BookTranslateJob] 在后台按队列进行（可见页插队），
     * 这里只负责"焦点 + 可见页有档案就渲染"；不再逐页按需跑全链。
     * ondevice 模式保持原逐页行为。
     */
    private fun maybeTranslateOnDevice(page: Int, prefetchOnly: Boolean = true,
                                       force: Boolean = false) {
        val st = open.value
        val mode = engineMode()
        if (mode == "backend") {
            if (!st.translateEnabled) return
            BookTranslateJob.focus(st.bookId, page, force)
            refreshBackendPage(page)
            return
        }
        if (mode != "ondevice" || !st.translateEnabled) return
        if (page !in 0 until st.pageCount) return
        val states = trStates.value
        val cur = states[page]
        // FAILED 也视为本会话终态：无文本页（插页/封面）如果允许重试，每次重组都会
        // 白跑一遍 det+OCR。需要重试时走“重译当前页”（会把状态清回 NONE 并重跑）。
        if (cur == TranslatePageState.READY || cur == TranslatePageState.BUSY ||
            cur == TranslatePageState.QUEUED || cur == TranslatePageState.FAILED) return
        // only translate around the visible page, not the whole prefetch window
        if (prefetchOnly && page != st.page) return
        trStates.value = states + (page to TranslatePageState.QUEUED)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                trStates.value = trStates.value + (page to TranslatePageState.BUSY)
                val bmp = pageAt(page)
                if (bmp == null) {
                    // 位图未就绪（切页过快 / 解码未完）——这是瞬态，不是页面的终态：
                    // 置回 NONE 留给下次触发。此前这里静默落 FAILED，把页面永久毒化
                    // （表现为"翻回该页永不翻译"，2026-10-02 修复）。
                    Log.i("Reader", "ondevice translate skip page $page: bitmap not ready")
                    trStates.value = trStates.value + (page to TranslatePageState.NONE)
                    return@launch
                }
                val soft = try {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) { null }
                val result = soft?.let { OnDeviceTranslator.translatePage(st.bookId, page, it) }
                if (result != null) {
                    synchronized(trCache) { trCache.put(page, result.bitmap) }
                    trStates.value = trStates.value + (page to TranslatePageState.READY)
                    revision.value++
                    Log.i("Reader", "ondevice page $page translated: ${result.timings}")
                } else {
                    Log.i("Reader", "ondevice translate no-result for page $page")
                    trStates.value = trStates.value + (page to TranslatePageState.FAILED)
                }
            } catch (t: Throwable) {
                // Never let a GPU/OCR hiccup take the reader down.
                Log.e("Reader", "ondevice translate failed for page $page", t)
                trStates.value = trStates.value + (page to TranslatePageState.FAILED)
            }
        }
    }

    /** backend：可见页已有档案 → 渲染显示（无网络）；无档案则等后台队列 pageDone。 */
    private fun refreshBackendPage(page: Int) {
        val st = open.value
        if (!st.translateEnabled) return
        if (translatedAt(page) != null) return
        synchronized(trInflight) { if (!trInflight.add(page)) return }   // 防并发重复渲染
        viewModelScope.launch(Dispatchers.Default) {
            try {
                var bmp = pageAt(page)
                if (bmp == null) {                 // 翻页瞬间位图可能还没落缓存：稍等一次
                    delay(400)
                    bmp = pageAt(page)
                }
                if (bmp == null) return@launch
                val out = OnDeviceTranslator.renderBackendPage(st.bookId, page, bmp)
                    ?: return@launch
                synchronized(trCache) { trCache.put(page, out) }
                trStates.value = trStates.value + (page to TranslatePageState.READY)
                revision.value++
                Log.i("Reader", "backend page $page rendered from archive")
            } catch (t: Throwable) {
                // 渲染/解析异常绝不允许带崩进程（2026-10-02: HARDWARE 位图崩过）
                Log.e("Reader", "backend render failed for page $page", t)
            } finally {
                synchronized(trInflight) { trInflight.remove(page) }
            }
        }
    }

    fun goto(page: Int, persist: Boolean = true) {
        val st = open.value
        if (page !in 0 until st.pageCount) return
        open.value = st.copy(page = page)
        if (st.translateEnabled && engineMode() == "sidecar") {
            viewModelScope.launch(CoreDispatcher) { NativeBridge.translateFocus(page) }
        }
        if (persist && st.bookId != 0L) {
            viewModelScope.launch(CoreDispatcher) {
                NativeBridge.saveProgress(st.bookId, page, st.pageCount)
            }
        }
        maybeTranslateOnDevice(page, prefetchOnly = false)
        // v0.3.4 修复（真空卡死根因）：goto 是"当前页变更"的统一入口，必须由它保证
        // "当前页有加载在途或已缓存"这一状态不变量。此前加载触发依赖 UI 偶发事件
        // （页项组合时或 pager 落定 else 分支），快滑场景两条都不满足 → 页永不加载。
        ensurePage(page)
    }

    fun setPrefs(p: ReaderPrefs) {
        prefs.value = p
        CsSettings.setInt("reader_fit", p.fit)
        CsSettings.setBool("reader_rtl", p.rtl)
        CsSettings.setBool("reader_spread", p.spread)
        CsSettings.setInt("reader_bg", p.bg)
    }

    // -------------------------------------------------------------- translate

    fun translatedAt(page: Int): Bitmap? = synchronized(trCache) { trCache.get(page) }

    private fun startTranslatePolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (open.value.translateEnabled && open.value.bookId != 0L) {
                val page = open.value.page
                // Poll a window around the focus page.
                val states = HashMap<Int, TranslatePageState>()
                for (p in (page - 1)..(page + 4)) {
                    if (p !in 0 until open.value.pageCount) continue
                    val s = withContext(CoreDispatcher) { NativeBridge.translateState(p) }
                    states[p] = when (s) {
                        1 -> TranslatePageState.QUEUED
                        2 -> TranslatePageState.BUSY
                        3 -> TranslatePageState.READY
                        4 -> TranslatePageState.FAILED
                        else -> TranslatePageState.NONE
                    }
                    if (states[p] == TranslatePageState.READY && translatedAt(p) == null) {
                        loadTranslated(p)
                    }
                }
                trStates.value = states
                delay(250)
            }
        }
    }

    private fun loadTranslated(page: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val bundle = NativeBridge.translateGetPage(page, targetDim)
            val (dims, px) = parseImageBundle(bundle) ?: return@launch
            val bmp = com.comicshelf.app.core.CoverStore.rgbaToBitmap(dims, px)
            if (bmp != null) {
                synchronized(trCache) { trCache.put(page, bmp) }
                revision.value++
                Log.d("Reader", "translated page $page ${bmp.width}x${bmp.height} ready")
            }
        }
    }

    /**
     * 翻译开关。persist=true 时写入本册偏好（下次打开这本书仍然启用）。
     * backend 模式：开启 = 启动整本后台翻译队列 [BookTranslateJob]；关闭 = 停止队列（档案保留）。
     */
    fun setTranslateEnabled(on: Boolean, persist: Boolean) {
        val st = open.value
        if (st.bookId == 0L) return
        open.value = st.copy(translateEnabled = on)
        viewModelScope.launch(CoreDispatcher) {
            if (persist) NativeBridge.setBookTranslateEnabled(st.bookId, on)
            if (on) {
                when (engineMode()) {
                    "sidecar" -> {
                        NativeBridge.translateOpenBook(st.bookId)
                        NativeBridge.translateFocus(st.page)
                        startTranslatePolling()
                    }
                    "backend" -> {
                        BookTranslateJob.ensureStarted(st.bookId, st.pageCount, st.page)
                        refreshBackendPage(st.page)
                    }
                    else -> {
                        NativeBridge.translateConfigure(null, null, null, -1, -1, -1, true)
                        maybeTranslateOnDevice(st.page, prefetchOnly = false)
                    }
                }
            } else {
                pollJob?.cancel()
                if (engineMode() == "sidecar") NativeBridge.translateCloseBook()
                if (engineMode() == "backend") BookTranslateJob.cancel()
            }
        }
    }

    /** 设置页的“全部关闭”后回到阅读器时调用：把会话状态拉回配置 */
    fun syncTranslateFromPref() {
        val st = open.value
        if (st.bookId == 0L) return
        viewModelScope.launch(CoreDispatcher) {
            val pref = NativeBridge.getBookTranslateEnabled(st.bookId)
            if (pref != st.translateEnabled) open.value = st.copy(translateEnabled = pref)
        }
    }

    fun retranslateCurrent() {
        val st = open.value
        if (engineMode() == "backend") {
            // 只重译本页（fresh=1 让服务端绕缓存），**不清整本档案**——整本进度不能丢
            BookTranslateJob.focus(st.bookId, st.page, fresh = true)
            synchronized(trCache) { trCache.remove(st.page) }
            trStates.value = trStates.value + (st.page to TranslatePageState.QUEUED)
            revision.value++
            return
        }
        viewModelScope.launch(CoreDispatcher) {
            NativeBridge.clearBookArchive(st.bookId)
            NativeBridge.translateRetranslate(st.page)
            synchronized(trCache) { trCache.remove(st.page) }
            if (engineMode() == "ondevice") {
                trStates.value = trStates.value + (st.page to TranslatePageState.NONE)
                revision.value++
                maybeTranslateOnDevice(st.page, prefetchOnly = false, force = true)
            }
        }
    }

    // -------------------------------------------------------------- bookmarks

    suspend fun bookmarks(): List<BookmarkRow> = withContext(CoreDispatcher) {
        Json.bookmarks(NativeBridge.bookmarks(open.value.bookId))
    }

    fun addBookmark(page: Int, label: String) = viewModelScope.launch(CoreDispatcher) {
        NativeBridge.addBookmark(open.value.bookId, page, label)
    }

    fun removeBookmark(id: Long) = viewModelScope.launch(CoreDispatcher) {
        NativeBridge.removeBookmark(id)
    }
}
