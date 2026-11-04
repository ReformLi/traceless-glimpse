package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.RemuxAudioGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音轨门禁的纯逻辑测试：每一条出口都对应一种「转出来会没声音」的现场。
 *
 * 这些判定跑在 JVM 上，和 `MediaExtractor` 无关，所以新增出口必须在这里先立住，
 * 再由 [RemuxWiringTest] 钉住调用点。
 */
class RemuxAudioGuardTest {

    @Test
    fun noAudioTrackIsRejectedBeforeMuxing() {
        assertEquals(RemuxAudioGuard.NO_AUDIO_TRACK, RemuxAudioGuard.trackGate(null))
        assertNull(RemuxAudioGuard.trackGate(0))
        assertNull(RemuxAudioGuard.trackGate(2))
    }

    @Test
    fun videoOnlySuccessIsNotAllowedToLookLikeDone() {
        // 视频样本 > 0、音频样本 0 —— 旧的 Done 条件会放过这一种，正是无声 MP4 的来源。
        assertEquals(RemuxAudioGuard.AUDIO_EMPTY, RemuxAudioGuard.sampleGate(0))
        assertEquals(RemuxAudioGuard.AUDIO_EMPTY, RemuxAudioGuard.sampleGate(-1))
        assertNull(RemuxAudioGuard.sampleGate(1))
        assertNull(RemuxAudioGuard.sampleGate(716))
    }

    @Test
    fun aacNeedsAUsableCsdEitherFromContainerOrAdtsHeader() {
        assertNull(
            "容器自带可用的 csd-0 就放行",
            RemuxAudioGuard.csdGate(RemuxAudioGuard.AAC_MIME, hasUsableCsd = true, synthesized = null),
        )
        assertNull(
            "ADTS 头合成成功也放行",
            RemuxAudioGuard.csdGate(
                RemuxAudioGuard.AAC_MIME,
                hasUsableCsd = false,
                synthesized = byteArrayOf(0x12, 0x10),
            ),
        )
        assertEquals(
            RemuxAudioGuard.AAC_CSD_MISSING,
            RemuxAudioGuard.csdGate(RemuxAudioGuard.AAC_MIME, hasUsableCsd = false, synthesized = null),
        )
        assertEquals(
            "长度为 0 的 csd-0 等同于缺失",
            RemuxAudioGuard.AAC_CSD_MISSING,
            RemuxAudioGuard.csdGate(RemuxAudioGuard.AAC_MIME, hasUsableCsd = false, synthesized = ByteArray(0)),
        )
        assertNull(
            "非 AAC 轨道不要求 csd-0",
            RemuxAudioGuard.csdGate("audio/ac3", hasUsableCsd = false, synthesized = null),
        )
    }

    @Test
    fun outputMustHaveAReadableAudioTrack() {
        assertEquals(
            RemuxAudioGuard.OUTPUT_AUDIO_MISSING,
            RemuxAudioGuard.outputGate(audioTrackIndex = -1, firstSampleBytes = 0),
        )
        assertEquals(
            "轨在但读不出样本，同样算哑巴",
            RemuxAudioGuard.OUTPUT_AUDIO_MISSING,
            RemuxAudioGuard.outputGate(audioTrackIndex = 1, firstSampleBytes = 0),
        )
        assertNull(RemuxAudioGuard.outputGate(audioTrackIndex = 1, firstSampleBytes = 512))
    }

    @Test
    fun avToleranceGrowsWithSegmentDurationButNeverBelowFiveSeconds() {
        assertEquals(
            "没有分片信息时按 5 秒兜底",
            RemuxAudioGuard.MIN_AV_TOLERANCE_US,
            RemuxAudioGuard.avToleranceUs(segmentDurationMs = 0L),
        )
        assertEquals(
            "4 秒分片按 1.5 倍算，6 秒",
            6_000_000L,
            RemuxAudioGuard.avToleranceUs(segmentDurationMs = 4_000L),
        )
        assertEquals(
            "2 秒分片低于 5 秒下限",
            RemuxAudioGuard.MIN_AV_TOLERANCE_US,
            RemuxAudioGuard.avToleranceUs(segmentDurationMs = 2_000L),
        )
        assertEquals(
            "8 秒分片放宽到 12 秒",
            12_000_000L,
            RemuxAudioGuard.avToleranceUs(segmentDurationMs = 8_000L),
        )
        assertEquals(
            "缺失/异常的分片时长不能收紧容差",
            RemuxAudioGuard.MIN_AV_TOLERANCE_US,
            RemuxAudioGuard.avToleranceUs(segmentDurationMs = -1L),
        )
    }

    @Test
    fun durationMismatchRejectsSilentlyTruncatedAudio() {
        val tolerance = RemuxAudioGuard.avToleranceUs(segmentDurationMs = 4_000L)
        assertNull(
            "正常片尾部差几百毫秒要放行",
            RemuxAudioGuard.avDurationGate(
                videoLastPtsUs = 78_919_700L,
                audioLastPtsUs = 78_981_224L,
                toleranceUs = tolerance,
            ),
        )
        assertNull(
            "正好等于容差仍算安全",
            RemuxAudioGuard.avDurationGate(875_000_000L, 870_000_000L, tolerance),
        )
        assertEquals(
            "875 秒视频只剩 63 秒音频——正是静默丢样本的现场",
            RemuxAudioGuard.OUTPUT_AV_DURATION_MISMATCH,
            RemuxAudioGuard.avDurationGate(875_000_000L, 63_000_000L, tolerance),
        )
        assertEquals(
            "音频比视频长同样算不齐",
            RemuxAudioGuard.OUTPUT_AV_DURATION_MISMATCH,
            RemuxAudioGuard.avDurationGate(100_000_000L, 107_000_000L, tolerance),
        )
    }

    @Test
    fun audioGapCatchesMiddleDropsThatTailPtsWouldMiss() {
        val tolerance = RemuxAudioGuard.avToleranceUs(segmentDurationMs = 4_000L)
        assertNull(
            "正常片相邻样本只差一两百毫秒",
            RemuxAudioGuard.audioGapGate(maxGapUs = 371_534L, toleranceUs = tolerance),
        )
        assertNull(
            "正好等于容差仍算安全",
            RemuxAudioGuard.audioGapGate(maxGapUs = tolerance, toleranceUs = tolerance),
        )
        assertEquals(
            "中段丢一大截、尾巴还在时靠间隔发现",
            RemuxAudioGuard.OUTPUT_AV_DURATION_MISMATCH,
            RemuxAudioGuard.audioGapGate(maxGapUs = 162_000_000L, toleranceUs = tolerance),
        )
    }

    @Test
    fun allAudioReasonsRouteToTheAudioFallbackCopy() {
        assertEquals(
            setOf(
                RemuxAudioGuard.NO_AUDIO_TRACK,
                RemuxAudioGuard.AUDIO_EMPTY,
                RemuxAudioGuard.AAC_CSD_MISSING,
                RemuxAudioGuard.OUTPUT_AUDIO_MISSING,
                RemuxAudioGuard.OUTPUT_AV_DURATION_MISMATCH,
            ),
            RemuxAudioGuard.AUDIO_FALLBACK_REASONS,
        )
    }
}
