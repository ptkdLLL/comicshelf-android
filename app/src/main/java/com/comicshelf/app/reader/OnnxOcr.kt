package com.comicshelf.app.reader

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import com.comicshelf.app.util.AppContextHolder
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Manga OCR through ONNX Runtime, using the NNAPI execution provider so the
 * graphs can be dispatched to the Hexagon DSP / Adreno GPU where the device
 * provides a driver, falling back to the CPU kernels otherwise.
 *
 *   det : PP-OCRv5 mobile detector  960x960 -> DB probability map
 *   rec : PP-OCRv5 mobile recognizer 48x320 -> CTC logits [40,18385]
 *
 * Both models run as shipped (fp32, verified bit-exact against the reference
 * implementation); the NNAPI EP handles device-side precision.
 */
object OnnxOcr {

    private const val TAG = "OnnxOcr"
    private const val DET = 960
    private const val REC_H = 48
    private const val REC_W = 320
    private const val REC_T = 40
    private const val REC_C = 18385

    data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float)

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dict: List<String> = emptyList()

    @Volatile var ready = false
        private set
    var backend: String = ""
        private set

    // reusable scratch
    private val detBuf = FloatArray(3 * DET * DET)

    /** Stages the models out of the assets and creates the sessions. */
    fun init(): Boolean {
        if (ready) return true
        val ctx = AppContextHolder.app
        val dir = File(ctx.filesDir, "ort_models")
        if (!dir.isDirectory && !dir.mkdirs()) return false
        try {
            for (n in listOf("ocr_det.onnx", "ocr_rec.onnx", "ppocrv5_dict.txt")) {
                val out = File(dir, n)
                val asset = ctx.assets.open("qnn/$n")
                val want = asset.available().toLong()
                if (out.isFile && out.length() == want && want > 0) { asset.close(); continue }
                asset.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            }
        } catch (e: Exception) {
            Log.e(TAG, "staging failed: $e")
            return false
        }

        dict = File(dir, "ppocrv5_dict.txt").readText(Charsets.UTF_8).split("\n")
        if (dict.isEmpty()) {
            Log.e(TAG, "empty dictionary")
            return false
        }

        val e = OrtEnvironment.getEnvironment()
        env = e
        // Prefer NNAPI (Hexagon/Adreno); the CPU provider stays as fallback for
        // the partition of the graph the device driver does not accept.
        val nnapi = OrtSession.SessionOptions().apply {
            try {
                addNnapi()
                backend = "nnapi+cpu"
            } catch (t: Throwable) {
                Log.w(TAG, "NNAPI unavailable: $t")
                backend = "cpu"
            }
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        try {
            detSession = e.createSession(File(dir, "ocr_det.onnx").absolutePath, nnapi)
            recSession = e.createSession(File(dir, "ocr_rec.onnx").absolutePath, nnapi)
        } catch (t: Throwable) {
            Log.w(TAG, "session (nnapi) failed: $t; retrying cpu-only")
            backend = "cpu"
            val cpu = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return try {
                detSession = e.createSession(File(dir, "ocr_det.onnx").absolutePath, cpu)
                recSession = e.createSession(File(dir, "ocr_rec.onnx").absolutePath, cpu)
                warmup()
                ready = true
                Log.i(TAG, "ready ($backend)")
                true
            } catch (t2: Throwable) {
                Log.e(TAG, "session failed: $t2")
                false
            }
        }
        warmup()
        ready = true
        Log.i(TAG, "ready ($backend)")
        return true
    }

    /** One throwaway run of each graph: NNAPI compiles on first execution. */
    private fun warmup() {
        try {
            val blank = Bitmap.createBitmap(DET, DET, Bitmap.Config.ARGB_8888)
            detect(blank)
            blank.recycle()
            recRun(FloatArray(3 * REC_H * REC_W))
            Log.i(TAG, "warmup done")
        } catch (t: Throwable) {
            Log.w(TAG, "warmup failed: $t")
        }
    }

    // ------------------------------------------------------------- detection

    /** Runs the detector and returns boxes in the coordinates of [bitmap]. */
    fun detect(bitmap: Bitmap): List<Box> {
        val scaled = Bitmap.createScaledBitmap(bitmap, DET, DET, true)
        val px = IntArray(DET * DET)
        scaled.getPixels(px, 0, DET, 0, 0, DET, DET)
        if (scaled !== bitmap) scaled.recycle()
        fillDetChw(px)

        val s = detSession ?: return emptyList()
        val chw = detBuf
        val raw = ArrayList<Box>(64)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw),
            longArrayOf(1, 3, DET.toLong(), DET.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val map = (res[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                postprocess(map, raw)
            }
        }
        val sx = bitmap.width.toFloat() / DET
        val sy = bitmap.height.toFloat() / DET
        return raw.map { Box(it.x0 * sx, it.y0 * sy, it.x1 * sx, it.y1 * sy, it.score) }
    }

    private fun fillDetChw(px: IntArray) {
        val plane = DET * DET
        val mul = floatArrayOf(1f / 255f / 0.229f, 1f / 255f / 0.224f, 1f / 255f / 0.225f)
        val add = floatArrayOf(-0.485f / 0.229f, -0.456f / 0.224f, -0.406f / 0.225f)
        for (i in px.indices) {
            val p = px[i]
            detBuf[i] = ((p shr 16) and 0xFF) * mul[0] + add[0]
            detBuf[plane + i] = ((p shr 8) and 0xFF) * mul[1] + add[1]
            detBuf[2 * plane + i] = (p and 0xFF) * mul[2] + add[2]
        }
    }

    /** DB postprocess: binarise, connected components, score filter, unclip. */
    private fun postprocess(prob: Array<FloatArray>, out: MutableList<Box>) {
        val w = DET
        val h = DET
        val thresh = 0.3f
        val boxThresh = 0.5f
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (seen[idx] || prob[y][x] < thresh) continue
                var sp = 0
                stack[sp++] = idx
                seen[idx] = true
                var minX = x; var maxX = x; var minY = y; var maxY = y
                var area = 0
                var sum = 0.0
                while (sp > 0) {
                    val cur = stack[--sp]
                    val cy = cur / w
                    val cx = cur - cy * w
                    sum += prob[cy][cx]
                    area++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    if (cx > 0) { val n = cur - 1; if (!seen[n] && prob[cy][cx - 1] >= thresh) { seen[n] = true; stack[sp++] = n } }
                    if (cx < w - 1) { val n = cur + 1; if (!seen[n] && prob[cy][cx + 1] >= thresh) { seen[n] = true; stack[sp++] = n } }
                    if (cy > 0) { val n = cur - w; if (!seen[n] && prob[cy - 1][cx] >= thresh) { seen[n] = true; stack[sp++] = n } }
                    if (cy < h - 1) { val n = cur + w; if (!seen[n] && prob[cy + 1][cx] >= thresh) { seen[n] = true; stack[sp++] = n } }
                }
                if (area < 12) continue
                val score = (sum / area).toFloat()
                if (score < boxThresh) continue
                val bw = (maxX - minX + 1).toFloat()
                val bh = (maxY - minY + 1).toFloat()
                val pad = max(1.5f, min(bw, bh) * 0.12f)
                out.add(Box(
                    (minX - pad).coerceAtLeast(0f),
                    (minY - pad).coerceAtLeast(0f),
                    (maxX + 1 + pad).coerceAtMost(w.toFloat()),
                    (maxY + 1 + pad).coerceAtMost(h.toFloat()),
                    score))
            }
        }
    }

    // ---------------------------------------------------------- recognition

    private val recBuf = FloatArray(3 * REC_H * REC_W)

    private fun recRun(chw: FloatArray): FloatArray {
        val s = recSession ?: return FloatArray(0)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, REC_H.toLong(), REC_W.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = (res[0].value as Array<Array<FloatArray>>)[0]
                val flat = FloatArray(REC_T * REC_C)
                for (t2 in 0 until REC_T) System.arraycopy(out[t2], 0, flat, t2 * REC_C, REC_C)
                return flat
            }
        }
    }

    /** Recognises one text-line crop; returns UTF-8 text (may be empty). */
    fun recognize(crop: Bitmap): String = recognizeConf(crop).first

    /**
     * Same as [recognize], plus a mean logit-margin confidence (0 when empty).
     * Margin = best - second-best logit at each emitted step; it needs no softmax
     * (cheap) and is only used to rank/route: high margin ⇒ PP-OCRv5 is sure.
     */
    fun recognizeConf(crop: Bitmap): Pair<String, Float> {
        if (!ready) return "" to 0f
        var bmp = crop
        // vertical CJK columns are rotated so the model reads left-to-right
        if (bmp.height > bmp.width * 3 / 2) {
            val m = android.graphics.Matrix()
            m.postRotate(-90f)
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp = r
        }
        // Long columns are cut into pieces of the recogniser's native aspect:
        // squeezing a whole column into 320px destroys the glyphs.
        val maxPieceW = max(1, REC_W * bmp.height / REC_H)
        val out = StringBuilder()
        var confSum = 0f
        var confN = 0
        var x = 0
        try {
            while (x < bmp.width) {
                val w = min(maxPieceW, bmp.width - x)
                if (w < 4) break
                val piece = Bitmap.createBitmap(bmp, x, 0, w, bmp.height)
                val (s, c) = recognizePiece(piece)
                out.append(s)
                if (s.isNotEmpty()) { confSum += c * s.length; confN += s.length }
                piece.recycle()
                x += w
            }
        } finally {
            if (bmp !== crop) bmp.recycle()
        }
        val text = out.toString().trim()
        return text to (if (confN > 0 && text.isNotEmpty()) confSum / confN else 0f)
    }

    private fun recognizePiece(bmp: Bitmap): Pair<String, Float> {
        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
        var rw = REC_W
        var rh = REC_H
        if (REC_H * ratio <= REC_W) rw = max(1, (REC_H * ratio).roundToInt())
        else rh = max(1, (REC_W / ratio).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bmp, rw, rh, true)
        val px = IntArray(rw * rh)
        scaled.getPixels(px, 0, rw, 0, 0, rw, rh)
        scaled.recycle()
        java.util.Arrays.fill(recBuf, -1f)   // canvas: (0/255-0.5)/0.5 == -1
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val p = px[y * rw + x]
                val i = y * REC_W + x
                recBuf[i] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                recBuf[REC_H * REC_W + i] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                recBuf[2 * REC_H * REC_W + i] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
            }
        }

        val logits = recRun(recBuf)
        if (logits.isEmpty()) return "" to 0f
        val sb = StringBuilder()
        var prev = -1
        var margin = 0f
        var n = 0
        for (t in 0 until REC_T) {
            var best = 0
            var bestV = -Float.MAX_VALUE
            var secondV = -Float.MAX_VALUE
            val base = t * REC_C
            for (c in 0 until REC_C) {
                val v = logits[base + c]
                if (v > bestV) { secondV = bestV; bestV = v; best = c }
                else if (v > secondV) secondV = v
            }
            if (best != prev && best > 0) {
                val di = best - 1
                when {
                    di < dict.size -> sb.append(dict[di])
                    di == dict.size -> sb.append(' ')
                }
                margin += (bestV - secondV)
                n++
            }
            prev = best
        }
        return sb.toString() to (if (n > 0) margin / n else 0f)
    }
}
