package com.hpu.browseguard.data.browser

import android.content.Context
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.data.backend.CookieProvider
import com.hpu.browseguard.data.backend.LocalSniffMapping
import com.hpu.browseguard.data.backend.NoCookieProvider
import com.hpu.browseguard.data.backend.hls.HlsProbeClient
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject

/** 面板里的一行。`result` 是下载真正要用的形状，其余字段只服务展示。 */
data class BrowserCandidate(
    val key: String,
    val result: SniffResult,
    val source: String,
    val quality: String,
    val note: String? = null,
    val segments: Int = 0,
    /** 直播/SAMPLE-AES 这类清单可以展示，但不能点下载。 */
    val downloadable: Boolean = true,
    val discoveredAt: Long,
) {
    val formatLabel: String
        get() = if (result.type == MediaStreamType.HLS) "HLS" else result.container.uppercase()

    val host: String
        get() = result.mediaUrl.toHttpUrlOrNull()?.host ?: result.mediaUrl
}

/**
 * 面板顺序：HLS 优先、画质高在前，**同画质按发现先后**。
 *
 * 视频站的内联播放配置里 `url` 是本集、`url_next` 是下一集，两者同域名同画质，
 * 只差几毫秒的发现时间。按时间倒序会把「下一集」顶到第一张卡——用户点下去就下错集，
 * 这在真机第一轮验收里当场复现了。先到的才是正在播的那一集。
 */
internal val BROWSER_CANDIDATE_ORDER: Comparator<BrowserCandidate> =
    compareByDescending<BrowserCandidate> { it.result.type == MediaStreamType.HLS }
        .thenByDescending { it.result.estimatedQuality.height }
        .thenBy { it.discoveredAt }

data class BrowserSniffState(
    val candidates: List<BrowserCandidate> = emptyList(),
    val blobLinks: List<String> = emptyList(),
    val pageUrl: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    /** 每一次新命中都变；界面据此展开抽屉。 */
    val hitAt: Long = 0L,
)

/**
 * 内置浏览器的嗅探中枢：JS Bridge 与 `shouldInterceptRequest` 两条路都汇到这里。
 *
 * 去重键只认 URL（fragment 丢掉）；HLS 候选异步读一次清单补画质/段数。
 * blob/MSE 不伪造 [SniffResult]，单独放在 [BrowserSniffState.blobLinks] 里，
 * 由界面明确说「改用电脑引擎」。
 */
class BrowserSniffController(
    context: Context,
    private val audit: AuditLogger = NoopAuditLogger,
    private val cookies: CookieProvider = NoCookieProvider,
    http: OkHttpClient = DEFAULT_HTTP,
) {

    private val appContext = context.applicationContext
    private val probeClient = HlsProbeClient(http, cookies)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val probed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val blobSeen: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val blockedHosts: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val _state = MutableStateFlow(BrowserSniffState())
    val state: StateFlow<BrowserSniffState> = _state.asStateFlow()

    /** 文档开始时注入到**所有 frame**；读取失败就是空串，调用方仍可用 `onPageStarted` 兜底。 */
    val hookScript: String = runCatching {
        appContext.assets.open(HOOK_ASSET).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    @Volatile
    var userAgent: String = ""

    /** 主文档换页：上一页的候选不再属于这一页，清空重来。 */
    fun reset(url: String) {
        seen.clear()
        blobSeen.clear()
        probed.clear()
        blockedHosts.clear()
        _state.update {
            it.copy(
                candidates = emptyList(),
                blobLinks = emptyList(),
                pageUrl = url,
                loading = true,
                error = null,
                hitAt = 0L,
            )
        }
    }

    fun onPageStarted(url: String) {
        if (url.startsWith("http")) reset(url)
    }

    fun onPageFinished(url: String) {
        _state.update {
            it.copy(
                pageUrl = if (url.startsWith("http")) url else it.pageUrl,
                loading = false,
            )
        }
    }

    fun onUrlChanged(url: String, canGoBack: Boolean, canGoForward: Boolean) {
        _state.update {
            it.copy(
                pageUrl = if (url.startsWith("http")) url else it.pageUrl,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )
        }
    }

    fun onTitle(title: String) {
        _state.update { it.copy(title = title.take(MAX_TITLE)) }
    }

    fun onError(message: String) {
        _state.update { it.copy(loading = false, error = message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /** JS 桥：只接受一段 JSON 字符串，字段不全/类型不对就当没收到。 */
    fun onBridgeJson(json: String) {
        if (json.isBlank() || json.length > MAX_BRIDGE_CHARS) return
        val payload = runCatching { JSONObject(json) }.getOrNull() ?: return
        val url = payload.optString("url")
        if (url.startsWith("blob:", ignoreCase = true)) {
            addBlob(url)
            return
        }
        if (!url.startsWith("http", ignoreCase = true)) return

        val frameUrl = payload.optString("frameUrl")
        val referrer = payload.optString("referrer")
        val page = refererFor(frameUrl, referrer)
        val headers = if (page.isNotBlank()) mapOf("Referer" to page) else emptyMap()
        val result = LocalSniffMapping.toResult(
            rawUrl = url,
            pageUrl = page,
            userAgent = userAgent,
            requestHeaders = headers,
            discoveredAt = Instant.now().toString(),
            contentType = payload.optString("contentType").takeIf { it.isNotBlank() },
            heightHint = payload.optInt("height"),
        ) ?: return
        add(result, "js:" + payload.optString("source").ifBlank { "unknown" })
    }

    /**
     * `shouldInterceptRequest` 的第二路捕获：JS 注入失败、iframe 里没跑到脚本时，
     * 这里仍然能看见请求。`requestHeaders` 里通常没有 Content-Type，所以主要靠扩展名。
     */
    fun onRequest(
        rawUrl: String,
        method: String,
        requestHeaders: Map<String, String>,
        mainFrameUrl: String,
    ) {
        if (!method.equals("GET", ignoreCase = true)) return
        if (!rawUrl.startsWith("http", ignoreCase = true)) return
        val headerReferer = requestHeaders.entries
            .firstOrNull { it.key.equals("Referer", ignoreCase = true) }
            ?.value
        val page = headerReferer?.takeIf { it.startsWith("http") }
            ?: mainFrameUrl.takeIf { it.startsWith("http") }
            .orEmpty()
        val result = LocalSniffMapping.toResult(
            rawUrl = rawUrl,
            pageUrl = page,
            userAgent = userAgent,
            requestHeaders = requestHeaders,
            discoveredAt = Instant.now().toString(),
        ) ?: return
        add(result, "network")
    }

    /**
     * 广告资源被拦的报账。同一域名每页只留一行：
     * 一个页面常会向同一个广告域打几十个请求，逐条写会把 logcat 的取证行冲掉。
     */
    fun onAdBlocked(host: String) {
        if (blockedHosts.add(host)) audit.log("adblock_hit", mapOf("host" to host))
    }

    /**
     * 主框架跳转闸门：站外一律丢弃，站内要求用户手势（重定向除外）。
     *
     * 返回 `true` 表示 WebView 应当吞掉这次导航；被吞掉的地址留一行
     * `navigation_blocked`，真机上能看出是哪家广告落地页在劫持。
     */
    fun onMainFrameNavigation(
        target: String,
        currentPage: String,
        hasGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean {
        val blocked = NavigationPolicy.shouldBlock(target, currentPage, hasGesture, isRedirect)
        if (blocked) audit.log("navigation_blocked", mapOf("url" to target.take(MAX_BLOCKED_URL)))
        return blocked
    }

    /**
     * 会话结束：先报账，再掐协程。
     *
     * 浏览器这一侧的探测（读 m3u8）也会走 Cookie 门槛，被拦的次数同样按域名压成了
     * 一行 `cookie_blocked`。收尾补一条 `browser_close_summary`：`blocked>0` 是「读了、
     * 被门槛拦了」，这一行整个缺失才是「这一站没探出清单、根本没读」。
     */
    fun close() {
        cookies.finish("browser_close_summary")
        scope.cancel()
    }

    private fun add(result: SniffResult, source: String) {
        val key = LocalSniffMapping.dedupeKey(result.mediaUrl) ?: return
        if (!seen.add(key)) return
        val candidate = result.toCandidate(key, source)
        _state.update { current ->
            val next = (current.candidates + candidate).sortedWith(BROWSER_CANDIDATE_ORDER)
            current.copy(candidates = next.take(MAX_CANDIDATES), hitAt = System.currentTimeMillis())
        }
        audit.log(
            "browser_candidate",
            mapOf(
                "host" to candidate.host,
                "kind" to result.type.name,
                "source" to source,
            ),
        )
        if (result.type == MediaStreamType.HLS) probe(key, result)
    }

    private fun addBlob(url: String) {
        if (!blobSeen.add(url)) return
        _state.update { current ->
            current.copy(
                blobLinks = (current.blobLinks + url).take(MAX_BLOBS),
                hitAt = System.currentTimeMillis(),
            )
        }
        audit.log("browser_blob", mapOf("kind" to "mse"))
    }

    private fun probe(key: String, result: SniffResult) {
        if (!probed.add(key)) return
        scope.launch {
            val probe = runCatching { probeClient.probe(result) }.getOrNull()
            if (probe == null) {
                audit.log("browser_probe", mapOf("host" to hostOf(result.mediaUrl), "outcome" to "failed"))
                return@launch
            }
            val quality = buildString {
                if (probe.height > 0) append("${probe.height}p")
                if (probe.segmentCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${probe.segmentCount} 段")
                }
                if (isEmpty()) append("未知画质")
            }
            val note = when {
                probe.unsupportedEncryption -> "SAMPLE-AES/DRM，本机不支持"
                probe.live -> "直播流，本机只下点播"
                probe.encrypted -> "AES-128"
                else -> null
            }
            val downloadable = !probe.unsupportedEncryption && !probe.live
            _state.update { current ->
                current.copy(
                    candidates = current.candidates.map { candidate ->
                        if (candidate.key != key) {
                            candidate
                        } else {
                            candidate.copy(
                                result = candidate.result.copy(
                                    estimatedQuality = EstimatedQuality(
                                        height = probe.height,
                                        bitrateKbps = probe.bitrateKbps,
                                        label = quality,
                                    ),
                                ),
                                quality = quality,
                                segments = probe.segmentCount,
                                note = note,
                                downloadable = downloadable,
                            )
                        }
                    },
                )
            }
            audit.log(
                "browser_probe",
                mapOf(
                    "host" to hostOf(result.mediaUrl),
                    "outcome" to "ok",
                    "segments" to probe.segmentCount.toString(),
                    "encrypted" to probe.encrypted.toString(),
                ),
            )
        }
    }

    /** 页面内 iframe 的媒体请求：Referer 用主页面，拿不到才退到 iframe 自己的地址。 */
    private fun refererFor(frameUrl: String, referrer: String): String {
        val main = _state.value.pageUrl
        return when {
            frameUrl.startsWith("http") && frameUrl == main -> frameUrl
            referrer.startsWith("http") -> referrer
            frameUrl.startsWith("http") -> frameUrl
            else -> main
        }
    }

    private fun SniffResult.toCandidate(key: String, source: String): BrowserCandidate {
        val height = estimatedQuality.height
        return BrowserCandidate(
            key = key,
            result = this,
            source = source,
            quality = when {
                type == MediaStreamType.HLS && height <= 0 -> "解析画质中…"
                height > 0 -> "${height}p"
                else -> "未知画质"
            },
            discoveredAt = System.currentTimeMillis(),
        )
    }

    private fun hostOf(rawUrl: String): String = rawUrl.toHttpUrlOrNull()?.host ?: "invalid"

    private companion object {
        const val HOOK_ASSET = "bgv_browser_hook.js"
        const val MAX_CANDIDATES = 60
        const val MAX_BLOBS = 10
        const val MAX_TITLE = 120
        const val MAX_BRIDGE_CHARS = 16_384
        const val MAX_BLOCKED_URL = 300

        val DEFAULT_HTTP = OkHttpClient()
    }
}
