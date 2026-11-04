package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.PtsGate
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
                RemuxAudioGuard.AUDIO_GAP_TOO_LARGE,
                RemuxAudioGuard.AUDIO_COVERAGE_TOO_LOW,
                RemuxAudioGuard.AUDIO_DENSITY_TOO_LOW,
                RemuxAudioGuard.AUDIO_PTS_NEGATIVE,
                RemuxAudioGuard.AUDIO_TRACK_REJECTED,
                PtsGate.AUDIO_PTS_BACKWARD,
                PtsGate.AUDIO_PTS_GAP,
            ),
            RemuxAudioGuard.AUDIO_FALLBACK_REASONS,
        )
    }

    @Test
    fun theVideoTimelineIsCutIntoTenSecondWindows() {
        assertEquals(0, RemuxAudioGuard.sliceWindowCount(0L))
        assertEquals(0, RemuxAudioGuard.sliceWindowCount(-5 * US))
        assertEquals(1, RemuxAudioGuard.sliceWindowCount(10 * US - 1))
        assertEquals(1, RemuxAudioGuard.sliceWindowCount(10 * US))
        assertEquals(2, RemuxAudioGuard.sliceWindowCount(10 * US + 1))
        assertEquals(48, RemuxAudioGuard.sliceWindowCount(478 * US))

        assertEquals(0, RemuxAudioGuard.sliceWindowIndex(0L, 0L))
        assertEquals(0, RemuxAudioGuard.sliceWindowIndex(0L, 9 * US + 900_000L))
        assertEquals(1, RemuxAudioGuard.sliceWindowIndex(0L, 10 * US))
        assertEquals(47, RemuxAudioGuard.sliceWindowIndex(0L, 477 * US + 900_000L))
        assertEquals("早于起点的样本并入第 0 格", 0, RemuxAudioGuard.sliceWindowIndex(0L, -3 * US))
        assertEquals("起点有偏移时按相对位置归格", 0, RemuxAudioGuard.sliceWindowIndex(25 * US, 30 * US))
        assertEquals(1, RemuxAudioGuard.sliceWindowIndex(25 * US, 40 * US))
    }

    @Test
    fun consecutiveEmptyWindowsMeasureTheSilentStretch() {
        assertEquals(
            "478 秒只有前 170 秒有声：后面 31 格全空",
            31,
            RemuxAudioGuard.maxConsecutiveEmptyWindows(48, (0 until 17).toSet()),
        )
        assertEquals(0, RemuxAudioGuard.maxConsecutiveEmptyWindows(10, (0 until 10).toSet()))
        assertEquals(
            "中间断两格，尾巴接上",
            2,
            RemuxAudioGuard.maxConsecutiveEmptyWindows(10, setOf(0, 1, 2, 3, 6, 7, 8, 9)),
        )
        assertEquals(0, RemuxAudioGuard.maxConsecutiveEmptyWindows(0, emptySet()))
    }

    @Test
    fun threeSilentWindowsAreRefused() {
        assertEquals(
            RemuxAudioGuard.AUDIO_GAP_TOO_LARGE,
            RemuxAudioGuard.timeSliceGate(0L, 478 * US, (0 until 17).associateWith { FULL }),
        )
        assertEquals(
            "结尾连续三格没有音频，同样算断层",
            RemuxAudioGuard.AUDIO_GAP_TOO_LARGE,
            RemuxAudioGuard.timeSliceGate(0L, 100 * US, (0 until 7).associateWith { FULL }),
        )
        assertNull(
            "只断两格、缺失占比 20% —— 还在容忍范围内",
            RemuxAudioGuard.timeSliceGate(0L, 100 * US, (0 until 8).associateWith { FULL }),
        )
    }

    @Test
    fun coverageBelowSeventyPercentOfWindowsIsRefused() {
        val everyOtherWindow = (0 until 48 step 2).associateWith { FULL }
        assertEquals(
            "每 20 秒只响 10 秒：首尾都对得上，最长断档也只有 1 格，只能靠占比发现",
            RemuxAudioGuard.AUDIO_COVERAGE_TOO_LOW,
            RemuxAudioGuard.timeSliceGate(0L, 478 * US, everyOtherWindow),
        )
        assertEquals(
            RemuxAudioGuard.AUDIO_COVERAGE_TOO_LOW,
            RemuxAudioGuard.timeSliceGate(0L, 200 * US, denseWindows(20) - setOf(1, 4, 7, 10, 13, 16, 19)),
        )
        assertNull(
            "正好 30% 空格按约定放行",
            RemuxAudioGuard.timeSliceGate(0L, 200 * US, denseWindows(20) - setOf(1, 4, 7, 10, 13, 16)),
        )
    }

    @Test
    fun uniformRetimedSparsityFailsTheDensityBaseline() {
        // 底层把剩下的样本均匀重铺到整条时间轴：48 格全都有样本，空格判据全瞎，
        // 但开头三格是 40 个、后面只剩 1 个——密度基准把这种伪装抓出来。
        val retimed = (0 until 48).associateWith { if (it < 3) 40 else 1 }
        assertEquals(
            RemuxAudioGuard.AUDIO_DENSITY_TOO_LOW,
            RemuxAudioGuard.timeSliceGate(0L, 478 * US, retimed),
        )
    }

    @Test
    fun densityAllowsAnEightyPercentDropButNotMore() {
        // 基准 30/格 → 阈值 6 个：等于 6 放行，5 个就拒。
        val atThreshold = denseWindows(3, 30) + (3 until 11).associateWith { 6 }
        assertNull(
            RemuxAudioGuard.timeSliceGate(0L, 120 * US, atThreshold),
        )
        val belowThreshold = denseWindows(3, 30) + (3 until 11).associateWith { 5 }
        assertEquals(
            RemuxAudioGuard.AUDIO_DENSITY_TOO_LOW,
            RemuxAudioGuard.timeSliceGate(0L, 120 * US, belowThreshold),
        )
    }

    @Test
    fun theFinalPartialWindowIsNotDensityChecked() {
        // 600 秒的真实产物尾部就是这形状：音频比视频先收尾，最后一格只剩零点几秒，样本数必然偏低。
        val tailThin = denseWindows(60, 30) + mapOf(60 to 2)
        assertNull(
            "最后一格薄不参与判定，否则正常片会被成片误杀",
            RemuxAudioGuard.timeSliceGate(0L, 600 * US + 113_000L, tailThin),
        )
        val secondLastThin = denseWindows(59, 30) + mapOf(59 to 2, 60 to 30)
        assertEquals(
            "倒数第二格薄就要拒",
            RemuxAudioGuard.AUDIO_DENSITY_TOO_LOW,
            RemuxAudioGuard.timeSliceGate(0L, 600 * US + 113_000L, secondLastThin),
        )
    }

    @Test
    fun emptyWindowsBelongToThePresenceRulesNotDensity() {
        // 48 格里只有一格孤立全空：断层要 3 连格、覆盖率要 30%，都不到；密度也不该管空格。
        val oneHole = (0 until 48).filter { it != 20 }.associateWith { FULL }
        assertNull(RemuxAudioGuard.timeSliceGate(0L, 478 * US, oneHole))
    }

    @Test
    fun densityGateNeedsThreeFilledWindowsBeforeItDaresToJudge() {
        assertNull(RemuxAudioGuard.densityGate(10, mapOf(0 to 30, 1 to 1)))
        assertNull(RemuxAudioGuard.densityGate(10, denseWindows(3)))
    }

    @Test
    fun sliceAuditNormalisesAVideoStartOffsetAndIgnoresWindowsOutsideTheVideo() {
        assertEquals(
            "视频从 25 秒起，音频只到 50 秒：平移后是结尾连续三格全空",
            RemuxAudioGuard.AUDIO_GAP_TOO_LARGE,
            RemuxAudioGuard.timeSliceGate(25 * US, 85 * US, mapOf(2 to FULL, 3 to FULL, 4 to FULL)),
        )
        assertEquals(
            "平移后开头三格做基准，相对第 3 格只有 1 个样本 → 密度不过",
            RemuxAudioGuard.AUDIO_DENSITY_TOO_LOW,
            RemuxAudioGuard.timeSliceGate(25 * US, 145 * US, (2..13).associateWith { if (it == 5) 1 else 40 }),
        )
        assertNull(
            "落在视频范围外的音频格子不参与统计",
            RemuxAudioGuard.timeSliceGate(0L, 100 * US, denseWindows(10) + mapOf(50 to FULL)),
        )
    }

    @Test
    fun sliceAuditSkipsClipsShorterThanThreeWindows() {
        assertNull(
            "20 秒的短片交给前面的首尾对齐与最大间隔去判",
            RemuxAudioGuard.timeSliceGate(0L, 20 * US, emptyMap()),
        )
        assertNull(RemuxAudioGuard.timeSliceGate(0L, 0L, denseWindows(10)))
    }

    private fun denseWindows(count: Int, samples: Int = FULL): Map<Int, Int> =
        (0 until count).associateWith { samples }

    private companion object {
        const val US = 1_000_000L

        /** 一格满密度样本数：478 秒的片子按 30 个/格算，跟真机实测的打包率同量级。 */
        const val FULL = 30
    }
}
