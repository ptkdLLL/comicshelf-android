package com.comicshelf.app.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * On-demand PDF cover + page-count service for the shelf.
 *
 * The native cover pipeline deliberately defers PDFs (see generate_cover's
 * "pdf managed by app"). This service renders page 0 through the same
 * ProxyFD/PdfRenderer path as the reader, then persists the JPEG + DB metadata
 * through the existing put_cover/update_pages JNI. One job per book, at most
 * two concurrent opens, and a cooldown so a flaky SMB share cannot be hammered.
 */
object PdfMetaService {

    sealed class Result {
        data class Ok(val cover: PdfBookSource.PdfCover) : Result()
        data class Failed(val permanent: Boolean, val reason: String) : Result()
    }

    private data class Fail(val permanent: Boolean, val at: Long, val reason: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap<Long, Deferred<Result>>()
    private val failures = ConcurrentHashMap<Long, Fail>()
    private val sem = Semaphore(2)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun ensure(bookId: Long): Result {
        failures[bookId]?.let { f ->
            if (f.permanent || SystemClock.elapsedRealtime() - f.at < 30_000L) {
                return Result.Failed(f.permanent, f.reason)
            }
        }
        inflight[bookId]?.let { return it.await() }

        val job = scope.async { sem.withPermit { generate(bookId) } }
        val prev = inflight.putIfAbsent(bookId, job)
        if (prev != null) {
            job.cancel()
            return prev.await()
        }
        job.invokeOnCompletion {
            // Only remove this job; a newer one may have replaced it.
            if (inflight[bookId] === job) inflight.remove(bookId)
        }
        return job.await()
    }

    private suspend fun generate(bookId: Long): Result {
        return try {
            val path = NativeBridge.bookPath(bookId)
                ?: return fail(bookId, permanent = true, "书路径不可用")
            val cover = try {
                PdfBookSource.coverAndMeta(bookId, path)
            } catch (t: Throwable) {
                return fail(bookId, PdfFailurePolicy.permanent(t), PdfBookSource.describe(t))
            }
            val stored = NativeBridge.putCover(bookId, cover.w, cover.h, cover.argb)
            if (!stored) {
                cover.bmp.recycle()
                return fail(bookId, permanent = false, "封面写入失败")
            }
            // Page count is a bonus; a failure here must not discard the cover.
            runCatching { NativeBridge.setBookPageCount(bookId, cover.pages) }
            _version.value++
            Result.Ok(cover)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            fail(bookId, permanent = false, PdfBookSource.describe(t))
        }
    }

    private fun fail(bookId: Long, permanent: Boolean, reason: String): Result {
        failures[bookId] = Fail(permanent, SystemClock.elapsedRealtime(), reason)
        return Result.Failed(permanent, reason)
    }

    fun invalidate(bookId: Long) {
        failures.remove(bookId)
    }

    fun invalidateAll() {
        failures.clear()
    }
}
