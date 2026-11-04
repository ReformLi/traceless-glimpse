package com.hpu.browseguard.data.backend.remux

/**
 * 转封装的音轨门禁：任何一条不满足，就保留 `.ts`，绝不产出只有画面的 MP4。
 *
 * 这里只放纯判定，不碰 `MediaExtractor`/`MediaMuxer`，所以 JVM 单测能覆盖每一条出口；
 * 真正的读写仍由 [TsRemuxer] 负责，调用点必须逐条过这里，不允许「先 mux 再说」。
 */
internal object RemuxAudioGuard {

    const val AAC_MIME = "audio/mp4a-latm"

    /** 源里找不到音频轨：多半是 extractor 不认识这种封装，转出来的 MP4 一定是哑巴。 */
    const val NO_AUDIO_TRACK = "no_audio_track"

    /** 有音轨却一个样本都没写进去：空轨等于没声音。 */
    const val AUDIO_EMPTY = "audio_empty"

    /** AAC 轨没有可用的 `csd-0`，ADTS 头也合成不出来：播放器拿不到解码配置。 */
    const val AAC_CSD_MISSING = "aac_csd_missing"

    /** 产物复开读不到音轨/样本：`addTrack` 成功不代表文件能出声。 */
    const val OUTPUT_AUDIO_MISSING = "output_audio_missing"

    /**
     * 产物音视频时长对不上：`MediaMuxer` 遇到 PTS 大跳变会**静默丢样本**，
     * `writeSampleData` 不报错、复开首样本也读得出来，但音轨可能只剩前几十秒。
     */
    const val OUTPUT_AV_DURATION_MISMATCH = "output_av_duration_mismatch"

    /**
     * 时间窗口切片审计的判据：`MediaMuxer` 能把 moov 头部时长写成完整值，
     * 也能把剩下的音频样本重新铺开，但它变不出样本——按 10 秒一格统计音频的**分布**，
     * 空洞就会暴露。首尾 PTS 对齐和最大间隔都能被「头部伪装 + 样本重铺」绕过，这一条不会。
     */
    const val AUDIO_GAP_TOO_LARGE = "audio_gap_too_large"

    /** 空窗口占比超过 30%：整片大面积没声音。 */
    const val AUDIO_COVERAGE_TOO_LOW = "audio_coverage_too_low"

    /**
     * 音频样本密度低于基准：底层把剩下的样本均匀重铺到整条时间轴时，
     * 每格「都有」样本，空格判据就瞎了——但每格的样本数会明显低于开头的基准。
     */
    const val AUDIO_DENSITY_TOO_LOW = "audio_density_too_low"

    /**
     * 音频样本的时间戳早于流的起点：整段音频被判定为「负时间」，只能整体放弃转封装。
     * 和 [PtsGate] 那两条一样，它拒的是音频时间轴，保留下来的 `.ts` 依然带声音。
     */
    const val AUDIO_PTS_NEGATIVE = "audio_pts_negative"

    /** `MediaMuxer.addTrack` 直接拒绝音频轨：容器不认识这种解码配置，产物必然是哑巴。 */
    const val AUDIO_TRACK_REJECTED = "audio_track_rejected"

    /**
     * 这些原因都意味着「转出来的文件会没声音/不完整」，UI 统一换成更准确的降级文案。
     *
     * 含 [PtsGate] 的两条音轨时间戳判据：它们拒的也是「音频时间轴接不上」的片子，
     * 保留下来的 `.ts` 依然带音频，不该走「系统播放器可能不认这个格式」那种通用说法。
     *
     * 登记口径：凡是带 `audio_` 前缀、且失败后 `.ts` 仍带音频的理由都在这里——
     * `TsRemuxer` 里以字面量出现的 `video_*` 不在，因为它们拒的是画面，文案不该说「含音频」。
     */
    val AUDIO_FALLBACK_REASONS: Set<String> = setOf(
        NO_AUDIO_TRACK,
        AUDIO_EMPTY,
        AAC_CSD_MISSING,
        OUTPUT_AUDIO_MISSING,
        OUTPUT_AV_DURATION_MISMATCH,
        AUDIO_GAP_TOO_LARGE,
        AUDIO_COVERAGE_TOO_LOW,
        AUDIO_DENSITY_TOO_LOW,
        AUDIO_PTS_NEGATIVE,
        AUDIO_TRACK_REJECTED,
        PtsGate.AUDIO_PTS_BACKWARD,
        PtsGate.AUDIO_PTS_GAP,
    )

    /** 音视频末尾至少允许差 5 秒：分片边界、编码器尾部补齐在这个量级。 */
    const val MIN_AV_TOLERANCE_US = 5_000_000L

    /** 切片宽度：10 秒。478 秒的片子就是 48 格。 */
    const val SLICE_WINDOW_US = 10_000_000L

    /** 连续 3 格没有任何音频样本，就是 30 秒的哑巴段。 */
    const val MAX_CONSECUTIVE_EMPTY_WINDOWS = 3

    /** 空格占比的上限：严格超过 30% 才算覆盖率过低。 */
    const val MAX_EMPTY_WINDOW_PERCENT = 30

    /** 不足 3 格（约 30 秒）不做切片判定：比例统计在这种长度上没有意义。 */
    const val MIN_SLICE_WINDOWS = 3

    /** 密度基准取开头几个有声窗口：片子开头的音频总是最完整的。 */
    const val DENSITY_BASELINE_WINDOWS = 3

    /** 后续窗口低于基准密度的 20%（即允许 80% 波动）就判被稀释。 */
    const val DENSITY_MIN_RATIO_PERCENT = 20

    /** [audioIndex] 为 null 表示 extractor 没找到音频轨。 */
    fun trackGate(audioIndex: Int?): String? =
        if (audioIndex == null) NO_AUDIO_TRACK else null

    /** 源里明明有音轨，写进 muxer 的音频样本却是 0。 */
    fun sampleGate(audioSamples: Int): String? =
        if (audioSamples <= 0) AUDIO_EMPTY else null

    /**
     * AAC 必须有解码配置：容器自带的 `csd-0` 可用就放行，否则必须由 ADTS 头合成；
     * 两者都没有就拒绝。非 AAC 轨道不要求 `csd-0`。
     */
    fun csdGate(mime: String?, hasUsableCsd: Boolean, synthesized: ByteArray?): String? {
        if (mime != AAC_MIME) return null
        if (hasUsableCsd) return null
        return if (synthesized != null && synthesized.isNotEmpty()) null else AAC_CSD_MISSING
    }

    /** 产物复开的结果：必须存在音频轨，且第一个样本真的读得出来。 */
    fun outputGate(audioTrackIndex: Int, firstSampleBytes: Int): String? =
        if (audioTrackIndex < 0 || firstSampleBytes <= 0) OUTPUT_AUDIO_MISSING else null

    /**
     * 动态容差：目标分片时长越长，正常的尾部差异越大。
     *
     * 4 秒分片仍按 5 秒算；8 秒分片放宽到 12 秒——否则正常片也会被误杀成降级。
     */
    fun avToleranceUs(segmentDurationMs: Long): Long =
        maxOf(MIN_AV_TOLERANCE_US, segmentDurationMs.coerceAtLeast(0L) * 1_000L * 3 / 2)

    /** 音轨末尾与视频末尾差超过容差，说明中间有样本被静默丢弃，或有整段音频缺失。 */
    fun avDurationGate(videoLastPtsUs: Long, audioLastPtsUs: Long, toleranceUs: Long): String? =
        if (kotlin.math.abs(videoLastPtsUs - audioLastPtsUs) > toleranceUs) {
            OUTPUT_AV_DURATION_MISMATCH
        } else {
            null
        }

    /**
     * 音频覆盖率：只看首尾 PTS 会漏掉「中段整段丢失、尾巴还在」的情况。
     *
     * 这里不用「样本数 × 1024」，因为 `MediaExtractor` 返回的样本常把多个 ADTS 帧并成一包
     * （实测 79 秒音频只有 488 个样本），按 1024 估算会把正常片全部误杀。
     * 改为看相邻音频样本 PTS 的最大间隔：中间丢了东西，间隔必然暴露。
     */
    fun audioGapGate(maxGapUs: Long, toleranceUs: Long): String? =
        if (maxGapUs > toleranceUs) OUTPUT_AV_DURATION_MISMATCH else null

    /** 视频总时长切成的窗口数；时长非正数就没有窗口可言。 */
    fun sliceWindowCount(durationUs: Long): Int {
        if (durationUs <= 0L) return 0
        val windows = (durationUs + SLICE_WINDOW_US - 1) / SLICE_WINDOW_US
        return if (windows > Int.MAX_VALUE) Int.MAX_VALUE else windows.toInt()
    }

    /** 一个 PTS 落在哪一格；早于起点的样本算第 0 格，不放过也没关系。 */
    fun sliceWindowIndex(startUs: Long, ptsUs: Long): Int {
        val offset = ptsUs - startUs
        if (offset <= 0L) return 0
        val index = offset / SLICE_WINDOW_US
        return if (index > Int.MAX_VALUE) Int.MAX_VALUE else index.toInt()
    }

    /** 最长的一段连续空格：中间断了多久，看这个就知道。 */
    fun maxConsecutiveEmptyWindows(windowCount: Int, occupied: Set<Int>): Int {
        var run = 0
        var best = 0
        for (window in 0 until windowCount) {
            if (occupied.contains(window)) {
                if (run > best) best = run
                run = 0
            } else {
                run++
            }
        }
        if (run > best) best = run
        return best
    }

    /** 空格数量，供覆盖率判定用；超出视频范围的格子不参与统计。 */
    fun emptyWindowCount(windowCount: Int, occupied: Set<Int>): Int =
        (0 until windowCount).count { it !in occupied }

    /**
     * 时间窗口切片审计：把视频时长切成 10 秒一格，数每格里有多少个音频样本。
     *
     * - 连续 [MAX_CONSECUTIVE_EMPTY_WINDOWS] 格全空 → [AUDIO_GAP_TOO_LARGE]；
     * - 空格占比严格超过 [MAX_EMPTY_WINDOW_PERCENT]% → [AUDIO_COVERAGE_TOO_LOW]；
     * - 基准之后的格子低于基准密度的 [DENSITY_MIN_RATIO_PERCENT]% → [AUDIO_DENSITY_TOO_LOW]。
     *
     * 视频起点未必是 0（`MediaMuxer` 有时会留个偏移），所以先把绝对窗口号平移回相对号，
     * 再只保留落在视频范围内的那些；不足 [MIN_SLICE_WINDOWS] 格直接放行，交给前面的门禁。
     */
    fun timeSliceGate(videoStartUs: Long, videoEndUs: Long, audioWindowCounts: Map<Int, Int>): String? {
        val windowCount = sliceWindowCount(videoEndUs - videoStartUs)
        if (windowCount < MIN_SLICE_WINDOWS) return null
        val baseWindow = if (videoStartUs <= 0L) 0 else (videoStartUs / SLICE_WINDOW_US).toInt()
        val counts = normalizeWindowCounts(audioWindowCounts, baseWindow, windowCount)
        val occupied = counts.keys
        if (maxConsecutiveEmptyWindows(windowCount, occupied) >= MAX_CONSECUTIVE_EMPTY_WINDOWS) {
            return AUDIO_GAP_TOO_LARGE
        }
        if (emptyWindowCount(windowCount, occupied) * 100 > windowCount * MAX_EMPTY_WINDOW_PERCENT) {
            return AUDIO_COVERAGE_TOO_LOW
        }
        return densityGate(windowCount, counts)
    }

    /** 把绝对窗口号平移成相对号，丢掉范围外的和计数为 0 的格子（同格多次会累加）。 */
    fun normalizeWindowCounts(
        audioWindowCounts: Map<Int, Int>,
        baseWindow: Int,
        windowCount: Int,
    ): Map<Int, Int> {
        val shifted = HashMap<Int, Int>(audioWindowCounts.size)
        audioWindowCounts.forEach { (window, samples) ->
            val relative = window - baseWindow
            if (relative in 0 until windowCount && samples > 0) {
                shifted[relative] = (shifted[relative] ?: 0) + samples
            }
        }
        return shifted
    }

    /**
     * 密度审计：专门堵「每格都有样本、但每格都很薄」的重铺伪装。
     *
     * 基准取开头 [DENSITY_BASELINE_WINDOWS] 个有声窗口——片子开头的音频总是最完整的；
     * 空格由断层与覆盖率两条分支负责，这里只看有样本却明显偏薄的格子。
     * 最后一格通常不满 10 秒（音频往往先于视频收尾），不参与判定，否则正常片会被误杀。
     */
    fun densityGate(windowCount: Int, counts: Map<Int, Int>): String? {
        val baseline = (0 until windowCount)
            .filter { (counts[it] ?: 0) > 0 }
            .take(DENSITY_BASELINE_WINDOWS)
        if (baseline.size < DENSITY_BASELINE_WINDOWS) return null
        val baseTotal = baseline.sumOf { counts[it] ?: 0 }
        for (window in baseline.last() + 1 until windowCount - 1) {
            val samples = counts[window] ?: 0
            if (samples == 0) continue
            if (samples * DENSITY_BASELINE_WINDOWS * 100 < baseTotal * DENSITY_MIN_RATIO_PERCENT) {
                return AUDIO_DENSITY_TOO_LOW
            }
        }
        return null
    }
}
