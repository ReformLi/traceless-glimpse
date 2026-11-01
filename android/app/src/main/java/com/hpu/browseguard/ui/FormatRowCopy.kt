package com.hpu.browseguard.ui

import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.SniffResult
import java.util.Locale

/**
 * 媒体行文案（格式表 [FormatRowCopy] 与嗅探结果 [SniffRowCopy]，PROTOCOL §3.2 / §3.4
 * + 引擎 `services/formats.py` 的降级规则）。
 *
 * 文案只描述「点下去实际会发生什么」。**上限判定能否生效取决于整张格式表**——
 * 引擎对表内任一 `height <= 0` 直接降级 `best`（`degraded_reason=incomplete_metadata`），
 * 单行画质说得再准也不算数，所以判定函数一律接收整表。
 *
 * 这里只做文案，不改 [FormatSelection.selectorFor] 的下发规则。
 */
object FormatRowCopy {

    /** 首行：点下去实际拿到的选择策略。 */
    fun primary(format: FormatInfo, formats: List<FormatInfo>): String =
        when (FormatSelection.kindOf(format)) {
            FormatSelection.Kind.UNKNOWN_METADATA -> "最佳可用（画质未标注）"
            FormatSelection.Kind.AUDIO_ONLY -> "纯音频"
            FormatSelection.Kind.VIDEO_ONLY -> "视频（无音轨）"
            FormatSelection.Kind.VIDEO_AND_AUDIO ->
                if (heightCapIneffective(formats)) {
                    "最佳可用（画质上限不生效）"
                } else {
                    val codec = FormatSelection.preferCodecLabel(
                        FormatSelection.preferCodecOf(format.codec.video),
                    )
                    val quality = "${format.resolution.height}p"
                    if (codec == null) quality else "$quality · $codec"
                }
        }

    /** 次行：只列真实拿到的元数据，元数据未知不渲染 `0×0 · 0 kbps` 这种假精确。 */
    fun secondary(format: FormatInfo, formats: List<FormatInfo>): String {
        if (FormatSelection.kindOf(format) == FormatSelection.Kind.UNKNOWN_METADATA) {
            return "画质与编码待引擎解析"
        }
        return buildList {
            if (format.resolution.height > 0) {
                add("${format.resolution.width}×${format.resolution.height}")
            }
            if (format.bitrateKbps > 0) add("${format.bitrateKbps} kbps")
            if (format.fileSizeEstimateBytes > 0L) add("~${formatBytes(format.fileSizeEstimateBytes)}")
            add("${format.codec.video}/${format.codec.audio}")
            if (FormatSelection.kindOf(format) == FormatSelection.Kind.VIDEO_ONLY) {
                add("音视频分离 · 需合并")
            }
        }.joinToString(" · ")
    }

    /** 引擎规则的客户端镜像：整表任一 height<=0（或表为空）→ `max_height` 不参与判定。 */
    fun heightCapIneffective(formats: List<FormatInfo>): Boolean =
        formats.isEmpty() || formats.any { it.resolution.height <= 0 }
}

/**
 * 嗅探结果行文案（PROTOCOL §3.2 `SniffResult`）。
 *
 * 与 [FormatRowCopy] 同一条底线：引擎没给的元数据不渲染成 `0p · 0 kbps` 这种假精确，
 * 线名 `unknown` 也不当画质标签直接进界面。
 */
object SniffRowCopy {

    /** 首行：类型线名 + 画质标签。 */
    fun primary(result: SniffResult): String =
        "${result.type.name.lowercase(Locale.ROOT)} · ${qualityLabel(result.estimatedQuality)}"

    /** 次行：容器恒有，画质与码率只在引擎真给了值时出现；DRM 系统名仅在实际判出 DRM 时跟出。 */
    fun secondary(result: SniffResult): String = buildList {
        add(result.container)
        val quality = result.estimatedQuality
        if (quality.height > 0) add("${quality.height}p")
        if (quality.bitrateKbps > 0) add("${quality.bitrateKbps} kbps")
        if (result.drmDetected) result.drmSystem?.let { add(it.name.lowercase(Locale.ROOT)) }
    }.joinToString(" · ")

    /** 有高度才谈标签：引擎给的 `label` 优先，缺失回落到 `{height}p`；无高度一律「画质未标注」。 */
    fun qualityLabel(quality: EstimatedQuality): String = when {
        quality.height <= 0 -> "画质未标注"
        quality.label.isNotBlank() -> quality.label
        else -> "${quality.height}p"
    }
}
