package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.AdtsAudioConfig
import com.hpu.browseguard.data.backend.remux.PtsGate
import com.hpu.browseguard.data.backend.remux.PtsVerdict
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转封装里能在 JVM 上钉住的两块纯逻辑：ADTS → AudioSpecificConfig，以及 PTS 闸门。
 *
 * 真机的 MediaExtractor / MediaMuxer 跑不了单测，但「会不会误判成可以写」必须在这里定死：
 * 闸门放行一个跳变的时间戳，产物就是后半段播不了的 MP4。
 */
class TsRemuxLogicTest {

    @Test
    fun aacLc44kStereoBecomesTheTwoByteAudioSpecificConfig() {
        // profile=LC(1), freqIndex=4(44100), channelConfig=2
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte(), 0, 0, 0)

        val csd = AdtsAudioConfig.fromAdtsHeader(header, header.size)

        // objectType=2 << 11 | 4 << 7 | 2 << 3 = 0x1210
        assertArrayEquals(byteArrayOf(0x12, 0x10), csd)
    }

    @Test
    fun aNonAdtsSampleIsRejected() {
        val notAdts = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0, 0, 0)
        assertNull(AdtsAudioConfig.fromAdtsHeader(notAdts, notAdts.size))
    }

    @Test
    fun anExplicitSampleRateCannotFitInTwoBytes() {
        // freqIndex=15：采样率写在后续字节里，2 字节的 AudioSpecificConfig 表达不了
        val header = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x7C.toByte(), 0x80.toByte(), 0, 0, 0)
        assertNull(AdtsAudioConfig.fromAdtsHeader(header, header.size))
    }

    @Test
    fun bFrameReorderingIsNotMistakenForADiscontinuity() {
        val gate = PtsGate()
        // 解码顺序里的 PTS：0, 120ms, 40ms, 80ms —— B 帧正常倒退
        assertTrue(gate.checkVideo(0L) is PtsVerdict.Ok)
        assertTrue(gate.checkVideo(120_000L) is PtsVerdict.Ok)
        assertTrue(gate.checkVideo(40_000L) is PtsVerdict.Ok)
        assertTrue(gate.checkVideo(80_000L) is PtsVerdict.Ok)
    }

    @Test
    fun aBackwardTimestampJumpRejectsTheWholeRemux() {
        val gate = PtsGate()
        gate.checkVideo(0L)
        gate.checkVideo(4_000_000L)

        val verdict = gate.checkVideo(1_000_000L)

        assertEquals("video_pts_backward", (verdict as PtsVerdict.Reject).reason)
    }

    @Test
    fun aForwardTimestampGapRejectsTheWholeRemux() {
        val gate = PtsGate()
        gate.checkVideo(0L)

        val verdict = gate.checkVideo(6_000_000L)

        assertEquals("video_pts_gap", (verdict as PtsVerdict.Reject).reason)
    }

    @Test
    fun audioMustStayMonotonicWithinTighterTolerance() {
        val gate = PtsGate()
        gate.checkAudio(0L)
        gate.checkAudio(200_000L)

        val verdict = gate.checkAudio(50_000L)

        assertEquals("audio_pts_backward", (verdict as PtsVerdict.Reject).reason)
    }

    @Test
    fun tracksAreJudgedIndependently() {
        val gate = PtsGate()
        gate.checkVideo(0L)
        gate.checkVideo(3_000_000L)
        // 视频的 max 不能被音频的首样本拉低，反之亦然
        assertTrue(gate.checkAudio(1_000_000L) is PtsVerdict.Ok)
        assertTrue(gate.checkVideo(2_500_000L) is PtsVerdict.Ok)
    }
}
