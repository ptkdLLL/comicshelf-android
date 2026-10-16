package com.comicshelf.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势消费门槛（READER_SCROLL_STUCK_FIX_PLAN §4.2）：死区与平移空间判定的数值锁定。
 * 背景（实机取证 reader_scroll_stuck_20261016）：掌缘 8ms 擦碰在「抬指帧」把 zoom 算成
 * 1.000523（Compose calculateCentroidSize 语义），旧逻辑把它当真缩放存储 → 该页拖动被吞。
 */
class ReaderGestureGateTest {

    // ---------------- P2 死区 ----------------

    @Test
    fun grazeFrame_zoomDust_snapsToIdentity() {
        // 实机擦碰帧数值：帧2（抬指帧）zoom = 1.000523 → 必须归 1f（不再是「已放大」）
        assertEquals(1f, accumulateZoom(1f, 1.000523f), 0f)
    }

    @Test
    fun deadZone_boundary() {
        assertEquals(1f, accumulateZoom(1f, 1.0199f), 0f)
        assertEquals(1.02f, accumulateZoom(1f, 1.02f), 1e-6f)
    }

    @Test
    fun realPinch_accumulates() {
        // 真实捏合合成路径 1.05^3 × 1.09^3 ≈ 1.499（与实机「捏到 1.5」一致）
        var s = 1f
        repeat(3) { s = accumulateZoom(s, 1.05f) }
        repeat(3) { s = accumulateZoom(s, 1.09f) }
        assertEquals(1.499f, s, 0.002f)
    }

    @Test
    fun pinchBack_resetsToIdentity() {
        // 合拢（zoom<1）使累积 ≤1 → 钳回 1f（实机「合拢自愈」73.2% 的数学依据）
        var s = accumulateZoom(1f, 1.5f)
        s = accumulateZoom(s, 1f / 1.7f)
        assertEquals(1f, s, 0f)
    }

    @Test
    fun maxZoomClamped() {
        assertEquals(6f, accumulateZoom(5f, 3f), 0f)
    }

    // ---------------- P1 平移空间（与 clampPan 同源几何） ----------------
    // 视口 1080×2400；页面 1080×1512；fit = 宽度(1)

    @Test
    fun widthFit_belowFivePercent_noPanRoom() {
        assertFalse(hasPanRoom(1f, 1080f, 2400f, 1080, 1512, 1, 0))
        assertFalse(hasPanRoom(1.02f, 1080f, 2400f, 1080, 1512, 1, 0))
        // 5% 处溢出恰为 54px，判定为「严格大于」→ 不成立
        assertFalse(hasPanRoom(1.05f, 1080f, 2400f, 1080, 1512, 1, 0))
    }

    @Test
    fun widthFit_realZoom_hasPanRoom() {
        assertTrue(hasPanRoom(1.06f, 1080f, 2400f, 1080, 1512, 1, 0))
        assertTrue(hasPanRoom(1.5f, 1080f, 2400f, 1080, 1512, 1, 0))
    }

    @Test
    fun invalidInputs_safe() {
        assertFalse(hasPanRoom(2f, 1080f, 2400f, 0, 0, 1, 0))
        assertFalse(hasPanRoom(1f, 1080f, 2400f, 1080, 1512, 0, 0))
    }
}
