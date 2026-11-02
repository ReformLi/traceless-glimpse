package com.hpu.browseguard.data.backend

import android.webkit.CookieManager

/**
 * 本地直链下载前的 Cookie 通道。
 *
 * 只给 [LocalDirectDownloader] 用：WebView 的 `requestHeaders` 里拿不到 Cookie
 * （§25.4 真机结论），但页面写入的 Cookie 存在 WebView 的 CookieStore 里。
 * 这个值**不得**进入 `SniffResult.headersHint`，更不得上行给 PC 引擎——
 * 守卫见 `EnginePayloadGuard` 与 `SniffHeaderPolicy` 的白名单。
 */
fun interface CookieProvider {
    /** 返回该 URL 的 `Cookie` 头值；没有则 `null`。 */
    fun cookieFor(url: String): String?
}

/** 不注入任何 Cookie：测试与「没有 WebView 上下文」时使用。 */
internal object NoCookieProvider : CookieProvider {
    override fun cookieFor(url: String): String? = null
}

/** 读 WebView 的 CookieStore。取不到时返回 `null`，不把异常带进下载链路。 */
class WebViewCookieProvider(
    private val store: CookieManager = CookieManager.getInstance(),
) : CookieProvider {
    override fun cookieFor(url: String): String? =
        runCatching { store.getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
}
