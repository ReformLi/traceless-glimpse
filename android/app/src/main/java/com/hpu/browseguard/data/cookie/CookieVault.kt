package com.hpu.browseguard.data.cookie

import android.webkit.CookieManager
import com.hpu.browseguard.data.AuditLogger

/**
 * 应用内 WebView 的 Cookie 容器：读值与清空都收在这里。
 *
 * 单列一层有两个原因：一是界面与 ViewModel 不该直接碰 `CookieManager`（那样
 * 「谁读了 Cookie」就散在 UI 里，JVM 单测也换不掉）；二是系统只给了全局开关和
 * `removeAllCookies`，**没有**按域名枚举、也没有按域名删除——撤销授权只能收回
 * 「读」的许可，真正把值抹掉只有 [clearAll] 这一条全量出口。
 *
 * 「WebView 收不收 Cookie」这个开关在 `LocalWebViewSniffer` 那一侧：它按页面域名
 * 的授权状态逐次会话决定，不属于容器本身。
 */
interface CookieVault {
    /** 读值。只在授权门槛之后调用。 */
    fun read(url: String): String?

    fun clearAll()

    fun flush()
}

/** [CookieManager] 实现。取不到时一律当作「没有」，不把异常带进下载链路。 */
class WebViewCookieVault(
    private val store: CookieManager = CookieManager.getInstance(),
    private val audit: AuditLogger,
) : CookieVault {

    override fun read(url: String): String? =
        runCatching { store.getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun clearAll() {
        runCatching { store.removeAllCookies(null) }
        runCatching { store.removeSessionCookies(null) }
        flush()
        audit.log("audit_cookie_cleared_all")
    }

    override fun flush() {
        runCatching { store.flush() }
    }
}
