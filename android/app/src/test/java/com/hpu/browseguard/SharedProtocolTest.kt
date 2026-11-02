package com.hpu.browseguard

import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorEnvelope
import com.hpu.browseguard.shared.model.FormatsResponse
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResultsResponse
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** 证明 app 单元测试可直接消费 :shared 的协议模型。 */
class SharedProtocolTest {
    @Test
    fun decodesTaskPayloadFromEngine() {
        val payload = """
            {
              "id": "01a19243-74e6-7fc4-afb2-aed49cf6cbe2",
              "sourceUrl": "https://example.com/watch?v=abc",
              "mode": "download",
              "cookiePolicy": "lazy",
              "outputDir": "/home/user/Videos/BGV",
              "status": "queued",
              "createdAt": "2026-10-31T00:00:00Z",
              "updatedAt": "2026-10-31T00:00:00Z"
            }
        """.trimIndent()

        val task = BgvJson.decodeFromString<Task>(payload)

        assertEquals(TaskMode.DOWNLOAD, task.mode)
        assertEquals(CookiePolicy.LAZY, task.cookiePolicy)
        assertEquals(TaskStatus.QUEUED, task.status)
    }

    /** PROTOCOL §1.1：客户端在协议层无法指定凭据来源或注入请求头。 */
    @Test
    fun createTaskPayloadCarriesNoCredentialOrHeaderFields() {
        val json = BgvJson.encodeToString(
            TaskCreateRequest(
                sourceUrl = "https://example.com/watch?v=abc",
                mode = TaskMode.SNIFF,
                cookiePolicy = CookiePolicy.LAZY,
                outputDir = "/home/user/Videos/BGV",
            ),
        )

        for (forbidden in listOf("cookieFile", "cookiePath", "browser", "userAgent", "headers")) {
            assertFalse("$forbidden 不应出现在请求体：$json", json.contains(forbidden))
        }
        assertFalse("等于默认值的字段不输出：$json", json.contains("postActions"))
        assertEquals(
            BgvJson.decodeFromString(TaskCreateRequest.serializer(), json),
            TaskCreateRequest(
                sourceUrl = "https://example.com/watch?v=abc",
                mode = TaskMode.SNIFF,
                cookiePolicy = CookiePolicy.LAZY,
                outputDir = "/home/user/Videos/BGV",
            ),
        )
    }

    @Test
    fun taskPayloadRejectsUnknownField() {
        val payload = """
            {
              "id": "01a19243", "sourceUrl": "https://example.com/v", "mode": "sniff",
              "cookiePolicy": "lazy", "outputDir": "/tmp", "status": "queued",
              "createdAt": "2026-10-31T00:00:00Z", "updatedAt": "2026-10-31T00:00:00Z",
              "cookieFile": "/home/user/.config/browser/Cookies"
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            BgvJson.decodeFromString(Task.serializer(), payload)
        }
    }

    /** §6.6 + §6.5 样例逐字解码。 */
    @Test
    fun decodesHealthAndSelfcheck() {
        val health = BgvJson.decodeFromString(
            HealthResponse.serializer(),
            """{ "status": "ok", "version": "0.1.0", "protocolVersion": "0.2", "uptimeSeconds": 3600 }""",
        )
        assertEquals("0.1.0", health.version)
        assertEquals("0.2", health.protocolVersion)
        assertEquals(3600L, health.uptimeSeconds)

        val selfcheck = BgvJson.decodeFromString(
            SelfcheckResponse.serializer(),
            """
            {
              "listenAddress": "127.0.0.1:8730",
              "tlsEnabled": false,
              "authEnabled": false,
              "activeProxyIds": [],
              "cookiePolicyDefault": "lazy",
              "outputDirAllowlist": ["/home/user/Videos/BGV"],
              "cacheDir": "/home/user/.cache/bgv",
              "cacheTtlHours": 48,
              "cacheSizeBytes": 0,
              "lastCleanupAt": null,
              "dependencyVersions": { "yt-dlp": "2026.10.01", "ffmpeg": "7.1", "playwright": "1.50.0" }
            }
            """.trimIndent(),
        )
        assertEquals(CookiePolicy.LAZY, selfcheck.cookiePolicyDefault)
        assertEquals(listOf("/home/user/Videos/BGV"), selfcheck.outputDirAllowlist)
        assertEquals("2026.10.01", selfcheck.dependencyVersions["yt-dlp"])
    }

    /** §3.2 / §3.3 样例：嗅探结果与格式列表。 */
    @Test
    fun decodesSniffAndFormatEnvelopes() {
        val sniff = BgvJson.decodeFromString(
            SniffResultsResponse.serializer(),
            """
            {
              "items": [{
                "taskId": "0192f3a4",
                "mediaUrl": "https://cdn.example.com/hls/master.m3u8",
                "type": "hls",
                "container": "m3u8",
                "estimatedQuality": { "height": 1080, "bitrateKbps": 4500, "label": "1080p" },
                "drmDetected": false,
                "drmSystem": null,
                "headersHint": { "referer": "https://example.com/" },
                "discoveredAt": "2026-10-30T08:00:05Z"
              }]
            }
            """.trimIndent(),
        )
        assertEquals(1, sniff.items.size)
        assertEquals("1080p", sniff.items[0].estimatedQuality.label)
        assertFalse(sniff.items[0].drmDetected)

        val formats = BgvJson.decodeFromString(
            FormatsResponse.serializer(),
            """
            {
              "taskId": "0192f3a4",
              "items": [{
                "formatId": "137+140",
                "container": "mp4",
                "resolution": { "width": 1920, "height": 1080 },
                "bitrateKbps": 4500,
                "codec": { "video": "avc1.640028", "audio": "mp4a.40.2" },
                "hasAudio": true,
                "hasVideo": true,
                "fileSizeEstimateBytes": 524288000,
                "source": "yt-dlp"
              }]
            }
            """.trimIndent(),
        )
        assertEquals("137+140", formats.items[0].formatId)
    }

    /** §9.2：安全拒绝样例。 */
    @Test
    fun decodesSsrErrorEnvelope() {
        val envelope = BgvJson.decodeFromString(
            ErrorEnvelope.serializer(),
            """
            { "error": { "code": "SSRF_BLOCKED", "category": "security", "retryable": false,
                         "message": "目标地址指向受限网络，已拒绝", "detail": { "host": "127.0.0.1" } } }
            """.trimIndent(),
        )
        assertEquals(ErrorCode.SSRF_BLOCKED, envelope.error.code)
        assertFalse(envelope.error.retryable)
    }
}
