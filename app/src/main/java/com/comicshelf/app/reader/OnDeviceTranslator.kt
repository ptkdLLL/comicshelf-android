package com.comicshelf.app.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.comicshelf.app.core.CoreDispatcher
import com.comicshelf.app.core.CsSettings
import com.comicshelf.app.core.NativeBridge
import com.comicshelf.app.util.AppContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device translation engine:
 *   detect (ctd, ncnn GPU) -> OCR per block (PaddleOCR-VL, ncnn GPU)
 *   -> LLM translate (OpenAI-compatible endpoint via the C++ HTTP stack)
 *   -> Canvas render (inpaint + typeset with the system CJK font).
 *
 * Results are archived as plain text through the same schema the Windows
 * sidecar uses (library.db text_archive), so cached pages skip the GPU work.
 */
object OnDeviceTranslator {

    const val PIPELINE = "ondevice-ctd+paddleocrvl-v1"
    const val PIPELINE_ORT = "ondevice-ort-detrec-v1"
    const val PIPELINE_NPU = "ondevice-qnn-htp-2.50-v1"
    const val PIPELINE_NPU_VL = "ondevice-qnn-htp-vl448-v1"
    const val PIPELINE_LOCAL_LLM = "ondevice-llama-cpp-hymt-v1"

    /** 本机翻译模型的默认位置（HF 官方 GGUF：tencent/Hy-MT2-1.8B-GGUF） */
    const val DEFAULT_LOCAL_MODEL = "/sdcard/ComicShelfModels/hymt/Hy-MT2-1.8B-Q4_K_M.gguf"
    /**
     * system prompt = BT `trans_sakura.py` `_CHAT_SYSTEM_TEMPLATE_009` 原文(0.9 版)。
     * 本机 Hy-MT2 走 BT 同款契约: 非空行 → 去 emoji/♥ 归一/包「」→ '\n' 连接整页一次请求。
     */
    const val DEFAULT_LOCAL_SYS =
        "你是一个轻小说翻译模型，可以流畅通顺地以日本轻小说的风格将日文翻译成简体中文，" +
        "并联系上下文正确使用人称代词，不擅自添加原文中没有的代词。"

    /** backend 模式（局域网后端）默认地址：留空 = 未配置（不会误连占位 IP）。
     *  用户在设置页填写：Wi-Fi 直连填服务机的局域网 IP:8787；USB 用 adb reverse tcp:8787 后填 http://127.0.0.1:8787。 */
    const val DEFAULT_BACKEND = ""

    private var initialized = false
    private var ort = false
    private var npu = false
    private var vl = false
    /** CTBD 检测器（BT 同款 RT-DETR-V2，HTP 244ms/页，输出已按 BT 规则后处理）。 */
    private var ctbd = false

    /** 影子模式：VL 之外同时跑 PP-OCRv5 rec 并记录对比（标定级联阈值用）。 */
    private val shadowRec = CsSettings.bool("ocr_cascade_shadow", false)
    /** 调和填充用的 4 邻域偏移。 */
    private val NEIGH4 = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0),
                                 intArrayOf(0, 1), intArrayOf(0, -1))
    private val initLock = kotlinx.coroutines.sync.Mutex()

    /**
     * 整页流水线串行锁：检测(NPU) + OCR(NPU, 但前后处理吃 CPU) + 本机 LLM(CPU 4 线程)
     * 三者同时跑会互相抢 CPU（实测某页 llm 从 8.6s 掉到 63s）。这里让"一页"独占，
     * 页与页之间排队——总时长基本不变，但每页都是满速，也不会把手机烤热。
     */
    private val pipelineLock = kotlinx.coroutines.sync.Mutex()

    suspend fun ensureInit(): Boolean {
        if (initialized) return true
        // 阅读器会并发触发预取，初始化必须串行：第二次 QNN 上下文创建
        // （VL 2.4GB）在竞态下会失败并把结果回退到 rec。
        initLock.lock()
        try {
            if (initialized) return true
            val ok = ensureInitLocked()
            return ok
        } finally {
            initLock.unlock()
        }
    }

    private suspend fun ensureInitLocked(): Boolean {
        // Preferred path: PP-OCR det+rec on the Hexagon NPU through QNN (2.50
        // runtime, context binary compiled for this HTP).
        val qnnDir = withContext(Dispatchers.IO) { stageQnnAssets() }
        if (qnnDir != null) {
            val dataDir = AppContextHolder.app.filesDir.absolutePath
            val ok = withContext(Dispatchers.IO) {
                runCatching { NativeBridge.ocrInitQnn(qnnDir, dataDir) }.getOrDefault(false)
            }
            if (ok) {
                npu = true
                // 检测器升级：CTBD（BalloonTranslator 同款 RT-DETR-V2）。旧 ctd 在
                // 插画页全是噪声细条、真字全漏；CTBD 实测同页 21 框 vs BT 基准一致。
                // 初始化失败仅降级旧检测器，不影响 OCR 主路径。
                val ctbdDir = ctbdModelDir()
                if (ctbdDir != null) {
                    ctbd = withContext(Dispatchers.IO) {
                        runCatching { NativeBridge.ocrInitCtbdQnn(ctbdDir) }.getOrDefault(false)
                    }
                    Log.i("OnDeviceTr", "CTBD detector init=" + (if (ctbd) "ok" else "FAIL"))
                }
                // 识别器升级：PaddleOCR-VL-For-Manga（448²，4 图合一 NPU 上下文）
                val vlDir = vlModelDir()
                if (vlDir != null) {
                    vl = withContext(Dispatchers.IO) {
                        runCatching { NativeBridge.ocrInitQnnVl(vlDir) }.getOrDefault(false)
                    }
                }
                initialized = true
                Log.i("OnDeviceTr", "OCR backend: QNN/HTP NPU det + " +
                        if (vl) "PaddleOCR-VL-For-Manga (448²/NPU)" else "PP-OCRv5 rec")
                // 影子/级联实验需要 PP-OCRv5 rec（ONNX）常驻：初始化失败不影响主路径。
                if (CsSettings.bool("ocr_cascade_shadow", false) ||
                    CsSettings.bool("ocr_cascade", false)) {
                    val okOrt = withContext(Dispatchers.IO) {
                        runCatching { OnnxOcr.init() }.getOrDefault(false)
                    }
                    Log.i("OnDeviceTr", "cascade rec init=$okOrt (${OnnxOcr.backend})")
                }
                return true
            }
            Log.w("OnDeviceTr", "QNN init failed; falling back")
        }
        val ortOk = withContext(Dispatchers.IO) { runCatching { OnnxOcr.init() }.getOrDefault(false) }
        if (ortOk) {
            ort = true
            initialized = true
            Log.i("OnDeviceTr", "OCR backend: onnxruntime (${OnnxOcr.backend}) det+rec")
            return true
        }
        Log.w("OnDeviceTr", "onnxruntime init failed; falling back to ncnn")
        val dir = findModelsDir() ?: return false
        val gpu = CsSettings.bool("ocr_gpu", true)
        initialized = withContext(Dispatchers.IO) { NativeBridge.ocrInit(dir, gpu) }
        return initialized
    }

    fun isNpu(): Boolean = npu || ort

    /**
     * Copies the QNN context binaries, dictionary and the DSP skel out of the
     * APK assets into app-private storage. Runs once; later launches reuse the
     * staged copies (matched by size).
     */
    private fun stageQnnAssets(): String? {
        val dst = File(AppContextHolder.app.filesDir, "qnn_models")
        if (!dst.isDirectory && !dst.mkdirs()) return null
        val names = listOf("ocr_ctx.bin", "libQnnHtpV73Skel.so", "ppocrv5_dict.txt")
        try {
            for (n in names) {
                val out = File(dst, n)
                val asset = AppContextHolder.app.assets.open("qnn/$n")
                val want = asset.available().toLong()
                if (out.isFile && out.length() == want && want > 0) { asset.close(); continue }
                asset.use { input ->
                    out.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        } catch (e: Exception) {
            Log.w("OnDeviceTr", "stageQnnAssets failed: $e")
            return null
        }
        return dst.absolutePath
    }

    /**
     * PaddleOCR-VL-For-Manga 模型包目录（vl_ctx.bin ~2.4GB + 嵌入表等）。
     * 查找顺序：设置项 vl_model_dir → 应用外部目录/vlmodel → /sdcard/ComicShelfModels/vlmodel
     */
    private fun vlModelDir(): String? {
        val ext = AppContextHolder.app.getExternalFilesDir(null)
        val cands = buildList {
            val custom = runCatching { CsSettings.get("vl_model_dir", "") }.getOrDefault("")
            if (custom.isNotBlank()) add(File(custom))
            if (ext != null) add(File(ext, "vlmodel"))
            add(File("/sdcard/ComicShelfModels/vlmodel"))
        }
        return cands.firstOrNull {
            it.isDirectory && File(it, "vl_ctx.bin").isFile && File(it, "vl_embed_f16.bin").isFile
        }?.absolutePath
    }

    /** CTBD 检测器模型包（ctbd_ctx.bin + libQnnHtpV73Skel.so 同目录）。 */
    private fun ctbdModelDir(): String? {
        val ext = AppContextHolder.app.getExternalFilesDir(null)
        val cands = buildList {
            val custom = runCatching { CsSettings.get("ctbd_model_dir", "") }.getOrDefault("")
            if (custom.isNotBlank()) add(File(custom))
            if (ext != null) add(File(ext, "ctbd"))
            add(File("/sdcard/ComicShelfModels/ctbd"))
        }
        return cands.firstOrNull {
            it.isDirectory && File(it, "ctbd_ctx.bin").isFile
        }?.absolutePath
    }

    private fun findModelsDir(): String? {
        val cands = listOf(
            File(AppContextHolder.cacheDir, "models"),
            File("/storage/emulated/0/Android/data/com.comicshelf.app/files/models"),
            File("/sdcard/ComicShelfModels"),
        )
        return cands.firstOrNull { it.isDirectory && File(it, "vl_step.ncnn.param").exists() }
            ?.absolutePath
    }

    data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float,
                   val vertical: Boolean, val score: Float,
                   val label: Int = 2, val bubble: Boolean = false)

    class Result(val bitmap: Bitmap, val sourceLines: List<String>,
                 val translated: List<String>, val timings: String)

    /**
     * Translates one page. [page] is the decoded page bitmap (software).
     * Returns null on failure / when nothing was detected.
     */
    suspend fun translatePage(bookId: Long, pageIndex: Int, page: Bitmap): Result? =
        withContext(Dispatchers.Default) {
            if (!ensureInit()) {
                Log.w("OnDeviceTr", "models not initialized")
                return@withContext null
            }
            val t0 = System.nanoTime()

            // --- archive fast path keyed by the raw page bytes -----------------
            val pageBytes = withContext(Dispatchers.IO) {
                NativeBridge.readPage(bookId, pageIndex)
            }
            val imgHash = fnv1a(pageBytes ?: ByteArray(0))
            val arch = NativeBridge.loadTextArchive(bookId, pageIndex)
            if (arch == null) Log.i("OnDeviceTr", "archive absent: book=$bookId page=$pageIndex")
            arch?.let { arch ->
                val o = JSONObject(arch)
                // 归档必须与当前 OCR 引擎一致：换引擎（如 rec → VL）后旧文本失效。
                // 合成式须与存档端一致：本机 LLM 开启时串带 "local_llm+" 前缀——
                // 此前读取端只比 OCR 段 → 复合串永不相等，本机引擎下档案从不命中
                // （每页白跑 OCR+LLM ≈100s；2026-10-02 修复）。
                val wantPipe = (if (CsSettings.bool("llm_local_enabled", false))
                                    PIPELINE_LOCAL_LLM + "+" else "") +
                               (if (vl) PIPELINE_NPU_VL else if (npu) PIPELINE_NPU
                                else if (ort) PIPELINE_ORT else PIPELINE)
                val pipeOk = !o.has("pipeline") || o.optString("pipeline") == wantPipe
                val hashOk = o.optString("imgHash") == imgHash
                if (pipeOk && hashOk && !o.optJSONArray("texts").isNull(0)) {
                    // texts cached; still need boxes -> detect only
                    val det = runDetect(page)
                    val texts = toStringList(o.optJSONArray("texts"))
                    if (det.first.isEmpty() || texts.isEmpty()) return@let
                    val bmp = render(page, det.first, texts)
                    if (bmp != null) {
                        Log.i("OnDeviceTr", "archive hit, ${(System.nanoTime()-t0)/1e6} ms")
                        return@withContext Result(bmp, texts, texts, det.second)
                    }
                } else {
                    Log.i("OnDeviceTr", "archive miss: pipeOk=$pipeOk hashOk=$hashOk " +
                        "(stored=${o.optString("pipeline")})")
                }
            }

            // --- 以下整段串行（见 pipelineLock 注释）-------------------------------
            pipelineLock.lock()
            try {

            // --- detect ----------------------------------------------------------
            val (rawBoxes, detJson) = runDetect(page)
            Log.i("OnDeviceTr", "page ${page.width}x${page.height}; $detJson")
            if (rawBoxes.isEmpty()) {
                Log.i("OnDeviceTr", "no text blocks detected: $detJson")
                return@withContext null
            }
            // det 常把一个文本区切成多块（竖排分栏、笔画断开）。合并同类框：
            // 每合并 1 块省一次 vision(≈1.0s)+prefill(≈0.4s)，整句送 LLM 也更准。
            // CTBD 路径除外：native 后处理已按 BT 规范做过 IoU0.7 union 合并，
            // 这里再合并会把相邻独立台词并成一块（实测 11→3 的帮凶之一）。
            val boxes = if (detJson.startsWith("ctbd")) rawBoxes else mergeBoxes(rawBoxes)
            if (boxes.size != rawBoxes.size)
                Log.i("OnDeviceTr", "boxes merged: ${rawBoxes.size} -> ${boxes.size}")

            // --- OCR per block：分流(rec/ort 立即) + VL 段收集，一批送 NPU ----------
            // VL 侧改为页面级批量（vision/merger 为 batch=4 图）：vision exec 的
            // 1067ms 里 57% 是 softmax+eltwise（内存受限，真机 profiling），分批
            // 摊薄其固定成本。
            val sources = ArrayList(java.util.Collections.nCopies(boxes.size, ""))
            val vlSegs = ArrayList<IntArray>(16)     // [boxIdx, x0, y0, x1, y1]
            var ocrMs = 0.0
            for ((bi, b) in boxes.withIndex()) {
                val crop = cropBox(page, b)
                if (crop == null) continue
                // 块级分流：
                //  · 长条块（竖排长文/长行）：VL 的 448² 画布会把字压到 448/aspect px
                //    （22×1400 的列 ⇒ 7px < 一个 patch），物理上读不了 ⇒ NPU rec 快路径
                //    （自带竖排旋转+原生比例切段，~0.16s vs VL ~1.9s）；
                //  · 微小块（噪声、单字 SFX）：rec 够用，省掉整条 VL 流水；
                //  · 其余（气泡、普通短句）仍走 VL —— 漫画风格字它读得最好。
                val bw = b.x1 - b.x0
                val bh = b.y1 - b.y0
                val tiny = bw * bh < 1600f
                val tallWide = bh > 16f * bw || bw > 16f * bh
                val fastPath = npu && !ort && (tallWide || tiny)
                if (ort) {
                    val t1 = System.nanoTime()
                    val bmp = Bitmap.createBitmap(crop.first, crop.second, crop.third,
                                                  Bitmap.Config.ARGB_8888)
                    val r = withContext(Dispatchers.IO) {
                        runCatching { OnnxOcr.recognize(bmp) }.getOrDefault("")
                    }
                    bmp.recycle()
                    sources[bi] = r
                    ocrMs += (System.nanoTime() - t1) / 1e6
                    continue
                }
                if (fastPath) {
                    val t1 = System.nanoTime()
                    val r = withContext(Dispatchers.IO) {
                        NativeBridge.ocrRecSmall(crop.first, crop.second, crop.third)
                            ?.toString(Charsets.UTF_8)?.trim().orEmpty()
                    }
                    ocrMs += (System.nanoTime() - t1) / 1e6
                    if (r.isNotEmpty()) { sources[bi] = r; continue }
                    if (tiny) continue            // 噪声块：rec 读不出 ⇒ 不跑 VL
                    // 长条块 rec 读空 ⇒ 落 VL 兜底
                }
                // 整页文字块（小说页）：448² 固定画布把 33px 的字压到 <10px，
                // VL 在噪声上必然幻觉（实测每页输出同一串乱码）。改用**投影切条**
                // （竖排切列/横排切行）逐条走 rec —— rec 专为行识别设计
                // （自带竖排旋转 + 原生比例切段，~0.16s/条）。
                run {
                    val pageArea = page.width.toFloat() * page.height
                    if (npu && !ort && bw * bh > 0.35f * pageArea && bw > 200 && bh > 500) {
                        val t1 = System.nanoTime()
                        val (vert, bands) = splitTextBands(page, b)
                        if (bands.size >= 3) {
                            val sb = StringBuilder()
                            var ok = 0
                            withContext(Dispatchers.IO) {
                                for (r in bands) {          // bands 已是阅读序(竖排 R→L)
                                    val seg = recRegion(page, r)
                                    if (seg.isNotEmpty()) { sb.append(seg); ok++ }
                                }
                            }
                            ocrMs += (System.nanoTime() - t1) / 1e6
                            if (ok > 0) {
                                sources[bi] = sb.toString()
                                Log.i("OnDeviceTr", "text-page: ${bands.size} 条 v=$vert -> rec ok=$ok")
                                return@run
                            }
                        }
                        ocrMs += (System.nanoTime() - t1) / 1e6
                    }
                }
                if (sources[bi].isNotEmpty()) continue
                // 细长竖排块分段：**默认关闭**（2026-10-02 实测，fp16 ctx 下单段
                // 直接可读且更完整，全页 46→31s；分段仅对 ≥380px 超长块略优但会
                // 切字）。需要时用设置项 reenable（见 BALLOONTRANSLATOR_WORKFLOW §E）。
                val segOn = runCatching { CsSettings.bool("ocr_vl_segment", false) }
                    .getOrDefault(false)
                val nSeg = if (segOn && npu && !ort && !fastPath && b.vertical &&
                    bh > 2.6f * bw && bh > 200)
                    ((bh / (2.2f * bw)).toInt().coerceAtLeast(2)).coerceAtMost(4) else 1
                val x0 = b.x0.toInt().coerceAtLeast(0)
                val x1 = b.x1.toInt().coerceAtMost(page.width).coerceAtLeast(x0 + 4)
                val y0 = b.y0.toInt().coerceAtLeast(0)
                val y1 = b.y1.toInt().coerceAtMost(page.height).coerceAtLeast(y0 + 4)
                val segH = ((y1 - y0) + nSeg - 1) / nSeg
                for (k in 0 until nSeg) {
                    val sy0 = (y0 + k * segH).coerceAtMost(y1 - 4)
                    val sy1 = if (k == nSeg - 1) y1 else (sy0 + segH).coerceAtMost(y1)
                    if (sy1 - sy0 < 8) continue
                    vlSegs.add(intArrayOf(bi, x0, sy0, x1, sy1))
                }
            }
            // 一次批量 VL：所有段像素顺序拼接 → native 批量 vision+merger → 逐段 decode
            if (vlSegs.isNotEmpty()) {
                var total = 0L
                for (s in vlSegs) total += ((s[3] - s[1]) * (s[4] - s[2])).toLong()
                val flat = IntArray(total.toInt())
                val ws = IntArray(vlSegs.size)
                val hs = IntArray(vlSegs.size)
                val caps = IntArray(vlSegs.size)
                var off = 0
                for ((i, s) in vlSegs.withIndex()) {
                    val w = s[3] - s[1]
                    val h = s[4] - s[2]
                    Bitmap.createBitmap(page, s[1], s[2], w, h)
                        .getPixels(flat, off, w, 0, 0, w, h)
                    ws[i] = w
                    hs[i] = h
                    // 逐段生成上限（§8 #8）：按裁剪面积估字数（~900px²/字 = 30px 字格），
                    // 下限 16（短文/拟声词足够），上限 51 = 全局 kGenMax。
                    // 噪声块不再跑满上限 → 每段最多省 ~2s decode。
                    caps[i] = ((w.toLong() * h / 900) + 8).toInt().coerceIn(16, 51)
                    off += w * h
                }
                val t1 = System.nanoTime()
                val json = withContext(Dispatchers.IO) {
                    NativeBridge.ocrVlPage(flat, ws, hs, caps)
                }
                ocrMs += (System.nanoTime() - t1) / 1e6
                val texts = runCatching { JSONObject(json).optJSONArray("texts") }.getOrNull()
                if (texts == null) Log.w("OnDeviceTr", "vl-batch failed: ${json.take(80)}")
                val perBox = HashMap<Int, StringBuilder>()
                for (i in vlSegs.indices) {
                    val t = texts?.optString(i).orEmpty().trim()
                    if (t.isNotEmpty())
                        perBox.getOrPut(vlSegs[i][0]) { StringBuilder() }.append(t)
                }
                for ((bi, sb) in perBox) sources[bi] = sb.toString()
            }
            // 收尾：重复折叠 + 块日志
            for ((bi, b) in boxes.withIndex()) {
                val raw = sources[bi].trim()
                val (t, reps) = foldRepeats(raw)
                if (reps > 0)
                    Log.i("OnDeviceTr", "repeat x$reps folded: '${raw.take(20)}…'")
                // 注意：OCR 文本必须作为 %s 参数传入——若拼进格式串，文本里的 '%'
                // 会被 java.util.Formatter 当作转换符抛异常，直接中止整页翻译。
                Log.i("OnDeviceTr", "block[%.0f,%d %dx%d s=%.2f] vertical=%b -> '%s'".format(
                    b.x0, b.y0.toInt(), (b.x1 - b.x0).toInt(), (b.y1 - b.y0).toInt(),
                    b.score, b.vertical, t))
                sources[bi] = t
            }
            // 退化/幻觉文本防线：VL 对"没有文字"的裁剪（插画噪声框）会输出同一段
            // 混合字节的乱码（大量 U+FFFD）且同页多块重复。这类文本直接判空，
            // 既不送 LLM 白烧时间，也不会把乱码排回页面上。
            run {
                val freq = sources.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
                for (i in sources.indices) {
                    val t = sources[i]
                    if (t.isBlank()) continue
                    val degenerate = t.count { it == '\uFFFD' } >= 2 ||
                                     (t.length >= 8 && (freq[t] ?: 0) >= 2)
                    if (degenerate) {
                        Log.i("OnDeviceTr", "drop degenerate block(${t.length}): '${t.take(30)}…'")
                        sources[i] = ""
                    }
                }
            }
            val valid = sources.count { it.isNotEmpty() }
            if (valid == 0) {
                Log.i("OnDeviceTr", "OCR found nothing")
                return@withContext null
            }

            // --- 翻译：优先本机内置模型（离线），否则走旁车 LLM 服务 ----------------
            var localMs = 0.0
            var engineTag = ""
            var translated: List<String>? = null
            if (CsSettings.bool("llm_local_enabled", false)) {
                val t2 = System.nanoTime()
                translated = withContext(Dispatchers.IO) { localTranslate(sources) }
                localMs = (System.nanoTime() - t2) / 1e6
                if (translated != null) engineTag = "local"
                else Log.w("OnDeviceTr", "本机模型翻译失败，回退旁车服务")
            }
            if (translated == null) {
                val glossary = runCatching { CsSettings.get("glossary_cache", "[]") }.getOrDefault("[]")
                val realGlossary = withContext(CoreDispatcher) { NativeBridge.glossary() }
                val url = CsSettings.get("llm_url", "")
                if (url.isBlank()) {
                    Log.w("OnDeviceTr", "llm_url not configured; showing source text")
                    val bmp = render(page, boxes, sources.map { it.ifEmpty { "?" } }, sources)
                    return@withContext if (bmp != null)
                        Result(bmp, sources, sources, "no-llm") else null
                }
                val t2 = System.nanoTime()
                val resp = withContext(Dispatchers.IO) {
                    NativeBridge.llmTranslate(
                        url,
                        CsSettings.get("llm_key", ""),
                        CsSettings.get("llm_model", ""),
                        CsSettings.get("llm_prompt", ""),
                        JSONArray(sources).toString(),
                        realGlossary,
                    )
                }
                val llmMs = (System.nanoTime() - t2) / 1e6
                translated = JSONObject(resp).optJSONArray("texts")?.let { toStringList(it) }
                if (translated == null) {
                    Log.w("OnDeviceTr", "llm failed: $resp")
                    return@withContext null
                }
                localMs = llmMs
            }

            // --- render -------------------------------------------------------------
            val bmp = render(page, boxes, translated, sources)
            if (bmp == null) return@withContext null

            withContext(CoreDispatcher) {
                NativeBridge.saveTextArchive(
                    bookId, pageIndex, imgHash,
                    (if (engineTag == "local") PIPELINE_LOCAL_LLM + "+" else "") +
                    (if (vl) PIPELINE_NPU_VL else if (npu) PIPELINE_NPU
                     else if (ort) PIPELINE_ORT else PIPELINE),
                    JSONArray(translated).toString())
            }

            val timings = "det=$detJson ocr=${"%.0f".format(ocrMs)}ms(${boxes.size}块) " +
                          "llm=${"%.0f".format(localMs)}ms${if (engineTag == "local") "(本机)" else ""}" +
                          when {
                              ort -> " ort(${OnnxOcr.backend})"
                              vl -> " vl448-npu=${NativeBridge.ocrVlTimings()}"
                              else -> " vl=${NativeBridge.ocrVlTimings()}"
                          }
            Log.i("OnDeviceTr", "page done in ${(System.nanoTime() - t0) / 1e6} ms")
            Log.i("OnDeviceTr", "src: ${sources.joinToString(" | ")}")
            Log.i("OnDeviceTr", "dst: ${translated.joinToString(" | ")}")
            Result(bmp, sources, translated, timings)

            } finally {
                pipelineLock.unlock()
            }
        }

    /**
     * 合并属于同一文本区域的框（det 常把竖排多列/长句切成多块）。
     * 判据：同朝向、投影重叠 ≥60%、间隙 ≤0.35×较小边。每合并 1 块省一次
     * vision(≈1.0s)+prefill(≈0.4s) 的 NPU 开销，且整句送 LLM 更连贯。
     */
    private fun mergeBoxes(input: List<Box>): List<Box> {
        val boxes = ArrayList(input)
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    val m = tryMerge(boxes[i], boxes[j]) ?: continue
                    boxes[i] = m
                    boxes.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return boxes
    }

    private fun tryMerge(a: Box, b: Box): Box? {
        if (a.vertical != b.vertical) return null
        if (a.vertical) {
            val ov = minOf(a.y1, b.y1) - maxOf(a.y0, b.y0)
            val minH = minOf(a.y1 - a.y0, b.y1 - b.y0)
            if (minH <= 0f || ov < minH * 0.6f) return null
            val gap = if (a.x0 < b.x0) b.x0 - a.x1 else a.x0 - b.x1
            if (gap > minOf(a.x1 - a.x0, b.x1 - b.x0) * 0.35f) return null
        } else {
            val ov = minOf(a.x1, b.x1) - maxOf(a.x0, b.x0)
            val minW = minOf(a.x1 - a.x0, b.x1 - b.x0)
            if (minW <= 0f || ov < minW * 0.6f) return null
            val gap = if (a.y0 < b.y0) b.y0 - a.y1 else a.y0 - b.y1
            if (gap > minOf(a.y1 - a.y0, b.y1 - b.y0) * 0.35f) return null
        }
        return Box(minOf(a.x0, b.x0), minOf(a.y0, b.y0),
                   maxOf(a.x1, b.x1), maxOf(a.y1, b.y1),
                   a.vertical, maxOf(a.score, b.score))
    }

    /**
     * OCR 遇到重复纹理/花纹字会输出复读（"誰かが誰が誰が…"×20，实测 51 token）。
     * 折叠为 2 个周期再送下游：每个 token 省 ≈60ms 的 decode（解码器权重按 exec
     * 流读）；仅在重复 ≥4 次时折叠，正常叠词（ドキドキ等）不受影响。
     */
    private fun foldRepeats(s: String): Pair<String, Int> {
        val n = s.length
        if (n < 16) return s to 0
        val tol = (n * 0.1).toInt().coerceAtLeast(1)
        for (p in 2..(n / 4)) {
            var mism = 0
            var i = p
            while (i < n) {
                if (s[i] != s[i % p] && ++mism > tol) break
                i++
            }
            if (i >= n) {
                val reps = (n + p - 1) / p
                if (reps >= 4) return (s.substring(0, p) + s.substring(0, p)) to reps
            }
        }
        return s to 0
    }

    private suspend fun runDetect(page: Bitmap): Pair<List<Box>, String> {
        // CTBD（BT 同款检测器）：native 内部做 640² 双线性拉伸 + /255，输出即
        // 页面坐标并带 BT 后处理（conf0.3/5px/IoU0.7/0.8包含），气泡/文字分类
        // （label 0=气泡）也一并返回。无需预缩放页面。
        if (npu && ctbd) {
            val px = IntArray(page.width * page.height)
            page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
            val t0 = System.nanoTime()
            val json = withContext(Dispatchers.IO) {
                NativeBridge.ocrDetectCtbd(px, page.width, page.height)
            }
            val ms = (System.nanoTime() - t0) / 1e6
            val o = JSONObject(json)
            if (o.has("error")) {
                Log.w("OnDeviceTr", "ctbd failed: $json; falling back to legacy det")
            } else {
                val boxes = ArrayList<Box>()
                val arr = o.optJSONArray("boxes") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    // 只要文字块（label 1/2）；气泡框用于后续掩码/排版约束，
                    // 此处不进 OCR 流水线。
                    if (b.optInt("label", 2) == 0) continue
                    boxes.add(Box(b.getDouble("x0").toFloat(), b.getDouble("y0").toFloat(),
                                  b.getDouble("x1").toFloat(), b.getDouble("y1").toFloat(),
                                  b.optBoolean("vertical"), b.optDouble("score").toFloat(),
                                  b.optInt("label", 2), b.optBoolean("bubble")))
                }
                return boxes to "ctbd=${"%.0f".format(ms)}ms ${boxes.size}块"
            }
        }
        if (npu) {
            // 页图可能被解码到长边 ~4800（为了捏合缩放清晰），detector 内部还会
            // letterbox 到 ~1024：等于再缩 4.7 倍，一页小字（≈12px）只剩 2-3px，
            // 检测不到真字，插画区却被当成文字框 →（见 Hospital 页：22 个噪声框）。
            // 先把页图缩到长边 ≤1600 再检测，框坐标乘回页面空间。
            val detLong = runCatching { CsSettings.int("ocr_det_long", 1600) }.getOrDefault(1600)
            val s = min(1f, detLong.toFloat() / max(page.width, page.height).toFloat())
            val detBmp = if (s < 0.999f)
                Bitmap.createScaledBitmap(page, (page.width * s).roundToInt(),
                                          (page.height * s).roundToInt(), true)
            else page
            val px = IntArray(detBmp.width * detBmp.height)
            detBmp.getPixels(px, 0, detBmp.width, 0, 0, detBmp.width, detBmp.height)
            val t0 = System.nanoTime()
            val json = withContext(Dispatchers.IO) {
                NativeBridge.ocrDetect(px, detBmp.width, detBmp.height)
            }
            if (detBmp !== page) detBmp.recycle()
            val ms = (System.nanoTime() - t0) / 1e6
            val o = JSONObject(json)
            if (o.has("error")) return emptyList<Box>() to json
            val boxes = ArrayList<Box>()
            val arr = o.optJSONArray("boxes") ?: JSONArray()
            val inv = if (s > 0f) 1f / s else 1f
            // 过小框（脏点/装饰）不成字，丢掉可显著缩短密排页耗时。注意阈值按
            // 页面空间算：检测空间的框已乘回 inv。
            val minPx = runCatching { CsSettings.int("ocr_min_block_px", 20) }.getOrDefault(20)
            for (i in 0 until arr.length()) {
                val b = arr.getJSONObject(i)
                val box = Box(b.getDouble("x0").toFloat() * inv, b.getDouble("y0").toFloat() * inv,
                              b.getDouble("x1").toFloat() * inv, b.getDouble("y1").toFloat() * inv,
                              b.optBoolean("vertical"), b.optDouble("score").toFloat())
                val w = box.x1 - box.x0
                val h = box.y1 - box.y0
                if (min(w, h) < minPx) continue
                boxes.add(box)
            }
            return boxes to "qnn-det=${"%.0f".format(ms)}ms ${boxes.size}块"
        }
        if (ort) {
            val t0 = System.nanoTime()
            val bs = withContext(Dispatchers.IO) { OnnxOcr.detect(page) }
            val ms = (System.nanoTime() - t0) / 1e6
            val boxes = bs.filter { (it.x1 - it.x0) >= 10 || (it.y1 - it.y0) >= 24 }
                .map { b -> Box(b.x0, b.y0, b.x1, b.y1,
                                b.y1 - b.y0 > (b.x1 - b.x0) * 3 / 2, b.score) }
            return boxes to "ort-det=${"%.0f".format(ms)}ms ${boxes.size}块"
        }
        // detection input: downscale to long edge <= 1024 (matches ctd training)
        val long0 = max(page.width, page.height)
        val scale = if (long0 > 1024) 1024f / long0 else 1f
        val dw = (page.width * scale).toInt().coerceAtLeast(1)
        val dh = (page.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(page, dw, dh, true)
        val px = IntArray(dw * dh)
        small.getPixels(px, 0, dw, 0, 0, dw, dh)
        val json = withContext(Dispatchers.IO) { NativeBridge.ocrDetect(px, dw, dh) }
        val o = JSONObject(json)
        if (o.has("error")) return emptyList<Box>() to json
        val boxes = ArrayList<Box>()
        val arr = o.optJSONArray("boxes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            boxes.add(Box(
                b.getDouble("x0").toFloat() / scale,
                b.getDouble("y0").toFloat() / scale,
                b.getDouble("x1").toFloat() / scale,
                b.getDouble("y1").toFloat() / scale,
                b.optBoolean("vertical"), b.optDouble("score").toFloat()))
        }
        return boxes to "fwd=${"%.0f".format(o.optDouble("fwdMs"))}ms " +
                        "post=${"%.0f".format(o.optDouble("postMs"))}ms ${o.optInt("blocks")}块"
    }

    private fun cropBox(page: Bitmap, b: Box): Triple<IntArray, Int, Int>? {
        val x0 = max(0, b.x0.toInt()); val y0 = max(0, b.y0.toInt())
        val x1 = min(page.width, b.x1.toInt().coerceAtLeast(b.x0.toInt() + 1))
        val y1 = min(page.height, b.y1.toInt().coerceAtLeast(b.y0.toInt() + 1))
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        val w = x1 - x0; val h = y1 - y0
        val px = IntArray(w * h)
        Bitmap.createBitmap(page, x0, y0, w, h).getPixels(px, 0, w, 0, 0, w, h)
        return Triple(px, w, h)
    }

    /** Inpaints detected regions and typesets the translated lines. */
    private fun render(page: Bitmap, boxes: List<Box>, texts: List<String>,
                       srcs: List<String>? = null): Bitmap? {
        if (boxes.size != texts.size) return null
        // HARDWARE 位图不支持 getPixels()（InkMask 需要）——统一先拷软件副本。
        // 阅读器缓存页/队列解码页都可能是 HARDWARE（2026-10-02：backend 可见页渲染
        // 直接传原图 → IllegalStateException 崩进程，此处兜底）。
        val base = if (page.config == Bitmap.Config.HARDWARE)
            page.copy(Bitmap.Config.ARGB_8888, false) else page
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val inkMode = runCatching { CsSettings.bool("render_ink_mask", true) }
            .getOrDefault(true)
        var inkRes: InkMask.Result? = null
        if (inkMode) {
            // 字迹级擦除（BT 规范）：只回填字迹连通块，气泡网点/渐变/插画不受影响。
            val rects = ArrayList<RectF>(boxes.size)
            for (i in boxes.indices) {
                if (texts[i].trim().isEmpty()) continue
                val b = boxes[i]
                rects.add(RectF(b.x0, b.y0,
                    b.x1.coerceAtLeast(b.x0 + 1f), b.y1.coerceAtLeast(b.y0 + 1f)))
            }
            if (rects.isNotEmpty()) {
                val ir = ArrayList<android.graphics.Rect>(rects.size)
                for (r in rects)
                    ir.add(android.graphics.Rect(r.left.toInt(), r.top.toInt(),
                                                r.right.toInt(), r.bottom.toInt()))
                val ink = InkMask.build(base, ir)   // base = 软件副本(HARDWARE 无 getPixels)
                inkRes = ink
                run {
                    var cov = 0L
                    val total = ink.mask.size.toLong()
                    for (m in ink.mask) if (m != 0.toByte()) cov++
                    Log.i("OnDeviceTr", "ink mask: ${ink.ccs.size} cc, " +
                        "cover=${"%.2f".format(100.0 * cov / total)}%")
                }
                val w = out.width
                val h = out.height
                val buf = IntArray(w * h)
                out.getPixels(buf, 0, w, 0, 0, w, h)
                // 调和(harmonic)扩散填充：掩码像素由邻域迭代平均求值，边界 = 块内
                // 未掩码像素 —— 纸面退化为平色（与旧平填一致），网点/渐变/画面色调
                // 自然延续；且**结构上不可能采到块外颜色**（旧环带中位在巨型 CC 吞
                // 块时环带落到块外画面上，灰阶块被整块填成粉色，§E7/E8）。
                run {
                    val mask = ink.mask
                    var cnt = 0
                    for (m in mask) if (m != 0.toByte()) cnt++
                    if (cnt in 1 until w * h) {
                        val idxs = IntArray(cnt)
                        val slot = IntArray(w * h) { -1 }
                        var k = 0
                        for (p in mask.indices)
                            if (mask[p] != 0.toByte()) { idxs[k] = p; slot[p] = k; k++ }
                        // RGB 交错存值；初值=页面均值(调和填充对初值不敏感)
                        var mr = 0L; var mg = 0L; var mb = 0L
                        for (p in idxs) {
                            val c0 = buf[p]
                            mr += c0 shr 16 and 0xFF; mg += c0 shr 8 and 0xFF; mb += c0 and 0xFF
                        }
                        val i0r = (mr / cnt).toFloat(); val i0g = (mg / cnt).toFloat()
                        val i0b = (mb / cnt).toFloat()
                        var cur = FloatArray(3 * cnt)
                        var prv = FloatArray(3 * cnt)
                        for (n in 0 until cnt) {
                            cur[3 * n] = i0r; cur[3 * n + 1] = i0g; cur[3 * n + 2] = i0b
                        }
                        System.arraycopy(cur, 0, prv, 0, cur.size)
                        // Jacobi:未掩码邻域取 buf(固定边界),掩码邻域取上一轮值
                        for (it in 0 until 32) {
                            for (n in idxs.indices) {
                                val p = idxs[n]
                                val x = p % w
                                val y = p / w
                                var s0 = 0f; var s1 = 0f; var s2 = 0f
                                var cN = 0
                                for (d in NEIGH4) {
                                    val nx = x + d[0]; val ny = y + d[1]
                                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                                    val q = ny * w + nx
                                    if (mask[q] == 0.toByte()) {
                                        val c0 = buf[q]
                                        s0 += c0 shr 16 and 0xFF
                                        s1 += c0 shr 8 and 0xFF
                                        s2 += c0 and 0xFF
                                    } else {
                                        val s = 3 * slot[q]
                                        s0 += prv[s]; s1 += prv[s + 1]; s2 += prv[s + 2]
                                    }
                                    cN++
                                }
                                cur[3 * n] = s0 / cN
                                cur[3 * n + 1] = s1 / cN
                                cur[3 * n + 2] = s2 / cN
                            }
                            val t = prv; prv = cur; cur = t
                        }
                        for (n in idxs.indices) {
                            val p = idxs[n]
                            val r = prv[3 * n].toInt().coerceIn(0, 255)
                            val g = prv[3 * n + 1].toInt().coerceIn(0, 255)
                            val b2 = prv[3 * n + 2].toInt().coerceIn(0, 255)
                            buf[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b2
                        }
                    }
                }
                out.setPixels(buf, 0, w, 0, 0, w, h)
                // 调试 dump（排查"擦除失效"）：before/after 落盘供像素级对比。
                // 默认关（每次渲染写 2×2.5MB）；排查时开 render_ink_debug。
                runCatching {
                    val d = java.io.File("/sdcard/ComicShelfModels")
                    if (CsSettings.bool("render_ink_debug", false) && d.isDirectory) {
                        d.resolve("ink_dbg_before.png").outputStream().use {
                            page.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                        }
                        d.resolve("ink_dbg_after.png").outputStream().use {
                            out.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it)
                        }
                    }
                }
            }
        }
        // 全部框(扩张避让用)
        val allRects = ArrayList<RectF>(boxes.size)
        for (i in boxes.indices) {
            val b = boxes[i]
            allRects.add(RectF(b.x0, b.y0, b.x1.coerceAtLeast(b.x0 + 1f),
                               b.y1.coerceAtLeast(b.y0 + 1f)))
        }
        for (i in boxes.indices) {
            val b = boxes[i]
            val text = texts[i].trim()
            if (text.isEmpty()) continue
            val rect = RectF(b.x0, b.y0, b.x1, b.y1)
            if (!inkMode) inpaint(canvas, out, rect)
            // 排版方向与字号按**原图实测**（框宽高比在整页框上会判错：991x1444 的
            // 竖排正文页因 1.46<1.5 被判横排 → 竖版被强制横版化，见 §E3）：
            // 竖排 → 竖列 R→L、字号与列位置均取原图（列栅格对齐）; 横排 → 原路径。
            // 文字颜色同样按原图实测（黑底白字页写死黑色=隐形, §E4）。
            // 竖排列数提示: OCR 行数 ≈ 原列数(竖排每列一行), 用于修复密排投影
            // 融合导致的"多列被当成单列"→ 字号被压到极小(§E10)。
            val srcLines = srcs?.getOrNull(i)?.let {
                if (it.isBlank()) 0 else it.count { c -> c == '\n' } + 1
            } ?: 0
            val (vert, cell, slots) = measureType(base, rect, b.vertical, srcLines)
            val spec = chooseInk(base, out, inkRes?.mask, rect)
            // 动态字号适配(§E10):仅当译文在**原文实测字号**下装不下(需要比原文更多
            // 空间)时,向周边平坦留白扩张可用域再重拟合;原文观感内装得下的框保持
            // 原位不动(已验收页面的排版不受影响)。
            val needMore = if (vert) {
                val perCol = ((rect.height() - 6f) / (cell * 1.06f)).toInt().coerceAtLeast(1)
                val cols = (text.length + perCol - 1) / perCol
                val avail = if (slots.isNotEmpty()) slots.size
                            else ((rect.width() - 6f) / (cell * 1.06f)).toInt().coerceAtLeast(1)
                cols > avail
            } else {
                val cpl = (rect.width() / cell).toInt().coerceAtLeast(1)
                val lines = (text.length + cpl - 1) / cpl
                lines * cell * 1.05f > rect.height()
            }
            val area = if (needMore) expandRect(base, rect, allRects) else rect
            val grew = area.width() > rect.width() + 0.5f ||
                       area.height() > rect.height() + 0.5f
            val size = if (vert) typesetVertical(canvas, text, area, cell, slots, spec, grew)
                       else typeset(canvas, text, area, spec)
            // §E10 逐块字号证据: box=原框 area=扩张域 cell=原文实测 n=字数 size=最终字号
            Log.i("OnDeviceTr", "fit #$i v=$vert n=${text.length} srcL=$srcLines cell=${"%.0f".format(cell)}" +
                " box=[${rect.left.toInt()},${rect.top.toInt()}" +
                " ${(rect.width() + 0.5f).toInt()}x${(rect.height() + 0.5f).toInt()}]" +
                (if (grew) " area=[${area.left.toInt()},${area.top.toInt()}" +
                    " ${(area.width() + 0.5f).toInt()}x${(area.height() + 0.5f).toInt()}]"
                 else "") +
                " size=${"%.1f".format(size)}")
        }
        return out
    }

    /** Fill the region with the median border colour (cheap, robust on flat bubbles). */
    private fun inpaint(canvas: Canvas, bmp: Bitmap, r: RectF) {
        val pad = 2
        val x0 = max(0, r.left.toInt() - pad); val y0 = max(0, r.top.toInt() - pad)
        val x1 = min(bmp.width - 1, r.right.toInt() + pad)
        val y1 = min(bmp.height - 1, r.bottom.toInt() + pad)
        if (x1 - x0 < 2 || y1 - y0 < 2) return
        val xs = intArrayOf(x0, x0, x1, x1, (x0 + x1) / 2)
        val ys = intArrayOf(y0, y1, y0, y1, y0)
        val xe = intArrayOf(x1, x1, x0, x0, x1)
        val ye = intArrayOf(y1, y0, y1, y1, y1)
        val samples = ArrayList<Int>(20)
        for (k in xs.indices) {
            for (s in 0 until 4) {
                val xx = (xs[k] + s * (xe[k] - xs[k]) / 4).coerceIn(0, bmp.width - 1)
                val yy = (ys[k] + s * (ye[k] - ys[k]) / 4).coerceIn(0, bmp.height - 1)
                samples.add(bmp.getPixel(xx, yy))
            }
        }
        samples.sortBy { Color.rgb(it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }
        val fill = samples[samples.size / 2]
        val paint = Paint().apply { color = fill; style = Paint.Style.FILL }
        val r2 = RectF(r)
        r2.inset(-1f, -1f)
        canvas.drawRoundRect(r2, 4f, 4f, paint)
    }

    /** 横排排版(StaticLayout 自适应,行为保持既有验收版)。竖排走 [typesetVertical]。 */
    /** @return 最终绘制字号(兜底为 9f)。 */
    private fun typeset(canvas: Canvas, text: String, r: RectF, spec: InkSpec): Float {
        val boxW = r.width().coerceAtLeast(8f)
        val boxH = r.height().coerceAtLeast(8f)
        // choose text size so the (possibly wrapped) text fits the box
        var size = (boxH * 0.8f).coerceIn(10f, 64f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.color
            this.textSize = size
            isFakeBoldText = true
        }
        var layout: StaticLayout
        // 逐 1px 精确搜索"最大可容纳字号"(旧 0.85 连乘 8 次不收敛会**直接不画**,
        // 小框长文整块无输出);仍放不下时 9px 兜底 + 裁剪在框内,保证必有输出。
        while (size > 9.5f) {
            paint.textSize = size
            layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, boxW.toInt())
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.05f)
                .build()
            if (layout.height <= boxH) {
                canvas.save()
                canvas.translate(r.left + (boxW - layout.width) / 2f,
                                 r.top + (boxH - layout.height) / 2f)
                if (spec.busy) {          // 压图文字:同排版细描边(块中位色)保可读
                    val hp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = spec.halo
                        textSize = size
                        isFakeBoldText = true
                        style = Paint.Style.STROKE
                        strokeWidth = (size * 0.09f).coerceAtLeast(1f)
                    }
                    StaticLayout.Builder
                        .obtain(text, 0, text.length, hp, boxW.toInt())
                        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.05f)
                        .build().draw(canvas)
                }
                layout.draw(canvas)
                canvas.restore()
                return size
            }
            size -= 1f
        }
        // 兜底:9px 仍画(裁剪到框内)
        paint.textSize = 9f
        layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, boxW.toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .build()
        canvas.save()
        canvas.clipRect(r)
        canvas.translate(r.left + (boxW - layout.width) / 2f,
                         r.top + (boxH - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        Log.w("OnDeviceTr", "H fallback 9px clip (box=${boxW.toInt()}x${boxH.toInt()})")
        return 9f
    }

    /**
     * 竖排排版(日式):字向下堆叠、列从右向左,段落 '\n' 断列。
     * cell = 原文字号估计；实际字号取「≤cell 且装得下的最大 size」——逐 1px
     * 精确搜索(旧 0.85 连乘要么超调缩小、要么 8 次不收敛直接不画),因此**永不
     * 出现放不下/溢出**。slots = 原图各列中心 x(升序); 给定则列直接落在原文
     * 列栅格上("根据原文版式嵌入"),装不下才回落自由列距。
     */
    private fun typesetVertical(canvas: Canvas, text: String, r: RectF, cellTarget: Float,
                                slots: List<Float>, spec: InkSpec,
                                grow: Boolean = false): Float {
        val pad = 3f
        val availW = (r.width() - pad * 2f).coerceAtLeast(8f)
        val availH = (r.height() - pad * 2f).coerceAtLeast(8f)
        val n = text.length
        fun perCol(c: Float) = (availH / (c * 1.06f)).toInt().coerceAtLeast(1)
        fun freeCols(c: Float) = (availW / (c * 1.06f)).toInt().coerceAtLeast(1)
        var useSlots = slots.isNotEmpty()
        fun fits(c: Float) =
            (n + perCol(c) - 1) / perCol(c) <= (if (useSlots) slots.size else freeCols(c))
        fun minPitch(): Float {
            var mp = Float.MAX_VALUE
            for (i in 1 until slots.size) mp = minOf(mp, slots[i] - slots[i - 1])
            return if (mp == Float.MAX_VALUE) 64f else mp
        }
        // §E10b 双拟合取优:「原文列栅格」与「自由列距」各自 1px 精确拟合,取较大
        // 者。密排竖排的投影融合会把多列当成少列(栅格拟合被压到 9~13px),自由
        // 列距作为下界兜底;栅格仅在**不劣于**自由拟合时使用(尊重原文列位)。
        fun fitWith(want: Boolean): Float {
            useSlots = want
            var f = cellTarget.coerceIn(9f, 64f)
            if (want && slots.size >= 2) f = minOf(f, minPitch() / 1.06f)
            while (f > 9.5f && !fits(f)) f -= 1f
            return f
        }
        val cFree = fitWith(false)
        val cGrid = if (slots.isNotEmpty()) fitWith(true) else -1f
        useSlots = slots.isNotEmpty() && cGrid >= cFree
        var c = if (useSlots) cGrid else cFree
        // §E10: 经"需时扩张"的块(原文装不下→已获额外留白)允许字号上探铺满,
        // 封顶 1.5×原文实测、列栅距、单列可用宽 —— 短译文不再缩在气泡一角。
        if (grow) {
            val up = minOf(cellTarget * 1.5f,
                           if (useSlots && slots.size >= 2) minPitch() / 1.06f else 64f,
                           availW).coerceAtMost(64f)
            while (c + 1f <= up && fits(c + 1f)) c += 1f
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.color
            textSize = c
            isFakeBoldText = true
        }
        val haloPaint = if (spec.busy) TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.halo
            textSize = c
            isFakeBoldText = true
            style = Paint.Style.STROKE
            strokeWidth = (c * 0.09f).coerceAtLeast(1f)
        } else null
        val pitch = c * 1.06f
        val pc = perCol(c)
        val mc = if (useSlots) slots.size else freeCols(c)
        var idx = 0
        var col = 0
        val right = r.right - pad
        while (idx < n && col < mc) {
            val cx = if (useSlots) {
                val k = slots.size - 1 - col     // slots 升序 → 右→左取列
                if (k < 0) break
                slots[k]
            } else right - (col + 0.5f) * pitch
            var y = r.top + pad + c * 0.85f       // 基线
            var k2 = 0
            while (idx < n && k2 < pc) {
                val ch = text[idx]
                idx++
                if (ch == '\n') break              // 段落断列
                if (ch.isWhitespace()) { k2++; y += pitch; continue }
                var dx = 0f
                var dy = 0f
                if (ch in "、。，．,.") { dx = c * 0.22f; dy = -c * 0.30f }  // 竖排标点居右上
                val w = paint.measureText(ch.toString())
                if (haloPaint != null)
                    canvas.drawText(ch.toString(), cx - w / 2f + dx, y + dy, haloPaint)
                canvas.drawText(ch.toString(), cx - w / 2f + dx, y + dy, paint)
                k2++
                y += pitch
            }
            col++
        }
        return c
    }

    /**
     * 从原图测量块的排版方向、字号与竖排列栅格。
     * vertical = 框宽高比(旧判据,对单列细长块可靠) **或** 数据判据(沿 X 的条数
     * > 沿 Y 的条数 —— 整页竖排: 列多"行"少; 整页横排反之)。整页框上旧判据会
     * 误判(991x1444→横排),数据判据兜底。
     * cell: 墨迹条中位宽 / 0.72 —— CJK 字形墨宽 ≈ 0.72em,列墨宽 31px 的字号
     * 实际 ≈ 43px(直接拿 31 会画小一圈,正文页只铺满 1/3 块宽, §E3)。
     * slots(竖排): 各列条中心 x(页坐标,升序)。
     */
    private fun measureType(page: Bitmap, r: RectF, aspectVertical: Boolean,
                            srcLines: Int = 0): Triple<Boolean, Float, List<Float>> {
        val x0 = r.left.toInt().coerceIn(0, page.width - 2)
        val y0 = r.top.toInt().coerceIn(0, page.height - 2)
        val x1 = r.right.toInt().coerceIn(x0 + 2, page.width)
        val y1 = r.bottom.toInt().coerceIn(y0 + 2, page.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 12 || h < 12) return Triple(aspectVertical, 24f, emptyList<Float>())
        val px = IntArray(w * h)
        Bitmap.createBitmap(page, x0, y0, w, h).getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices)
            gray[i] = (((px[i] shr 16) and 0xFF) * 77 + ((px[i] shr 8) and 0xFF) * 150 +
                       (px[i] and 0xFF) * 29) shr 8
        val projX = IntArray(w)
        val projY = IntArray(h)
        // 墨迹判定自适应极性(块中位灰为背景,|g-bg|>40 为墨):
        // 黑底白字页里 gray<128 会把整块当墨 → 方向/字号量测全失真(§E4)
        val gs = gray.copyOf().also { it.sort() }
        val bgm = gs[gs.size / 2]
        for (y in 0 until h) {
            var s = 0
            var x = 0
            while (x < w) { val d = gray[y * w + x] - bgm; if (d > 40 || d < -40) s++; x += 2 }
            projY[y] = s
        }
        for (x in 0 until w) {
            var s = 0
            var y = 0
            while (y < h) { val d = gray[y * w + x] - bgm; if (d > 40 || d < -40) s++; y += 2 }
            projX[x] = s
        }
        fun cut(proj: IntArray): List<IntArray> {
            val thr = ((proj.maxOrNull() ?: 0) * 0.05f).toInt().coerceAtLeast(1)
            val out = ArrayList<IntArray>(24)
            var i = 0
            while (i < proj.size) {
                if (proj[i] <= thr) { i++; continue }
                var j = i
                var gap = 0
                // 合并间隙 3px(原 6): 密排竖排列间隙仅 3~5px, 6px 会把多列融成
                // 一条巨带 → slots/cell 全失真、字号被压小(§E10, 实测 9~13px)。
                while (j < proj.size && gap < 3) {
                    if (proj[j] <= thr) gap++ else gap = 0
                    j++
                }
                val end = (j - gap).coerceAtLeast(i + 2)
                if (end - i >= 6) out.add(intArrayOf(i, end))
                i = end.coerceAtLeast(i + 1)
            }
            return out
        }
        val bx = cut(projX)
        val by = cut(projY)
        // 数据判据需**显著**优势才推翻宽高比(1.5px 带间隙下 3 vs 2 的噪声性
        // 计数会误翻方向, 实测 #23 横排块被翻成竖排)。
        val vertical = aspectVertical || bx.size > by.size * 1.5f
        val ws = (if (vertical) bx else by).map { it[1] - it[0] }
        val med = (ws.sorted().getOrNull(ws.size / 2) ?: 24).toFloat()
        val cell = (med / 0.72f).coerceIn(10f, 64f)   // 墨宽≈0.72em → 还原字号
        var slots = if (vertical) bx.map { x0 + (it[0] + it[1]) / 2f } else emptyList()
        // §E10 列栅格修复: 密排竖排的列间隙(3~5px)低于合并间隙 → 多列被投影融成
        // 一条巨带(slots=1、cell 顶到 64) → 译文被迫单列、字号压到 9~12px(用户
        // 实测: "但是…困扰"块 3 列→12px、"多亏了老师"块 3 列→9px)。竖排 OCR
        // 行数=原列数(每列一行): 融合时按 OCR 列数在墨迹范围均布列栅格(列距
        // ≥14px 才可信, 防 OCR 误分行导致列距过小而反压字号)。
        if (vertical && srcLines >= 2 && srcLines > bx.size && bx.isNotEmpty()) {
            val span = (bx.last()[1] - bx.first()[0]).toFloat()
            val pitch = span / srcLines
            if (pitch >= 14f) {
                slots = (0 until srcLines).map { k ->
                    x0 + bx.first()[0] + span * (k + 0.5f) / srcLines
                }
            }
        }
        return Triple(vertical, cell, slots)
    }

    /**
     * 动态字号适配之一:**可用域扩张**。检测框是原文字的紧框,译文可用空间通常
     * 更大(气泡内衬/段落留白/行尾)。每边逐 8px 外扩,仅当新增条带是**平坦背景**
     * (条带内 |g-条带中位|>60 的占比 <8%)且不与其他文字块相交;每边上限 40% 边长,
     * 且不越出页面。扩张后字号在更大区域内重拟合 → 气泡里的小字不再被硬压小。
     */
    private fun expandRect(page: Bitmap, r: RectF, others: List<RectF>): RectF {
        val out = RectF(r)
        val step = 8
        val maxX = (r.width() * 0.40f).toInt().coerceAtLeast(step)
        val maxY = (r.height() * 0.40f).toInt().coerceAtLeast(step)
        fun flat(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            val w = x1 - x0
            val h = y1 - y0
            if (w <= 0 || h <= 0) return false
            val px = IntArray(w * h)
            Bitmap.createBitmap(page, x0, y0, w, h).getPixels(px, 0, w, 0, 0, w, h)
            val g = IntArray(px.size)
            for (i in px.indices)
                g[i] = (((px[i] shr 16) and 0xFF) * 77 + ((px[i] shr 8) and 0xFF) * 150 +
                        (px[i] and 0xFF) * 29) shr 8
            val gs = g.copyOf().also { it.sort() }
            val bg = gs[gs.size / 2]
            var ink = 0
            for (v in g) if (Math.abs(v - bg) > 60) ink++
            return ink.toFloat() / g.size < 0.08f
        }
        fun free(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (o in others) if (o !== r && o.left < x1 && o.right > x0 &&
                o.top < y1 && o.bottom > y0) return false
            return true
        }
        var gx = 0
        while (gx < maxX) {
            val nx0 = (out.left - step).toInt()
            val nx1 = (out.right + step).toInt()
            if (nx0 >= 0 && free(nx0, out.top.toInt().coerceAtLeast(0), nx0 + step,
                                out.bottom.toInt().coerceAtMost(page.height)) &&
                flat(nx0, out.top.toInt().coerceAtLeast(0), nx0 + step,
                     out.bottom.toInt().coerceAtMost(page.height))) out.left = nx0.toFloat()
            else if (nx1 <= page.width && free(nx1 - step, out.top.toInt().coerceAtLeast(0),
                     nx1, out.bottom.toInt().coerceAtMost(page.height)) &&
                flat(nx1 - step, out.top.toInt().coerceAtLeast(0), nx1,
                     out.bottom.toInt().coerceAtMost(page.height))) out.right = nx1.toFloat()
            else break
            gx += step
        }
        var gy = 0
        while (gy < maxY) {
            val ny0 = (out.top - step).toInt()
            val ny1 = (out.bottom + step).toInt()
            if (ny0 >= 0 && free(out.left.toInt().coerceAtLeast(0), ny0,
                                out.right.toInt().coerceAtMost(page.width), ny0 + step) &&
                flat(out.left.toInt().coerceAtLeast(0), ny0,
                     out.right.toInt().coerceAtMost(page.width), ny0 + step)) out.top = ny0.toFloat()
            else if (ny1 <= page.height && free(out.left.toInt().coerceAtLeast(0), ny1 - step,
                     out.right.toInt().coerceAtMost(page.width), ny1) &&
                flat(out.left.toInt().coerceAtLeast(0), ny1 - step,
                     out.right.toInt().coerceAtMost(page.width), ny1)) out.bottom = ny1.toFloat()
            else break
            gy += step
        }
        return out
    }

    /** 译文配色:color=文字色;halo=描边色(文字色反色);busy=是否加描边。 */
    class InkSpec(val color: Int, val halo: Int, val busy: Boolean)

    /**
     * WCAG 相对亮度 / 对比度 —— 配色决策的量化标准(Kimi 方案,§E9)。
     * CR = (L1+0.05)/(L2+0.05) ∈ [1,21];正文 ≥4.5,≥3 配描边可读。
     */
    private fun wcagL(rgb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) +
               0.0722 * ch(rgb and 0xFF)
    }

    private fun wcagCr(a: Int, b: Int): Double {
        val la = wcagL(a)
        val lb = wcagL(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }

    /**
     * 原文主色(**候选色之一**,不直接使用):|灰-块中位|>40 为墨,量化 32 级取
     * **众数簇**均值——多彩字的中位是"泥色",主簇才是原文字色(§E5)。无墨返回 -1。
     */
    private fun dominantInk(page: Bitmap, r: RectF): Int {
        val x0 = r.left.toInt().coerceIn(0, page.width - 2)
        val y0 = r.top.toInt().coerceIn(0, page.height - 2)
        val x1 = r.right.toInt().coerceIn(x0 + 2, page.width)
        val y1 = r.bottom.toInt().coerceIn(y0 + 2, page.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 8) return -1
        val px = IntArray(w * h)
        Bitmap.createBitmap(page, x0, y0, w, h).getPixels(px, 0, w, 0, 0, w, h)
        val n = px.size
        val gray = IntArray(n)
        for (i in 0 until n)
            gray[i] = ((px[i] shr 16 and 0xFF) * 77 + (px[i] shr 8 and 0xFF) * 150 +
                       (px[i] and 0xFF) * 29) shr 8
        val gs = gray.copyOf().also { it.sort() }
        val bg = gs[n / 2]
        val packed = LongArray(n)
        var m = 0
        for (i in 0 until n) {
            val d = gray[i] - bg
            if (d > 40 || d < -40) {
                val key = ((px[i] shr 16 and 0xFF) / 32 shl 12) or
                          ((px[i] shr 8 and 0xFF) / 32 shl 6) or
                          (px[i] and 0xFF) / 32
                packed[m++] = key.toLong() shl 24 or i.toLong()
            }
        }
        if (m == 0) return -1
        java.util.Arrays.sort(packed, 0, m)
        val minCnt = (m / 20).coerceAtLeast(16)
        var bestLen = 0
        var bestSt = 0
        var st = 0
        var k = 1
        while (k <= m) {
            if (k == m || (packed[k] shr 24) != (packed[st] shr 24)) {
                if (k - st > bestLen && k - st >= minCnt) { bestLen = k - st; bestSt = st }
                st = k
            }
            k++
        }
        if (bestLen == 0) return -1
        var rs = 0L
        var gsum = 0L
        var bsum = 0L
        for (j in bestSt until bestSt + bestLen) {
            val i = (packed[j] and 0xFFFFFF).toInt()
            rs += px[i] shr 16 and 0xFF
            gsum += px[i] shr 8 and 0xFF
            bsum += px[i] and 0xFF
        }
        return Color.rgb((rs / bestLen).toInt(), (gsum / bestLen).toInt(),
                         (bsum / bestLen).toInt())
    }

    /**
     * 配色决策(Kimi/WCAG,§E9)：
     *  bg = **填充后**、框内掩码区的中位色(修图结果本身——不采样原文字、不越出块);
     *  文字色 = 候选 {黑,白,近黑(20),近白(245),原文主色} 中 **CR 最大且 ≥4.5** 者,
     *  全不达标则按 bg 亮度退回黑/白(bgL>0.5 → 黑);
     *  halo = **文字色反色**(白字黑描边=万能解);busy(框内掩码占比>25% 或 CR<4.5)。
     */
    private fun chooseInk(page: Bitmap, filled: Bitmap, mask: ByteArray?, r: RectF): InkSpec {
        val x0 = r.left.toInt().coerceIn(0, filled.width - 1)
        val y0 = r.top.toInt().coerceIn(0, filled.height - 1)
        val x1 = r.right.toInt().coerceIn(x0 + 1, filled.width)
        val y1 = r.bottom.toInt().coerceIn(y0 + 1, filled.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 4 || h < 4) return InkSpec(Color.BLACK, Color.WHITE, false)
        val fpx = IntArray(w * h)
        Bitmap.createBitmap(filled, x0, y0, w, h).getPixels(fpx, 0, w, 0, 0, w, h)
        val fw = filled.width
        val samples = ArrayList<Int>(4096)
        var mk = 0
        val all = w * h
        val stride = (all / 4096).coerceAtLeast(1)
        var i = 0
        while (i < all) {
            val mi = (y0 + i / w) * fw + (x0 + i % w)
            if (mask != null && mask[mi] != 0.toByte()) { mk++; samples.add(fpx[i]) }
            i += stride
        }
        val sampled = (all + stride - 1) / stride
        // 无掩码像素(未擦除)→ bg 取整框中位
        if (samples.isEmpty()) {
            i = 0
            while (i < all) { samples.add(fpx[i]); i += stride }
        }
        samples.sort()
        val bg = samples[samples.size / 2]
        // 候选集(Kimi:黑白优先 + 原图主色作候选)
        val black = Color.BLACK
        val white = Color.WHITE
        val cands = intArrayOf(black, white, Color.rgb(20, 20, 20), Color.rgb(245, 245, 245))
        val orig = dominantInk(page, r)
        var best = -1
        var bestCr = -1.0
        for (c in cands) {
            val cr = wcagCr(c, bg)
            if (cr >= 4.5 && cr > bestCr) { bestCr = cr; best = c }
        }
        if (orig >= 0) {
            val cr = wcagCr(orig, bg)
            if (cr >= 4.5 && cr > bestCr) { bestCr = cr; best = orig }
        }
        if (best < 0) best = if (wcagL(bg) > 0.5) black else white
        val halo = if (wcagL(best) > 0.5) Color.BLACK else Color.WHITE
        val busy = (sampled > 0 && mk.toFloat() / sampled > 0.25f) || bestCr < 4.5
        return InkSpec(best, halo, busy)
    }

    /**
     * 本机模型翻译 —— **BT Sakura 机制移植**(trans_sakura.py @BT, 0.9 版):
     *
     *  · payload(`_preprocess_queries`/`_request_translation`): 非空块压平为单行 →
     *    去 emoji(`[\x{10000}-\x{10FFFF}]`)、`❤`→`♥`、每行包「」→ '\n' 连接整页
     *    一次请求; user = "将下面的日文文本翻译成中文：{raw}", system = BT 0.9 模板;
     *  · 质量阶梯(`_check_translation_quality`): 重复检测(阈值 = max(各行请求最长重复, 20),
     *    `detect_and_calculate_repeats` 同款 `(.{p})\1+` 正则) ∨ 行数不齐 → 风格重试
     *    precise(0.1/0.3) → normal(0.3/0.3) → aggressive(0.3/0.3) → 逐行翻译
     *    (`_translate_single_lines`: 单行仍退化则该行返回原文);
     *  · **绝不空手而归**: 模型不可用/请求失败 → 整页返回原文(`_handle_translation_request`
     *    的"返回原始文本"语义), 不再整页 FAILED;
     *  · 收尾: `_delete_quotation_mark` 去「」;
     *  · 移植分歧点(Hy-MT2 特有, Sakura 无): 行间空行在质检前滤除(段落式输出习惯);
     *    「」被渲染成中文引号“”时在收尾一并剥离(同一包装标记的等价形式)。
     */
    private fun localTranslate(sources: List<String>, backend: Boolean = false): List<String>? {
        val path = CsSettings.get("llm_local_path", DEFAULT_LOCAL_MODEL)
        val threads = CsSettings.int("llm_local_threads", 4)
        if (!backend && !NativeBridge.llmLocalLoaded()) {
            Log.i("OnDeviceTr", "加载本机模型: $path (threads=$threads)")
            if (!NativeBridge.llmLocalLoad(path, threads, CsSettings.int("llm_local_ctx", 4096))) {
                return null
            }
        }
        // 行 = 非空块(块内换行压平, BT 驱动同款), 保序记录所属块下标
        val rows = ArrayList<Int>(sources.size)
        val queries = ArrayList<String>(sources.size)
        sources.forEachIndexed { i, t ->
            if (t.isBlank()) return@forEachIndexed
            rows.add(i)
            queries.add(btPreprocessQuery(t.replace('\n', ' ')))
        }
        if (queries.isEmpty()) return null
        val sys = CsSettings.get("llm_local_prompt", DEFAULT_LOCAL_SYS)
        val maxTok = CsSettings.int("llm_local_maxtok", 1024) // BT参数 `max tokens`: 1024

        fun chat(qs: List<String>, style: Int): String? {
            val (temp, topP, freqP) = BT_STYLES[style]
            val user = "将下面的日文文本翻译成中文：" + qs.joinToString("\n")
            return try {
                if (backend) httpChat(sys, user, maxTok, temp, topP, freqP)   // 局域网 llama-server
                else NativeBridge.llmLocalChat(sys, user, maxTok, temp, topP, 20)
            } catch (t: Throwable) {
                Log.w("OnDeviceTr", "本机模型请求异常: $t")
                null
            }
        }

        fun unusable(r: String?): Boolean =
            r == null || r.isBlank() || r.trimStart().startsWith("{")
        // Hy-MT2 把每行译文当独立段落输出(行间插空行) → 对齐前滤空行。这是对
        // BT "模型恰好输出 N 行"假设的最小适配(Sakura 无此习惯, BT 原版无需过滤)。
        fun textLines(r: String): List<String> = r.split('\n').filter { it.isNotBlank() }
        fun diag(tag: String, r: String, thr: Int) =
            "$tag ${r.length}字/${textLines(r).size}行 重复=${btDetectRepeats(r, thr).first}" +
                " | ${NativeBridge.llmLocalInfo()} | 头:${r.take(40)} 尾:${r.takeLast(40)}"

        var ans: String? = chat(queries, 0)
        if (unusable(ans)) {
            Log.w("OnDeviceTr", "本机模型不可用 → 整页原文兜底")
            return sources
        }
        // 实际阈值 = max(各行请求的最长重复数, 20)(BT `_check_translation_quality`)
        var actThr = 20
        for (q in queries) actThr = maxOf(actThr, btDetectRepeats(q, 20).second)
        if (btDetectRepeats(queries.joinToString(""), 20).first)
            Log.w("OnDeviceTr", "请求内容本身含有超过默认阈值 20 的重复内容")
        Log.i("OnDeviceTr", diag("首轮", ans!!, actThr))

        // 风格阶梯重试(BT `_retry_translation`: precise → normal → aggressive;
        // 两种判据各自独立成梯——BT 的重复梯不会因对齐通过而提前退出, 反之亦然)
        fun ladder(check: (String) -> Boolean): String? {
            for (st in BT_STYLES.indices) {
                val r = chat(queries, st) ?: continue
                Log.i("OnDeviceTr", diag("阶梯${st + 1}/3 风格=${BT_STYLE_NAMES[st]}", r, actThr))
                if (check(r)) return r
            }
            return null
        }

        // 逐行翻译(BT `_translate_single_lines`): 单行仍退化/退化判据命中 → 该行返回原文
        fun singleLines(): List<String> {
            Log.i("OnDeviceTr", "逐行翻译(${queries.size} 行)")
            val one = ArrayList<String>(queries.size)
            for (q in queries) {
                val r = chat(listOf(q), 2)
                if (unusable(r) || btDetectRepeats(r!!, 20).first) {
                    Log.w("OnDeviceTr", "单行翻译结果存在重复内容，返回原文：${q.take(24)}")
                    one.add(q)
                } else one.add(r.trim())
            }
            return one
        }

        // ① 疑似模型退化 → 重复检测梯(BT 先查重复)
        if (btDetectRepeats(ans!!, actThr).first) {
            Log.w("OnDeviceTr", "检测到大量重复内容(当前阈值: $actThr)，疑似模型退化，重新翻译")
            val r = ladder { x -> !btDetectRepeats(x, actThr).first }
            if (r != null) ans = r else {
                Log.w("OnDeviceTr", "疑似模型退化，尝试 3 次仍未解决，进行单行翻译")
                ans = null
            }
        }
        // ② 行数不齐 → 对齐梯(BT 再查对齐; 此梯只查对齐, 与 BT 相同)
        if (ans != null && textLines(ans!!).size != queries.size) {
            Log.w("OnDeviceTr", "行数不匹配 - 原文行数: ${queries.size}，译文行数： ${textLines(ans!!).size}")
            val r = ladder { x -> textLines(x).size == queries.size }
            if (r != null) ans = r else {
                Log.w("OnDeviceTr", "原文与译文行数不匹配，尝试 3 次仍未解决，进行单行翻译")
                ans = null
            }
        }
        val lines = ans?.let { textLines(it) } ?: singleLines()
        // 收尾: 去「」(BT `_delete_quotation_mark`; Hy-MT2 会把「」渲染成中文引号
        // “” — 同为包装标记, 一并去除以保持"剥离预处理器标记"的原语义), 回填到块
        val out = sources.toMutableList()
        for ((k, v) in lines.withIndex()) {
            val r = rows.getOrNull(k) ?: break
            out[r] = v.trim().trim('「', '」', '“', '”')
        }
        Log.i("OnDeviceTr", "本机翻译 ${lines.size}/${queries.size} 行(BT Sakura 契约); " +
            NativeBridge.llmLocalInfo())
        return out
    }

    // ---------------------------------------------------------------- backend 模式

    /** 后端档案 pipeline 前缀（服务端 /ocr_page 的 `pipeline` 字段）。换前缀=旧档失效。 */
    const val BACKEND_PIPELINE = "bt-mac"

    /**
     * v0.3.5：后端地址归一化——用户只填 `IP:端口`（少写 http://）时自动补全。
     * 空串保持空（继续走"未配置"守卫，不产生 `http://` 这种半配置）；
     * 显式带协议（含 `://`）则原样保留。设置页保存/测试与实际请求三处共用此函数。
     */
    fun normalizedBackend(raw: String): String {
        val u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return u
        return if (u.contains("://")) u else "http://$u"
    }

    private fun backendUrl(): String =
        normalizedBackend(runCatching { CsSettings.get("backend_url", DEFAULT_BACKEND) }
            .getOrDefault(DEFAULT_BACKEND))

    /** backend 一页的翻译产物。 */
    private class BackendPage(val boxes: List<Box>, val sources: List<String>,
                              val translated: List<String>, val pipeline: String,
                              val timings: String)

    /**
     * backend 核心：整页上传到局域网后端（CTBD 检测 + PaddleOCR-VL 识别都在后端，
     * MPS 加速）→ 翻译仍是**本机端 BT Sakura 阶梯机制**（仅 chat 传输换成 HTTP
     * llama-server，频率惩罚按 `_set_gpt_style` 补齐）。不含渲染/落档。
     *
     * `fresh=true`（"重译当前页"）让后端绕过其 sha1 整页缓存重跑。
     */
    private suspend fun backendCore(page: Bitmap, fresh: Boolean): BackendPage? =
        withContext(Dispatchers.Default) {
        val url = backendUrl()
        if (url.isEmpty()) {
            Log.w("OnDeviceTr", "backend_url 未配置：请在 设置 → 翻译 → 后端地址 填写服务机 IP:8787")
            return@withContext null
        }
        val t0 = System.nanoTime()
        val png = withContext(Dispatchers.IO) {
            java.io.ByteArrayOutputStream(1 shl 21).use { bo ->
                page.compress(Bitmap.CompressFormat.PNG, 100, bo)
                bo.toByteArray()
            }
        }
        val resp = withContext(Dispatchers.IO) {
            httpPost(url + "/ocr_page" + (if (fresh) "?fresh=1" else ""), png, "image/png", 300_000)
        }
        if (resp == null) {
            Log.w("OnDeviceTr", "backend 不可达或 HTTP 失败: $url")
            return@withContext null
        }
        val o = runCatching { JSONObject(String(resp, Charsets.UTF_8)) }.getOrNull()
        if (o == null || o.has("error")) {
            Log.w("OnDeviceTr", "backend 错误: ${String(resp, Charsets.UTF_8).take(160)}")
            return@withContext null
        }
        val arr = o.optJSONArray("boxes") ?: JSONArray()
        val txt = o.optJSONArray("texts")
        val boxes = ArrayList<Box>(arr.length())
        val sources = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optInt("label", 2) == 0) continue   // 气泡框仅作约束, 不进 OCR 流水
            boxes.add(Box(b.getDouble("x0").toFloat(), b.getDouble("y0").toFloat(),
                          b.getDouble("x1").toFloat(), b.getDouble("y1").toFloat(),
                          b.optBoolean("vertical"), b.optDouble("score").toFloat(),
                          b.optInt("label", 2), b.optBoolean("bubble")))
            sources.add(txt?.optString(i).orEmpty().trim())
        }
        if (boxes.isEmpty()) {
            // 无文字页（插画/空白封面等）：不是失败——后台队列按"完成"计。
            // 不落档（存档表要求 n>=1），下次扫描会重新验证（一页 ~0.2s，可忽略）。
            Log.i("OnDeviceTr", "backend: 0 文本块 (${o.optString("timings")})")
            return@withContext BackendPage(emptyList(), emptyList(), emptyList(),
                                           o.optString("pipeline", BACKEND_PIPELINE),
                                           o.optString("timings"))
        }
        // 清洁与端侧同款：重复折叠 + 退化文本防线 + 块日志
        for ((bi, b) in boxes.withIndex()) {
            val raw = sources[bi]
            val (t, reps) = foldRepeats(raw)
            if (reps > 0) Log.i("OnDeviceTr", "repeat x$reps folded: '${raw.take(20)}…'")
            Log.i("OnDeviceTr", "block[%.0f,%d %dx%d s=%.2f] vertical=%b -> '%s'".format(
                b.x0, b.y0.toInt(), (b.x1 - b.x0).toInt(), (b.y1 - b.y0).toInt(),
                b.score, b.vertical, t))
            sources[bi] = t
        }
        run {
            val freq = sources.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            for (i in sources.indices) {
                val t = sources[i]
                if (t.isBlank()) continue
                if (t.count { it == '\uFFFD' } >= 2 || (t.length >= 8 && (freq[t] ?: 0) >= 2)) {
                    Log.i("OnDeviceTr", "drop degenerate block(${t.length}): '${t.take(30)}…'")
                    sources[i] = ""
                }
            }
        }
        if (sources.count { it.isNotEmpty() } == 0) {
            // 全部块退化为空 → 等效无文字页（不是失败，见上）
            Log.i("OnDeviceTr", "backend: OCR 无有效文本（全部退化）")
            return@withContext BackendPage(emptyList(), emptyList(), emptyList(),
                                           o.optString("pipeline", BACKEND_PIPELINE),
                                           o.optString("timings"))
        }
        val t2 = System.nanoTime()
        val translated = withContext(Dispatchers.IO) { localTranslate(sources, backend = true) }
        val llmMs = (System.nanoTime() - t2) / 1e6
        if (translated == null) {
            Log.w("OnDeviceTr", "backend 翻译失败")
            return@withContext null
        }
        val ms = (System.nanoTime() - t0) / 1e6
        val tim = "backend llm=${"%.0f".format(llmMs)}ms ${o.optString("timings")}".trim() +
            (if (o.optBoolean("cached")) " srv-cached" else "")
        Log.i("OnDeviceTr", "page done in ${"%.0f".format(ms)} ms ($tim)")
        Log.i("OnDeviceTr", "src: ${sources.joinToString(" | ")}")
        Log.i("OnDeviceTr", "dst: ${translated.joinToString(" | ")}")
        BackendPage(boxes, sources, translated,
                    o.optString("pipeline", BACKEND_PIPELINE), tim)
    }

    /**
     * 整本后台队列用：核心 + **落设备档案**（每块一条 JSON：框+原文+译文）。
     * 档案是"整本翻译"的持久化产物——进度=已落档页数，离线可重渲染（无需再联网）。
     */
    suspend fun backendTranslateAndStore(bookId: Long, pageIndex: Int, page: Bitmap,
                                         fresh: Boolean): Boolean {
        val bp = backendCore(page, fresh) ?: return false
        if (bp.boxes.isEmpty()) return true    // 无文字页 = 完成（无需落档）
        val bytes = withContext(Dispatchers.IO) { NativeBridge.readPage(bookId, pageIndex) }
        val hash = fnv1a(bytes ?: ByteArray(0))
        val arr = JSONArray()
        for (i in bp.boxes.indices) {
            val b = bp.boxes[i]
            arr.put(JSONObject()
                .put("x0", b.x0.toDouble()).put("y0", b.y0.toDouble())
                .put("x1", b.x1.toDouble()).put("y1", b.y1.toDouble())
                .put("v", b.vertical).put("l", b.label).put("b", b.bubble)
                .put("s", b.score.toDouble())
                .put("src", bp.sources[i]).put("dst", bp.translated[i]).toString())
        }
        val ok = withContext(Dispatchers.IO) {
            NativeBridge.saveTextArchive(bookId, pageIndex, hash, bp.pipeline, arr.toString())
        }
        if (!ok) Log.w("OnDeviceTr", "backend 档案写入失败 page=$pageIndex")
        return ok
    }

    /** 轻量档案存在性检查（不解析块）。 */
    fun hasBackendArchive(bookId: Long, pageIndex: Int): Boolean {
        val arch = NativeBridge.loadTextArchive(bookId, pageIndex) ?: return false
        return runCatching {
            JSONObject(arch).optString("pipeline").startsWith(BACKEND_PIPELINE)
        }.getOrDefault(false)
    }

    /** 档案 → (框, 原文, 译文)；无档/不兼容/损坏返回 null。 */
    private fun loadBackendArchive(bookId: Long, pageIndex: Int):
            Triple<List<Box>, List<String>, List<String>>? {
        val arch = NativeBridge.loadTextArchive(bookId, pageIndex) ?: return null
        val o = runCatching { JSONObject(arch) }.getOrNull() ?: return null
        if (!o.optString("pipeline").startsWith(BACKEND_PIPELINE)) return null
        val arr = o.optJSONArray("texts") ?: return null
        if (arr.length() == 0) return null
        val boxes = ArrayList<Box>(arr.length())
        val srcs = ArrayList<String>(arr.length())
        val dsts = ArrayList<String>(arr.length())
        try {
            for (i in 0 until arr.length()) {
                val b = JSONObject(arr.getString(i))
                boxes.add(Box(b.getDouble("x0").toFloat(), b.getDouble("y0").toFloat(),
                              b.getDouble("x1").toFloat(), b.getDouble("y1").toFloat(),
                              b.optBoolean("v"), b.optDouble("s").toFloat(),
                              b.optInt("l", 2), b.optBoolean("b")))
                srcs.add(b.optString("src"))
                dsts.add(b.optString("dst"))
            }
        } catch (t: Throwable) {
            Log.w("OnDeviceTr", "档案解析失败 book=$bookId page=$pageIndex: $t")
            return null
        }
        return Triple(boxes, srcs, dsts)
    }

    /**
     * 可见页渲染：读档案（无网络）→ 墨迹擦除/排版。null = 无档/损坏。
     * 后台队列只产档案不渲染；渲染只发生在页真正可见时。
     */
    suspend fun renderBackendPage(bookId: Long, pageIndex: Int, page: Bitmap): Bitmap? {
        val (boxes, srcs, dsts) = loadBackendArchive(bookId, pageIndex) ?: return null
        return render(page, boxes, dsts, srcs)
    }

    /** backend 传输: POST /chat → 服务转发 llama-server(OpenAI 兼容)。阻塞调用, 需在 IO 上下文。 */
    private fun httpChat(system: String, user: String, maxTok: Int, temp: Float,
                         topP: Float, freq: Float): String? {
        return try {
            val body = JSONObject()
                .put("system", system).put("user", user).put("max_tokens", maxTok)
                .put("temperature", temp.toDouble()).put("top_p", topP.toDouble())
                .put("frequency_penalty", freq.toDouble())
                .toString().toByteArray(Charsets.UTF_8)
            val resp = httpPost(backendUrl() + "/chat", body, "application/json", 240_000)
                ?: return null
            val o = JSONObject(String(resp, Charsets.UTF_8))
            o.optString("content").ifBlank { null }
        } catch (t: Throwable) {
            Log.w("OnDeviceTr", "后端 chat 异常: $t")
            null
        }
    }

    /** 极简 HTTP POST（HttpURLConnection）: 2xx 返回响应体; 否则记录错误体并返回 null。 */
    private fun httpPost(url: String, body: ByteArray, contentType: String,
                         readTimeoutMs: Int): ByteArray? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val out = stream?.use { it.readBytes() }
            conn.disconnect()
            if (code in 200..299) out
            else {
                Log.w("OnDeviceTr", "backend HTTP $code: ${out?.let { b ->
                    String(b, Charsets.UTF_8).take(160)
                }}")
                null
            }
        } catch (t: Throwable) {
            Log.w("OnDeviceTr", "backend HTTP 异常: $t")
            null
        }
    }

    /** BT 风格阶梯参数(`_set_gpt_style`): (temperature, top_p, frequency_penalty),
     *  顺序 precise→normal→aggressive。端侧 llama.cpp 无频率惩罚, 该位仅 backend 传输使用。 */
    private val BT_STYLES = arrayOf(
        Triple(0.1f, 0.3f, 0.05f), Triple(0.3f, 0.3f, 0.2f), Triple(0.3f, 0.3f, 0.3f))
    private val BT_STYLE_NAMES = arrayOf("precise", "normal", "aggressive")

    /** BT `_preprocess_queries`: 去 emoji(`[\x{10000}-\x{10FFFF}]`)、`❤`→`♥`、每行包「」。 */
    private val BT_EMOJI = Regex("[\\x{10000}-\\x{10FFFF}]")
    private fun btPreprocessQuery(t: String): String =
        "「" + BT_EMOJI.replace(t.replace('❤', '♥'), "") + "」"

    /**
     * BT `detect_and_calculate_repeats` 的检测部分: 对模式长 p=1..len/2 用 `(.{p})\1+`
     * 匹配, 任一重复次数 ≥ threshold 即判退化;
     * 返回 (是否退化, 实际阈值 = max(threshold, 各匹配的最大重复次数))。
     */
    private fun btDetectRepeats(s: String, threshold: Int): Pair<Boolean, Int> {
        var repeated = false
        var maxCount = 0
        var p = 1
        val limit = s.length / 2
        while (p <= limit && !repeated) {
            val re = Regex("(.{$p})\\1+")
            for (m in re.findAll(s)) {
                val c = m.value.length / p
                if (c > maxCount) maxCount = c
                if (c >= threshold) { repeated = true; break }
            }
            p++
        }
        return repeated to maxOf(threshold, maxCount)
    }

    /**
     * 整页文字页切条：竖排按列投影、横排按行投影，返回各文字条矩形（页面坐标）。
     * 投影 = 该列/行的暗像素计数（步进 3 采样）；条内允许 ≤6px 间隙，
     * 条宽/高 <10px 丢弃。
     */
    /**
     * 整页文字页切条。**方向数据驱动**：沿 X/Y 两个方向分别投影切条，条数多的一方
     * = 文字行进轴（整页竖排: 列多"行"少; 整页横排反之）。此前只用框宽高比判方向，
     * 991x1444 的整页竖排框（比值 1.46<1.5）被误判横排 → 按行切 → 每条横穿所有列
     * → rec 全是乱码（§E3）。方向 = 宽高比 ∨ 数据判据（与 [measureType] 一致）。
     * 竖排按 **R→L 阅读序** 返回（右列先读，日式竖排原文如此），横排按 T→B。
     * 返回 (vertical, 条矩形列表)。
     */
    private fun splitTextBands(page: Bitmap, b: Box): Pair<Boolean, List<RectF>> {
        val x0 = b.x0.toInt().coerceAtLeast(0)
        val x1 = b.x1.toInt().coerceAtMost(page.width).coerceAtLeast(x0 + 4)
        val y0 = b.y0.toInt().coerceAtLeast(0)
        val y1 = b.y1.toInt().coerceAtMost(page.height).coerceAtLeast(y0 + 4)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 16 || h < 16) return false to emptyList()
        val px = IntArray(w * h)
        Bitmap.createBitmap(page, x0, y0, w, h).getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in px.indices)
            gray[i] = (((px[i] shr 16) and 0xFF) * 77 + ((px[i] shr 8) and 0xFF) * 150 +
                       (px[i] and 0xFF) * 29) shr 8
        val projX = IntArray(w)
        val projY = IntArray(h)
        // 墨迹判定自适应极性(同 measureType):黑底白字页 gray<128 全块皆墨
        val gs = gray.copyOf().also { it.sort() }
        val bgm = gs[gs.size / 2]
        for (x in 0 until w) {
            var s = 0
            var y = 0
            while (y < h) { val d = gray[y * w + x] - bgm; if (d > 40 || d < -40) s++; y += 3 }
            projX[x] = s
        }
        for (y in 0 until h) {
            var s = 0
            var x = 0
            while (x < w) { val d = gray[y * w + x] - bgm; if (d > 40 || d < -40) s++; x += 3 }
            projY[y] = s
        }
        fun cut(proj: IntArray): List<IntArray> {
            val thr = ((proj.maxOrNull() ?: 0) * 0.06f).toInt().coerceAtLeast(1)
            val out = ArrayList<IntArray>(24)
            var i = 0
            while (i < proj.size) {
                if (proj[i] <= thr) { i++; continue }
                var j = i
                var gap = 0
                while (j < proj.size && gap < 6) {  // 字内/字间小空隙不切
                    if (proj[j] <= thr) gap++ else gap = 0
                    j++
                }
                val end = (j - gap).coerceAtLeast(i + 8)
                if (end - i >= 10) out.add(intArrayOf(i, end))
                i = end.coerceAtLeast(i + 1)
            }
            return out
        }
        val bx = cut(projX)
        val by = cut(projY)
        val vertical = b.vertical || bx.size > by.size
        val bands = ArrayList<RectF>(24)
        if (vertical) {
            for (k in bx.indices.reversed()) {      // 竖排: 右→左阅读序
                val bnd = bx[k]
                bands.add(RectF((x0 + bnd[0] - 2).toFloat(), y0.toFloat(),
                                (x0 + bnd[1] + 2).toFloat(), y1.toFloat()))
            }
        } else {
            for (bnd in by) {
                bands.add(RectF(x0.toFloat(), (y0 + bnd[0] - 2).toFloat(),
                                x1.toFloat(), (y0 + bnd[1] + 2).toFloat()))
            }
        }
        return vertical to bands
    }

    /** 单条文字带 → rec（PP-OCRv5 行识别，自带竖排旋转与比例切段）。 */
    private fun recRegion(page: Bitmap, r: RectF): String {
        val x0 = r.left.toInt().coerceIn(0, page.width - 1)
        val y0 = r.top.toInt().coerceIn(0, page.height - 1)
        val x1 = r.right.toInt().coerceIn(x0 + 1, page.width)
        val y1 = r.bottom.toInt().coerceIn(y0 + 1, page.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 6 || h < 6) return ""
        val px = IntArray(w * h)
        Bitmap.createBitmap(page, x0, y0, w, h).getPixels(px, 0, w, 0, 0, w, h)
        return NativeBridge.ocrRecSmall(px, w, h)?.toString(Charsets.UTF_8)?.trim().orEmpty()
    }

    private fun toStringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    fun fnv1a(data: ByteArray): String {
        // 64-bit FNV-1a with natural Long wraparound (matches the C++ side).
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (b in data) {
            h = h xor (b.toLong() and 0xFF)
            h *= 1099511628211L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    }
