package com.hpu.browseguard.data.history

import com.hpu.browseguard.shared.model.FormatInfo
import java.net.URI

/**
 * 历史条目标题：协议里没有「视频标题」字段，只拿 URL 拼一个可辨认的名字。
 * 正式标题要等功能层提供，这里不猜网站语义。
 */
fun defaultHistoryTitle(url: String): String {
    val trimmed = url.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed
    val host = uri.host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return trimmed
    val segment = uri.path.orEmpty()
        .split('/')
        .lastOrNull { it.isNotBlank() }
        ?.take(48)
    return if (segment.isNullOrBlank()) host else "$host · $segment"
}

/** 历史行上的格式摘要；未知元数据不渲染 `0p`。 */
fun formatSummary(format: FormatInfo): String = buildList {
    if (format.resolution.height > 0) add("${format.resolution.height}p")
    if (format.container.isNotBlank()) add(format.container)
}.joinToString(" · ").ifBlank { "未知格式" }
