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
    fun allAudioReasonsRouteToTheAudioFallbackCopy() {
        assertEquals(
            setOf(
                RemuxAudioGuard.NO_AUDIO_TRACK,
                RemuxAudioGuard.AUDIO_EMPTY,
                RemuxAudioGuard.AAC_CSD_MISSING,
                RemuxAudioGuard.OUTPUT_AUDIO_MISSING,
            ),
            RemuxAudioGuard.AUDIO_FALLBACK_REASONS,
        )
    }
}
