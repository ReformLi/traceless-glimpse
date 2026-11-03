package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebView 拦截到的请求 → 本机可下载的 [SniffResult]。
 *
 * 这一层不碰 Android WebView API，JVM 单测可以直接钉住三件事：
 * 只认可下载的完整资源（HLS 清单可以，切片与 DASH 清单不算一条结果）、
 * 头在进入 `headersHint` 之前就过滤、Referer / UA 在缺失时用页面侧捕获的值合成。
 */
internal object LocalSniffMapping {

    /** WebView 会话没有引擎任务号；界面不拿它发请求，只要求非空。 */
    const val SESSION_TASK_ID = "local_webview"

    /**
     * 可下载的扩展名。HLS 清单交给 [HlsDownloadSession] 逐段拉取，
     * 所以 `.m3u8` 是一条**真**的可下载结果；`.mpd`（DASH）没有本地执行者，
     * `.ts` / `.m4s` 只是某条清单的切片，单独列出来只会下出一个残片。
     */
    private val VIDEO_EXTENSIONS = mapOf(
        "m3u8" to MediaStreamType.HLS,
        "mp4" to MediaStreamType.MP4,
        "m4v" to MediaStreamType.MP4,
        "mov" to MediaStreamType.MP4,
        "mkv" to MediaStreamType.OTHER,
        "webm" to MediaStreamType.OTHER,
    )

    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "oga", "opus", "wav", "flac")

    /** 切片/清单类扩展名：即使服务端回的是视频 MIME，也不能当一条可下载结果。 */
    private val REJECTED_EXTENSIONS = setOf("ts", "m4s", "mpd", "cmfv", "cmfa", "key")

    fun directExtension(rawUrl: String): String? {
        val ext = extensionOf(rawUrl) ?: return null
        return ext.takeIf { it in VIDEO_EXTENSIONS || it in AUDIO_EXTENSIONS }
    }

    /** 去重键：同一 URL 的多次 Range 请求只留一行；fragment 与签名无关，丢掉。 */
    fun dedupeKey(rawUrl: String): String? =
        rawUrl.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()

    /**
     * [contentType] 只在扩展名给不出答案时兜底：很多播放器把清单挂在
     * `/play?id=...` 这类无扩展名路径上，靠 `application/vnd.apple.mpegurl` 才认得出。
     * [heightHint] 来自页面侧 `<video>.videoHeight`，没有就留 0（不猜）。
     */
    fun toResult(
        rawUrl: String,
        pageUrl: String?,
        userAgent: String,
        requestHeaders: Map<String, String>,
        discoveredAt: String,
        contentType: String? = null,
        heightHint: Int = 0,
    ): SniffResult? {
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        val ext = extensionOf(rawUrl)
        if (ext != null && ext in REJECTED_EXTENSIONS) return null
        val kind = kindOf(ext, contentType) ?: return null
        val hints = SniffHeaderPolicy.sanitize(requestHeaders).toMutableMap()
        if (hints["referer"] == null && pageUrl?.startsWith("http") == true) {
            hints["referer"] = pageUrl
        }
        if (hints["user-agent"] == null && userAgent.isNotBlank()) {
            hints["user-agent"] = userAgent
        }
        return SniffResult(
            taskId = SESSION_TASK_ID,
            mediaUrl = url.newBuilder().fragment(null).build().toString(),
            type = kind.type,
            container = kind.container,
            estimatedQuality = EstimatedQuality(
                height = heightHint.coerceAtLeast(0),
                bitrateKbps = 0,
                label = if (heightHint > 0) "${heightHint}p" else "未知画质",
            ),
            drmDetected = false,
            headersHint = hints,
            discoveredAt = discoveredAt,
        )
    }

    private data class Kind(val type: MediaStreamType, val container: String)

    private fun kindOf(ext: String?, contentType: String?): Kind? {
        if (ext != null && ext in VIDEO_EXTENSIONS) {
            return Kind(VIDEO_EXTENSIONS.getValue(ext), if (ext == "oga") "ogg" else ext)
        }
        if (ext != null && ext in AUDIO_EXTENSIONS) {
            return Kind(MediaStreamType.AUDIO, if (ext == "oga") "ogg" else ext)
        }
        if (ext != null) return null

        // 只靠 Content-Type 兜底；`video/mp2t` 是 HLS 切片，`dash+xml` 本地没有执行者。
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            mime in HLS_MIME_TYPES -> Kind(MediaStreamType.HLS, "m3u8")
            mime == "application/dash+xml" || mime == "video/mp2t" -> null
            mime.startsWith("video/") -> {
                val subtype = mime.substringAfter('/')
                when (subtype) {
                    "mp4", "m4v" -> Kind(MediaStreamType.MP4, subtype)
                    "quicktime" -> Kind(MediaStreamType.MP4, "mov")
                    else -> Kind(MediaStreamType.OTHER, subtype)
                }
            }

            mime.startsWith("audio/") -> Kind(MediaStreamType.AUDIO, mime.substringAfter('/'))
            else -> null
        }
    }

    private fun extensionOf(rawUrl: String): String? =
        rawUrl.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    private val HLS_MIME_TYPES = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )
}
