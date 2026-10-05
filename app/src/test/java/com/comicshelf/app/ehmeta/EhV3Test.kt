package com.comicshelf.app.ehmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1 引擎单元自测 · v3 算法 Kotlin 移植（EhV3）。
 * 覆盖：canon 归一化、keysOf 索引键、match 查询枚举（含两条铁律的行为验证）。
 * 最终对账以真机 keys.bin 字节级 + 逐名一致为准（S1 验收）。
 */
class EhV3Test {

    // ---------------------------------------------------------------- canon

    @Test
    fun canonNfkcAndLower() {
        assertEquals("1", EhV3.canon("①"))                    // NFKC
        assertEquals("m2", EhV3.canon("㎡"))
        assertEquals("abc", EhV3.canon("ＡＢＣ"))               // 全角→半角
        assertEquals("straße", EhV3.canon("Straße"))           // Kotlin 语义：ß 保留（非 casefold）
        assertEquals("abc", EhV3.canon("a b\tc"))              // 空白全剥
        assertEquals("アイウ", EhV3.canon("ｱｲｳ"))               // 半角片假名→全角（NFKC）
    }

    @Test
    fun stripExt() {
        assertEquals("abc", EhV3.stripExt("abc.zip"))
        assertEquals("abc", EhV3.stripExt("abc.ZIP"))
        assertEquals("abc", EhV3.stripExt("abc.cbz"))
        assertEquals("abc.1", EhV3.stripExt("abc.1"))          // 非已知扩展名不动
        assertEquals("a.b", EhV3.stripExt("a.b"))              // .b 不是扩展名
        assertEquals("abc", EhV3.stripExt("abc"))
    }

    // ---------------------------------------------------------------- keysOf（索引侧）

    @Test
    fun keysOfTailBracketStripping() {
        // 逐层去尾部括号组 + forbid 变体
        val keys = EhV3.keysOf("[ABC] Title (Fate/Grand Order) [Chinese]")
        assertTrue("含 canon 全名", keys.contains("[abc]title(fate/grandorder)[chinese]"))
        assertTrue("含去尾[Chinese]", keys.contains("[abc]title(fate/grandorder)"))
        assertTrue("含去尾+去(Fate/Grand Order)", keys.contains("[abc]title"))
        assertTrue("含禁用字符变体(FateGrand Order)", keys.contains("[abc]title(fategrandorder)"))
        assertTrue("首部[ABC]不可去", !keys.contains("title(fate/grandorder)[chinese]"))
    }

    @Test
    fun keysOfNoBracket() {
        val keys = EhV3.keysOf("Simple Title")
        assertEquals(listOf("simpletitle"), keys)
    }

    @Test
    fun keysOfForbidVariant() {
        val keys = EhV3.keysOf("Re:ゼロ")
        assertTrue(keys.contains("re:ゼロ") || keys.contains("re:ゼロ".let { EhV3.canon(it) }))
        assertTrue("禁用字符变体", keys.contains("reゼロ"))
    }

    // ---------------------------------------------------------------- match（查询侧）

    private fun indexOf(titles: Map<String, Int>): (String) -> Int {
        val m = HashMap<Long, Int>(titles.size * 2)
        for ((t, gid) in titles) for (k in EhV3.keysOf(t)) m.putIfAbsent(EhV3.fnv64(k), gid)
        return { s -> m[EhV3.fnv64(s)] ?: -1 }
    }

    @Test
    fun matchExact() {
        val probe = indexOf(mapOf("[ABC] Title" to 100))
        assertEquals(100, EhV3.match("[ABC] Title.zip", probe))
    }

    @Test
    fun matchWithDecorations() {
        // 文件名带额外尾巴（汉化组/版本/分辨率）→ 丢尾段后命中
        val probe = indexOf(mapOf("(C91) [ABC] Title" to 200))
        assertEquals(200, EhV3.match("(C91) [ABC] Title [Chinese][dl].zip", probe))
        // 分辨率码跟在括号组后（真实形态；直接粘在正文段后属算法边界，设计上不救）
        assertEquals(200, EhV3.match("(C91) [ABC] Title [Chinese] -1280x720.rar", probe))
    }

    @Test
    fun matchForbidVariant() {
        // EH 标题含 "/"，文件名被 Windows 消毒 → 双向禁用字符变体
        val probe = indexOf(mapOf("Title (Fate/Grand Order)" to 300))
        assertEquals(300, EhV3.match("Title (FateGrand Order).zip", probe))
    }

    @Test
    fun matchMiddleDropRequiresSpanSort() {
        // 铁律1：中间段丢弃必须按位置排序再拼接（乱序拼接会漏删中间段）
        val probe = indexOf(mapOf("abcdefghi" to 400))
        // 文件名 = 目标名中间插入两个可丢括号组（均为中段 → 优先级相同，枚举按索引倒序）
        assertEquals(400, EhV3.match("abc(1)def(2)ghi.zip", probe))
    }

    @Test
    fun matchNoFalsePositive() {
        val probe = indexOf(mapOf("abcdefghi" to 400))
        // 破坏"受保护正文段"（CJK 内容段）不得命中
        assertEquals(-1, EhV3.match("abc(1)defXXX(2)ghi.zip", probe))
    }

    @Test
    fun noiseShapeCodePointCount() {
        // 非 BMP（emoji）按【码点】计长（与 Python len() 参考语义一致）：
        // 真机全库对账曾抓出 1 例差异（486,678 行中唯一）——9 ASCII + 5 emoji = 14 码点 /
        // 19 UTF-16 单元，UTF-16 计长会误判为内容段（不可丢）。
        assertEquals(2, EhV3.noiseShapedT("comic4p🥵🍆🍆🍆💢"))          // 14 码点 → 噪声
        assertEquals(-1, EhV3.noiseShapedT("ab😀".repeat(6)))             // 18 码点 → 内容
        assertEquals(-1, EhV3.noiseShapedT("汉字".repeat(9)))              // CJK（17 码点）
    }

    @Test
    fun matchEmojiNoiseSegment() {
        // 与参考语义对齐后的行为：emoji 噪声段可丢 → 退化候选不因计长差异而改变
        val probe = indexOf(mapOf("[GSUS] comic 4P 🥵🍆🍆🍆💢 [Chinese]" to 483058))
        assertEquals(483058, EhV3.match("[GSUS] comic 4P 🥵🍆🍆🍆💢 [Chinese].zip", probe))
    }

    @Test
    fun matchBudgetAndMiss() {
        val probe = indexOf(mapOf("target" to 500))
        assertEquals(-1, EhV3.match("complete different name.zip", probe))
    }
}
