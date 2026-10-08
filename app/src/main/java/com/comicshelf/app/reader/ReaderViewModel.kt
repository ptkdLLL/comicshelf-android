package com.comicshelf.app.reader

import android.graphics.Bitmap
import android.util.LruCache
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.first
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
// MAX_LOAD_ATTEMPTS / STUCK_LOAD_MS / RETRY_DELAY_MS / 页位图预算 → ReaderPolicy.kt
// （v0.6.0 所有权归位：常量集中，评估 G1-G8）

class ReaderViewModel : ViewModel() {

    val open = MutableStateFlow(ReaderOpenState())
    val prefs = MutableStateFlow(ReaderPrefs())
    // ---- v0.6.0 页位图所有权归位（READER_FLICKER_FIX_PLAN v2 / READER_FIX_EVAL.md）----
    /** page -> decoded original page 的**借用表**（弱引用）。位图本体由组合项持有
     *  （ReaderScreen 的 produceState）；本表只服务 VM 侧查询：翻译取原图 / 预取查重 / P-R8 记账。
     *  项销毁即注销。旧 160MB LruCache 已删——"逐出 ↔ 需求"反馈回路不复存在（I1）。 */
    private val borrowed = ConcurrentHashMap<Int, WeakReference<Bitmap>>()
    /** P-R8 记账：自算字节（w×h×4）。byteCount 退出一切决策。 */
    private var budgetBytes: Long = PageBudget.DEVICE_BUDGET_CAP
    /** P-R8：page -> 最近解码时刻（判"同页 60s 二次解码"违例）。 */
    private val decodeStamps = ConcurrentHashMap<Int, ArrayDeque<Long>>()
    /** page -> translated overlay bitmap (sidecar engine, RAM only)，按字节封顶（64MB）。 */
    private val trCache = object : LruCache<Int, Bitmap>(64 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    val trStates = MutableStateFlow<Map<Int, TranslatePageState>>(emptyMap())

    /** backend 可见页渲染的去重（同一页并发触发只渲染一次）。 */
    private val trInflight = HashSet<Int>()

    private var pollJob: Job? = null
    private var targetDim = 2048   // 仅剩翻译路径（translateGetPage 返回尺寸）使用

    var lastFlipMs = 0.0

    // ---- 切分阅读模式（docs/READER_SPLIT_PLAN.md P-S1/P-S6）----
    /** 每册记忆的手动开关（CsSettings "reader_split_<bookId>"）；显示页域与 raw 域经 SplitMode 换算。 */
    private val _splitOn = MutableStateFlow(false)
    val splitOn: StateFlow<Boolean> = _splitOn.asStateFlow()
    /** 源页数（raw 域）；open.pageCount 是显示页数（2N | N）。 */
    @Volatile private var rawCount = 0

    // ---- 连续滚动模式（docs/READER_VERTICAL_SCROLL_PLAN.md P-V1/P-V7）----
    /** 每册记忆：方向（0/1/2）× 连续（关/开）；显示页域与按页模式同域，切换仅换容器。 */
    private val _mode = MutableStateFlow(ReaderMode())
    val mode: StateFlow<ReaderMode> = _mode.asStateFlow()
    /** D-V9/P-V7 会话级页尺寸缓存（raw → w<<32|h）；连续模式页项占位高度用；开书清空。 */
    private val pageDims = ConcurrentHashMap<Int, Long>()

    // ---- 自适应门控状态（v0.3.2 语义原样；v0.6.0 起以"准入"为载体，P-R6）----
    /** 连续慢页达到 2 页 → 进入大页模式：只为当前页加载，不再预取邻居。 */
    @Volatile private var bigPageMode = false
    private var slowStreak = 0
    private var fastStreak = 0
    /** 本次开书以来已完成的加载数；前 2 页不计入门控（首开含 SMB 建连开销，不代表稳态）。 */
    private var loadsDone = 0
    /** v0.3.3：大页模式"读一页预取下一页"的一次性放行目标（落地/goto 即清除）。 */
    @Volatile private var allowAhead: Int? = null
    /** 准入信号（P-R6）：bigPageMode/goto/allowAhead 变更都 bump，唤醒挂起的页项。 */
    private val admissionTick = MutableStateFlow(0)
    private fun bumpAdmission() { admissionTick.value++ }
    /** v0.3.3：大页模式"读一页预取下一页"的定时任务（只保留最后一个）。 */
    private var prefetchJob: Job? = null

    init {
        // backend 模式：后台队列每落档一页 → 若正是可见页则立即渲染显示
        // （job 发的是 raw 页号，与显示页比较须换算；P-S8）
        viewModelScope.launch {
            BookTranslateJob.pageDone.collect { p ->
                if (engineMode() != "backend") return@collect
                val st = open.value
                if (st.translateEnabled && p == rawOfPage(st.page)) refreshBackendPage(p)
            }
        }
        // 后台队列进度 → 可见页状态（驱动"翻译中"指示器）
        viewModelScope.launch {
            BookTranslateJob.state.collect { s ->
                if (engineMode() != "backend") return@collect
                val st = open.value
                if (st.bookId == 0L || s.bookId != st.bookId) return@collect
                val vis = rawOfPage(st.page)
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
        // P-R8：不变量巡检（"无环"写进代码）——resident ≤ BUDGET、无"同页 60s 二次解码"。
        // 违反只记日志不改行为（release 同样计数）；数据供 M1/M6 与 DAP 抽查。
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(30_000)
                val resident = residentBytes()
                if (resident > budgetBytes) {
                    Log.w("Reader", "P-R8 违例: resident=${resident / 1_048_576}MB > " +
                        "budget=${budgetBytes / 1_048_576}MB")
                }
                val cutoff = System.currentTimeMillis() - 60_000
                decodeStamps.forEach { (k, dq) ->   // k = raw*2 + (half>=1 ? 1 : 0)（P-S6 d）
                    val n = synchronized(dq) {
                        while (dq.isNotEmpty() && dq.first() < cutoff) dq.removeFirst()
                        dq.size
                    }
                    if (n >= 3) Log.w("Reader", "P-R8 违例: page ${k / 2} 60s 内解码 $n 次（≥3=风暴签名）")
                }
            }
        }
    }

    // ---------------------------------------------------------------- open

    fun openBook(bookId: Long, title: String, forceTranslate: Boolean) {
        viewModelScope.launch(CoreDispatcher) {
            val info = NativeBridge.readerOpen(bookId) ?: return@launch
            resetLoadGating()
            borrowed.clear()   // 切书防串页：旧书借用全部作废（新书页项自会重登记）
            pageDims.clear()   // 尺寸缓存同样不跨书（P-V7）
            val pageCount = info[0]          // raw 源页数
            val lastPage = info[1]           // raw 页号
            prefs.value = ReaderPrefs(
                fit = CsSettings.int("reader_fit", 0),
                rtl = CsSettings.bool("reader_rtl", false),
                spread = CsSettings.bool("reader_spread", false),
                bg = CsSettings.int("reader_bg", 0),
            )
            // P-V1：方向/连续按册解析（方向缺键回退旧全局 rtl——迁移语义，P-V9）
            _mode.value = ReaderMode.load(bookId, prefs.value.rtl)
            // P-S1：切分开关按册读取；open.page/pageCount 全部为显示页域。
            val split = CsSettings.bool("reader_split_$bookId", false)
            _splitOn.value = split
            rawCount = pageCount
            val vCount = SplitMode.vCount(pageCount, split)
            val rawPage = if (lastPage in 0 until pageCount) lastPage else 0
            val vPage = if (split) rawPage * 2 else rawPage
            // 默认不翻译：只有本册被手动启用（或本次强制打开）才启动引擎。
            val wantTranslate = forceTranslate ||
                withContext(CoreDispatcher) { NativeBridge.getBookTranslateEnabled(bookId) }
            if (wantTranslate && engineMode() == "sidecar") {
                NativeBridge.translateConfigure(null, null, null, -1, -1, -1, true)
                NativeBridge.translateOpenBook(bookId)
            }
            open.value = ReaderOpenState(bookId, title, vCount, vPage, wantTranslate)
            if (wantTranslate) {
                when (engineMode()) {
                    "sidecar" -> {
                        NativeBridge.translateFocus(rawPage)      // 翻译全 raw 域（P-S8）
                        startTranslatePolling()
                    }
                    "backend" -> {
                        // 整本后台翻译：打开即续传/启动（可见页在 goto 时插队）
                        BookTranslateJob.ensureStarted(bookId, pageCount, rawPage)
                        refreshBackendPage(rawPage)
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
            // 值在派发前捕获（修 2026-10-07 系统性测试实测出的既有竞态：
            // 协程在 CoreDispatcher 上晚于下方 `open.value = ReaderOpenState()` 执行时，
            // 读到的 page 已是 0 → 把进度写成 0。JDWP/高负载下必现）。
            val raw = rawOfPage(open.value.page)
            val rawN = rawCount
            viewModelScope.launch(CoreDispatcher) {
                NativeBridge.saveProgress(id, raw, rawN)
                NativeBridge.readerClose(id)
                NativeBridge.translateCloseBook()
                NativeBridge.translateConfigure(null, null, null, -1, -1, -1,
                                                CsSettings.bool("translate_enabled", false))
            }
        }
        pollJob?.cancel()
        borrowed.clear()                      // 页位图归组合项所有——离开阅读器即全释放（I1）
        pageDims.clear()
        synchronized(trCache) { trCache.evictAll() }
        resetLoadGating()
        open.value = ReaderOpenState()
    }

    /** v0.3.2：清空门控状态。v0.6.0：重试/看门狗已项内化，这里只剩大页模式条带（P-R6）。 */
    private fun resetLoadGating() {
        bigPageMode = false
        slowStreak = 0
        fastStreak = 0
        loadsDone = 0
        prefetchJob?.cancel()
        allowAhead = null
        bumpAdmission()
    }

    override fun onCleared() {
        closeBook()
        super.onCleared()
    }

    // ---------------------------------------------------------------- pages

    fun setScreenHint(width: Int, height: Int) {
        targetDim = maxOf(width, height) * 2   // 2x for crisp pinch zoom（翻译路径用；解码已改字节上限）
    }

    /** P-R2 借用表查询（语义与旧 pageCache.get 相同：没有就是 null）。 */
    fun pageAt(page: Int): Bitmap? = borrowed[page]?.get()

    /** P-R8/测试：当前常驻页位图自算字节（不依赖 Bitmap.byteCount）。 */
    fun residentBytes(): Long {
        var sum = 0L
        borrowed.values.forEach { w ->
            val b = w.get() ?: return@forEach
            sum += b.width.toLong() * b.height.toLong() * 4
        }
        return sum
    }

    /** 预算快照（进入阅读器时由 UI 推一次；只服务 P-R8 断言口径）。 */
    fun updateBudget(bytes: Long) { budgetBytes = bytes }

    // ---------------------------------------------------------------- split mode

    /** 显示页 → raw（P-S6：一切翻译/进度调用点的唯一换算式）。 */
    fun rawOfPage(v: Int): Int = SplitMode.rawOf(v, _splitOn.value)

    /** raw → 显示页首号（书签标签/跳转用）。 */
    fun pageOfRaw(raw: Int): Int = if (_splitOn.value) raw * 2 else raw

    /** 源页数（raw 域；open.pageCount 是显示页数）。 */
    fun rawPageCount(): Int = rawCount

    /**
     * 切分阅读开关（每册记忆；P-S1/P-S7「改即存」）。
     * 中途切换：锚定当前 raw（落地其首半），显示页域重算（I-S4 锚定不变量）。
     */
    fun setSplit(on: Boolean) {
        val st = open.value
        if (st.bookId == 0L || rawCount <= 0) return
        if (_splitOn.value == on) return
        val anchorRaw = SplitMode.rawOf(st.page, _splitOn.value)   // 旧映射下当前 raw
        CsSettings.setBool("reader_split_${st.bookId}", on)
        CsSettings.save()
        _splitOn.value = on
        open.value = st.copy(
            pageCount = SplitMode.vCount(rawCount, on),
            page = if (on) anchorRaw * 2 else anchorRaw,
        )
        if (st.translateEnabled && engineMode() == "sidecar") {
            viewModelScope.launch(CoreDispatcher) { NativeBridge.translateFocus(anchorRaw) }
        }
        allowAhead = null
        bumpAdmission()
    }

    // ---------------------------------------------------------------- continuous scroll

    /** 方向切换（P-V7：本册记忆，改即存；锚定 open.page——显示页域两模式同域，无重映射）。 */
    fun setDir(dir: Int) {
        val st = open.value
        if (st.bookId == 0L || dir !in ReaderMode.DIR_L2R..ReaderMode.DIR_T2B) return
        if (_mode.value.dir == dir) return
        CsSettings.setInt("reader_dir_${st.bookId}", dir)
        CsSettings.save()
        _mode.value = _mode.value.copy(dir = dir)
    }

    /** 连续滚动开关（P-V7：本册记忆，改即存；锚定 open.page）。 */
    fun setScroll(on: Boolean) {
        val st = open.value
        if (st.bookId == 0L || _mode.value.scroll == on) return
        CsSettings.setBool("reader_scroll_${st.bookId}", on)
        CsSettings.save()
        _mode.value = _mode.value.copy(scroll = on)
        allowAhead = null
        bumpAdmission()   // 准入窗口随模式变化（P-V7），唤醒挂起项重评估
    }

    /** P-V7/D-V9：已解页源尺寸（本会话）；连续模式页项占位高度用。 */
    fun pageDimsOf(raw: Int): Pair<Int, Int>? = pageDims[raw]?.let {
        ((it ushr 32).toInt()) to ((it and 0xFFFFFFFFL).toInt())
    }

    // ---- v0.6.0 页加载：项自有（P-R1）＋ 调度层准入（P-R6）----
    // 旧 ensurePage/ensurePageInner/loadsInFlight/loadAttempts/真空自愈看门狗全部退休：
    // 一页一项一协程（I1）——去重/卡死/重试/找活全由项的生命周期承担（评估 G3/G4/G8）。

    /**
     * P-R1 解码入口：**组合项**调用。日志/计时/计数口径与旧 ensurePageInner 一致
     * （M1 依赖 "load start page=" / "loaded in" 串，原样保留）。
     * 取消语义：项销毁 → 本协程（或以本函数为 body 的 job）取消 → 结果丢弃（位图 GC）。
     * [half]（P-S3）：WHOLE=整页；0/1=切分后的半页项（同 raw 两半各解一次，语义与现状同构）。
     */
    suspend fun decodePageItem(bookId: Long, page: Int, half: Int, maxBytes: Long): Bitmap? {
        // P-R8 精确形（动态阶段定稿）：**在位页**重新解码 = 回路签名本体
        //（回访是先离开窗口已注销再解码，属正常——旧 60s 计数形会误报）。
        // 切分开启时同 raw 的两个半页项互为"兄弟"（borrowed 按 raw 键共享），
        // 无法区分，此检查仅在整页模式（half=WHOLE）下生效；半页项的风暴由
        // (raw,half) 记账的 60s 计数兜底（P-S6 d）。
        if (half == SplitMode.WHOLE && borrowed[page]?.get() != null) {
            Log.w("Reader", "P-R8 违例: page $page 在位仍重新解码")
        }
        val t0 = System.nanoTime()
        Log.i("Reader", "load start page=$page")
        var bmp: Bitmap? = null
        try {
            bmp = PageDecoder.decode(bookId, page, maxBytes)
        } catch (e: Throwable) {
            Log.w("Reader", "page $page load failed: $e")
        }
        val ms = (System.nanoTime() - t0) / 1e6
        lastFlipMs = ms
        recordDecode(page, half)
        if (bmp != null) {
            Log.i("Reader", "page $page loaded in $ms ms")
            pageDims[page] = (bmp.width.toLong() shl 32) or (bmp.height.toLong() and 0xFFFFFFFFL)
            onLoadTiming(ms)
        } else {
            Log.w("Reader", "page $page load failed in $ms ms")
        }
        return bmp
    }

    /** P-R6 准入：大页模式只放行当前页/一次性预取目标；未放行者挂起（不占 IO、不占帧）。
     *  [page] 为 raw；与当前显示页比较需换算（P-S6 a）。 */
    suspend fun awaitAdmission(page: Int) {
        admissionTick.first { admits(page) }
    }
    private fun admits(page: Int): Boolean =
        !bigPageMode || page == rawOfPage(open.value.page) || page == allowAhead ||
            (_mode.value.scroll && page == rawOfPage(open.value.page) + 1)   // P-V7/D-V6：连续流下页可达

    /** P-R1 落地回调：登记借用 + 大页模式"读一页预取下一页" + 翻译触发（旧 ensurePage 尾调用语义）。
     *  [page] 为 raw。 */
    fun onItemDecoded(page: Int, bmp: Bitmap) {
        borrowed[page] = WeakReference(bmp)
        if (allowAhead == page) allowAhead = null
        if (bigPageMode) scheduleNeighborPrefetch(open.value.bookId, page)
        maybeTranslateOnDevice(page, prefetchOnly = page != rawOfPage(open.value.page))
    }

    /** 项销毁注销借用（P-R2/P-S6 e）。**身份判定**（READER_VERTICAL_SCROLL_PLAN 7.3-A）：
     *  仅当登记项已死、或登记位图确系本项所持（===）时才清除——连续模式"离屏→重组合→重解码"
     *  是常态访问（非风暴）；旧"仅清死引用"逻辑会让"在位重解"检查误报，且对兄弟项
     *  （同 raw 两半共享键）语义更精确。 */
    fun unregisterBorrowed(page: Int, bmp: Bitmap?) {
        borrowed.computeIfPresent(page) { _, w ->
            val cur = w.get()
            if (cur == null || cur === bmp) null else w
        }
    }

    /** P-R8 记账：解码时刻（60s 环形；巡检协程异步消费）。键含 half（P-S6 d）：
     *  同 raw 的两个半页项各有独立"页"身份，避免兄弟解码触发风暴误报。 */
    private fun recordDecode(page: Int, half: Int) {
        val key = if (half >= 1) page * 2 + 1 else page * 2
        val dq = decodeStamps.getOrPut(key) { ArrayDeque() }
        synchronized(dq) { dq.addLast(System.currentTimeMillis()) }
    }

    /**
     * v0.3.3 大页模式"读一页预取下一页"：当前页落地 600ms 后，若用户仍停在它上面
     * （= 在读，而非连翻走）→ 放行 +1 页（仅一页，不链式——只有 +1 页真正成为当前页时，
     * 它的落地才会再触发下一次预取）。v0.6.0：放行=准入信号，不再直接触发加载（P-R6）。
     */
    private fun scheduleNeighborPrefetch(bookId: Long, page: Int) {   // page = raw
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            val cur = open.value
            if (cur.bookId != bookId || rawOfPage(cur.page) != page) return@launch
            val next = page + 1
            if (next >= rawCount || pageAt(next) != null) return@launch   // raw 域边界（P-S6 b）
            allowAhead = next
            bumpAdmission()
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
                    bumpAdmission()
                    Log.i("Reader", "big-page mode ON (slowStreak=$slowStreak, last ${ms}ms): " +
                        "loading current page only, prefetch paused")
                }
            }
            ms < FAST_LOAD_MS -> {
                fastStreak++; slowStreak = 0
                if (bigPageMode && fastStreak >= 3) {
                    bigPageMode = false
                    bumpAdmission()
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
        if (page !in 0 until rawCount) return                        // raw 域边界（P-S6 c）
        val states = trStates.value
        val cur = states[page]
        // FAILED 也视为本会话终态：无文本页（插页/封面）如果允许重试，每次重组都会
        // 白跑一遍 det+OCR。需要重试时走“重译当前页”（会把状态清回 NONE 并重跑）。
        if (cur == TranslatePageState.READY || cur == TranslatePageState.BUSY ||
            cur == TranslatePageState.QUEUED || cur == TranslatePageState.FAILED) return
        // only translate around the visible page, not the whole prefetch window
        if (prefetchOnly && page != rawOfPage(st.page)) return
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
        if (page !in 0 until st.pageCount) return     // page = 显示页域
        val raw = rawOfPage(page)
        open.value = st.copy(page = page)
        if (st.translateEnabled && engineMode() == "sidecar") {
            viewModelScope.launch(CoreDispatcher) { NativeBridge.translateFocus(raw) }
        }
        if (persist && st.bookId != 0L) {
            val rawN = rawCount
            viewModelScope.launch(CoreDispatcher) {
                NativeBridge.saveProgress(st.bookId, raw, rawN)   // 进度恒 raw（P-S8）
            }
        }
        maybeTranslateOnDevice(raw, prefetchOnly = false)
        // v0.3.4 真空卡死修复的归位形态（P-R6）：goto 是"当前页变更"的统一入口——
        // 它只负责 ①清一次性预取放行 ②准入信号 bump（唤醒挂起的项；isCurrent key 变化重武装失败页）。
        // "当前页必有加载路径"由"组合项在=解码协程在"结构性成立（一页一项一协程，I1）。
        allowAhead = null
        bumpAdmission()
    }

    fun setPrefs(p: ReaderPrefs) {
        prefs.value = p
        CsSettings.setInt("reader_fit", p.fit)
        CsSettings.setBool("reader_rtl", p.rtl)
        CsSettings.setBool("reader_spread", p.spread)
        CsSettings.setInt("reader_bg", p.bg)
        // v0.4.3：对齐设置页"改即存"（SettingsScreen 六处先例）——否则阅读器偏好
        // 只活到进程结束，重启静默回默认（用户切了"宽度"却像没设置过）。
        CsSettings.save()
    }

    // -------------------------------------------------------------- translate

    /** 翻译 overlay 查询（消费者=页项 200ms 身份轮询——评估 G5：替代旧 revision 重启三态覆盖）。 */
    fun translatedAt(page: Int): Bitmap? = synchronized(trCache) { trCache.get(page) }

    private fun startTranslatePolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (open.value.translateEnabled && open.value.bookId != 0L) {
                // Poll a window around the focus page（raw 域；P-S8）
                val page = rawOfPage(open.value.page)
                val states = HashMap<Int, TranslatePageState>()
                for (p in (page - 1)..(page + 4)) {
                    if (p !in 0 until rawCount) continue
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
                        NativeBridge.translateFocus(rawOfPage(st.page))
                        startTranslatePolling()
                    }
                    "backend" -> {
                        BookTranslateJob.ensureStarted(st.bookId, rawCount, rawOfPage(st.page))
                        refreshBackendPage(rawOfPage(st.page))
                    }
                    else -> {
                        NativeBridge.translateConfigure(null, null, null, -1, -1, -1, true)
                        maybeTranslateOnDevice(rawOfPage(st.page), prefetchOnly = false)
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
        val raw = rawOfPage(st.page)          // 翻译全 raw 域（P-S8）
        if (engineMode() == "backend") {
            // 只重译本页（fresh=1 让服务端绕缓存），**不清整本档案**——整本进度不能丢
            BookTranslateJob.focus(st.bookId, raw, fresh = true)
            synchronized(trCache) { trCache.remove(raw) }
            trStates.value = trStates.value + (raw to TranslatePageState.QUEUED)
            return
        }
        viewModelScope.launch(CoreDispatcher) {
            NativeBridge.clearBookArchive(st.bookId)
            NativeBridge.translateRetranslate(raw)
            synchronized(trCache) { trCache.remove(raw) }
            if (engineMode() == "ondevice") {
                trStates.value = trStates.value + (raw to TranslatePageState.NONE)
                maybeTranslateOnDevice(raw, prefetchOnly = false, force = true)
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
