package com.comicshelf.app.shelf

import com.comicshelf.app.core.BookCell

/**
 * 读书返回的单本展示补丁。version 记录写入时的 queryVersion：
 * 任何内容/查询重载后版本变化，旧 patch 自动失效，避免覆盖新数据。
 */
internal data class ReadPatch(
    val pages: Int,
    val lastPage: Int,
    val readState: Int,
    val version: Int,
)

/**
 * 输入：pager 的 cell、该书的 patch（可为 null）、当前 queryVersion
 * 输出：同版本的 patch 合并后的 cell；否则原 cell（引用不变）
 * 边界：patch.version != version → 忽略；patch == null → 原 cell
 * 失败：无（纯函数，无 I/O）
 */
internal fun patchedCell(cell: BookCell, patch: ReadPatch?, version: Int): BookCell {
    if (patch == null || patch.version != version) return cell
    return cell.copy(
        pages = patch.pages,
        lastPage = patch.lastPage,
        readState = patch.readState,
    )
}
