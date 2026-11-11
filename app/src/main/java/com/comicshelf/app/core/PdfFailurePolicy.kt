package com.comicshelf.app.core

import java.io.FileNotFoundException
import java.io.IOException

/**
 * Maps PdfRenderer/SMB failures to a user-facing reason and a retry policy.
 *
 * Permanent errors (password protected, malformed xref) will not heal by
 * retrying; transient ones (SMB offline, timeout) should be retried after a
 * cooldown. Kept Android-free so it can be unit-tested on the JVM.
 */
internal object PdfFailurePolicy {
    fun permanent(t: Throwable): Boolean = when (t) {
        is SecurityException -> true
        is IOException -> t.message?.contains("Unable to load", ignoreCase = true) == true
        else -> false
    }

    fun message(t: Throwable): String = when (t) {
        is SecurityException -> "PDF 已加密，需要密码"
        is FileNotFoundException -> "文件不存在或无法访问"
        is IOException ->
            if (t.message?.contains("Unable to load", ignoreCase = true) == true) {
                "PDF 文件损坏或格式不受支持"
            } else {
                "读取 PDF 失败：${t.message ?: "IO 错误"}"
            }
        else -> "PDF 打开失败：${t.javaClass.simpleName}"
    }
}
