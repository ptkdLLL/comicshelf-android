package com.comicshelf.app.ehmeta

import java.text.Normalizer
import java.util.Locale

/**
 * E-Hentai 匹配引擎 v3 · 通用枚举算法（D9：索引侧零内容模式规则 + 查询侧内容盲段枚举）。
 *
 * 来源链：eh_work/ehmatch3.py（Python 参考）→ eh_work/bench_android（真机验证
 * 30000 样本 25293 命中与 Mac 逐名一致）→ 本文件（S1 生产化移植）。
 *
 * 两条铁律（真机移植事故换来，禁止"优化"掉）：
 *  1. 多段丢弃组合必须先按【位置】排序再拼接（按优先级顺序拼接会漏删中间段）；
 *  2. keys.bin 按【无符号】hash 升序，二分必须 Long.compareUnsigned（带符号比较
 *     在 hash >= 2^63 的一半键空间上失效：命中 15651 vs 25293）。
 *
 * 归一化口径：NFKC → lowercase(Locale.ROOT) → 空白全剥（Python 空格集合）。
 * Mac 侧参考脚本用 unicodedata.normalize('NFKC', s).lower() 复刻（已验证 0 差异）。
 */
object EhV3 {

    private val EXTS = arrayOf(".zip", ".rar", ".7z", ".cbz", ".cbr", ".pdf", ".epub")

    /** 段：'B' = 括号组（成对、同型嵌套），'T' = 自由文本段。[a,b) 为在 base 中的位置。 */
    class Seg(val kind: Char, val text: String, val a: Int, val b: Int)

    // ---------------------------------------------------------------- canon

    /** NFKC + 小写 + 空白全剥 */
    fun canon(s: String): String {
        val nn = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val lower = nn.lowercase(Locale.ROOT)
        val sb = StringBuilder(lower.length)
        for (c in lower) if (!isPySpace(c)) sb.append(c)
        return sb.toString()
    }

    fun stripExt(s: String): String {
        val low = s.lowercase(Locale.ROOT)
        for (e in EXTS) if (low.endsWith(e)) return s.substring(0, s.length - e.length)
        return s
    }

    // Python 的 str 空白集合（\s）在 BMP 上的等价枚举（与 bench/Python 双向一致）
    private fun isPySpace(c: Char): Boolean {
        val i = c.code
        return i == 0x09 || i == 0x0A || i == 0x0B || i == 0x0C || i == 0x0D || i == 0x20 ||
            i == 0x85 || i == 0xA0 || i == 0x1680 ||
            (i in 0x2000..0x200A) || i == 0x2028 || i == 0x2029 || i == 0x202F ||
            i == 0x205F || i == 0x3000
    }

    // ---------------------------------------------------------------- 段切分

    private val OPENS = charArrayOf('(', '（', '[', '［', '{', '｛', '【', '「', '『', '〈', '《')
    private val CLOSES = charArrayOf(')', '）', ']', '］', '}', '｝', '】', '」', '』', '〉', '》')

    private val CJK = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff\\u30fc]")

    /**
     * 形状判定（内容盲）：-1 = 内容段（含 CJK 或 >16 字符）；2 = 高可疑短码；4 = 低可疑短段。
     *
     * ⚠️ 长度按【码点】而非 UTF-16 单元计（与 Python len() 参考语义一致）：
     * 非 BMP 字符（emoji 等）在 Kotlin 中占 2 个 char，用 String.length 会把含 emoji 的
     * 短噪声段误判为内容段——真机全库对账曾抓出 1 例（486,678 行中唯一差异）。
     */
    fun noiseShapedT(t: String): Int {
        val tt = t.trim()
        if (tt.isEmpty()) return 2
        if (CJK.containsMatchIn(tt)) return -1
        val n = tt.codePointCount(0, tt.length)
        if (n > 16) return -1
        return if (n <= 6 || tt.any { it.isDigit() }) 2 else 4
    }

    fun segments(s: String): ArrayList<Seg> {
        val segs = ArrayList<Seg>(8)
        var i = 0
        val n = s.length
        while (i < n) {
            val oi = OPENS.indexOf(s[i])
            if (oi >= 0) {
                val close = CLOSES[oi]
                var depth = 1
                var j = i + 1
                while (j < n) {
                    val ch = s[j]
                    if (ch == s[i]) depth++
                    else if (ch == close) {
                        depth--
                        if (depth == 0) { j++; break }
                    }
                    j++
                }
                if (depth == 0) {
                    segs.add(Seg('B', s.substring(i, j), i, j))
                    i = j
                    continue
                }
            }
            var j = i
            while (j < n && OPENS.indexOf(s[j]) < 0) j++
            if (j == i) j = i + 1
            segs.add(Seg('T', s.substring(i, j), i, j))
            i = j
        }
        return segs
    }

    /** 删除禁用字符变体（库侧文件名被 Windows 消毒：/ : * ? " < > | 被剥掉） */
    fun forbid(s: String): String {
        var need = false
        for (c in s) {
            if (c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                need = true; break
            }
        }
        if (!need) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            if (!(c == '/' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|'))
                sb.append(c)
        }
        return sb.toString()
    }

    fun fnv64(s: String): Long {
        var h = 0xcbf29ce484222325uL.toLong()
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3uL.toLong()
        }
        return h
    }

    // ---------------------------------------------------------------- 索引侧键（Python eh_keys 移植）

    /**
     * 单个标题 → 索引键集合：
     *  canon 全名 → 逐层去【尾部】括号组（仅当该组不在行首）→ 每层追加禁用字符变体。
     *  与 Mac 参考 build_keys 逐位一致（byte-compare 验收）。
     */
    fun keysOf(title: String): List<String> {
        if (title.isEmpty()) return emptyList()
        var s = canon(title)
        val ks = ArrayList<String>(4)
        ks.add(s)
        while (true) {
            val segs = segments(s)
            if (segs.isEmpty()) break
            val last = segs[segs.size - 1]
            if (last.kind == 'B' && last.a > 0) {
                val s2 = dropSpans(s, intArrayOf(last.a), intArrayOf(last.b))
                if (s2.isNotEmpty() && s2 != s) { s = s2; ks.add(s) } else break
            } else break
        }
        val out = ArrayList<String>(ks.size * 2)
        for (k in ks) {
            out.add(k)
            val f = forbid(k)
            if (f.isNotEmpty() && f != k) out.add(f)
        }
        return out
    }

    /** 按位置删除若干 span（输入可乱序；内部先排序 —— 铁律1 的实现点） */
    fun dropSpans(s: String, as_: IntArray, bs_: IntArray): String {
        val m = as_.size
        if (m == 0) return s
        val idx = (0 until m).sortedBy { as_[it] }
        val sb = StringBuilder(s.length)
        var last = 0
        for (x in idx) {
            val a = as_[x]; val b = bs_[x]
            if (a > last) sb.append(s, last, a)
            if (b > last) last = b
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    // ---------------------------------------------------------------- 查询侧匹配（bench matchBook 移植）

    /**
     * 对一个原始名（书名/文件名）做匹配。
     * @param probe 传入候选串，返回命中的 gid（>=0）或 -1。
     * @return 命中的 gid；未命中 -1。
     * 语义与 bench 完全一致：probe(base) → [forbid] → 迭代加深丢 1..4 段 → 预算 256 probe，命中即停。
     */
    fun match(raw: String, probe: (String) -> Int): Int {
        val base = canon(stripExt(raw))
        val segs = segments(base)
        val sn = segs.size
        var firstC = 0
        var lastC = sn - 1
        var hasContent = false
        for (i in 0 until sn) {
            val s = segs[i]
            if (s.kind == 'T' && noiseShapedT(s.text) < 0) {
                if (!hasContent) { firstC = i; hasContent = true }
                lastC = i
            }
        }
        val droppable = ArrayList<IntArray>()
        for (i in 0 until sn) {
            val s = segs[i]
            if (s.kind == 'B') {
                val pri = if (i > lastC) 0 else if (i < firstC) 1 else 3
                droppable.add(intArrayOf(pri, i))
            } else {
                val pr = noiseShapedT(s.text)
                if (pr >= 0 && s.text.isNotBlank()) droppable.add(intArrayOf(pr, i))
            }
        }
        droppable.sortWith(compareBy({ it[0] }, { -it[1] }))
        val olen = minOf(10, droppable.size)
        val order = IntArray(olen) { droppable[it][1] }

        var emitted = 0
        var hit = -1
        fun probeOne(s: String) {
            if (hit >= 0 || s.isEmpty() || emitted >= 256) return
            emitted++
            val g = probe(s)
            if (g >= 0) hit = g
        }
        fun probeV(s: String) {
            probeOne(s)
            if (hit < 0) {
                val f = forbid(s)
                if (f != s) probeOne(f)
            }
        }
        probeV(base)
        if (hit < 0 && olen > 0) {
            val combo = IntArray(4)
            fun rec(depth: Int, start: Int, need: Int) {
                if (hit >= 0) return
                if (depth == need) {
                    // 铁律1：组合内段位置可能乱序 —— 先按位置排序再拼接
                    val as_ = IntArray(need)
                    val bs_ = IntArray(need)
                    for (x in 0 until need) {
                        val idx = order[combo[x]]
                        as_[x] = segs[idx].a
                        bs_[x] = segs[idx].b
                    }
                    for (x in 1 until need) {
                        var y = x
                        while (y > 0 && as_[y] < as_[y - 1]) {
                            val ta = as_[y]; as_[y] = as_[y - 1]; as_[y - 1] = ta
                            val tb = bs_[y]; bs_[y] = bs_[y - 1]; bs_[y - 1] = tb
                            y--
                        }
                    }
                    val sb = StringBuilder(base.length)
                    var last = 0
                    for (x in 0 until need) {
                        val a = as_[x]
                        val b = bs_[x]
                        if (a > last) sb.append(base, last, a)
                        if (b > last) last = b
                    }
                    sb.append(base, last, base.length)
                    probeV(sb.toString())
                    return
                }
                for (j in start until olen) {
                    if (hit >= 0) return
                    combo[depth] = j
                    rec(depth + 1, j + 1, need)
                }
            }
            for (need in 1..4) {
                if (hit >= 0) break
                rec(0, 0, need)
            }
        }
        return hit
    }
}
