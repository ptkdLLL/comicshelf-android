package com.comicshelf.app.core

import java.io.File

/**
 * SMB RAR 整档缓存（<filesDir>/data/smbcache）的统计与释放。
 *
 * 该目录由 native 侧在打开 smb:// 上的 RAR/CBR 时整档落盘（UnRAR 需真实文件句柄），
 * 只进不出（无上限/淘汰）；本对象是设置页「一键释放」的唯一入口。
 * 仅触碰 smbcache 目录：书库/进度/配置/翻译档案/封面均不受影响。
 * 注: 释放时若正有远程 RAR 在阅读/下载, 其缓存被删 → 该次翻页/下载失败,
 * 再次打开会自动重新缓存（自愈）; UI 确认文案已说明。
 */
object ArchiveCache {
    const val DIR_REL = "data/smbcache"

    data class Stats(val files: Int, val bytes: Long)

    data class ReleaseResult(val files: Int, val bytes: Long, val ok: Boolean)

    /** 递归统计目录内文件数与总字节；目录不存在 → (0, 0)。 */
    fun stats(filesDir: File): Stats {
        val dir = File(filesDir, DIR_REL)
        var files = 0
        var bytes = 0L
        fun walk(f: File) {
            if (f.isFile) {
                files++
                bytes += f.length()
                return
            }
            f.listFiles()?.forEach { walk(it) }
        }
        if (dir.isDirectory) dir.listFiles()?.forEach { walk(it) }
        return Stats(files, bytes)
    }

    /** 删除缓存目录全部内容并重建空目录；返回删除前统计与完成标记。
     *  单个条目删除失败则跳过（ok=false），不抛异常；空/不存在 → (0, 0, true)（幂等）。 */
    fun release(filesDir: File): ReleaseResult {
        val dir = File(filesDir, DIR_REL)
        if (!dir.isDirectory) return ReleaseResult(0, 0L, true)
        val before = stats(filesDir)
        var ok = true
        dir.listFiles()?.forEach { if (!it.deleteRecursively()) ok = false }
        dir.mkdirs()
        return ReleaseResult(before.files, before.bytes, ok)
    }
}
