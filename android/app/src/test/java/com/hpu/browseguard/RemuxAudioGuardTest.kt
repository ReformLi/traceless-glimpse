package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.PtsGate
import com.hpu.browseguard.data.backend.remux.RemuxAudioGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun videoOnlySuccessIsNotAllowedToLookLikeDone() {        // 视频样本 > 0、音频样本 0 —— 旧的 Done 条件会放过这一种，正是无声 MP4 的来源。
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
    fun adjacentAudioSamplesMayNeverBeFiveSecondsApart() {
        assertNull(
            "正常片相邻样本只差一两百毫秒",
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 371_534L),
        )
        assertNull(
            "正好 5 秒不算「超过」",
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 5_000_000L),
        )
        assertEquals(
            "越过 5 秒这条线就拒",
            RemuxAudioGuard.AUDIO_SAMPLE_GAP_TOO_LARGE,
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 5_000_001L),
        )
        assertEquals(
            "模拟底层注水：时间戳一次跳 10 秒",
            RemuxAudioGuard.AUDIO_SAMPLE_GAP_TOO_LARGE,
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 10_000_000L),
        )
        assertEquals(
            "中段丢一大截、尾巴还在时靠间隔发现",
            RemuxAudioGuard.AUDIO_SAMPLE_GAP_TOO_LARGE,
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 162_000_000L),
        )
    }

    @Test
    fun theSampleGapCapDoesNotStretchWithSegmentDuration() {
        // 长分片只该放宽首尾对齐，不该放宽「两段声音之间空了多久」。
        val tolerance = RemuxAudioGuard.avToleranceUs(segmentDurationMs = 8_000L)
        assertTrue(
            "8 秒分片的首尾容差是 12 秒",
            tolerance > RemuxAudioGuard.MAX_AUDIO_SAMPLE_GAP_US,
        )
        assertEquals(
            "6 秒的相邻间隔在旧口径下能过，在新口径下必须拒",
            RemuxAudioGuard.AUDIO_SAMPLE_GAP_TOO_LARGE,
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 6_000_000L),
        )
    }

    /**
     * 注水片是真机复测造出来的：40 秒视频只配 5 秒声音，时间戳均匀铺满。
     * 它的最大相邻间隔只有 0.371 秒、每格都有样本、密度和基准一致——三条老判据全是放行，
     * 只有帧数换算的内容时长看得见真相。这里把那两个实测数字原样钉进单测。
     */
    @Test
    fun uniformlyWateredDownAudioIsCaughtByTheFrameCount() {
        assertNull(
            "正常片：内容 78.947 秒 / 跨度 78.818 秒 = 100.2%",
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 78_818_000L, contentUs = 78_947_000L),
        )
        assertNull(
            "真实站点整集：内容 2816.16 秒 / 跨度 2779.06 秒 = 101.3%",
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 2_779_057_000L, contentUs = 2_816_160_000L),
        )
        assertNull(
            "正好 70% 算及格，不卡边界值",
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 100_000_000L, contentUs = 70_000_000L),
        )
        assertEquals(
            "注水片：内容 5.038 秒 / 跨度 39.729 秒 = 12.7%，必须拒",
            RemuxAudioGuard.AUDIO_CONTENT_TOO_SHORT,
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 39_729_000L, contentUs = 5_038_000L),
        )
        assertEquals(
            "69% 已经算注水",
            RemuxAudioGuard.AUDIO_CONTENT_TOO_SHORT,
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 100_000_000L, contentUs = 69_000_000L),
        )
        assertNull(
            "同一份注水片的相邻间隔过不了这一条：0.371 秒远小于 5 秒",
            RemuxAudioGuard.audioSampleGapGate(maxGapUs = 371_000L),
        )
    }

    @Test
    fun audioWhoseFramesCannotBeReadIsNotJudgedAsSilent() {
        assertNull(
            "认不出 ADTS 是未知，不能当「没有声音」——否则非 AAC 封装的正常片会被误杀",
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 60_000_000L, contentUs = 0L),
        )
        assertNull(
            "视频跨度都读不出来时无比例可言，交给前面的门禁",
            RemuxAudioGuard.contentRatioGate(videoSpanUs = 0L, contentUs = 5_000_000L),
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
                RemuxAudioGuard.AUDIO_SAMPLE_GAP_TOO_LARGE,
                RemuxAudioGuard.AUDIO_CONTENT_TOO_SHORT,
                RemuxAudioGuard.AUDIO_PTS_NEGATIVE,
                RemuxAudioGuard.AUDIO_TRACK_REJECTED,
                RemuxAudioGuard.AUDIO_AU_SPLIT_FAILED,
                RemuxAudioGuard.AUDIO_AU_COUNT_MISMATCH,
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

    /**
     * 拆分重封的前提三件事缺一不可：AAC、认得准采样率、首样本确实是 ADTS 串。
     *
     * 条件不满足就必须退回「原样搬运」——那正是过去产出并包 mp4a（Android 能放、严格播放器
     * 没声音）的那条路，但拆错的代价更大：拿不到采样率就拼不出正确的 1024 步进时间戳。
     */
    @Test
    fun onlyCleanAacWithAKnownRateGetsSplitIntoUnits() {
        assertTrue(
            "AAC + 48k + 首样本带 ADTS 头 = 拆",
            RemuxAudioGuard.splitsAudioUnits(RemuxAudioGuard.AAC_MIME, 48_000, firstSampleIsAdts = true),
        )
        assertFalse(
            "非 AAC 的轨道不拆：剥不出 ADTS，硬剥就是毁数据",
            RemuxAudioGuard.splitsAudioUnits("audio/mpeg", 44_100, firstSampleIsAdts = true),
        )
        assertFalse(
            "认不出采样率就不拆：时间戳步进无从算起",
            RemuxAudioGuard.splitsAudioUnits(RemuxAudioGuard.AAC_MIME, 0, firstSampleIsAdts = true),
        )
        assertFalse(
            "首样本不是 ADTS 就不拆：整条流按裸 AAC 原样搬运",
            RemuxAudioGuard.splitsAudioUnits(RemuxAudioGuard.AAC_MIME, 48_000, firstSampleIsAdts = false),
        )
    }

    /**
     * 解码配置的另一张坏脸：容器给的 `csd-0` 本身就是一整串 ADTS 头。
     *
     * 样本已经剥头按 AU 重封了，配置还留着 ADTS 就是混合封装——严格播放器照这段配置建解码器
     * 会失败，或者只解出半个 AU。所以这种 `csd-0` 视同「没有可用配置」，必须由 ADTS 头重新合成。
     */
    @Test
    fun anAdtsHeaderHandedBackAsCsdIsNotAUsableDecoderConfig() {
        assertFalse(
            "非 AAC 轨道不掺和：它本来就不要求 csd-0",
            RemuxAudioGuard.replacesDecoderConfig("audio/mpeg", hasUsableCsd = true, csdIsAdtsHeader = true),
        )
        assertTrue(
            "缺配置要合成",
            RemuxAudioGuard.replacesDecoderConfig(RemuxAudioGuard.AAC_MIME, hasUsableCsd = false, csdIsAdtsHeader = false),
        )
        assertTrue(
            "ADTS 头冒充的配置要换掉",
            RemuxAudioGuard.replacesDecoderConfig(RemuxAudioGuard.AAC_MIME, hasUsableCsd = true, csdIsAdtsHeader = true),
        )
        assertFalse(
            "裸 AudioSpecificConfig（实测的 1190）原样保留",
            RemuxAudioGuard.replacesDecoderConfig(RemuxAudioGuard.AAC_MIME, hasUsableCsd = true, csdIsAdtsHeader = false),
        )
    }

    /**
     * AU 数一致性：源帧数、写入数、产物复开的样本数必须一模一样。
     *
     * 这是唯一能直接看见「有声音被吃掉」的判据——时间戳、密度、首尾对齐在样本被均匀重铺时
     * 全都照常好看，只有绝对数量骗不了人。
     */
    @Test
    fun auCountsMustMatchExactlyAfterSplitting() {
        assertNull(RemuxAudioGuard.auCountGate(sourceFrames = 40_411, writtenAus = 40_411, outputSamples = 40_411))
        assertEquals(
            "muxer 少写了 29 帧，产物必须当哑巴拦下来",
            RemuxAudioGuard.AUDIO_AU_COUNT_MISMATCH,
            RemuxAudioGuard.auCountGate(sourceFrames = 40_411, writtenAus = 40_382, outputSamples = 40_382),
        )
        assertEquals(
            "写进去了但复开读到的样本更少：容器层丢样本",
            RemuxAudioGuard.AUDIO_AU_COUNT_MISMATCH,
            RemuxAudioGuard.auCountGate(sourceFrames = 2_611, writtenAus = 2_611, outputSamples = 488),
        )
        assertEquals(
            "拆出来的 AU 比源里数到的还多，只能是走帧两处不一致",
            RemuxAudioGuard.AUDIO_AU_COUNT_MISMATCH,
            RemuxAudioGuard.auCountGate(sourceFrames = 100, writtenAus = 101, outputSamples = 101),
        )
        assertNull(
            "非 ADTS 的一路（源帧数 0）不适用这条：没有 AU 可对",
            RemuxAudioGuard.auCountGate(sourceFrames = 0, writtenAus = 0, outputSamples = 488),
        )
    }

    @Test
    fun theNewAudioExitsSpeakTheAudioFallbackCopy() {
        assertTrue(
            "拆分失败与 AU 数不符都属于「保留的 .ts 仍带声音」这一类",
            RemuxAudioGuard.AUDIO_FALLBACK_REASONS.containsAll(
                setOf(RemuxAudioGuard.AUDIO_AU_SPLIT_FAILED, RemuxAudioGuard.AUDIO_AU_COUNT_MISMATCH),
            ),
        )
    }

    private fun denseWindows(count: Int, samples: Int = FULL): Map<Int, Int> =
        (0 until count).associateWith { samples }

    private companion object {
        const val US = 1_000_000L

        /** 一格满密度样本数：478 秒的片子按 30 个/格算，跟真机实测的打包率同量级。 */
        const val FULL = 30
    }
}
