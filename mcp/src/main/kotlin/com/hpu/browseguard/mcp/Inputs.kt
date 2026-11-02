package com.hpu.browseguard.mcp

import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.SelectionStrategy
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** 工具参数不合法：本地错误，绝不映射成协议错误码。 */
class ToolArgumentException(message: String) : RuntimeException(message)

fun requireHttpUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) throw ToolArgumentException("url 不能为空")
    val uri = try {
        URI(trimmed)
    } catch (e: URISyntaxException) {
        throw ToolArgumentException("无效 URL：${e.message}")
    }
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
        throw ToolArgumentException("仅支持 http/https 链接：$raw")
    }
    if (uri.host.isNullOrBlank()) {
        throw ToolArgumentException("URL 缺少主机名：$raw")
    }
    return trimmed
}

/** 与 CLI 相同的选择器文本语法；不接受 yt-dlp 原始 `-f` 字符串透传。 */
fun parseFormatSelector(raw: String): FormatSelector {
    val text = raw.trim()
    if (text.isEmpty()) throw ToolArgumentException("format 不能为空")
    if (text == "best") return FormatSelector(SelectionStrategy.BEST)
    if (text == "audio_only" || text == "audio") {
        return FormatSelector(SelectionStrategy.AUDIO_ONLY)
    }
    if (text.startsWith("max_height=")) {
        val value = text.removePrefix("max_height=").trim()
        val height = value.toIntOrNull()?.takeIf { it in 1..100_000 }
            ?: throw ToolArgumentException("无效的 max_height：$value（需为正整数）")
        return FormatSelector(SelectionStrategy.MAX_HEIGHT, maxHeight = height)
    }
    if (text.startsWith("exact=")) {
        val formatId = text.removePrefix("exact=").trim()
        if (formatId.isEmpty()) throw ToolArgumentException("exact= 后必须提供 formatId")
        return FormatSelector(SelectionStrategy.EXACT, formatId = formatId)
    }
    throw ToolArgumentException(
        "不支持的格式选择器：$raw（可用：best / audio_only / max_height=720 / exact=<formatId>）",
    )
}
