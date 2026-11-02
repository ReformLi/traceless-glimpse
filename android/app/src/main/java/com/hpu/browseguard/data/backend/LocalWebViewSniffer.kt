package com.hpu.browseguard.data.backend

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 手机侧 WebView 嗅探：离屏加载页面，用 `shouldInterceptRequest` 收直链。
 *
 * 线程模型：WebView 的创建、加载、销毁都在主线程；回调只做「判定 + 入队」，
 * 不碰视图、不阻塞；候选经 `Channel` 回到 `channelFlow`，再由 ViewModel 的 Main
 * 协程消费。下载本身仍由 [LocalDirectDownloader] 在 IO 线程完成。
 *
 * 头的策略是**观测优先**：每个请求都把脱敏后的头名与 Referer/UA/Cookie/Range
 * 是否存在打到 `BgvAudit`，候选的 `headersHint` 先过 [SniffHeaderPolicy] 再合成
 * 页面侧捕获的 Referer 与 UA。Cookie 不进 `headersHint`（§23.3 裁决）。
 *
 * 只收单文件直链；切片与清单不产生结果（[LocalSniffMapping]）。没有候选时
 * 如实说「页面没有」，WebView 起不来才说「不可用」——两种情况不能混成一句。
 */
class LocalWebViewSniffer(
    private val context: Context,
    private val audit: AuditLogger = NoopAuditLogger,
    private val timeoutMs: Long = LocalFailures.WEBVIEW_MS,
    private val quietMs: Long = QUIET_AFTER_LOAD_MS,
) : SniffBackend {

    override fun sniff(request: SniffRequest): Flow<SniffEvent> = channelFlow {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        var session: WebViewSession? = null
        try {
            session = withContext(Dispatchers.Main.immediate) {
                WebViewSession(context.applicationContext, request.sourceUrl, audit, signals)
            }
            if (session.webView == null) {
                audit.log("webview_session", mapOf("outcome" to "no_webview", "host" to hostOf(request.sourceUrl)))
                send(SniffEvent.Failed(LocalFailures.webViewUnavailable("本机没有可用的 WebView")))
                return@channelFlow
            }
            withContext(Dispatchers.Main.immediate) {
                session.start(request.sourceUrl)
            }

            send(SniffEvent.Status(LocalSniffMapping.SESSION_TASK_ID, TaskStatus.QUEUED))
            send(SniffEvent.Status(LocalSniffMapping.SESSION_TASK_ID, TaskStatus.SNIFFING))

            val results = LinkedHashMap<String, SniffResult>()
            var loaded = false
            var failure: String? = null
            var timedOut = false
            var quietUntil = 0L
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val now = System.currentTimeMillis()
                val remaining = deadline - now
                if (remaining <= 0) {
                    timedOut = true
                    break
                }
                val wait = if (loaded) minOf(remaining, quietUntil - now) else remaining
                if (wait <= 0) break
                val signal = withTimeoutOrNull(wait) { signals.receive() }
                if (signal == null) {
                    if (!loaded) timedOut = true
                    break
                }
                when (signal) {
                    is Signal.Media -> {
                        val key = LocalSniffMapping.dedupeKey(signal.result.mediaUrl)
                        if (key != null && results.put(key, signal.result) == null) {
                            audit.log(
                                "webview_candidate",
                                mapOf("host" to hostOf(signal.result.mediaUrl), "ext" to signal.result.container),
                            )
                        }
                        if (loaded) quietUntil = System.currentTimeMillis() + quietMs
                    }

                    Signal.Loaded -> {
                        loaded = true
                        quietUntil = System.currentTimeMillis() + quietMs
                    }

                    is Signal.Failed -> {
                        failure = signal.reason
                        break
                    }
                }
            }

            audit.log(
                "webview_session",
                mapOf(
                    "outcome" to when {
                        results.isNotEmpty() -> "candidates"
                        failure != null -> "page_error"
                        timedOut -> "timeout"
                        else -> "no_media"
                    },
                    "host" to hostOf(request.sourceUrl),
                    "requests" to session.requestCount.toString(),
                    "ranges" to session.rangeRequestCount.toString(),
                    "candidates" to results.size.toString(),
                    "loaded" to loaded.toString(),
                ),
            )

            when {
                results.isNotEmpty() -> send(SniffEvent.Results(results.values.toList()))
                failure != null -> send(SniffEvent.Failed(LocalFailures.webViewUnavailable(failure)))
                timedOut -> send(SniffEvent.Failed(LocalFailures.webViewTimeout()))
                else -> send(SniffEvent.Failed(LocalFailures.webViewNoMedia()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            audit.log(
                "webview_session",
                mapOf("outcome" to "crash", "host" to hostOf(request.sourceUrl), "error" to t.javaClass.simpleName),
            )
            send(SniffEvent.Failed(LocalFailures.webViewUnavailable(t.javaClass.simpleName)))
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { session?.destroy() }
            signals.close()
        }
    }

    private sealed interface Signal {
        data class Media(val result: SniffResult) : Signal
        data object Loaded : Signal
        data class Failed(val reason: String) : Signal
    }

    private class WebViewSession(
        context: Context,
        private val startUrl: String,
        private val audit: AuditLogger,
        private val signals: SendChannel<Signal>,
    ) {
        val webView: WebView? = runCatching { WebView(context) }.getOrNull()

        @Volatile
        private var pageUrl: String = startUrl

        @Volatile
        private var userAgent: String = ""

        @Volatile
        var requestCount: Int = 0
            private set

        @Volatile
        var rangeRequestCount: Int = 0
            private set

        @SuppressLint("SetJavaScriptEnabled")
        fun start(url: String) {
            val view = webView ?: return
            // 页面 JS 写的 Cookie 要留在 WebView 的 CookieStore 里，下载前由
            // WebViewCookieProvider 读取；这里只确保接收开关开着。
            runCatching { CookieManager.getInstance().setAcceptCookie(true) }
            userAgent = view.settings.userAgentString ?: ""
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            // API 30+ 起 allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs
            // 默认关闭且 setter 已失效，不再写。
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (!url.isNullOrBlank()) pageUrl = url
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!url.isNullOrBlank()) pageUrl = url
                    signals.trySend(Signal.Loaded)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        signals.trySend(Signal.Failed("页面加载失败：${error?.description ?: "未知错误"}"))
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    if (request?.isForMainFrame == true) {
                        signals.trySend(Signal.Failed("页面回 HTTP ${errorResponse?.statusCode}"))
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (request != null) observe(request)
                    return null
                }
            }
            view.loadUrl(url)
        }

        private fun observe(request: WebResourceRequest) {
            val raw = request.url?.toString() ?: return
            val headers = request.requestHeaders ?: emptyMap()
            requestCount += 1
            val names = headers.keys.map { it.lowercase() }.distinct().sorted()
            val referer = headerValue(headers, "referer")
            val userAgentHeader = headerValue(headers, "user-agent")
            val range = headerValue(headers, "range")
            if (range != null) rangeRequestCount += 1
            audit.log(
                "webview_request",
                linkedMapOf(
                    "host" to hostOf(raw),
                    "ext" to (LocalSniffMapping.directExtension(raw) ?: "none"),
                    "method" to request.method,
                    "headers" to names.joinToString(",").ifEmpty { "none" },
                    "referer" to (referer?.let(::safeUrl) ?: "absent"),
                    "user_agent" to (userAgentHeader?.let(::short) ?: "absent"),
                    "cookie" to if ("cookie" in names) "present" else "absent",
                    "range" to (range?.let(::short) ?: "absent"),
                ),
            )

            if (request.method != "GET") return
            val result = LocalSniffMapping.toResult(
                rawUrl = raw,
                pageUrl = pageUrl,
                userAgent = userAgent,
                requestHeaders = headers,
                discoveredAt = Instant.now().toString(),
            ) ?: return
            signals.trySend(Signal.Media(result))
        }

        fun destroy() {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            // 会话结束后把内存里的 Cookie 落盘：下载器在同一进程读得到，
            // 但应用被杀后下一次启动也要能续上同一站点的登录态。
            runCatching { CookieManager.getInstance().flush() }
        }

        private fun headerValue(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        private fun short(value: String): String =
            value.replace(Regex("[\\r\\n\\t]"), " ").take(MAX_LOG_VALUE).ifBlank { "blank" }

        /** Referer 可能带签名参数：只留 scheme://host/path。 */
        private fun safeUrl(raw: String): String {
            val url = raw.toHttpUrlOrNull() ?: return "unparsable"
            return "${url.scheme}://${url.host}${url.encodedPath}"
        }
    }

    private companion object {
        const val QUIET_AFTER_LOAD_MS = 2_000L
        const val MAX_LOG_VALUE = 80

        fun hostOf(rawUrl: String): String = rawUrl.toHttpUrlOrNull()?.host ?: "invalid"
    }
}
