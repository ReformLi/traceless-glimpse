package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.AuditLogger

/**
 * 嗅探头 → 下载头的唯一过滤点。
 *
 * 两个后端都必须走这里：WebView 在构造 [com.hpu.browseguard.shared.model.SniffResult]
 * 之前过滤一次，本地下载器在下发请求之前再过滤一次。两侧都不信任输入，
 * 但规则只有一份——改白名单不会出现「一边剥、一边放」。
 *
 * 白名单（§25.6 裁决）：只允许 `referer` 与 `user-agent` 上行。浏览器的
 * `accept` / `accept-encoding` / `origin` 等头一律不进 `headersHint`：前者可能
 * 改变站点分流，后者会让服务端回压缩流——字节直接写进 `.mp4` 就成了坏文件。
 */
internal object SniffHeaderPolicy {

    /** 上行白名单。新增一项必须先有真机证据（例如 406 后确认缺的是 `accept`）。 */
    val ALLOWED_HEADERS = setOf(
        "referer",
        "user-agent",
    )

    /** 凭据类头：即使将来开白名单也不能放行，单独留一种审计事件便于排查。 */
    val SENSITIVE_HEADERS = setOf(
        "cookie",
        "authorization",
        "proxy-authorization",
        "set-cookie",
    )

    fun sanitize(headers: Map<String, String>): Map<String, String> = buildMap {
        headers.forEach { (name, value) ->
            val lower = name.lowercase()
            if (lower in ALLOWED_HEADERS && value.isNotBlank()) put(lower, value)
        }
    }

    /** 被剥掉的头必须留审计行：真机排查「为什么 403/406」时，先要能看见客户端扔了什么。 */
    fun auditStripped(audit: AuditLogger, headers: Map<String, String>) {
        headers.keys.map { it.lowercase() }.distinct().forEach { lower ->
            if (lower in ALLOWED_HEADERS) return@forEach
            when {
                lower == "range" || lower == "if-range" -> audit.log("range_stripped", mapOf("header" to lower))
                lower in SENSITIVE_HEADERS -> audit.log("sensitive_header_stripped", mapOf("header" to lower))
                else -> audit.log("header_dropped", mapOf("header" to lower))
            }
        }
    }
}
