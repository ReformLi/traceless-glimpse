package com.hpu.browseguard.data

import android.util.Log
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PreferCodec
import com.hpu.browseguard.shared.model.SelectionStrategy

/**
 * 界面上「点了哪一条画质」到协议 [FormatSelector] 的映射。
 *
 * 引擎不接受 `exact`（real 模式对 formatId 透传返回 `FORMAT_UNAVAILABLE`，PROTOCOL §3.4
 * 也明确不做 yt-dlp `-f` 字符串透传），因此这里把选择降级成引擎能表达的能力级约束。
 * 降级只在元数据说话的时候做：编解码未知时猜 `audio_only` 会让 yt-dlp 报
 * `Requested format is not available`（见 docs/reports/smoke-report.md §9.8），所以宁可不猜。
 *
 * 列表文案与点击后的行为都取自 [kindOf]，不允许两处各自从 `hasVideo` 推断。
 */
object FormatSelection {

    /** 引擎把 yt-dlp 的 `null` / `"none"` 统一序列化成 [NONE_CODEC]（services/formats.py）。 */
    private const val NONE_CODEC = "none"

    /** 一条格式能被翻译成哪种能力级约束。 */
    enum class Kind { UNKNOWN_METADATA, VIDEO_AND_AUDIO, AUDIO_ONLY, VIDEO_ONLY }

    fun kindOf(format: FormatInfo): Kind = when {
        format.codec.video == NONE_CODEC && format.codec.audio == NONE_CODEC -> Kind.UNKNOWN_METADATA
        format.hasVideo && format.hasAudio -> Kind.VIDEO_AND_AUDIO
        format.hasAudio && !format.hasVideo -> Kind.AUDIO_ONLY
        else -> Kind.VIDEO_ONLY
    }

    fun selectorFor(format: FormatInfo): FormatSelector = when (kindOf(format)) {
        Kind.UNKNOWN_METADATA -> FormatSelector(strategy = SelectionStrategy.BEST)

        Kind.VIDEO_AND_AUDIO -> FormatSelector(
            strategy = SelectionStrategy.MAX_HEIGHT,
            maxHeight = format.resolution.height,
            preferCodec = preferCodecOf(format.codec.video),
        )

        Kind.AUDIO_ONLY -> FormatSelector(strategy = SelectionStrategy.AUDIO_ONLY)

        Kind.VIDEO_ONLY -> {
            Log.w(TAG, "selector_degraded formatId=${format.formatId} reason=video_only")
            FormatSelector(strategy = SelectionStrategy.BEST)
        }
    }

    /** 协议编码偏好的展示名（与 `@SerialName` 取值一致）；未识别的编码返回 `null`。 */
    fun preferCodecLabel(prefer: PreferCodec?): String? = when (prefer) {
        PreferCodec.H264 -> "h264"
        PreferCodec.H265 -> "h265"
        PreferCodec.AV1 -> "av1"
        PreferCodec.VP9 -> "vp9"
        null -> null
    }

    private const val TAG = "FormatSelection"

    /** yt-dlp 的四字符样本描述符（avc1.640028 / hev1.1.6 / av01.0.08 / vp09.00）→ 协议编码偏好。 */
    fun preferCodecOf(videoCodec: String): PreferCodec? = when {
        videoCodec.startsWith("avc1") || videoCodec.startsWith("avc3") -> PreferCodec.H264
        videoCodec.startsWith("hev1") || videoCodec.startsWith("hvc1") -> PreferCodec.H265
        videoCodec.startsWith("av01") -> PreferCodec.AV1
        videoCodec.startsWith("vp09") || videoCodec.startsWith("vp9") -> PreferCodec.VP9
        else -> null
    }

    /**
     * 「视频标题命名」客户端预校验。返回 `null` 表示合法。
     *
     * M24 起这个字段不再是模板（没有 `{title}` 那套变量），而是**产物主名**：
     * 留空就用站点标题，填了就用填的，后缀一律由系统按真实容器补。
     * 所以只校验「能不能安全落成一个文件名」，不再校验花括号。
     *
     * 引擎侧也会 `normalize_output_name` 兜一遍（非法字符换 `_` 并记审计行）；
     * 这里严格是为了**提交前**就让用户看见问题，而不是等下完一个名字被改过的文件。
     */
    fun outputNameError(name: String): String? {
        if (name.isEmpty()) return null
        if (name.length > OUTPUT_NAME_MAX_LENGTH) return "命名不能超过 $OUTPUT_NAME_MAX_LENGTH 字符"
        if (name.any { it in ILLEGAL_NAME_CHARS }) {
            return "命名不能包含这些字符：< > : \" / \\ | ? *"
        }
        if (name.any { it.code < 0x20 }) return "命名不能包含控制字符"
        if (name != name.trimEnd(' ', '.')) return "命名不能以空格或点结尾"
        return null
    }

    const val OUTPUT_NAME_MAX_LENGTH = 200

    /** Windows 文件名非法字符；后缀由系统补，所以这里也不许用户自己带路径分隔符。 */
    private val ILLEGAL_NAME_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
}
