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

    /** 这四个原因都意味着「转出来的文件会没声音」，UI 要换成更准确的降级文案。 */
    val AUDIO_FALLBACK_REASONS: Set<String> = setOf(
        NO_AUDIO_TRACK,
        AUDIO_EMPTY,
        AAC_CSD_MISSING,
        OUTPUT_AUDIO_MISSING,
    )

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
}
