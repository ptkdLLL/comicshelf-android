package com.comicshelf.app.core

/**
 * JNI surface of libcomicshelf.so. Every function maps 1:1 to an entry in
 * app/src/main/cpp/jni/bridge.cpp — see there for the exact packing.
 *
 * Call discipline: short metadata calls go through [CoreDispatcher]; long
 * jobs (page reads, health probes, selftest) are safe on any IO thread.
 */
object NativeBridge {
    init {
        System.loadLibrary("comicshelf")
    }

    // ---- lifecycle --------------------------------------------------------
    fun start(dataDir: String, tmpDir: String): Boolean =
        nativeStart(dataDir, tmpDir)

    fun stop() = nativeStop()

    // ---- settings ---------------------------------------------------------
    fun settingsGet(key: String, def: String): String = nativeSettingsGet(key, def)
    fun settingsSet(key: String, value: String) = nativeSettingsSet(key, value)
    fun settingsSave() = nativeSettingsSave()

    // ---- libraries --------------------------------------------------------
    /** JSON: [{id, root, name, count, lastScan}] */
    fun libraries(): String = nativeLibraries()
    fun addLibrary(root: String, scan: Boolean): Long = nativeAddLibrary(root, scan)
    fun removeLibrary(id: Long) = nativeRemoveLibrary(id)
    fun renameLibrary(id: Long, name: String): Boolean = nativeRenameLibrary(id, name)
    /** [books, folders, maxDepth] or null */
    fun libraryStats(id: Long): LongArray? = nativeLibraryStats(id)

    // ---- scanning ---------------------------------------------------------
    fun scanStart(libId: Long) = nativeScanStart(libId)
    fun scanCancel() = nativeScanCancel()
    fun scanPause(paused: Boolean) = nativeScanPause(paused)
    /** [running, cancel, paused, libId, seen, added, updated, removed, dirs, serial] */
    fun scanProgress(): LongArray? = nativeScanProgress()

    // ---- 本机翻译模型（内置 llama.cpp / GGUF）-------------------------------
    /** 加载 GGUF（失败返回 false）。同一路径重复调用是幂等的。 */
    fun llmLocalLoad(path: String, threads: Int = 4, nCtx: Int = 4096): Boolean =
        nativeLlmLocalLoad(path, threads, nCtx)
    fun llmLocalUnload() = nativeLlmLocalUnload()
    fun llmLocalLoaded(): Boolean = nativeLlmLocalLoaded()
    /** 诊断：路径/线程/上次耗时与 tok/s */
    fun llmLocalInfo(): String = nativeLlmLocalInfo()
    /** 一次 chat；出错时返回 {"error": "..."} 的 JSON */
    fun llmLocalChat(system: String, user: String, maxTokens: Int = 256,
                     temp: Float = 0.7f, topP: Float = 0.8f, topK: Int = 20): String =
        nativeLlmLocalChat(system, user, maxTokens, temp, topP, topK)

    // ---- SMB / 子树刷新 ---------------------------------------------------
    /** 注册（或更新）某个共享的凭据；启动时先注册再访问 smb:// 路径。 */
    fun registerSmb(host: String, share: String, user: String, pass: String, domain: String) =
        nativeRegisterSmb(host, share, user, pass, domain)

    /** "" = 连接成功；否则返回错误文本（服务器/凭据/共享名问题）。 */
    fun smbProbe(host: String, share: String, user: String, pass: String, domain: String): String =
        nativeSmbProbe(host, share, user, pass, domain)

    /** JSON {shares:[...], error:"?"}：服务器上的共享名列表（IPC$ ShareEnum）。 */
    fun smbShares(host: String, user: String, pass: String, domain: String): String =
        nativeSmbShares(host, user, pass, domain)

    /** JSON {path,dirs:[...],archives,images,error:"?"}：共享内某目录的子目录与书数量。 */
    fun smbList(host: String, share: String, sub: String, user: String, pass: String,
                domain: String): String =
        nativeSmbList(host, share, sub, user, pass, domain)

    /** 只重扫一棵子树（库根相对路径，'' = 整库）。 */
    fun refreshDir(libId: Long, rel: String) = nativeRefreshDir(libId, rel)

    /** 服务端已删除该目录时，直接清掉它（含子树）的索引。 */
    fun removeSubtree(libId: Long, rel: String): Long = nativeRemoveSubtree(libId, rel)

    /** 打开/关闭 SMB 实时同步（CHANGE_NOTIFY），返回状态文本。 */
    fun autoSync(on: Boolean): String = nativeAutoSync(on)

    /** 实时同步状态；recheck=true 时重新建立监视并返回新状态。 */
    fun autoSyncStatus(recheck: Boolean = false): String = nativeAutoSyncStatus(recheck)

    // ---- queries ----------------------------------------------------------
    fun count(libId: Long, search: String, dirRel: String, recursive: Boolean,
              favOnly: Boolean, readState: Int): Long =
        nativeCount(libId, search, dirRel, recursive, favOnly, readState)

    /** Object[6]: long[] ids, String[] titles, int[] fav, int[] readState, int[] lastPage, int[] pages */
    fun page(libId: Long, search: String, sort: Int, desc: Boolean, offset: Long, limit: Int,
             dirRel: String, recursive: Boolean, favOnly: Boolean, readState: Int): Array<Any?>? =
        nativePage(libId, search, sort, desc, offset, limit, dirRel, recursive, favOnly, readState)

    /** JSON: [{id, name, rel, parent, count, total, depth}] */
    fun childDirs(libId: Long, parentRel: String): String = nativeChildDirs(libId, parentRel)
    fun rebuildDirs(libId: Long) = nativeRebuildDirs(libId)

    /** 单本完整文件名(书名 + ".扩展名"; 目录型书籍无扩展名)。长按菜单"导出书名"用。 */
    fun bookFileName(bookId: Long): String = nativeBookFileName(bookId)

    // ---- covers -----------------------------------------------------------
    /** Object[3]: int[1] status (2 = ready), int[2] {w,h}, byte[] rgba */
    fun coverPoll(bookId: Long): Array<Any?>? = nativeCoverPoll(bookId)
    fun forgetCover(bookId: Long) = nativeForgetCover(bookId)
    /** 手动重提失败封面：清掉失败标记（含永久失败），返回重置条数。 */
    fun retryFailedCovers(): Int = nativeRetryFailedCovers()

    // ---- user state -------------------------------------------------------
    fun setFavorite(bookId: Long, fav: Boolean) = nativeSetFavorite(bookId, fav)
    fun setReadState(bookId: Long, state: Int) = nativeSetReadState(bookId, state)
    fun saveProgress(bookId: Long, page: Int, pageCount: Int) =
        nativeSaveProgress(bookId, page, pageCount)
    fun deleteBook(bookId: Long): Boolean = nativeDeleteBook(bookId)

    // ---- bookmarks / tags -------------------------------------------------
    fun bookmarks(bookId: Long): String = nativeBookmarks(bookId)
    fun allBookmarks(): String = nativeAllBookmarks()
    fun addBookmark(bookId: Long, page: Int, label: String): Long =
        nativeAddBookmark(bookId, page, label)
    fun removeBookmark(bmId: Long): Boolean = nativeRemoveBookmark(bmId)
    fun hasBookmark(bookId: Long, page: Int): Boolean = nativeHasBookmark(bookId, page)
    fun allTags(): String = nativeAllTags()
    fun bookTags(bookId: Long): String = nativeBookTags(bookId)
    fun setBookTags(bookId: Long, tagsJson: String) = nativeSetBookTags(bookId, tagsJson)
    fun deleteTag(name: String): Boolean = nativeDeleteTag(name)

    // ---- reader -----------------------------------------------------------
    /** [pageCount, lastPage, kind] or null */
    fun readerOpen(bookId: Long): IntArray? = nativeReaderOpen(bookId)
    fun readerClose(bookId: Long) = nativeReaderClose(bookId)
    fun readPage(bookId: Long, index: Int): ByteArray? = nativeReadPage(bookId, index)
    fun pageCount(bookId: Long): Int = nativePageCount(bookId)
    /** Object[2]: int[2] {w,h}, byte[] rgba — C++ decode fallback */
    fun decodePageRGBA(bytes: ByteArray, maxDim: Int): Array<Any?>? =
        nativeDecodePageRGBA(bytes, maxDim)

    // ---- translation (sidecar engine) --------------------------------------
    fun translateConfigure(url: String?, src: String?, dst: String?, fwd: Int, back: Int,
                           cacheMb: Int, enabled: Boolean) =
        nativeTranslateConfigure(url, src, dst, fwd, back, cacheMb, enabled)
    /** JSON {ok, pipeline, error} */
    fun translateHealth(timeoutMs: Int): String = nativeTranslateHealth(timeoutMs)
    fun translateOpenBook(bookId: Long): Boolean = nativeTranslateOpenBook(bookId)
    fun translateCloseBook() = nativeTranslateCloseBook()
    fun translateFocus(page: Int) = nativeTranslateFocus(page)
    fun translateRequestWindow(page: Int, back: Int, fwd: Int) =
        nativeTranslateRequestWindow(page, back, fwd)
    /** 0 none, 1 queued, 2 busy, 3 ready, 4 failed */
    fun translateState(page: Int): Int = nativeTranslateState(page)
    /** Object[2]: int[2] {w,h}, byte[] rgba */
    fun translateGetPage(page: Int, maxDim: Int): Array<Any?>? =
        nativeTranslateGetPage(page, maxDim)
    fun translateClearCache() = nativeTranslateClearCache()
    fun translateRetranslate(page: Int) = nativeTranslateRetranslate(page)
    fun translateServiceUp(): Boolean = nativeTranslateServiceUp()
    fun translateLastError(): String = nativeTranslateLastError()
    fun setBookTranslateEnabled(bookId: Long, on: Boolean) =
        nativeSetBookTranslateEnabled(bookId, on)
    fun getBookTranslateEnabled(bookId: Long): Boolean =
        nativeGetBookTranslateEnabled(bookId)
    fun clearBookArchive(bookId: Long) = nativeClearBookArchive(bookId)
    fun translateArchiveBytes(): Long = nativeTranslateArchiveBytes()
    fun translateEvict(mb: Int): Long = nativeTranslateEvict(mb)
    fun glossary(): String = nativeGlossary()
    fun glossarySet(src: String, dst: String) = nativeGlossarySet(src, dst)
    fun glossaryRemove(src: String) = nativeGlossaryRemove(src)

    // ---- diagnostics -------------------------------------------------------
    fun selftest(scale: Int, scratchDir: String): String = nativeSelftest(scale, scratchDir)

    // ---- on-device OCR / translate (ncnn GPU) --------------------------------
    fun ocrInit(dir: String, gpu: Boolean): Boolean = nativeOcrInit(dir, gpu)
    /** NPU path: QNN/HTP context binaries staged from assets into modelsDir. */
    fun ocrInitQnn(modelsDir: String, dataDir: String): Boolean =
        nativeOcrInitQnn(modelsDir, dataDir)
    /** Detector-only init (ncnn ctd); avoids loading the large VL graph. */
    fun ocrInitCtd(dir: String, gpu: Boolean): Boolean = nativeOcrInitCtd(dir, gpu)

    /** PaddleOCR-VL-For-Manga 448² 四图上下文（vl_ctx.bin + 嵌入表/词表/提示/rope） */
    fun ocrInitQnnVl(modelsDir: String): Boolean = nativeOcrInitQnnVl(modelsDir)
    /** CTBD 检测器（BT 同款 RT-DETR-V2，ctbd_ctx.bin + V73 skel 同目录） */
    fun ocrInitCtbdQnn(modelsDir: String): Boolean = nativeOcrInitCtbdQnn(modelsDir)
    /** JSON {boxes:[{x0,y0,x1,y1,vertical,score,label,bubble}], fwdMs, postMs} */
    fun ocrDetect(rgba: IntArray, w: Int, h: Int): String = nativeOcrDetect(rgba, w, h)
    /** CTBD 检测（页面坐标，BT 后处理 conf0.3/5px/IoU0.7/0.8 包含已做） */
    fun ocrDetectCtbd(rgba: IntArray, w: Int, h: Int): String =
        nativeOcrDetectCtbd(rgba, w, h)
    fun ocrVl(rgba: IntArray, w: Int, h: Int): ByteArray? = nativeOcrVl(rgba, w, h)
    /** 页面级批量 VL：px 为所有段像素拼接，ws/hs 每段宽高；
     *  caps 逐段生成上限（按裁剪尺寸收紧，0=全局）；返回 JSON {"texts":[...],...}。 */
    fun ocrVlPage(px: IntArray, ws: IntArray, hs: IntArray, caps: IntArray): String =
        nativeOcrVlPage(px, ws, hs, caps)

    /** 小框快速通道（PP-OCRv5 rec） */
    fun ocrRecSmall(rgba: IntArray, w: Int, h: Int): ByteArray? = nativeOcrRecSmall(rgba, w, h)
    /** JSON {pre,det,post,rec,total,lines,backend} on the NPU path */
    fun ocrVlTimings(): String = nativeOcrVlTimings()
    /** JSON {texts:[...]} or {error:...}; OpenAI-compatible chat endpoint */
    fun llmTranslate(url: String, key: String, model: String, prompt: String,
                     textsJson: String, glossaryJson: String): String =
        nativeLlmTranslate(url, key, model, prompt, textsJson, glossaryJson)
    fun saveTextArchive(bookId: Long, page: Int, imgHash: String, pipeline: String,
                        textsJson: String): Boolean =
        nativeSaveTextArchive(bookId, page, imgHash, pipeline, textsJson)
    /** JSON {n, imgHash, pipeline, texts[]} or null */
    fun loadTextArchive(bookId: Long, page: Int): String? =
        nativeLoadTextArchive(bookId, page)

    /** 后台整本翻译专用读页（独立阅读器会话；不占用/不打扰 UI 的阅读器）。 */
    fun jobReadPage(bookId: Long, page: Int): ByteArray? = nativeJobReadPage(bookId, page)

    /** 清空全部翻译档案（所有书的译文文本），返回删除行数。 */
    fun clearAllTextArchive(): Long = nativeClearAllTextArchive()

    /** 清空全部封面缓存（磁盘 JPEG + native 内存 LRU）。 */
    fun clearAllCovers() = nativeClearAllCovers()

    private external fun nativeOcrInit(dir: String, gpu: Boolean): Boolean
    private external fun nativeOcrInitQnn(modelsDir: String, dataDir: String): Boolean
    private external fun nativeOcrInitCtd(dir: String, gpu: Boolean): Boolean
    private external fun nativeOcrInitQnnVl(modelsDir: String): Boolean
    private external fun nativeOcrInitCtbdQnn(modelsDir: String): Boolean
    private external fun nativeOcrDetectCtbd(rgba: IntArray, w: Int, h: Int): String
    private external fun nativeOcrRecSmall(rgba: IntArray, w: Int, h: Int): ByteArray?
    private external fun nativeOcrDetect(rgba: IntArray, w: Int, h: Int): String
    private external fun nativeOcrVl(rgba: IntArray, w: Int, h: Int): ByteArray?
    private external fun nativeOcrVlPage(px: IntArray, ws: IntArray, hs: IntArray,
                                         caps: IntArray): String
    private external fun nativeOcrVlTimings(): String
    private external fun nativeLlmTranslate(url: String, key: String, model: String,
                                            prompt: String, textsJson: String,
                                            glossaryJson: String): String
    private external fun nativeSaveTextArchive(bookId: Long, page: Int, imgHash: String,
                                               pipeline: String, textsJson: String): Boolean
    private external fun nativeLoadTextArchive(bookId: Long, page: Int): String?
    private external fun nativeJobReadPage(bookId: Long, page: Int): ByteArray?
    private external fun nativeClearAllTextArchive(): Long
    private external fun nativeClearAllCovers()

    // ---- native declarations ------------------------------------------------
    private external fun nativeStart(dataDir: String, tmpDir: String): Boolean
    private external fun nativeStop()
    private external fun nativeSettingsGet(key: String, def: String): String
    private external fun nativeSettingsSet(key: String, value: String)
    private external fun nativeSettingsSave()
    private external fun nativeLibraries(): String
    private external fun nativeAddLibrary(root: String, scan: Boolean): Long
    private external fun nativeRemoveLibrary(id: Long)
    private external fun nativeRenameLibrary(id: Long, name: String): Boolean
    private external fun nativeLibraryStats(id: Long): LongArray?
    private external fun nativeScanStart(libId: Long)
    private external fun nativeScanCancel()
    private external fun nativeScanPause(paused: Boolean)
    private external fun nativeScanProgress(): LongArray?
    private external fun nativeRegisterSmb(host: String, share: String, user: String,
                                           pass: String, domain: String)
    private external fun nativeSmbProbe(host: String, share: String, user: String,
                                        pass: String, domain: String): String
    private external fun nativeSmbShares(host: String, user: String, pass: String,
                                         domain: String): String
    private external fun nativeSmbList(host: String, share: String, sub: String, user: String,
                                       pass: String, domain: String): String
    private external fun nativeRefreshDir(libId: Long, rel: String)
    private external fun nativeRemoveSubtree(libId: Long, rel: String): Long
    private external fun nativeAutoSync(on: Boolean): String
    private external fun nativeLlmLocalLoad(path: String, threads: Int, nCtx: Int): Boolean
    private external fun nativeLlmLocalUnload()
    private external fun nativeLlmLocalLoaded(): Boolean
    private external fun nativeLlmLocalInfo(): String
    private external fun nativeLlmLocalChat(system: String, user: String, maxTokens: Int,
                                            temp: Float, topP: Float, topK: Int): String
    private external fun nativeAutoSyncStatus(recheck: Boolean): String
    private external fun nativeCount(libId: Long, search: String, dirRel: String,
                                     recursive: Boolean, favOnly: Boolean, readState: Int): Long
    private external fun nativePage(libId: Long, search: String, sort: Int, desc: Boolean,
                                    offset: Long, limit: Int, dirRel: String, recursive: Boolean,
                                    favOnly: Boolean, readState: Int): Array<Any?>?
    private external fun nativeChildDirs(libId: Long, parentRel: String): String
    private external fun nativeRebuildDirs(libId: Long)
    private external fun nativeBookFileName(bookId: Long): String
    private external fun nativeCoverPoll(bookId: Long): Array<Any?>?
    private external fun nativeForgetCover(bookId: Long)
    private external fun nativeRetryFailedCovers(): Int
    private external fun nativeSetFavorite(bookId: Long, fav: Boolean)
    private external fun nativeSetReadState(bookId: Long, state: Int)
    private external fun nativeSaveProgress(bookId: Long, page: Int, pageCount: Int)
    private external fun nativeDeleteBook(bookId: Long): Boolean
    private external fun nativeBookmarks(bookId: Long): String
    private external fun nativeAllBookmarks(): String
    private external fun nativeAddBookmark(bookId: Long, page: Int, label: String): Long
    private external fun nativeRemoveBookmark(bmId: Long): Boolean
    private external fun nativeHasBookmark(bookId: Long, page: Int): Boolean
    private external fun nativeAllTags(): String
    private external fun nativeBookTags(bookId: Long): String
    private external fun nativeSetBookTags(bookId: Long, tagsJson: String)
    private external fun nativeDeleteTag(name: String): Boolean
    private external fun nativeReaderOpen(bookId: Long): IntArray?
    private external fun nativeReaderClose(bookId: Long)
    private external fun nativeReadPage(bookId: Long, index: Int): ByteArray?
    private external fun nativePageCount(bookId: Long): Int
    private external fun nativeDecodePageRGBA(bytes: ByteArray, maxDim: Int): Array<Any?>?
    private external fun nativeTranslateConfigure(url: String?, src: String?, dst: String?,
                                                   fwd: Int, back: Int, cacheMb: Int,
                                                   enabled: Boolean)
    private external fun nativeTranslateHealth(timeoutMs: Int): String
    private external fun nativeTranslateOpenBook(bookId: Long): Boolean
    private external fun nativeTranslateCloseBook()
    private external fun nativeTranslateFocus(page: Int)
    private external fun nativeTranslateRequestWindow(page: Int, back: Int, fwd: Int)
    private external fun nativeTranslateState(page: Int): Int
    private external fun nativeTranslateGetPage(page: Int, maxDim: Int): Array<Any?>?
    private external fun nativeTranslateClearCache()
    private external fun nativeTranslateRetranslate(page: Int)
    private external fun nativeTranslateServiceUp(): Boolean
    private external fun nativeTranslateLastError(): String
    private external fun nativeSetBookTranslateEnabled(bookId: Long, on: Boolean)
    private external fun nativeGetBookTranslateEnabled(bookId: Long): Boolean
    private external fun nativeClearBookArchive(bookId: Long)
    private external fun nativeTranslateArchiveBytes(): Long
    private external fun nativeTranslateEvict(mb: Int): Long
    private external fun nativeGlossary(): String
    private external fun nativeGlossarySet(src: String, dst: String)
    private external fun nativeGlossaryRemove(src: String)
    private external fun nativeSelftest(scale: Int, scratchDir: String): String
}
