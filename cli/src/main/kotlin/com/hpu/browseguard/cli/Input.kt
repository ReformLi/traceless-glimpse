package com.hpu.browseguard.cli

import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.SelectionStrategy
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** 本地输入校验：不合格的 URL 不发出请求，直接按输入错误（2）退出。 */
fun requireHttpUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw CliUsageException("URL 不能为空")
    val uri = try {
        URI(trimmed)
    } catch (e: URISyntaxException) {
        throw CliUsageException("无效 URL：${e.message}")
    }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
        throw CliUsageException("仅支持 http/https 链接：$raw")
    }
    if (uri.host.isNullOrBlank()) {
        throw CliUsageException("URL 缺少主机名：$raw")
    }
    return trimmed
}

/**
 * `--format` 的文本语法 → 结构化 [FormatSelector]（PROTOCOL §3.4）。
 *
 * 只接受显式枚举的四种策略，不接受 yt-dlp 原始 `-f` 字符串透传。
 */
fun parseFormatSelector(raw: String): FormatSelector {
    val text = raw.trim()
    if (text.isEmpty()) throw CliUsageException("--format 不能为空")
    if (text == "best") return FormatSelector(SelectionStrategy.BEST)
    if (text == "audio_only" || text == "audio") {
        return FormatSelector(SelectionStrategy.AUDIO_ONLY)
    }
    if (text.startsWith("max_height=")) {
        val value = text.removePrefix("max_height=").trim()
        val height = value.toIntOrNull()?.takeIf { it in 1..100_000 }
            ?: throw CliUsageException("无效的 max_height：$value（需为正整数）")
        return FormatSelector(SelectionStrategy.MAX_HEIGHT, maxHeight = height)
    }
    if (text.startsWith("exact=")) {
        val formatId = text.removePrefix("exact=").trim()
        if (formatId.isEmpty()) throw CliUsageException("exact= 后必须提供 formatId")
        return FormatSelector(SelectionStrategy.EXACT, formatId = formatId)
    }
    throw CliUsageException(
        "不支持的格式选择器：$raw（可用：best / audio_only / max_height=720 / exact=<formatId>）",
    )
}
