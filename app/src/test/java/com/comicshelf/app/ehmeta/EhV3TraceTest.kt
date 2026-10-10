package com.comicshelf.app.ehmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 动态轨迹测试（A2 替代取证，EHENTAI_MATCH_V2_PLAN §7）：
 * 包装真引擎 probe 捕获【逐次探查序列 + 命中序列】——对三条路径做行为签名断言。
 *
 * 签名依据（与审计模拟器 sim_final3 同语义）：
 *  - 改投：先命中已删(100) 后仍继续探查 → 再命中在架(200) → 终值 200（v1 会在 100 即停）
 *  - 守卫：命中在架泛用键("switch"→400) 被 runGuard 拒 → 探查越过它继续 → 终值=兜底 firstAny(300)
 *  - skel：命中键 = skel 变体且探查随命中立即收敛（v1 语义保留）
 */
class EhV3TraceTest {

    private class Trace {
        val attempts = ArrayList<String>()
        val hits = ArrayList<Triple<Int, String, Int>>()   // (attemptIdx, key, gid)

        fun probeOf(titles: Map<String, Int>): (String) -> Int {
            val m = HashMap<Long, Int>(titles.size * 2)
            for ((t, gid) in titles) for (k in EhV3.keysOf(t)) m.putIfAbsent(EhV3.fnv64(k), gid)
            return { s ->
                val g = m[EhV3.fnv64(s)] ?: -1
                attempts.add(s)
                if (g >= 0) hits.add(Triple(attempts.size - 1, s, g))
                g
            }
        }
    }

    @Test
    fun twopassReplacementTraceSignature() {
        val tr = Trace()
        val probe = tr.probeOf(
            mapOf(
                "(c102)[可老家(かろう)]原神牧場(原神)[不会汉化汉化组自费汉化] [中国翻訳] [DL版]" to 100,
                "[可老家 (かろう)] 原神牧場 (原神) [DL版]" to 200,
            )
        )
        val g = EhV3.match(
            "(C102) [可老家 (かろう)] 原神牧場 (原神) [不会汉化汉化组自费汉化] [DL版]",
            probe, { it == 100 },
        )
        assertEquals(200, g)                                     // 改投终值
        assertTrue("至少两次命中（已删与其后续在架）", tr.hits.size >= 2)
        assertEquals(100, tr.hits.first().third)                 // 首命中 = 已删
        assertEquals(200, tr.hits.last().third)                  // 末命中 = 在架改投目标
        assertTrue("改投发生在首命中之后（探查继续签名）", tr.hits.last().first > tr.hits.first().first)
        assertTrue("命中已删后并非立即收敛（>1 次命中即证）", tr.hits.size > 1)
    }

    @Test
    fun guardRejectTraceSignature() {
        val tr = Trace()
        val probe = tr.probeOf(
            mapOf(
                "[DAIGO] SWITCH (Yu-Gi-Oh)" to 300,
                "Switch (Fate/Stay Night)" to 400,
            )
        )
        val g = EhV3.match("【黑条汉化】[DAIGO] SWITCH【前編】.zip", probe, { it == 300 })
        assertEquals(300, g)                                     // 兜底（守卫拒后回 firstAny）
        assertTrue("曾命中在架泛用键 400", tr.hits.any { it.third == 400 })
        assertTrue("终值 ≠ 泛用键命中（=被守卫拒绝的签名）", tr.hits.any { it.third == 400 } && g != 400)
        assertTrue("runGuard(switch)=false", !EhV3.runGuard("switch"))
    }

    @Test
    fun skelHitConvergesImmediately() {
        val tr = Trace()
        val probe = tr.probeOf(mapOf("[NTRMAN] Tomato: Part 2 [English]" to 500))
        val g = EhV3.match("[NTRMAN] Tomato_ Part 2 [English].zip", probe, null)
        assertEquals(500, g)
        assertEquals(1, tr.hits.size)
        val hitIdx = tr.hits[0].first
        assertEquals("命中键 = skel 变体（':'→'_' 消杀后）", "[ntrman]tomatopart2[english]", tr.attempts[hitIdx])
        assertTrue("命中后至多本候选族尾（≤2）", tr.attempts.size - 1 - hitIdx <= 2)
    }

    @Test
    fun degenerateFirstLiveHitSkippedForGuardedLater() {
        // Rule A（v2.1 跨端对账定版）签名：首命中=live 但键退化（(c97)，守卫拒）→ 跳过 →
        // 后续合规键命中 → 终值=合规者（v1/旧 Rule B 会停在 (c97) 垃圾图库）
        val tr = Trace()
        val probe = tr.probeOf(
            mapOf(
                "(C97) [multiplication] (sample)" to 97,                 // 注册键 "(c97)"（嵌套去尾产物）
                "[Super Flat Lolinitron (Focke Wolf)] [dl]" to 180,      // 注册键 "[superflatlolinitron(fockewolf)]"
            )
        )
        val g = EhV3.match("(C97) [Super Flat Lolinitron (Focke Wolf)] js jc 5-1280x", probe, { false })
        assertEquals(180, g)
        assertTrue("(c97) 命中被见过但被跳过", tr.hits.any { it.third == 97 && it.second == "(c97)" })
        assertEquals(180, tr.hits.last().third)
    }

    @Test
    fun familyOrderIsBaseForbidSkel() {
        val tr = Trace()
        val probe = tr.probeOf(mapOf("[NTRMAN] Tomato: Part 2 [English]" to 500))
        EhV3.match("[NTRMAN] Tomato_ Part 2 [English].zip", probe, null)
        assertEquals("[ntrman]tomato_part2[english]", tr.attempts[0])   // s
        assertEquals("[ntrman]tomatopart2[english]", tr.attempts[1])    // forbid(s)==s 跳过 → skel(s)
    }
}
