package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebView 拦截到的请求 → 本机可下载的 [SniffResult]。
 *
 * 这一层不碰 Android WebView API，JVM 单测可以直接钉住三件事：
 * 只认完整单文件（切片/清单不算一条可下载结果）、头在进入 `headersHint` 之前就过滤、
 * Referer / UA 在缺失时用页面侧捕获的值合成。
 */
internal object LocalSniffMapping {

    /** WebView 会话没有引擎任务号；界面不拿它发请求，只要求非空。 */
    const val SESSION_TASK_ID = "local_webview"

    /**
     * 只收单文件直链。HLS/DASH 的清单与切片交给本地下载器只会下出一个残片，
     * 所以 `.m3u8` / `.mpd` / `.ts` / `.m4s` 一律不产生结果——宁可不给，也不给假行。
     */
    private val VIDEO_EXTENSIONS = mapOf(
        "mp4" to MediaStreamType.MP4,
        "m4v" to MediaStreamType.MP4,
        "mov" to MediaStreamType.MP4,
        "mkv" to MediaStreamType.OTHER,
        "webm" to MediaStreamType.OTHER,
    )

    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "oga", "opus", "wav", "flac")

    fun directExtension(rawUrl: String): String? {
        val ext = extensionOf(rawUrl) ?: return null
        return ext.takeIf { it in VIDEO_EXTENSIONS || it in AUDIO_EXTENSIONS }
    }

    /** 去重键：同一 URL 的多次 Range 请求只留一行；fragment 与签名无关，丢掉。 */
    fun dedupeKey(rawUrl: String): String? =
        rawUrl.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()

    fun toResult(
        rawUrl: String,
        pageUrl: String?,
        userAgent: String,
        requestHeaders: Map<String, String>,
        discoveredAt: String,
    ): SniffResult? {
        val url = rawUrl.toHttpUrlOrNull() ?: return null
        val ext = directExtension(rawUrl) ?: return null
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
            type = VIDEO_EXTENSIONS[ext] ?: MediaStreamType.AUDIO,
            container = if (ext == "oga") "ogg" else ext,
            estimatedQuality = EstimatedQuality(height = 0, bitrateKbps = 0, label = "未知画质"),
            drmDetected = false,
            headersHint = hints,
            discoveredAt = discoveredAt,
        )
    }

    private fun extensionOf(rawUrl: String): String? =
        rawUrl.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
}
