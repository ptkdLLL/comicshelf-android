package com.comicshelf.app.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.comicshelf.app.util.AppContextHolder
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDF page source backed by Android's PdfRenderer.
 *
 * Local files use a normal seekable FD. SMB files go through
 * [StorageManager.openProxyFileDescriptor], backed by a native ByteSource with
 * a 1 MiB block cache: PDFium's many small reads do not turn into a whole-file
 * download. A session owns one PdfRenderer; a dedicated render queue serializes
 * it (PdfRenderer is not thread-safe) and gives the visible page priority over
 * neighbouring prefetch and background translation pages.
 */
object PdfBookSource {

    private const val TAG = "PdfBookSource"
    private const val COVER_LONG_EDGE = 400.0
    private const val MAX_QUEUED_PAGES = 8

    data class PdfOpenResult(val pageCount: Int = 0, val error: String? = null)
    data class PdfCover(val pages: Int, val w: Int, val h: Int,
                        val argb: IntArray, val bmp: Bitmap)

    private class Session(
        val bookId: Long,
        val pfd: ParcelFileDescriptor,
        val renderer: PdfRenderer,
        val proxy: ProxyFd?,
    ) {
        val pageCount: Int = renderer.pageCount
        private val renderLock = Any()
        @Volatile var closed = false

        val uiQueue = PdfRenderQueue("ui-$bookId") { page, maxBytes ->
            renderPage(page) { w, h -> PdfRenderSize.target(w, h, maxBytes) }
        }
        val jobQueue = PdfRenderQueue("job-$bookId") { page, targetDim ->
            renderPage(page) { w, h -> jobTarget(w, h, targetDim.toInt()) }
        }

        fun renderPage(page: Int, sizeOf: (Int, Int) -> Pair<Int, Int>): Bitmap? {
            if (closed || page < 0 || page >= pageCount) return null
            synchronized(renderLock) {
                if (closed) return null
                var bmp: Bitmap? = null
                try {
                    renderer.openPage(page).use { p ->
                        val (tw, th) = sizeOf(p.width, p.height)
                        val out = Bitmap.createBitmap(tw.coerceAtLeast(1), th.coerceAtLeast(1),
                                                      Bitmap.Config.ARGB_8888)
                        bmp = out
                        out.eraseColor(Color.WHITE)
                        val m = Matrix()
                        m.setScale(out.width.toFloat() / p.width,
                                   out.height.toFloat() / p.height)
                        p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "render failed book=$bookId page=$page: " +
                        "${t.javaClass.simpleName}: ${t.message}")
                    bmp?.recycle()
                    bmp = null
                }
                return bmp
            }
        }

        fun renderCover(longEdge: Double): Bitmap? = renderPage(0) { w, h ->
            val le = max(w, h).toDouble()
            val scale = if (le > longEdge) longEdge / le else 1.0
            (w * scale).roundToInt().coerceAtLeast(1) to
                (h * scale).roundToInt().coerceAtLeast(1)
        }

        fun closeSync() {
            closed = true
            uiQueue.shutdown()
            jobQueue.shutdown()
            synchronized(renderLock) {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
                proxy?.close()
            }
        }
    }

    /**
     * ProxyFileDescriptor backed by the native SMB/local ByteSource. onRelease
     * may run after close(); AtomicBoolean + an idempotent native close keep it
     * safe. The callback receives a byte[] (SDK 26+ API), which the JNI layer
     * fills directly.
     */
    private class ProxyFd(bookId: Long) : Closeable {
        private val handle = NativeBridge.pdfSourceOpen(bookId)
        private val thread = HandlerThread("pdf-proxy-$bookId").apply { start() }
        private val released = AtomicBoolean(false)
        lateinit var pfd: ParcelFileDescriptor
            private set

        init {
            try {
                require(handle > 0) { "pdf source open failed" }
                val sm = AppContextHolder.app.getSystemService(StorageManager::class.java)
                pfd = sm.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    object : ProxyFileDescriptorCallback() {
                        override fun onGetSize(): Long =
                            NativeBridge.pdfSourceSize(handle).coerceAtLeast(0L)

                        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                            val n = NativeBridge.pdfSourceRead(handle, offset, data, size)
                            if (n < 0) throw ErrnoException("pdf-read", OsConstants.EIO)
                            return n
                        }

                        override fun onRelease() {
                            if (released.compareAndSet(false, true)) {
                                NativeBridge.pdfSourceClose(handle)
                            }
                        }
                    },
                    Handler(thread.looper),
                )
            } catch (t: Throwable) {
                thread.quitSafely()
                if (released.compareAndSet(false, true)) NativeBridge.pdfSourceClose(handle)
                throw t
            }
        }

        override fun close() {
            runCatching { pfd.close() }
            thread.quitSafely()
            if (released.compareAndSet(false, true)) NativeBridge.pdfSourceClose(handle)
        }
    }

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val jobSessions = ConcurrentHashMap<Long, Session>()
    private val pdfFlags = ConcurrentHashMap<Long, Boolean>()
    private val jobMutex = Mutex()
    private val closeExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "pdf-close").apply { isDaemon = true }
    }

    fun isOpen(bookId: Long): Boolean = sessions.containsKey(bookId)

    /** True when the book's path ends in .pdf (cached per book id). */
    suspend fun isPdfBook(bookId: Long): Boolean {
        pdfFlags[bookId]?.let { return it }
        val path = withContext(Dispatchers.IO) { NativeBridge.bookPath(bookId) } ?: return false
        val flag = path.endsWith(".pdf", ignoreCase = true)
        pdfFlags[bookId] = flag
        return flag
    }

    fun invalidateFlags(bookId: Long) {
        pdfFlags.remove(bookId)
    }

    fun invalidateAllFlags() {
        pdfFlags.clear()
    }

    fun describe(t: Throwable): String = PdfFailurePolicy.message(t)

    /**
     * Opens the book for the UI reader. SMB reads stay on demand through the
     * proxy FD; there is no materialize/download step. Returns a page count or
     * a typed error for the reader's error page.
     */
    suspend fun open(bookId: Long, path: String): PdfOpenResult = withContext(Dispatchers.IO) {
        close(bookId)
        val t0 = SystemClock.elapsedRealtime()
        try {
            val session = buildSession(bookId, path)
            sessions[bookId] = session
            pdfFlags[bookId] = true
            Log.i(TAG, "open book=$bookId pages=${session.pageCount} " +
                "via=${if (path.startsWith("smb://", ignoreCase = true)) "proxy" else "local"} " +
                "in ${SystemClock.elapsedRealtime() - t0}ms")
            PdfOpenResult(pageCount = session.pageCount)
        } catch (t: Throwable) {
            val msg = describe(t)
            Log.w(TAG, "open failed book=$bookId path=$path: $msg " +
                "(${t.javaClass.simpleName})")
            PdfOpenResult(error = msg)
        }
    }

    private fun buildSession(bookId: Long, path: String): Session {
        var proxy: ProxyFd? = null
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            val localFd: ParcelFileDescriptor = if (path.startsWith("smb://", ignoreCase = true)) {
                proxy = ProxyFd(bookId)
                proxy.pfd
            } else {
                val f = File(path)
                if (!f.isFile) throw FileNotFoundException(path)
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            fd = localFd
            val r = PdfRenderer(localFd)
            renderer = r
            if (r.pageCount <= 0) throw IOException("empty pdf")
            return Session(bookId, localFd, r, proxy)
        } catch (t: Throwable) {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
            proxy?.close()
            throw t
        }
    }

    fun close(bookId: Long) {
        val session = sessions.remove(bookId) ?: return
        session.closed = true
        closeExecutor.execute { runCatching { session.closeSync() } }
        Log.i(TAG, "close book=$bookId")
    }

    /** UI page render: visible page first, bounded by the reader byte budget. */
    suspend fun render(bookId: Long, page: Int, maxBytes: Long,
                       foreground: Boolean = false): Bitmap? {
        val session = sessions[bookId] ?: return null
        if (page < 0 || page >= session.pageCount) return null
        return session.uiQueue.submit(page, maxBytes, foreground)
    }

    /**
     * Background translation render. Own session so the UI reader can be
     * closed/reopened independently. Never upscales beyond [targetDim].
     */
    suspend fun renderJob(bookId: Long, page: Int, targetDim: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val session = jobSession(bookId) ?: return@withContext null
            if (page < 0 || page >= session.pageCount) return@withContext null
            session.jobQueue.submit(page, targetDim.toLong(), foreground = false)
        }

    fun setFocus(bookId: Long, page: Int) {
        sessions[bookId]?.uiQueue?.setFocus(page)
    }

    fun closeJob(bookId: Long) {
        val session = jobSessions.remove(bookId) ?: return
        session.closed = true
        closeExecutor.execute { runCatching { session.closeSync() } }
        Log.i(TAG, "close job book=$bookId")
    }

    private suspend fun jobSession(bookId: Long): Session? = withContext(Dispatchers.IO) {
        jobSessions[bookId]?.let { return@withContext it }
        jobMutex.withLock {
            jobSessions[bookId]?.let { return@withLock it }
            // Only one background job book is expected; bound FD usage.
            jobSessions.keys.filter { it != bookId }.forEach { stale ->
                jobSessions.remove(stale)?.let { s ->
                    s.closed = true
                    closeExecutor.execute { runCatching { s.closeSync() } }
                }
            }
            val path = NativeBridge.bookPath(bookId) ?: return@withLock null
            runCatching { buildSession(bookId, path) }.getOrNull()?.also {
                jobSessions[bookId] = it
                Log.i(TAG, "job open book=$bookId pages=${it.pageCount}")
            }
        }
    }

    /**
     * Transient session for the shelf cover service: page 0 at 400 px plus the
     * page count. It never enters [sessions], so it cannot steal the reader's
     * focus/queue.
     */
    suspend fun coverAndMeta(bookId: Long, path: String): PdfCover =
        withContext(Dispatchers.IO) {
            val session = buildSession(bookId, path)
            try {
                val bmp = session.renderCover(COVER_LONG_EDGE)
                    ?: throw IOException("cover render failed")
                val w = bmp.width
                val h = bmp.height
                val argb = IntArray(w * h)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                Log.i(TAG, "cover book=$bookId ${w}x${h} pages=${session.pageCount}")
                PdfCover(session.pageCount, w, h, argb, bmp)
            } finally {
                session.closeSync()
            }
        }

    private fun jobTarget(w: Int, h: Int, targetDim: Int): Pair<Int, Int> {
        val longEdge = max(w, h).toDouble()
        val scale = if (targetDim > 0 && longEdge > targetDim) targetDim / longEdge else 1.0
        return (w * scale).roundToInt().coerceAtLeast(1) to
            (h * scale).roundToInt().coerceAtLeast(1)
    }
}

/**
 * One render thread per PdfRenderer session. Requests are ordered by priority
 * (0 = visible, 1 = prefetch/job) then FIFO. A focus change promotes a queued
 * page; the queue is bounded so a fast scroll cannot build an unbounded
 * backlog of obsolete pages.
 */
private class PdfRenderQueue(
    private val name: String,
    private val render: (Int, Long) -> Bitmap?,
) {
    private data class Req(
        val page: Int,
        val maxBytes: Long,
        val prio: Int,
        val seq: Long,
        val cont: CancellableContinuation<Bitmap?>,
    )

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val q = PriorityQueue<Req>(compareBy<Req>({ it.prio }, { it.seq }))
    private var seq = 0L
    private var worker: Thread? = null
    private var closed = false

    @Volatile private var focus = -1

    fun setFocus(page: Int) {
        lock.withLock {
            focus = page
            val hit = q.firstOrNull { it.page == page } ?: return@withLock
            q.remove(hit)
            q.add(hit.copy(prio = 0, seq = seq++))
        }
    }

    suspend fun submit(page: Int, maxBytes: Long, foreground: Boolean): Bitmap? =
        suspendCancellableCoroutine { cont ->
            var rejected = false
            var victim: Req? = null
            lock.withLock {
                if (closed) {
                    rejected = true
                } else {
                    val r = Req(page, maxBytes,
                                if (foreground || page == focus) 0 else 1, seq++, cont)
                    if (q.size >= 8) {
                        val v = q.filter { it.prio > 0 }
                            .maxByOrNull { abs(it.page - focus) }
                        if (v != null) {
                            q.remove(v)
                            victim = v
                        }
                    }
                    q.add(r)
                    ensureWorkerLocked()
                    cond.signalAll()
                }
            }
            if (rejected) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            victim?.let { if (it.cont.isActive) it.cont.resume(null) }
            cont.invokeOnCancellation {
                lock.withLock { q.removeIf { it.cont === cont } }
            }
        }

    private fun ensureWorkerLocked() {
        if (worker != null) return
        worker = Thread(Runnable { runWorker() }, "pdf-render-$name").apply {
            isDaemon = true
            start()
        }
    }

    private fun runWorker() {
        while (true) {
            val req = lock.withLock {
                while (!closed && q.isEmpty()) cond.await()
                if (closed && q.isEmpty()) return@withLock null
                q.poll()
            }
            if (req == null) return
            if (!req.cont.isActive) continue
            val bmp = runCatching { render(req.page, req.maxBytes) }.getOrNull()
            if (req.cont.isActive) req.cont.resume(bmp)
        }
    }

    fun shutdown() {
        val pending = ArrayList<CancellableContinuation<Bitmap?>>()
        lock.withLock {
            closed = true
            while (q.isNotEmpty()) q.poll()?.let { pending.add(it.cont) }
            cond.signalAll()
        }
        pending.forEach { if (it.isActive) it.resume(null) }
    }
}

/**
 * Pure sizing rule for PDF rasterization (kept Android-free for JVM tests).
 *
 * Long edge is clamped to [1600, 2400] so a 768 px scan is drawn at ~1072 px
 * wide (1:1 on a 1080 px phone column) while a 6000 px page is downscaled.
 * The reader's byte budget then shrinks both dimensions proportionally.
 */
internal object PdfRenderSize {
    const val MIN_LONG_EDGE = 1600.0
    const val MAX_LONG_EDGE = 2400.0

    fun target(pageW: Int, pageH: Int, maxBytes: Long): Pair<Int, Int> {
        val pw = pageW.coerceAtLeast(1)
        val ph = pageH.coerceAtLeast(1)
        val longEdge = max(pw, ph).toDouble()
        var scale = when {
            longEdge > MAX_LONG_EDGE -> MAX_LONG_EDGE / longEdge
            longEdge < MIN_LONG_EDGE -> min(MIN_LONG_EDGE / longEdge, 2.0)
            else -> 1.0
        }
        if (maxBytes > 0) {
            val cap = sqrt(maxBytes / 4.0 / (pw.toDouble() * ph.toDouble()))
            if (scale > cap) scale = cap
        }
        val w = (pw * scale).roundToInt().coerceAtLeast(1)
        val h = (ph * scale).roundToInt().coerceAtLeast(1)
        return w to h
    }
}
