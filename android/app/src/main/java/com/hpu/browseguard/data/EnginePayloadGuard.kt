package com.hpu.browseguard.data

/**
 * 上行载荷守卫：客户端发往 PC 引擎的 JSON 里不许出现 Cookie 字段。
 *
 * 协议本身没有 headers 字段（`TaskCreateRequest` / `DownloadRequest`），
 * 但「现在没有」不等于「将来不会被加进来」。Cookie 只能由
 * `LocalDirectDownloader` 在手机本地使用（§25.4 裁决），一旦出现在上行载荷里，
 * 就是把网页凭据交给了另一台机器。这里在序列化出口做最后一道实名检查。
 */
internal object EnginePayloadGuard {

    /** 只认 JSON 键：`"cookie":` 命中，协议字段 `"cookiePolicy":` 不命中。 */
    private val COOKIE_KEY = Regex("\"(?i:cookie)\"\\s*:")

    fun check(json: String): String {
        require(!COOKIE_KEY.containsMatchIn(json)) {
            "上行载荷里出现 Cookie 字段：客户端不得把网页凭据发给引擎"
        }
        return json
    }
}
