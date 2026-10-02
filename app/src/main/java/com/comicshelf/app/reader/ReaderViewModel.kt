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

class ReaderViewModel : ViewModel() {

    val open = MutableStateFlow(ReaderOpenState())
    val prefs = MutableStateFlow(ReaderPrefs())
    /** page -> decoded original page (bounded LRU by bytes). */
    private val pageCache = object : LruCache<Int, Bitmap>(24) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }
    /** page -> translated overlay bitmap (sidecar engine, RAM only). */
    private val trCache = object : LruCache<Int, Bitmap>(8) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }
    val trStates = MutableStateFlow<Map<Int, TranslatePageState>>(emptyMap())

    /** backend 可见页渲染的去重（同一页并发触发只渲染一次）。 */
    private val trInflight = HashSet<Int>()

    /** Bumped whenever a bitmap lands so subscribers re-check the caches. */
    val revision = MutableStateFlow(0)

    private var pollJob: Job? = null
    private var targetDim = 2048

    var lastFlipMs = 0.0

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
    }

    // ---------------------------------------------------------------- open

    fun openBook(bookId: Long, title: String, forceTranslate: Boolean) {
        viewModelScope.launch(CoreDispatcher) {
            val info = NativeBridge.readerOpen(bookId) ?: return@launch
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
        open.value = ReaderOpenState()
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
     */
    fun ensurePage(page: Int) {
        if (page !in 0 until open.value.pageCount) return
        val had = pageAt(page) != null
        if (!had) {
            viewModelScope.launch(Dispatchers.IO) {
                val t0 = System.nanoTime()
                val bmp = PageDecoder.decode(open.value.bookId, page, targetDim)
                if (bmp != null) {
                    synchronized(pageCache) { pageCache.put(page, bmp) }
                    revision.value++
                    lastFlipMs = (System.nanoTime() - t0) / 1e6
                    Log.d("Reader", "page $page loaded in $lastFlipMs ms")
                }
            }
        }
        maybeTranslateOnDevice(page, prefetchOnly = page != open.value.page)
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
