package com.hpu.browseguard.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 画质选择策略。PROTOCOL §3.4。 */
@Serializable
enum class SelectionStrategy {
    @SerialName("exact") EXACT,
    @SerialName("max_height") MAX_HEIGHT,
    @SerialName("best") BEST,
    @SerialName("audio_only") AUDIO_ONLY,
}

/** 编码偏好。PROTOCOL §3.4 注释取值：h264 | h265 | av1 | vp9。 */
@Serializable
enum class PreferCodec {
    @SerialName("h264") H264,
    @SerialName("h265") H265,
    @SerialName("av1") AV1,
    @SerialName("vp9") VP9,
}

/**
 * 画质选择器（结构化对象，非 shell 字符串）。PROTOCOL §3.4。
 *
 * - strategy=exact 时 formatId 必填（引擎侧校验）
 * - strategy=max_height 时 maxHeight 必填（引擎侧校验）
 * - 不接受 yt-dlp 原始 -f 字符串透传
 */
@Serializable
data class FormatSelector(
    val strategy: SelectionStrategy,
    val formatId: String? = null,
    val maxHeight: Int? = null,
    val preferCodec: PreferCodec? = null,
    val maxSizeBytes: Long? = null,
)
