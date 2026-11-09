package com.hpu.browseguard.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器手势的数值边界（M12）。
 *
 * 手势层在真机上最难复现的是「滑到片头之前」和「滑过片尾」两条边界；
 * 这里把计算拆出来先钉住，真机只验手感。
 */
class PlayerGestureMathTest {

    private val duration = 1_200_000L // 20:00

    @Test
    fun horizontalDragMovesNinetySecondsPerScreen() {
        assertEquals(390_000L, PlayerGestureMath.seekTargetMs(300_000L, duration, 1f))
        assertEquals(210_000L, PlayerGestureMath.seekTargetMs(300_000L, duration, -1f))
        assertEquals(300_000L, PlayerGestureMath.seekTargetMs(300_000L, duration, 0f))
        assertEquals(345_000L, PlayerGestureMath.seekTargetMs(300_000L, duration, 0.5f))
    }

    @Test
    fun seekNeverLeavesTheVideo() {
        assertEquals(0L, PlayerGestureMath.seekTargetMs(10_000L, duration, -1f))
        assertEquals(duration, PlayerGestureMath.seekTargetMs(1_150_000L, duration, 1f))
    }

    @Test
    fun unknownDurationOnlyClampsAtZero() {
        assertEquals(0L, PlayerGestureMath.seekTargetMs(5_000L, 0L, -1f))
        assertEquals(95_000L, PlayerGestureMath.seekTargetMs(5_000L, 0L, 1f))
    }

    @Test
    fun verticalDragAdjustsBothEndsWithinRange() {
        assertEquals(0.75f, PlayerGestureMath.brightnessFor(0.5f, -0.25f), 0.0001f)
        assertEquals(PlayerGestureMath.MIN_BRIGHTNESS, PlayerGestureMath.brightnessFor(0.5f, 1f), 0.0001f)
        assertEquals(PlayerGestureMath.MAX_BRIGHTNESS, PlayerGestureMath.brightnessFor(0.5f, -1f), 0.0001f)
    }

    @Test
    fun volumeUsesTheDeviceStepCount() {
        assertEquals(10, PlayerGestureMath.volumeFor(5, -0.5f, 10))
        assertEquals(0, PlayerGestureMath.volumeFor(5, 1f, 10))
        assertEquals(10, PlayerGestureMath.volumeFor(5, -1f, 10))
        assertEquals(0, PlayerGestureMath.volumeFor(5, -1f, 0))
    }

    /** 锁定态没有手势轴，滑动和点击共用一条收尾分支：只有位移不超过 slop 才算「点一下」。 */
    @Test
    fun onlyAMovementWithinTouchSlopCountsAsATap() {
        val slop = 24f
        assertTrue("手指没动就是点击", PlayerGestureMath.isTap(0f, 0f, slop))
        assertTrue("刚好压在阈值上仍算点击", PlayerGestureMath.isTap(slop, 0f, slop))
        assertFalse("横向划过去不算点击", PlayerGestureMath.isTap(slop + 1f, 0f, slop))
        assertFalse("纵向划过去也不算点击", PlayerGestureMath.isTap(0f, -(slop + 1f), slop))
        assertTrue("斜着抖一下，只要直线距离没超阈值就算点击", PlayerGestureMath.isTap(slop * 0.6f, slop * 0.6f, slop))
        assertFalse("斜着划出阈值不算点击", PlayerGestureMath.isTap(slop * 0.8f, slop * 0.8f, slop))
    }

    /** 双击要同时满足「时间在窗口内」和「落点几乎重合」；差一条都不算，按两次单击走。 */
    @Test
    fun onlyAQuickCoLocatedSecondTapCountsAsADoubleTap() {
        val slop = 24f
        assertTrue("窗口内、同位置就是双击", PlayerGestureMath.isDoubleTap(120L, 0f, 0f, slop))
        assertTrue("压在窗口边界上仍算双击", PlayerGestureMath.isDoubleTap(PlayerGestureMath.DOUBLE_TAP_MS, 0f, 0f, slop))
        assertFalse(
            "隔太久只是两次单击，第一击唤出的控制条不该被吞掉",
            PlayerGestureMath.isDoubleTap(PlayerGestureMath.DOUBLE_TAP_MS + 1L, 0f, 0f, slop),
        )
        assertFalse(
            "两击离得太远（两击间手指挪了一掌宽）不算双击",
            PlayerGestureMath.isDoubleTap(120L, slop + 1f, 0f, slop),
        )
        assertFalse(
            "纵向也同理",
            PlayerGestureMath.isDoubleTap(120L, 0f, -(slop + 1f), slop),
        )
        assertTrue("小抖动不影响双击判定", PlayerGestureMath.isDoubleTap(120L, slop * 0.5f, slop * 0.5f, slop))
    }

    @Test
    fun speedLabelsKeepOneDecimalForWholeSteps() {
        assertEquals(listOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"), PlayerGestureMath.SPEED_OPTIONS.map(PlayerGestureMath::speedLabel))
    }
}
