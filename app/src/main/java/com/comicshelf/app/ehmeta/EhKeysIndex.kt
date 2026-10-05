package com.comicshelf.app.ehmeta

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * keys.bin 只读索引（mmap，页缓存可回收，不占显式堆）。
 *
 * 格式：[u64 hash LE][u32 gid LE] × N，按【无符号】hash 升序；同 hash 保留最小 gid。
 * 铁律2：二分必须 Long.compareUnsigned（见 EhV3 注释）。
 */
class EhKeysIndex(file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val mbb: MappedByteBuffer

    val count: Int

    init {
        val len = raf.length()
        require(len % 12L == 0L) { "keys.bin 长度不是 12 的倍数: $len" }
        count = (len / 12L).toInt()
        mbb = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len)
        mbb.order(ByteOrder.LITTLE_ENDIAN)
    }

    /** 命中返回 gid（>=0），未命中 -1 */
    fun search(hash: Long): Int {
        var lo = 0
        var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val off = mid * 12
            val hm = mbb.getLong(off)
            val c = java.lang.Long.compareUnsigned(hm, hash)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mbb.getInt(off + 8)
            }
        }
        return -1
    }

    /** 文本键直接查（内部 fnv64，与索引构建侧同一函数） */
    fun searchKey(s: String): Int = search(EhV3.fnv64(s))

    /** 第 i 条记录的 hash（调试/自检用） */
    fun hashAt(i: Int): Long = mbb.getLong(i * 12)

    /** 第 i 条记录的 gid（调试/自检用） */
    fun gidAt(i: Int): Int = mbb.getInt(i * 12 + 8)

    override fun close() {
        // Android 无公开 unmap；关闭句柄后映射随 GC 回收（页缓存文件背衬，无显式堆成本）
        runCatching { raf.close() }
    }
}
