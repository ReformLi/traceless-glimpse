package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.AuditLogger

/**
 * 嗅探头 → 下载头的唯一过滤点。
 *
 * 两个后端都必须走这里：WebView 在构造 [com.hpu.browseguard.shared.model.SniffResult]
 * 之前过滤一次，本地下载器在下发请求之前再过滤一次。两侧都不信任输入，
 * 但规则只有一份——改黑名单不会出现「一边剥、一边放」。
 */
internal object SniffHeaderPolicy {

    /**
     * `headersHint` 协议上已脱敏（§3.2），但那是引擎侧的约定；本机落地前再筛一遍。
     *
     * `range` / `if-range` 单独列出来是因为 §22 的 P0：嗅探探测 Range 被回放后，
     * 服务端回 `206 + 1 字节`，会被误判成下载完成。
     */
    val FORBIDDEN_HEADERS = setOf(
        "cookie",
        "authorization",
        "proxy-authorization",
        "set-cookie",
        "range",
        "if-range",
    )

    fun sanitize(headers: Map<String, String>): Map<String, String> = buildMap {
        headers.forEach { (name, value) ->
            val lower = name.lowercase()
            if (lower !in FORBIDDEN_HEADERS && value.isNotBlank()) put(lower, value)
        }
    }

    /** 被剥掉的头必须留审计行：真机排查「为什么 403」时，先要能看见客户端扔了什么。 */
    fun auditStripped(audit: AuditLogger, headers: Map<String, String>) {
        headers.keys.map { it.lowercase() }.distinct().forEach { lower ->
            if (lower !in FORBIDDEN_HEADERS) return@forEach
            if (lower == "range" || lower == "if-range") {
                audit.log("range_stripped", mapOf("header" to lower))
            } else {
                audit.log("sensitive_header_stripped", mapOf("header" to lower))
            }
        }
    }
}
