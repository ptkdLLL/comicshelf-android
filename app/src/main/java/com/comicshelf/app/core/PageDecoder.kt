package com.comicshelf.app.core

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import com.comicshelf.app.core.NativeBridge.readPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Page decode pipeline — the performance-critical path of the reader.
 *
 * 1. Native archive read (miniz / UnRAR / folder) → compressed image bytes.
 * 2. Android ImageDecoder: NEON-tuned platform codecs (JPEG via libjpeg-turbo,
 *    PNG, GIF, WebP, AVIF/HEIF on this device) with subsampled decode to the
 *    target size — no full-resolution intermediate is ever allocated.
 * 3. Fallback for formats the platform lacks (PSD/TGA/HDR/PNM): the proven
 *    C++ chain (stb_image / libwebp / libavif) via nativeDecodePageRGBA.
 */
object PageDecoder {

    /** Sniff formats ImageDecoder does not accept (falls back to native). */
    private fun needsNativeDecode(b: ByteArray): Boolean {
        if (b.size < 12) return true
        // PSD
        if (b[0] == '8'.code.toByte() && b[1] == 'B'.code.toByte() &&
            b[2] == 'P'.code.toByte() && b[3] == 'S'.code.toByte()) return true
        // HDR (Radiance)
        if (b[0] == '#'.code.toByte() && b[1] == '?'.code.toByte()) return true
        // PNM family P1..P6
        if (b[0] == 'P'.code.toByte() && b[1] in '1'.code.toByte()..'6'.code.toByte()) return true
        // TGA has no magic; heuristically: footer "TRUEVISION-XFILE" or
        // plausible raw header with colormap flag. Cheap conservative check:
        // let ImageDecoder try first, it fails fast on unknown data.
        return false
    }

    /**
     * Decodes page [page] of [bookId] so the longest edge is <= [targetDim].
     * Returns null when the page cannot be read at all.
     */
    suspend fun decode(bookId: Long, page: Int, targetDim: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val bytes = readPage(bookId, page) ?: return@withContext null
            decodeBytes(bookId, page, bytes, targetDim)
        }

    /**
     * 后台整本翻译专用：经独立阅读器会话读页（不依赖 UI 是否打开该书，
     * 也不打扰正在阅读的其他书）。解码路径与 [decode] 完全一致。
     */
    suspend fun decodeJob(bookId: Long, page: Int, targetDim: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val bytes = NativeBridge.jobReadPage(bookId, page) ?: return@withContext null
            decodeBytes(bookId, page, bytes, targetDim)
        }

    private fun decodeBytes(bookId: Long, page: Int, bytes: ByteArray,
                            targetDim: Int): Bitmap? {
        if (bytes.isEmpty()) {
            Log.w("PageDecoder", "readPage($bookId,$page) returned no bytes")
            return null
        }
        val t1 = System.nanoTime()
        var bmp: Bitmap? = null
        if (!needsNativeDecode(bytes)) {
            bmp = tryDecodePlatform(bytes, targetDim)
        }
        if (bmp == null) bmp = tryDecodeNative(bytes, targetDim)
        val tDecode = (System.nanoTime() - t1) / 1e6
        if (bmp == null) {
            Log.w("PageDecoder", "decode failed book=$bookId page=$page len=${bytes.size}")
        } else {
            Log.d("PageDecoder",
                  "page ok book=$bookId page=$page ${bmp.width}x${bmp.height} " +
                  "decode=${"%.1f".format(tDecode)}ms")
        }
        return bmp
    }

    private fun tryDecodePlatform(bytes: ByteArray, targetDim: Int): Bitmap? = try {
        val src = ImageDecoder.createSource(bytes)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            // Power-of-two subsample: decode at <= 2x the target, keeps zoom crisp.
            var sample = 1
            while (max(w, h) / (sample * 2) >= targetDim) sample *= 2
            // v0.3.3 超大页像素预算：单页解码 ≤ 12MP（≈48MB ARGB）。
            // 巨页（4535×6307 = 28.6MP）全清解码 = 114MB/张，5 张页位图就占 550MB →
            // 整机内存紧缩（实测 lmkd 连杀后台、单页加载退化到 9s、分配卡死 60s+）。
            // 12MP（本机 2268×3153）已覆盖 2x 捏合缩放的 1:1 像素（1080×2=2160），
            // 仅 >2x 超高倍缩放略软——用一点锐度换稳定性。
            while ((w.toLong() * h) / (sample.toLong() * sample) > 12_000_000L) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            // Hardware bitmaps draw straight from GPU memory — the fast path
            // for Compose. Translated overlays are composed separately.
            if (Build.VERSION.SDK_INT >= 28) {
                decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT // hw when possible
            }
        }
    } catch (t: Throwable) {
        Log.d("PageDecoder", "platform decode failed (${t.javaClass.simpleName}: ${t.message})")
        null
    }

    private fun tryDecodeNative(bytes: ByteArray, targetDim: Int): Bitmap? = try {
        val bundle = NativeBridge.decodePageRGBA(bytes, targetDim)
        val (dims, px) = parseImageBundle(bundle) ?: return null
        CoverStore.rgbaToBitmap(dims, px)
    } catch (t: Throwable) {
        Log.d("PageDecoder", "native decode failed (${t.message})")
        null
    }
}
