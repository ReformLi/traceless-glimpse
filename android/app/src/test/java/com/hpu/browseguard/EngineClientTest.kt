package com.hpu.browseguard

import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.data.TransportKind
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.PostActionType
import com.hpu.browseguard.shared.model.PreferCodec
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 用假引擎验证客户端的协议约束：请求头、错误信封、版本校验、不可达路径。 */
class EngineClientTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String
    private var serverAlive = false

    private val client = EngineClient(
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
    )

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        serverAlive = true
        baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun stopServer() {
        if (serverAlive) server.close()
    }

    private fun ok(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
        .body(body)
        .build()

    @Test
    fun healthSendsProtocolHeaderAndDecodesPayload() = runTest {
        server.enqueue(ok("""{"status":"ok","version":"0.1.0","protocolVersion":"0.2","uptimeSeconds":12}"""))

        val health = client.health(baseUrl)

        assertEquals("0.1.0", health.version)
        assertEquals("0.2", health.protocolVersion)
        val recorded = server.takeRequest()
        assertEquals("0.2", recorded.headers[EngineClient.PROTOCOL_HEADER])
        assertEquals("/v1/health", recorded.url.encodedPath)
    }

    @Test
    fun createTaskPostsStructuredBody() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(201)
                .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
                .body(
                    """
                    {"id":"01a1","sourceUrl":"https://example.com/v","mode":"sniff","cookiePolicy":"lazy",
                     "outputDir":"/tmp/bgv","status":"queued","createdAt":"2026-11-01T00:00:00Z",
                     "updatedAt":"2026-11-01T00:00:00Z"}
                    """.trimIndent(),
                )
                .build(),
        )

        val task = client.createTask(
            baseUrl,
            TaskCreateRequest(
                sourceUrl = "https://example.com/v",
                mode = TaskMode.SNIFF,
                cookiePolicy = CookiePolicy.LAZY,
                outputDir = "/tmp/bgv",
            ),
        )

        assertEquals("queued", task.status.name.lowercase())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/tasks", recorded.url.encodedPath)
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"mode\":\"sniff\""))
    }

    @Test
    fun errorEnvelopeBecomesProtocolFailure() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
                .body(
                    """{"error":{"code":"SSRF_BLOCKED","category":"security","retryable":false,
                       "message":"目标地址指向受限网络，已拒绝"}}""",
                )
                .build(),
        )

        try {
            client.createTask(
                baseUrl,
                TaskCreateRequest(
                    sourceUrl = "http://127.0.0.1/admin",
                    mode = TaskMode.SNIFF,
                    cookiePolicy = CookiePolicy.NONE,
                    outputDir = "/tmp/bgv",
                ),
            )
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            val failure = e.failure
            assertTrue(failure is EngineFailure.Protocol)
            failure as EngineFailure.Protocol
            assertEquals(400, failure.httpStatus)
            assertEquals(ErrorCode.SSRF_BLOCKED, failure.error.code)
        }
    }

    @Test
    fun missingProtocolHeaderIsTransportFailure() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())

        try {
            client.health(baseUrl)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertTrue((e.failure as EngineFailure.Transport).reason.contains("协议版本不符"))
        }
    }

    @Test
    fun nonProtocolBodyIsTransportFailure() = runTest {
        server.enqueue(ok("<html>gateway</html>"))

        try {
            client.health(baseUrl)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            assertTrue((e.failure as EngineFailure.Transport).reason.contains("不符合协议"))
        }
    }

    @Test
    fun formatsReadsTaskScopedEnvelope() = runTest {
        server.enqueue(
            ok(
                """
                {"taskId":"01a1","items":[
                  {"formatId":"137+140","container":"mp4","resolution":{"width":1920,"height":1080},
                   "bitrateKbps":4500,"codec":{"video":"avc1.640028","audio":"mp4a.40.2"},
                   "hasAudio":true,"hasVideo":true,"fileSizeEstimateBytes":524288000,"source":"yt-dlp"}
                ]}
                """.trimIndent(),
            ),
        )

        val formats = client.formats(baseUrl, "01a1")

        assertEquals(1, formats.size)
        assertEquals("137+140", formats[0].formatId)
        assertEquals(1080, formats[0].resolution.height)
        assertEquals("/v1/tasks/01a1/formats", server.takeRequest().url.encodedPath)
    }

    @Test
    fun startDownloadSendsSelectorAndReadsAcceptedTask() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(202)
                .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
                .body(
                    """
                    {"id":"01a1","sourceUrl":"https://example.com/v","mode":"download","cookiePolicy":"lazy",
                     "outputDir":"/tmp/bgv","status":"downloading",
                     "progress":{"phase":"downloading","bytesDownloaded":0,"bytesTotal":0,"speedBps":0,"etaSeconds":0,"percent":0.0},
                     "createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-01T00:00:01Z"}
                    """.trimIndent(),
                )
                .build(),
        )

        val task = client.startDownload(
            baseUrl,
            "01a1",
            DownloadRequest(
                formatSelector = FormatSelector(
                    strategy = SelectionStrategy.MAX_HEIGHT,
                    maxHeight = 1080,
                    preferCodec = PreferCodec.H264,
                ),
                namingTemplate = "{title}-{height}p",
                postActions = listOf(PostAction(type = PostActionType.MERGE)),
            ),
        )

        assertEquals(TaskStatus.DOWNLOADING, task.status)
        assertEquals(0.0, task.progress?.percent ?: -1.0, 0.0)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/tasks/01a1/download", recorded.url.encodedPath)
        val body = recorded.body?.utf8().orEmpty()
        assertTrue(body.contains("\"strategy\":\"max_height\""))
        assertTrue(body.contains("\"type\":\"merge\""))
        assertTrue(body.contains("\"namingTemplate\":\"{title}-{height}p\""))
        assertFalse(
            "下载请求不得携带凭据或请求头字段",
            listOf("cookieFile", "cookiePath", "browser", "userAgent", "\"headers\"").any { body.contains(it) },
        )
    }

    @Test
    fun cancelTaskSendsBodylessPost() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
                .body(
                    """
                    {"id":"01a1","sourceUrl":"https://example.com/v","mode":"download","cookiePolicy":"lazy",
                     "outputDir":"/tmp/bgv","status":"cancelled","createdAt":"2026-11-01T00:00:00Z",
                     "updatedAt":"2026-11-01T00:00:05Z"}
                    """.trimIndent(),
                )
                .build(),
        )

        val task = client.cancelTask(baseUrl, "01a1")

        assertEquals(TaskStatus.CANCELLED, task.status)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/tasks/01a1/cancel", recorded.url.encodedPath)
        assertTrue("取消请求不应带请求体", recorded.body?.utf8().isNullOrEmpty())
    }

    @Test
    fun stateConflictOnDownloadIsProtocolFailure() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(409)
                .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
                .body(
                    """{"error":{"code":"TASK_STATE_CONFLICT","category":"internal","retryable":false,
                       "message":"任务当前状态不允许该操作"}}""",
                )
                .build(),
        )

        try {
            client.startDownload(
                baseUrl,
                "01a1",
                DownloadRequest(formatSelector = FormatSelector(strategy = SelectionStrategy.BEST)),
            )
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            val failure = e.failure as EngineFailure.Protocol
            assertEquals(409, failure.httpStatus)
            assertEquals(ErrorCode.TASK_STATE_CONFLICT, failure.error.code)
        }
    }

    @Test
    fun unreachableEngineIsTransportFailure() = runTest {
        val dead = baseUrl
        server.close()
        serverAlive = false

        try {
            client.health(dead)
            fail("应抛 EngineException")
        } catch (e: EngineException) {
            val failure = e.failure as EngineFailure.Transport
            assertTrue(failure.reason.startsWith("无法连接引擎"))
            assertEquals(
                "连接拒绝要能和协议不符分开，界面才敢自动降级",
                TransportKind.UNREACHABLE,
                failure.kind,
            )
        }
    }
}
