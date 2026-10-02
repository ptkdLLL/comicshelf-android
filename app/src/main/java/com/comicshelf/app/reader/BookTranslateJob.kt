package com.comicshelf.app.reader

import android.graphics.Bitmap
import android.util.Log
import com.comicshelf.app.core.PageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 整本后台翻译队列（backend 模式，2026-10-02 设计定稿）。
 *
 * 设计要点：
 *  · 从书架/阅读器"启用翻译"即 [ensureStarted]：扫描档案得出未译页 → **单协程后台顺序**翻译；
 *    起始顺序从当前页环回（先 focus..total，再 0..focus）。
 *  · 阅读器翻页 → [focus] 把"可见页 + 后两页"插队（读得比后台快时可见页优先补上）；
 *  · [pause]/[resume] 随时停/续；[cancel] 停止本册。**进度 = 已落档案的页**，
 *    停/关/重启进程都不丢：再次 [ensureStarted] 时以档案扫描续传。
 *  · 每页产物 = 设备档案（框 + 原文 + 译文，见 OnDeviceTranslator.backendTranslateAndStore），
 *    **后台不渲染**——渲染只在页真正可见时做（省一半耗时）。
 *  · [pageDone] 每页落档后发一帧，阅读端据此刷新可见页。
 *  · 进程内运行（无前台服务）；连续 3 页失败判定后端不可达 → 自动停并记录错误。
 *  · 单页最多尝试 2 次（防坏页死循环），失败的页跳过留给下次续传。
 */
object BookTranslateJob {

    data class State(
        val bookId: Long = 0L,
        val total: Int = 0,
        val done: Int = 0,
        val failed: Int = 0,
        val running: Boolean = false,
        val paused: Boolean = false,
        val current: Int = -1,
        val avgMs: Double = 0.0,
        val error: String = "",
    ) {
        val active: Boolean get() = bookId != 0L && (running || paused)
        fun etaMs(): Long =
            if (!running || paused || avgMs <= 0) 0L
            else (((total - done).coerceAtLeast(0)) * avgMs).toLong()
    }

    private const val kPageDim = 2048   // 与 ReaderViewModel.targetDim 相同
    private const val kMaxAttempts = 2

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _pageDone = MutableSharedFlow<Int>(extraBufferCapacity = 512)
    val pageDone: SharedFlow<Int> = _pageDone.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var worker: Job? = null
    private val focusQ = ArrayDeque<Int>()       // 可见页插队（+前瞻 2 页）
    private val freshQ = ArrayDeque<Int>()       // 重译（绕服务端缓存，必做）
    private val attempts = HashMap<Int, Int>()   // 单页尝试次数
    private var pending = LinkedHashSet<Int>()   // 未译页（环回序）

    fun isActiveFor(bookId: Long): Boolean =
        state.value.bookId == bookId && state.value.active

    /** 启动/续传本册整本翻译。已在跑则只更新焦点。 */
    fun ensureStarted(bookId: Long, pageCount: Int, startPage: Int) {
        if (pageCount <= 0) return
        synchronized(lock) {
            if (_state.value.bookId == bookId && worker?.isActive == true) {
                pushFocus(startPage, pageCount)
                return
            }
            worker?.cancel()
            focusQ.clear()
            freshQ.clear()
            attempts.clear()
            val start = startPage.coerceIn(0, pageCount - 1)
            _state.value = State(bookId = bookId, total = pageCount, running = true)
            pushFocus(start, pageCount)
            launchWorker(bookId, pageCount, start)   // caller 持锁
        }
    }

    // caller 持锁。worker 内任何未预期异常都不允许带崩进程。
    private fun launchWorker(bookId: Long, total: Int, startPage: Int) {
        worker = scope.launch {
            try {
                run(bookId, total, startPage)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("BookTrJob", "worker 异常退出", t)
                _state.update {
                    it.copy(running = false, paused = true, current = -1,
                            error = "后台翻译异常: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    /** 阅读器可见页插队；fresh=true 为重译当前页（服务端也绕缓存）。 */
    fun focus(bookId: Long, page: Int, fresh: Boolean = false) {
        synchronized(lock) {
            val s = _state.value
            if (s.bookId != bookId || s.total <= 0) return
            pushFocus(page, s.total)
            if (fresh) {
                freshQ.remove(page)
                freshQ.addFirst(page)
                // 队列已完成/停止时 worker 不在 → fresh 会落进没人消费的空队列（哑火）。
                // 临时拉起 worker 只做这次重译（扫描后 pending 为空, pick 先取 freshQ）。
                if (worker?.isActive != true) {
                    Log.i("BookTrJob", "重启 worker 以执行重译页 $page (${s.done}/${s.total})")
                    launchWorker(bookId, s.total, page)   // caller 持锁
                }
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (_state.value.bookId == 0L) return
            _state.value = _state.value.copy(paused = true)
            Log.i("BookTrJob", "paused at ${_state.value.done}/${_state.value.total}")
        }
    }

    fun resume() {
        synchronized(lock) {
            val s = _state.value
            if (s.bookId == 0L) return
            if (worker?.isActive == true) {
                _state.value = s.copy(paused = false, error = "")
            } else {
                // worker 已退出(自动暂停/异常) → 不能只翻标志位, 必须重新拉起;
                // run() 重扫 pending 断点续传(已译页不重复), 从当前页继续。
                // 注意 paused 必须显式清掉: run() 循环开头就检查它, 否则新 worker 空转。
                val start = (if (s.current >= 0) s.current else 0)
                    .coerceIn(0, (s.total - 1).coerceAtLeast(0))
                _state.value = s.copy(paused = false, running = true, error = "")
                launchWorker(s.bookId, s.total, start)
            }
            Log.i("BookTrJob", "resumed at ${_state.value.done}/${_state.value.total}")
        }
    }

    /** 停止本册（不清档案；进度保留，再次 ensureStarted 从断点续传）。 */
    fun cancel() {
        synchronized(lock) {
            worker?.cancel()
            worker = null
            focusQ.clear()
            freshQ.clear()
            attempts.clear()
            _state.value = State()
        }
    }

    // caller 持锁
    private fun pushFocus(page: Int, total: Int) {
        // 逆序 addFirst：最后一次 focus 的页排最前，其后是前瞻页，旧焦点自然沉底
        for (p in intArrayOf(page + 2, page + 1, page)) {
            if (p in 0 until total) {
                focusQ.remove(p)
                focusQ.addFirst(p)
            }
        }
    }

    // caller 持锁
    private fun pick(): Pair<Int, Boolean>? {
        val f = freshQ.removeFirstOrNull()
        if (f != null) {
            pending.remove(f)
            return f to true
        }
        while (true) {
            val p = focusQ.removeFirstOrNull() ?: break
            if (pending.remove(p)) return p to false   // 已有档案的焦点页无需重译
        }
        val it = pending.iterator()
        if (it.hasNext()) {
            val p = it.next()
            it.remove()
            return p to false
        }
        return null
    }

    private suspend fun CoroutineScope.run(bookId: Long, total: Int, startPage: Int) {
        // 扫描档案：已译页直接计入 done（续传）
        var done = 0
        val miss = LinkedHashSet<Int>()
        for (i in 0 until total) {
            val p = (startPage + i) % total
            if (OnDeviceTranslator.hasBackendArchive(bookId, p)) done++
            else miss.add(p)
        }
        synchronized(lock) { pending = miss }
        _state.update { it.copy(done = done, running = true, current = -1) }
        Log.i("BookTrJob", "本册 $bookId 后台翻译: 共 $total 页, 已译 $done, 待译 ${miss.size}")

        var consecFail = 0
        var avg = 0.0
        while (isActive) {
            if (_state.value.paused) {
                delay(250)
                continue
            }
            val picked = synchronized(lock) { pick() } ?: break
            val (page, fresh) = picked
            _state.update { it.copy(current = page) }
            val t0 = System.nanoTime()
            var raw: Bitmap? = null
            var soft: Bitmap? = null
            var decoded = false
            val ok = try {
                // 独立阅读器会话读页：UI 关书/换书不影响后台；也不打扰正在阅读的书
                raw = PageDecoder.decodeJob(bookId, page, kPageDim)
                decoded = raw != null
                soft = raw?.copy(Bitmap.Config.ARGB_8888, false)   // hw 位图→软件副本
                decoded && soft != null &&
                    OnDeviceTranslator.backendTranslateAndStore(bookId, page, soft, fresh)
            } catch (t: Throwable) {
                Log.w("BookTrJob", "page $page 异常: $t")
                false
            } finally {
                raw?.recycle()
                soft?.recycle()
            }
            val ms = (System.nanoTime() - t0) / 1e6
            avg = if (avg <= 0) ms else avg * 0.7 + ms * 0.3
            synchronized(lock) {
                if (ok) {
                    attempts.remove(page)
                    if (!fresh) done++            // fresh 为覆盖重译, 不增覆盖数
                    consecFail = 0
                } else {
                    if (!decoded) Log.w("BookTrJob", "page $page 读取/解码失败(文件不可读?)")
                    val n = (attempts[page] ?: 0) + 1
                    if (n < kMaxAttempts) {
                        attempts[page] = n
                        pending.add(page)          // 回队尾, 稍后重试一次
                    } else {
                        attempts.remove(page)
                        Log.w("BookTrJob", "page $page 放弃(${kMaxAttempts} 次失败)")
                    }
                    // 只有"解出来但后端失败"才计连续失败（读失败多为文件/会话问题，
                    // 不该触发"后端不可达"保护）
                    if (decoded) consecFail++ else consecFail = 0
                }
            }
            _state.update {
                it.copy(done = done, avgMs = avg,
                        failed = it.failed + if (ok) 0 else 1)
            }
            if (ok) _pageDone.tryEmit(page)
            Log.i("BookTrJob", "page $page ${if (ok) "ok" else "FAIL"} ${"%.0f".format(ms)}ms " +
                "($done/$total)")
            if (consecFail >= 3) {
                _state.update {
                    it.copy(running = false, paused = true,
                            error = "后端连续失败，已暂停（恢复网络后点继续）")
                }   // 保留 current: 点"继续"时从断点附近重启 worker
                Log.w("BookTrJob", "连续 $consecFail 页失败 → 暂停")
                return
            }
            delay(80)   // 让出 UI/网络
        }
        _state.update { it.copy(running = false, current = -1) }
        Log.i("BookTrJob", "本册 $bookId 后台翻译完成/结束: $done/$total")
    }
}
