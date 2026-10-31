package com.hpu.browseguard.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 媒体流类型。PROTOCOL §3.2。 */
@Serializable
enum class MediaStreamType {
    @SerialName("hls") HLS,
    @SerialName("dash") DASH,
    @SerialName("mp4") MP4,
    @SerialName("audio") AUDIO,
    @SerialName("other") OTHER,
}

/** DRM 系统。PROTOCOL §3.2：drmDetected=true 时后续 POST /download 必须返回 DRM_PROTECTED。 */
@Serializable
enum class DrmSystem {
    @SerialName("widevine") WIDEVINE,
    @SerialName("fairplay") FAIRPLAY,
    @SerialName("playready") PLAYREADY,
    @SerialName("unknown") UNKNOWN,
}

/** 预估质量。PROTOCOL §3.2 estimatedQuality 对象。 */
@Serializable
data class EstimatedQuality(
    val height: Int,
    val bitrateKbps: Int,
    val label: String,
)

/**
 * 嗅探结果。PROTOCOL §3.2。
 *
 * headersHint 必须已脱敏：禁止出现 Cookie / Authorization / Set-Cookie / X-*Token*。
 */
@Serializable
data class SniffResult(
    val taskId: String,
    val mediaUrl: String,
    val type: MediaStreamType,
    val container: String,
    val estimatedQuality: EstimatedQuality,
    val drmDetected: Boolean,
    val drmSystem: DrmSystem? = null,
    val headersHint: Map<String, String> = emptyMap(),
    val discoveredAt: String,
)
