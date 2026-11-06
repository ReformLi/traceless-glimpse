package com.hpu.browseguard.ui.player

import org.junit.Assert.assertEquals
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

    @Test
    fun speedLabelsKeepOneDecimalForWholeSteps() {
        assertEquals(listOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x"), PlayerGestureMath.SPEED_OPTIONS.map(PlayerGestureMath::speedLabel))
    }
}
