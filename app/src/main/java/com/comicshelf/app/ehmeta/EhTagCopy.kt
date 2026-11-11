package com.comicshelf.app.ehmeta

/** 长按 tag 行的剪贴板载荷（纯数据；契约由 EhTagCopyTest 锁定）。 */
internal data class TagCopyPayload(val text: String, val toast: String)

internal const val TAG_COPY_TOAST_PREFIX = "已复制标签："

/**
 * 决定"长按一个 tag 时要复制什么"：
 *   text  = 原名 [name] —— tag 的规范标识（唯一、恒非空、与 E-Hentai 站内检索一致）；
 *           [nameZh] 是可空翻译显示层，**不进入剪贴板**。
 *   toast = 含 text 的确认文案；有中文名时以括号附注（仅用于确认）。
 * 空白 name（脏数据）→ null：调用方不得写剪贴板（不覆盖用户已有内容）。
 */
internal fun tagCopyPayload(name: String, nameZh: String?): TagCopyPayload? {
    if (name.isBlank()) return null
    val label = if (nameZh.isNullOrBlank()) name else "$name（$nameZh）"
    return TagCopyPayload(text = name, toast = "$TAG_COPY_TOAST_PREFIX$label")
}
