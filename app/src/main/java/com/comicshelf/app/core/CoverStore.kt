package com.comicshelf.app.core

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Cover bitmaps on the UI side. The heavy lifting (generation, disk JPEG
 * cache, RGBA memory LRU) stays native; this only converts ready RGBA
 * payloads into Bitmaps once per book and keeps a bounded reference so
 * recomposition is free.
 */
object CoverStore {
    private val cache = object : LruCache<Long, Bitmap>(192) {
        override fun sizeOf(key: Long, value: Bitmap): Int = 1
    }

    fun get(bookId: Long): Bitmap? = synchronized(cache) { cache.get(bookId) }

    fun put(bookId: Long, bmp: Bitmap) {
        synchronized(cache) { cache.put(bookId, bmp) }
    }

    /** 清空 UI 侧封面位图缓存（配合"释放全部翻译与封面"）。 */
    fun clear() = synchronized(cache) { cache.evictAll() }

    fun rgbaToBitmap(dims: IntArray, px: ByteArray): Bitmap? {
        if (dims.size < 2) return null
        val w = dims[0]; val h = dims[1]
        if (w <= 0 || h <= 0 || px.size < w * h * 4) return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ints = IntArray(w * h)
            var i = 0
            var o = 0
            while (i < ints.size) {
                val r = px[o].toInt() and 0xFF
                val g = px[o + 1].toInt() and 0xFF
                val b = px[o + 2].toInt() and 0xFF
                val a = px[o + 3].toInt() and 0xFF
                ints[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                ++i; o += 4
            }
            bmp.setPixels(ints, 0, w, 0, 0, w, h)
            bmp
        } catch (t: Throwable) {
            null
        }
    }
}
