package com.hpu.browseguard

import android.os.Environment
import android.util.Log
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hpu.browseguard.data.LogcatAuditLogger
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.ConsentCookieProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.LocalWebViewSniffer
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.WebViewCookieProvider
import com.hpu.browseguard.data.cookie.PrefsCookieConsentStore
import com.hpu.browseguard.data.cookie.WebViewCookieVault
import com.hpu.browseguard.data.cookie.cookieHostOf
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机闭环取证：WebView 嗅探 → CookieStore → 本地下载器注入 Cookie → 受保护直链落地。
 *
 * 夹具 `/page-cookie.html` 的响应带 `Set-Cookie: sid=good-cookie`，
 * `/media/protected.mp4` 没有这个 Cookie 时回 403。两条路径都要被证明：
 * 不注入时确实是 `LINK_UNLOADABLE`，注入后才落成 `done` 产物。
 *
 * 页面地址由 instrumentation 参数 `baseUrl` 传入，默认走 `adb reverse` 的
 * media_server（`http://127.0.0.1:8731`）。观察一律走 `BgvAudit`。
 */
@RunWith(AndroidJUnit4::class)
class CookieClosedLoopDeviceTest {

    @Test
    fun webViewCookieIsTheOnlyWayThroughTheProtectedLink(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val baseUrl = InstrumentationRegistry.getArguments().getString("baseUrl")
            ?: "http://127.0.0.1:8731"
        val audit = LogcatAuditLogger()

        val cookieManager = CookieManager.getInstance()
        runCatching { cookieManager.removeAllCookies(null) }
        cookieManager.flush()

        val pageUrl = "$baseUrl/page-cookie.html"
        // 授权门槛上线后，WebView 会话按页面域名的授权状态决定收不收 Cookie；
        // 这条闭环要复现「授权 → 存下登录态」，所以先把这一站点头。
        val consent = PrefsCookieConsentStore(context, audit)
        consent.revoke(cookieHostOf(pageUrl))
        consent.grant(cookieHostOf(pageUrl))

        val events = LocalWebViewSniffer(
            context = context,
            audit = audit,
            consent = consent,
            timeoutMs = 20_000L,
            quietMs = 2_000L,
        ).sniff(SniffRequest(pageUrl, CookiePolicy.NONE, context.cacheDir.absolutePath)).toList()

        events.forEach { Log.w("BgvAudit", "device_event=${it.javaClass.simpleName}") }
        val results = events.filterIsInstance<SniffEvent.Results>().singleOrNull()
        assertNotNull("WebView 必须拦到受保护直链：$events", results)
        val link = results!!.results.single { it.mediaUrl.endsWith("/media/protected.mp4") }
        assertFalse(
            "Cookie 不得进入 headersHint",
            link.headersHint.keys.any { it.equals("cookie", ignoreCase = true) },
        )

        val cookie = requireNotNull(WebViewCookieProvider().cookieFor(link.mediaUrl)) {
            "登录态 Cookie 必须能从 CookieManager 取到"
        }
        Log.w("BgvAudit", "device_cookie_present=true")
        assertTrue("Cookie 值来自页面响应", cookie.contains("sid=good-cookie"))

        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "cookie-loop",
        ).apply { mkdirs() }
        outputDir.listFiles()?.forEach { it.delete() }
        Log.w("BgvAudit", "device_output_dir=${outputDir.absolutePath}")
        val roots = listOf(outputDir.absolutePath)

        val without = LocalDirectDownloader(audit = audit).start(
            StartRequest.ViaLocal(link, outputDir.absolutePath, roots),
        ).toList()
        val denied = without.filterIsInstance<DownloadEvent.Failed>().singleOrNull()
            ?: error("没有 Cookie 必须失败：$without")
        val deniedFailure = denied.failure as BackendFailure.Local
        assertEquals(LocalReason.LINK_UNLOADABLE, deniedFailure.reason)
        assertTrue("403 要如实写进提示：${deniedFailure.message}", deniedFailure.message.contains("403"))
        assertFalse("失败不得留下成品文件", File(outputDir, "protected.mp4").exists())
        Log.w("BgvAudit", "device_download_without_cookie=403")

        val gated = ConsentCookieProvider(WebViewCookieVault(audit = audit), consent, audit)
        val withCookie = LocalDirectDownloader(audit = audit, cookies = gated).start(
            StartRequest.ViaLocal(link, outputDir.absolutePath, roots),
        ).toList()
        val terminal = withCookie.filterIsInstance<DownloadEvent.Terminal>().lastOrNull()
            ?: error("注入 Cookie 后必须落到终态：$withCookie")
        assertEquals(TaskStatus.DONE, terminal.task.status)

        val product = File(terminal.task.outputDir, terminal.task.namingTemplate.orEmpty())
        assertTrue("产物必须真的落盘：$product", product.isFile && product.length() > 0L)
        assertFalse("不得留下 .part", File(product.path + ".part").exists())
        Log.w(
            "BgvAudit",
            "device_download_with_cookie=done bytes=${product.length()} name=${product.name}",
        )
    }
}
