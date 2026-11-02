package com.hpu.browseguard.data.backend

import android.webkit.CookieManager
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.cookie.CookieConsentStore
import com.hpu.browseguard.data.cookie.CookieVault
import com.hpu.browseguard.data.cookie.cookieHostOf

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

/**
 * 授权门槛之后的读：这一站没点头，就**根本不读** CookieStore。
 *
 * 默认关闭是 M6-5 的地基——「读」发生在这一行，所以「绝不发给电脑引擎」之外
 * 还要能保证「没同意就连本机都不读」。拒绝时留一条 `cookie_blocked` 审计行
 * （只有域名与存在性，没有值），让真机能 grep 出被拦下的次数。
 */
class ConsentCookieProvider(
    private val vault: CookieVault,
    private val consent: CookieConsentStore,
    private val audit: AuditLogger,
) : CookieProvider {
    override fun cookieFor(url: String): String? {
        val host = cookieHostOf(url)
        if (!consent.isGranted(host)) {
            audit.log("cookie_blocked", mapOf("host" to host))
            return null
        }
        consent.markUsed(host)
        val cookie = vault.read(url)
        audit.log(
            "cookie_read",
            mapOf("host" to host, "cookie" to if (cookie == null) "absent" else "present"),
        )
        return cookie
    }
}
