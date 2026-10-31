package com.hpu.browseguard.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 格式来源。PROTOCOL §3.3。 */
@Serializable
enum class FormatSource {
    @SerialName("yt-dlp") YT_DLP,
    @SerialName("manifest") MANIFEST,
    @SerialName("sniff") SNIFF,
}

/** 分辨率。PROTOCOL §3.3 resolution 对象。 */
@Serializable
data class Resolution(
    val width: Int,
    val height: Int,
)

/** 编码信息。PROTOCOL §3.3 codec 对象。 */
@Serializable
data class Codec(
    val video: String,
    val audio: String,
)

/** 可用格式。PROTOCOL §3.3。 */
@Serializable
data class FormatInfo(
    val formatId: String,
    val container: String,
    val resolution: Resolution,
    val bitrateKbps: Int,
    val codec: Codec,
    val hasAudio: Boolean,
    val hasVideo: Boolean,
    val fileSizeEstimateBytes: Long,
    val source: FormatSource,
)
