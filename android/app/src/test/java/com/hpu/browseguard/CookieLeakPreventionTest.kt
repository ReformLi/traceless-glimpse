package com.hpu.browseguard

import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.backend.ConsentCookieProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.HttpDownloader
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalSniffMapping
import com.hpu.browseguard.data.backend.PrepareRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.cookie.cookieHostOf
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeCookieVault
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cookie 隔离防泄漏（M6-5 要求 3）：授权之后，凭据只属于「手机 ↔ 站点」这一条线。
 *
 * 两个假服务器各站一边：`site` 是需要登录态的视频站（它**应该**收到 Cookie），
 * `engine` 是电脑引擎（一个字节都不该收到）。判定不靠「日志里没有」这种反证，
 * 而是把引擎侧收到的每一条请求连头带体摊开搜一遍。
 */
class CookieLeakPreventionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val engine = MockWebServer()
    private val site = MockWebServer()
    private lateinit var engineBase: String
    private lateinit var out: File

    private val cookieValue = "sid=good-cookie; token=also-secret"
    private val payload = "0123456789".repeat(10)

    @Before
    fun startServers() {
        engine.start()
        site.start()
        engineBase = engine.url("/").toString().trimEnd('/')
        out = folder.newFolder("download")
    }

    @After
    fun stopServers() {
        engine.close()
        site.close()
    }

    /** 页面响应带了 `Set-Cookie`：映射成 `SniffResult` 时它必须已经被丢掉。 */
    @Test
    fun theSetCookieHeaderNeverBecomesAHint() {
        val mediaUrl = site.url("/media/protected.mp4").toString()
        val pageUrl = site.url("/page-cookie.html").toString()

        val result = requireNotNull(
            LocalSniffMapping.toResult(
                rawUrl = mediaUrl,
                pageUrl = pageUrl,
                userAgent = "BgTest/1.0",
                requestHeaders = mapOf(
                    "Cookie" to cookieValue,
                    "Set-Cookie" to "sid=good-cookie",
                    "Referer" to pageUrl,
                ),
                discoveredAt = "2026-11-02T10:00:00Z",
            ),
        )

        assertEquals(pageUrl, result.headersHint["referer"])
        assertEquals("BgTest/1.0", result.headersHint["user-agent"])
        listOf("cookie", "set-cookie").forEach { name ->
            assertNull("$name 不许进 headersHint：${result.headersHint}", result.headersHint[name])
        }
    }

    /** 授权之后 Cookie 确实发出去了——但只发给站点。 */
    @Test
    fun theConsentedCookieGoesToTheSite() {
        val mediaUrl = site.url("/media/protected.mp4").toString()
        val consent = FakeCookieConsentStore()
        consent.grant(cookieHostOf(mediaUrl))
        val audit = FakeAuditLogger()
        val gated = ConsentCookieProvider(FakeCookieVault(mapOf(mediaUrl to cookieValue)), consent, audit)
        site.enqueue(MockResponse.Builder().code(200).body(payload).build())

        val events = runBlocking {
            LocalDirectDownloader(audit = audit, cookies = gated)
                .start(StartRequest.ViaLocal(link(mediaUrl), out.absolutePath, listOf(out.absolutePath)))
                .toList()
        }

        assertEquals(
            TaskStatus.DONE,
            (events.last() as DownloadEvent.Terminal).task.status,
        )
        assertEquals(cookieValue, site.takeRequest().headers["Cookie"])
        assertEquals("注入审计只报存在性", "present", audit.of("cookie_injected").single().fields["cookie"])
        assertEquals(payload, File(out, "protected.mp4").readText())
    }

    /**
     * 核心那条：同一次「带 Cookie 的本机嗅探结果」交给电脑引擎建任务、开下载，
     * 引擎侧收到的每一条请求——头与体——都搜不到 Cookie、Set-Cookie 或凭据值。
     */
    @Test
    fun theEngineFacingTrafficCarriesNoCookieAtAll() {
        val mediaUrl = site.url("/media/protected.mp4").toString()
        val pageUrl = site.url("/page-cookie.html").toString()
        val sniffed = requireNotNull(
            LocalSniffMapping.toResult(
                rawUrl = mediaUrl,
                pageUrl = pageUrl,
                userAgent = "BgTest/1.0",
                requestHeaders = mapOf("Cookie" to cookieValue, "Set-Cookie" to "sid=good-cookie"),
                discoveredAt = "2026-11-02T10:00:00Z",
            ),
        )

        engine.enqueue(engineResponse(201, taskJson("queued")))
        engine.enqueue(engineResponse(200, taskJson("awaiting_selection")))
        engine.enqueue(engineResponse(200, FORMATS_BODY))
        engine.enqueue(engineResponse(202, taskJson("downloading", withProgress = true)))

        runBlocking {
            HttpDownloader(engineBase)
                .prepare(
                    PrepareRequest(
                        sourceUrl = pageUrl,
                        cookiePolicy = CookiePolicy.LAZY,
                        outputDir = "/engine/out",
                        sniffResults = listOf(sniffed),
                    ),
                )
                .take(2)
                .toList()
            HttpDownloader(engineBase)
                .start(
                    StartRequest.ViaEngine(
                        taskId = "01a1",
                        formatSelector = FormatSelector(strategy = SelectionStrategy.BEST),
                        namingTemplate = "{title}.{ext}",
                        postActions = null,
                    ),
                )
                .take(1)
                .toList()
        }

        val requests = engine.takeAll()
        assertTrue("引擎侧至少要收到建任务、查状态、取画质、开下载四次，实际 ${requests.size}", requests.size >= 4)
        requests.forEach { request ->
            val body = request.body?.utf8().orEmpty()
            val traffic = "${request.method} ${request.url} ${request.headers} $body"
            assertNull("请求头里不许有 Cookie：$traffic", request.headers["Cookie"])
            assertNull(request.headers["Set-Cookie"])
            assertFalse("请求体里不许有 Cookie 键：$traffic", COOKIE_KEY.containsMatchIn(body))
            listOf("sid=good-cookie", "token=also-secret", cookieValue).forEach { secret ->
                assertFalse("引擎侧搜得到凭据值 $secret：$traffic", traffic.contains(secret))
            }
        }
    }

    /** 搜索本身必须真的能咬住：不然上面那条「搜不到」等于什么都没断。 */
    @Test
    fun theEngineSearchWouldHaveFoundACookie() {
        listOf(
            """{"Cookie":"sid=good-cookie"}""",
            """{"cookie":"sid=good-cookie"}""",
            """{"headers":{"Set-Cookie" : "sid=good-cookie"}}""",
        ).forEach { leaked ->
            assertTrue("伪造的泄漏载荷没被搜出来：$leaked", COOKIE_KEY.containsMatchIn(leaked))
            assertTrue("伪造的泄漏值没被搜出来：$leaked", leaked.contains("sid=good-cookie"))
        }
        assertFalse("协议字段 cookiePolicy 不算泄漏", COOKIE_KEY.containsMatchIn("""{"cookiePolicy":"lazy"}"""))
    }

    private fun link(url: String) = SniffResult(
        taskId = "local",
        mediaUrl = url,
        type = MediaStreamType.MP4,
        container = "mp4",
        estimatedQuality = EstimatedQuality(height = 720, bitrateKbps = 2500, label = "720p"),
        drmDetected = false,
        discoveredAt = "2026-11-02T10:00:00Z",
    )

    private fun taskJson(status: String, withProgress: Boolean = false): String = buildString {
        append("""{"id":"01a1","sourceUrl":"https://site.test/page","mode":"download","cookiePolicy":"lazy",""",
        )
        append(""""outputDir":"/engine/out","status":"$status",""")
        if (withProgress) {
            append(""""progress":{"phase":"downloading","bytesDownloaded":0,"bytesTotal":0,"speedBps":0,"etaSeconds":0,"percent":0.0},""")
        }
        append(""""createdAt":"2026-11-02T00:00:00Z","updatedAt":"2026-11-02T00:00:01Z"}""")
    }

    private fun engineResponse(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
        .body(body)
        .build()

    private fun MockWebServer.takeAll(): List<RecordedRequest> {
        val collected = mutableListOf<RecordedRequest>()
        while (true) {
            collected += takeRequest(1, TimeUnit.SECONDS) ?: break
        }
        return collected
    }

    private companion object {
        const val FORMATS_BODY = """{"taskId":"01a1","items":[{"formatId":"137+140","container":"mp4",""" +
            """"resolution":{"width":1920,"height":1080},"bitrateKbps":4500,""" +
            """"codec":{"video":"avc1.640028","audio":"mp4a.40.2"},"hasAudio":true,"hasVideo":true,""" +
            """"fileSizeEstimateBytes":524288000,"source":"yt-dlp"}]}"""

        /** `"cookie":` / `"Set-Cookie":` 这类键；`cookiePolicy` 是协议字段，不该被抓。 */
        val COOKIE_KEY = Regex(""""(?i)(set-)?cookie"\s*:""")
    }
}
