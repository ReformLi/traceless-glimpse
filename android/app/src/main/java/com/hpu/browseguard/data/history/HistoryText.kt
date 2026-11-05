package com.hpu.browseguard.data.history

import com.hpu.browseguard.shared.model.FormatInfo
import java.net.URI
import java.util.Locale

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

/**
 * 列表项上的来源域名：只取 host，去掉 `www.`。
 *
 * 解析不出来就返回空串，由界面省略这一行——URL 全文已经在详情面板里，
 * 列表不该拿一整条带 query 的链接当副标题。
 */
fun sourceHost(url: String): String = runCatching { URI(url.trim()) }.getOrNull()?.host
    ?.removePrefix("www.")
    ?.lowercase(Locale.ROOT)
    .orEmpty()

/** 历史行上的格式摘要；未知元数据不渲染 `0p`。 */
fun formatSummary(format: FormatInfo): String = buildList {
    if (format.resolution.height > 0) add("${format.resolution.height}p")
    if (format.container.isNotBlank()) add(format.container)
}.joinToString(" · ").ifBlank { "未知格式" }
