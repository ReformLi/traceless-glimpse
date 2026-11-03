package com.hpu.browseguard

import com.hpu.browseguard.data.backend.LocalSniffMapping
import com.hpu.browseguard.shared.model.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 请求 → `SniffResult` 的纯逻辑（JVM 可测）。
 *
 * 真机只负责证明「能拦到请求」；这里负责证明拦到之后**不会**把 Range/Cookie
 * 带进下载、不会把 HLS 切片当成一条可下载结果、不会丢掉签名查询串。
 */
class LocalSniffMappingTest {

    @Test
    fun directFilesBecomeOneDownloadableResult() {
        val result = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/v/clip.mp4?token=abc",
            pageUrl = "https://example.com/watch",
            userAgent = "BGV-UA",
            requestHeaders = emptyMap(),
            discoveredAt = NOW,
        )!!

        assertEquals("https://cdn.example.com/v/clip.mp4?token=abc", result.mediaUrl)
        assertEquals(MediaStreamType.MP4, result.type)
        assertEquals("mp4", result.container)
        assertFalse("WebView 这一层不做 DRM 推断", result.drmDetected)
        assertEquals(LocalSniffMapping.SESSION_TASK_ID, result.taskId)
    }

    /** HLS 清单是一条真结果：本地下载器会逐段拉取，不再下出一个 96 字节的假文件。 */
    @Test
    fun hlsMasterPlaylistIsADownloadableResult() {
        val result = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/hls/master.m3u8?token=abc",
            pageUrl = "https://example.com/watch",
            userAgent = "BGV-UA",
            requestHeaders = emptyMap(),
            discoveredAt = NOW,
        )!!

        assertEquals(MediaStreamType.HLS, result.type)
        assertEquals("m3u8", result.container)
        assertEquals("https://example.com/watch", result.headersHint["referer"])
    }

    /** 扩展名给不出答案时，Content-Type 可以兜底；切片与 DASH 仍然拒绝。 */
    @Test
    fun contentTypeCanIdentifyAnExtensionlessPlaylistButNeverASegment() {
        val playlist = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/play?id=42",
            pageUrl = "https://example.com/watch",
            userAgent = "BGV-UA",
            requestHeaders = emptyMap(),
            discoveredAt = NOW,
            contentType = "application/vnd.apple.mpegurl; charset=utf-8",
        )!!
        assertEquals(MediaStreamType.HLS, playlist.type)

        val mp4 = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/stream/abc",
            pageUrl = null,
            userAgent = "",
            requestHeaders = emptyMap(),
            discoveredAt = NOW,
            contentType = "video/mp4",
        )!!
        assertEquals(MediaStreamType.MP4, mp4.type)
        assertEquals("mp4", mp4.container)

        listOf(
            "https://cdn.example.com/seg0.ts",
            "https://cdn.example.com/chunk-1.m4s",
            "https://cdn.example.com/manifest.mpd",
            "https://cdn.example.com/evil.exe",
            "https://cdn.example.com/v/videoplayback",
        ).forEach { url ->
            assertNull("不该成为候选：$url", LocalSniffMapping.toResult(url, null, "", emptyMap(), NOW))
        }
        assertNull(
            "video/mp2t 是切片，不是一条可下载结果",
            LocalSniffMapping.toResult(
                "https://cdn.example.com/play?id=42",
                null,
                "",
                emptyMap(),
                NOW,
                contentType = "video/mp2t",
            ),
        )
    }

    /**
     * 进入 `headersHint` 之前就过滤：下载侧再过滤一次，但这里不能先漏出去。
     *
     * 白名单裁决（§25.6）：只放 `referer` / `user-agent`；浏览器的
     * `accept` / `accept-encoding` 也不再跟随，避免站点分流与压缩流。
     */
    @Test
    fun onlyRefererAndUserAgentReachHeadersHint() {
        val result = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/v/clip.mp4",
            pageUrl = "https://example.com/watch",
            userAgent = "BGV-UA",
            requestHeaders = mapOf(
                "Range" to "bytes=0-0",
                "Cookie" to "sid=secret",
                "Authorization" to "Bearer leaked",
                "Accept" to "application/json",
                "Accept-Encoding" to "gzip, br",
                "Referer" to "https://ref.example/page",
                "User-Agent" to "Browser-UA",
            ),
            discoveredAt = NOW,
        )!!

        assertEquals(
            "headersHint 只允许白名单两项",
            setOf("referer", "user-agent"),
            result.headersHint.keys,
        )
        assertEquals("https://ref.example/page", result.headersHint["referer"])
        assertEquals("Browser-UA", result.headersHint["user-agent"])
    }

    /** WebView 拦截时 Referer/UA 常常不在 `requestHeaders` 里：用页面侧捕获的值补上。 */
    @Test
    fun refererAndUserAgentAreSynthesizedWhenTheBrowserDidNotSendThem() {
        val result = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/v/clip.mp4",
            pageUrl = "https://example.com/watch?ep=3",
            userAgent = "Captured-UA",
            requestHeaders = emptyMap(),
            discoveredAt = NOW,
        )!!

        assertEquals("https://example.com/watch?ep=3", result.headersHint["referer"])
        assertEquals("Captured-UA", result.headersHint["user-agent"])
    }

    /** 签名直链靠查询串，去重键与结果都必须保留；fragment 与请求无关。 */
    @Test
    fun signatureQueryIsKeptAndFragmentIsDropped() {
        val result = LocalSniffMapping.toResult(
            rawUrl = "https://cdn.example.com/v/clip.mp4?token=abc#t=10",
            pageUrl = null,
            userAgent = "",
            requestHeaders = emptyMap(),
            discoveredAt = NOW,
        )!!

        assertEquals("https://cdn.example.com/v/clip.mp4?token=abc", result.mediaUrl)
        assertEquals(
            "https://cdn.example.com/v/clip.mp4?token=abc",
            LocalSniffMapping.dedupeKey("https://cdn.example.com/v/clip.mp4?token=abc#t=10"),
        )
    }

    /** 同一路径不同 Range 只产出一条结果：去重键只认 URL，不认请求头。 */
    @Test
    fun theSameUrlIsDedupedRegardlessOfRangeHeaders() {
        val keyA = LocalSniffMapping.dedupeKey("https://cdn.example.com/v/clip.mp4?token=abc")
        val keyB = LocalSniffMapping.dedupeKey("https://cdn.example.com/v/clip.mp4?token=abc#t=1")
        assertEquals(keyA, keyB)
        assertTrue(keyA!!.endsWith("token=abc"))
    }

    private companion object {
        const val NOW = "2026-11-02T10:00:00Z"
    }
}
