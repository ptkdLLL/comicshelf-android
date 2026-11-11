package com.comicshelf.app.ehmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1 契约锁（计划 §五 B4）：长按 tag 行时"复制什么"由纯函数决定，
 * 本测试把决策钉死在三条可判定的性质上：
 *  1) text 恒为原名 name（不是中文显示名 nameZh）；
 *  2) toast 恒含 text，并在有中文名时附注（仅确认用，不进剪贴板）；
 *  3) 空白 name 不给载荷（防止把空串覆盖用户剪贴板）。
 * 变异判别：把实现改成 `text = nameZh ?: name`，用例 1 会红。
 */
class EhTagCopyTest {

    @Test
    fun copiesOriginalNameNotChineseDisplayName() {
        val p = tagCopyPayload("full color", "全彩")!!
        assertEquals("full color", p.text)
        assertEquals("已复制标签：full color（全彩）", p.toast)
    }

    @Test
    fun untranslatedTagStillCopiesOriginalName() {
        val p = tagCopyPayload("chinese", null)!!
        assertEquals("chinese", p.text)
        assertEquals("已复制标签：chinese", p.toast)
    }

    @Test
    fun blankDisplayNameTreatedAsAbsent() {
        val p = tagCopyPayload("chinese", "  ")!!
        assertEquals("已复制标签：chinese", p.toast)
    }

    @Test
    fun blankNameYieldsNullPayload() {
        assertNull(tagCopyPayload("", "全彩"))
        assertNull(tagCopyPayload("   ", null))
    }

    @Test
    fun textIsVerbatim() {
        val raw = "a:b / c_d (x)"
        assertEquals(raw, tagCopyPayload(raw, null)!!.text)
    }

    @Test
    fun toastAlwaysContainsCopiedText() {
        val p = tagCopyPayload("sole female", "单女")!!
        assertTrue(p.toast.contains(p.text))
    }
}
