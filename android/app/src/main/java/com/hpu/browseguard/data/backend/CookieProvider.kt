package com.hpu.browseguard.data.backend

import android.webkit.CookieManager
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.cookie.CookieConsentStore
import com.hpu.browseguard.data.cookie.CookieVault
import com.hpu.browseguard.data.cookie.cookieHostOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * 一轮会话收尾：把这一轮攒下的统计写成一行汇总。默认什么都不做。
     *
     * 事件名由调用方给：下载收尾是 `cookie_audit_summary`，浏览器收尾是
     * `browser_close_summary`。这一行只出现一次，所以 grep 到哪个名字就知道
     * 是谁在报账；没有这一行则说明这一会话**根本没读过** Cookie。
     */
    fun finish(summaryEvent: String = "cookie_audit_summary"): Unit = Unit
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
 *
 * 一条 HLS 有 703 段就要读 703 次，逐次打行会把 logcat 环形缓冲冲干净、
 * 把同一轮里别的证据挤掉（M7 真机第一轮现场）。所以按域名抑制：
 * `cookie_blocked` 每个域名只留第一行，`cookie_read` 只在「有没有值」发生翻转时留行
 * （清 Cookie 这类状态变化还得看得见）。总数不靠行数，靠 [finish] 那一条汇总。
 */
class ConsentCookieProvider(
    private val vault: CookieVault,
    private val consent: CookieConsentStore,
    private val audit: AuditLogger,
) : CookieProvider {

    private val loggedBlocked = ConcurrentHashMap.newKeySet<String>()
    private val loggedPresence = ConcurrentHashMap<String, String>()
    private val blocked = AtomicInteger()
    private val injected = AtomicInteger()

    override fun cookieFor(url: String): String? {
        val host = cookieHostOf(url)
        if (!consent.isGranted(host)) {
            blocked.incrementAndGet()
            if (loggedBlocked.add(host)) {
                audit.log("cookie_blocked", mapOf("host" to host))
            }
            return null
        }
        consent.markUsed(host)
        val cookie = vault.read(url)
        val presence = if (cookie == null) "absent" else "present"
        if (loggedPresence.put(host, presence) != presence) {
            audit.log("cookie_read", mapOf("host" to host, "cookie" to presence))
        }
        if (cookie != null) injected.incrementAndGet()
        return cookie
    }

    /**
     * 一行说清这一轮到底拦了多少、注了多少：抑制的是重复行，不是事实。
     *
     * 完全没读过就不打行——「没有这一行」本身就是「根本没读」的答案，
     * 不该用一行 `blocked=0 injected=0` 去冒充「读了但没拦」。
     */
    override fun finish(summaryEvent: String) {
        val blockedTotal = blocked.getAndSet(0)
        val injectedTotal = injected.getAndSet(0)
        if (blockedTotal == 0 && injectedTotal == 0) return
        audit.log(
            summaryEvent,
            mapOf("blocked" to blockedTotal.toString(), "injected" to injectedTotal.toString()),
        )
        loggedBlocked.clear()
        loggedPresence.clear()
    }
}
