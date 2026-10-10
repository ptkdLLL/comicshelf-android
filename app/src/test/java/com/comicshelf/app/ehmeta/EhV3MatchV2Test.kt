package com.comicshelf.app.ehmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 引擎单测（EHENTAI_MATCH_V2_PLAN §4/§5）：
 *  - skel 骨架谓词（范畴表：消杀家族全删 / 字母数字括号保留）
 *  - runGuard 标定点（审计数据集：拒泛用键 / 收标题键）
 *  - keysOf 骨架变体；match 探查族 + 改投 + 守卫兜底三条路径
 * 夹具取自审计实例（REPORT.md 附录 A/B/C）。
 */
class EhV3MatchV2Test {

    // ---------------------------------------------------------------- skel / runGuard

    @Test
    fun skelDropsSeparatorFamilies() {
        assertEquals("tomatopart2", EhV3.skel("tomato:part2"))          // : 消杀
        assertEquals("mitsuhanetorare3", EhV3.skel("mitsuha_netorare3_")) // _ 消杀
        assertEquals("challenge4番外篇", EhV3.skel("challenge4|番外篇"))   // | 消杀
        assertEquals("doukyomicchaku", EhV3.skel("doukyo?micchaku"))    // ? 消杀
        assertEquals("netorare", EhV3.skel("~netorare~"))               // ~ 消杀
        assertEquals("netorare", EhV3.skel("〜netorare〜"))             // 〜（U+301C）消杀
        assertEquals("aib", EhV3.skel("a・i・b"))                       // 中点消杀
        assertEquals("ab", EhV3.skel("☆a♥b"))                          // 符号/emoji 消杀
        assertEquals("", EhV3.skel("☆☆☆"))
        assertEquals("abc", EhV3.skel("abc"))                           // 字母保留
        assertEquals("原神牧場", EhV3.skel("原神牧場"))                     // CJK 保留
        assertEquals("[]（）", EhV3.skel("[]（）"))                       // 结构括号保留
        assertEquals("々ー", EhV3.skel("々ー"))                          // 叠字点(Lm)/长音(Lm) 保留
    }

    @Test
    fun runGuardKnownCases() {
        // 审计标定：泛用/退化键 → 拒
        assertFalse(EhV3.runGuard("aki"))
        assertFalse(EhV3.runGuard("[chinese]"))
        assertFalse(EhV3.runGuard("[english]"))
        assertFalse(EhV3.runGuard("(c96)"))
        assertFalse(EhV3.runGuard("(c58)"))
        assertFalse(EhV3.runGuard("switch"))
        assertFalse(EhV3.runGuard("["))
        // v2：括号内长单词不算（跨端对账实证坑：[aigenerated]/[decensored] 曾骗过 v1 守卫）
        assertFalse(EhV3.runGuard("[aigenerated]"))
        assertFalse(EhV3.runGuard("[decensored]"))
        // 标题类键 → 收
        assertTrue(EhV3.runGuard("[メメ50]発情警報"))
        assertTrue(EhV3.runGuard("[可老家(かろう)]原神牧場(原神)"))
        assertTrue(EhV3.runGuard("blacknoise"))                          // 顶层裸标题单串 ≥10
        assertTrue(EhV3.runGuard("bootlegvol1"))                         // 同上
        assertTrue(EhV3.runGuard("[abc]title(x)"))
    }

    // ---------------------------------------------------------------- keysOf 骨架变体

    @Test
    fun keysOfAddsSkelVariants() {
        val keys = EhV3.keysOf("[NSFW] A_B: C [Chinese]")
        assertTrue("含骨架变体", keys.contains("[nsfw]abc[chinese]"))
        // 短骨架（< SKEL_MIN）不追加：Re:ゼロ → skel=reゼロ(5) 不新增（forbid 变体已有一个）
        val k2 = EhV3.keysOf("Re:ゼロ")
        assertTrue(k2.contains("reゼロ"))
        assertEquals("短骨架不重复追加", 1, k2.count { it == "reゼロ" })
    }

    // ---------------------------------------------------------------- match 三路径

    private fun indexOf(
        titles: Map<String, Int>,
        expunged: Set<Int> = emptySet(),
    ): Pair<(String) -> Int, (Int) -> Boolean> {
        val m = HashMap<Long, Int>(titles.size * 2)
        for ((t, gid) in titles) for (k in EhV3.keysOf(t)) m.putIfAbsent(EhV3.fnv64(k), gid)
        return Pair({ s -> m[EhV3.fnv64(s)] ?: -1 }, { g -> g in expunged })
    }

    @Test
    fun conservativeWhenNoExpungedInfo() {
        // isExpunged=null → 与 v1 行为一致：首命中即返（不启用改投）
        val (probe, _) = indexOf(mapOf("[ABC] Title (X) [Group]" to 100))
        assertEquals(100, EhV3.match("[ABC] Title (X) [Group] [dl].zip", probe))
    }

    @Test
    fun skelHitColonUnderscore() {
        // 用户报障原例：Tomato_ Part 2 ← 图库 Tomato: Part 2（':' → '_' 消毒）
        val (probe, exp) = indexOf(mapOf("[NTRMAN] Tomato: Part 2 [English]" to 500))
        assertEquals(500, EhV3.match("[NTRMAN] Tomato_ Part 2 [English].zip", probe, exp))
    }

    @Test
    fun twopassReplacesExpungedWithLiveSibling() {
        // 原神牧場形态：firstAny=已删（含汉化组键），在架同作品 = 去组名键 → 改投
        val (probe, exp) = indexOf(
            mapOf(
                "(c102)[可老家(かろう)]原神牧場(原神)[不会汉化汉化组自费汉化] [中国翻訳] [DL版]" to 100,  // 已删
                "[可老家 (かろう)] 原神牧場 (原神) [DL版]" to 200,                                        // 在架
            ),
            expunged = setOf(100),
        )
        assertEquals(200,
            EhV3.match("(C102) [可老家 (かろう)] 原神牧場 (原神) [不会汉化汉化组自费汉化] [DL版]", probe, exp))
    }

    @Test
    fun twopassGuardRejectsDegenerateReplacement() {
        // SWITCH 形态：firstAny=已删（[daigo]switch 正主），唯有在架命中 = 泛用键 "switch"（守卫拒）
        // → 兜底保留 firstAny（用户原则：正确前提下多多益善）
        val (probe, exp) = indexOf(
            mapOf(
                "[DAIGO] SWITCH (Yu-Gi-Oh)" to 300,      // 已删（正确作品）
                "Switch (Fate/Stay Night)" to 400,       // 在架（不同作品，泛用键）
            ),
            expunged = setOf(300),
        )
        assertEquals(300, EhV3.match("【黑条汉化】[DAIGO] SWITCH【前編】.zip", probe, exp))
    }

    @Test
    fun skelDoesNotBreakStructureLayer() {
        // 结构层纪律：走失的假名排除（v1 铁律不因 v2 松弛）
        val (probe, exp) = indexOf(mapOf("abcdefghi" to 400))
        assertEquals(-1, EhV3.match("abc(1)defXXX(2)ghi.zip", probe, exp))
    }
}
