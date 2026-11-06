package com.hpu.browseguard.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 续播点归一的边界。
 *
 * 这一层决定「下次打开从哪儿播」，写错了用户看到的就是凭空跳了一段或者从头重播，
 * 所以两个阈值都按边界值钉住，而不是只测几个顺眼的数。
 */
class PlaybackResumeTest {

    @Test
    fun positionsBeforeTheNoiseFloorAreNotAViewingPoint() {
        assertEquals(0L, PlaybackResume.resumePositionMs(0, 120_000))
        assertEquals(0L, PlaybackResume.resumePositionMs(1_999, 120_000))
        assertEquals("到线就算：这段时间是真看到了画面", 2_000L, PlaybackResume.resumePositionMs(2_000, 120_000))
    }

    @Test
    fun theTailOfAShortClipCountsAsFinished() {
        assertEquals(104_999L, PlaybackResume.resumePositionMs(104_999, 120_000))
        assertEquals("距结尾不足 15 秒 = 看完", 0L, PlaybackResume.resumePositionMs(105_000, 120_000))
        assertEquals(0L, PlaybackResume.resumePositionMs(120_000, 120_000))
    }

    /** 长片子只看 15 秒的余量太宽松：97% 那条线得单独生效。 */
    @Test
    fun aLongClipUsesTheRatioToo() {
        val twoHours = 7_200_000L
        assertEquals("比例先到就归零", 0L, PlaybackResume.resumePositionMs(6_990_000, twoHours))
        assertEquals("余量这条线在这类片子上是次要的", 0L, PlaybackResume.resumePositionMs(7_140_000, twoHours))
        assertEquals(6_900_000L, PlaybackResume.resumePositionMs(6_900_000, twoHours))
    }

    @Test
    fun anUnknownDurationNeverDeclaresTheClipFinished() {
        assertEquals("时长还不知道就只能削掉起播抖动", 30_000L, PlaybackResume.resumePositionMs(30_000, 0))
        assertEquals(30_000L, PlaybackResume.resumePositionMs(30_000, -1))
        assertEquals(0L, PlaybackResume.resumePositionMs(500, 0))
    }

    @Test
    fun theStoredDurationSurvivesAResetResumePoint() {
        assertEquals(45_000L to 120_000L, PlaybackResume.stored(45_000, 120_000))
        assertEquals("归零的是续播点，不是总时长：列表还得显示这条片子多长", 0L to 120_000L, PlaybackResume.stored(119_000, 120_000))
        assertEquals("播放器没给时长就不写一个假的进去", 30_000L to 0L, PlaybackResume.stored(30_000, 0))
    }
}
