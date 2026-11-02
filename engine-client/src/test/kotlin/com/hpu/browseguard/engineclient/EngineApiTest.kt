package com.hpu.browseguard.engineclient

import com.hpu.browseguard.shared.Protocol
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class EngineApiTest {

    private lateinit var server: TestHttpServer
    private lateinit var api: EngineApi

    @Before
    fun setUp() {
        server = TestHttpServer()
        api = EngineApi(server.baseUrl)
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun healthSendsVersionHeaderAndDecodes() = runBlocking {
        server.handler = {
            StubResponse(body = """{"status":"ok","version":"0.1.0","protocolVersion":"0.2","uptimeSeconds":9}""")
        }

        val health = api.health()

        assertEquals("0.2", health.protocolVersion)
        assertEquals(9L, health.uptimeSeconds)
        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/health", request.uri)
        assertEquals("0.2", request.headers[Protocol.PROTOCOL_HEADER.lowercase()])
    }

    @Test
    fun createTaskPostsGuardedJson() = runBlocking {
        server.handler = { StubResponse(status = 201, body = taskJson("t-1", status = "queued")) }

        val task = api.createTask(
            TaskCreateRequest(
                sourceUrl = "https://example.com/watch",
                mode = TaskMode.SNIFF,
                cookiePolicy = CookiePolicy.LAZY,
                outputDir = "/out",
            ),
        )

        assertEquals("t-1", task.id)
        assertEquals(TaskStatus.QUEUED, task.status)
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/tasks", request.uri)
        assertTrue(request.body.contains("\"cookiePolicy\":\"lazy\""))
        assertFalse(Regex("\"(?i:cookie)\"\\s*:").containsMatchIn(request.body))
    }

    @Test
    fun listTasksForwardsPaginationAndDecodesNullCursor() = runBlocking {
        server.handler = { StubResponse(body = """{"items":[${taskJson("t-1")}],"nextCursor":null}""") }

        val page = api.listTasks(limit = 10, cursor = "a b/c")

        assertEquals(1, page.items.size)
        assertNull(page.nextCursor)
        assertEquals("/v1/tasks?limit=10&cursor=a%20b%2Fc", server.requests.single().uri)
    }

    @Test
    fun taskDecodesOutputPathAndEncodesId() = runBlocking {
        server.handler = { StubResponse(body = taskJson("t 1", outputPath = "/out/a.mp4")) }

        val task = api.task("t 1")

        assertEquals("/out/a.mp4", task.outputPath)
        assertEquals("/v1/tasks/t%201", server.requests.single().uri)
    }

    @Test
    fun sniffResultsAndFormatsDecodeEnvelopes() = runBlocking {
        server.handler = { request ->
            when {
                request.uri.endsWith("/sniff-results") -> StubResponse(
                    body = """
                        {"items":[{
                          "taskId":"t-1",
                          "mediaUrl":"https://cdn.example.com/a.m3u8",
                          "type":"hls",
                          "container":"ts",
                          "estimatedQuality":{"height":1080,"bitrateKbps":4200,"label":"1080p"},
                          "drmDetected":false,
                          "discoveredAt":"2026-11-02T00:00:00Z"
                        }]}
                    """.trimIndent(),
                )
                else -> StubResponse(
                    body = """
                        {"taskId":"t-1","items":[{
                          "formatId":"137",
                          "container":"mp4",
                          "resolution":{"width":1920,"height":1080},
                          "bitrateKbps":4200,
                          "codec":{"video":"avc1","audio":"none"},
                          "hasAudio":false,
                          "hasVideo":true,
                          "fileSizeEstimateBytes":123456,
                          "source":"manifest"
                        }]}
                    """.trimIndent(),
                )
            }
        }

        val sniff = api.sniffResults("t-1")
        val formats = api.formats("t-1")

        assertEquals("https://cdn.example.com/a.m3u8", sniff.single().mediaUrl)
        assertEquals("137", formats.single().formatId)
        assertEquals(
            listOf("/v1/tasks/t-1/sniff-results", "/v1/tasks/t-1/formats"),
            server.requests.map { it.uri },
        )
    }

    @Test
    fun downloadAndCancelHitExpectedRoutes() = runBlocking {
        server.handler = { request ->
            val status = if (request.uri.endsWith("/download")) "downloading" else "cancelled"
            StubResponse(body = taskJson("t-1", status = status))
        }

        val downloading = api.startDownload(
            "t-1",
            DownloadRequest(formatSelector = FormatSelector(strategy = SelectionStrategy.BEST)),
        )
        val cancelled = api.cancelTask("t-1")

        assertEquals(TaskStatus.DOWNLOADING, downloading.status)
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(
            listOf("POST" to "/v1/tasks/t-1/download", "POST" to "/v1/tasks/t-1/cancel"),
            server.requests.map { it.method to it.uri },
        )
        assertTrue(server.requests.first().body.contains("\"strategy\":\"best\""))
    }

    @Test
    fun protocolVersionMismatchIsTransportFailure() = runBlocking {
        server.handler = { StubResponse(body = "{}", protocolVersion = "0.1") }

        val failure = assertFailsWith<EngineException> { api.health() }.failure
        val transport = failure as EngineFailure.Transport

        assertEquals(TransportKind.OTHER, transport.kind)
        assertTrue(transport.reason.contains("协议版本不符"))
    }

    @Test
    fun missingProtocolHeaderIsTransportFailure() = runBlocking {
        server.handler = { StubResponse(body = "{}", protocolVersion = null) }

        val failure = assertFailsWith<EngineException> { api.health() }.failure
        val transport = failure as EngineFailure.Transport

        assertEquals(TransportKind.OTHER, transport.kind)
        assertTrue(transport.reason.contains("缺失"))
    }

    @Test
    fun errorEnvelopeBecomesProtocolFailure() = runBlocking {
        server.handler = {
            StubResponse(
                status = 409,
                body = """
                    {"error":{"code":"TASK_STATE_CONFLICT","category":"internal",
                     "message":"任务当前状态不允许该操作","retryable":false}}
                """.trimIndent(),
            )
        }

        val failure = assertFailsWith<EngineException> { api.cancelTask("t-1") }.failure
        val protocol = failure as EngineFailure.Protocol

        assertEquals(409, protocol.httpStatus)
        assertEquals(ErrorCode.TASK_STATE_CONFLICT, protocol.error.code)
    }

    @Test
    fun nonProtocolErrorBodyIsTransportFailure() = runBlocking {
        server.handler = { StubResponse(status = 500, body = "oops") }

        val failure = assertFailsWith<EngineException> { api.health() }.failure
        val transport = failure as EngineFailure.Transport

        assertEquals(TransportKind.OTHER, transport.kind)
        assertTrue(transport.reason.contains("HTTP 500"))
    }

    @Test
    fun malformedSuccessBodyIsTransportFailure() = runBlocking {
        server.handler = { StubResponse(body = """{"status":"ok"}""") }

        val failure = assertFailsWith<EngineException> { api.health() }.failure
        val transport = failure as EngineFailure.Transport

        assertEquals(TransportKind.OTHER, transport.kind)
        assertTrue(transport.reason.contains("不符合协议"))
    }

    @Test
    fun closedPortIsUnreachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val offline = EngineApi("http://127.0.0.1:$port")

        val failure = assertFailsWith<EngineException> { offline.health() }.failure
        val transport = failure as EngineFailure.Transport

        assertEquals(TransportKind.UNREACHABLE, transport.kind)
        assertTrue(transport.reason.contains("无法连接引擎"))
    }

    private fun taskJson(
        id: String,
        status: String = "done",
        outputPath: String? = null,
    ): String {
        val path = if (outputPath == null) "" else ",\"outputPath\":\"$outputPath\""
        return "{\"id\":\"$id\",\"sourceUrl\":\"https://example.com/watch\",\"mode\":\"download\"," +
            "\"cookiePolicy\":\"none\",\"outputDir\":\"/out\",\"status\":\"$status\"," +
            "\"createdAt\":\"2026-11-02T00:00:00Z\",\"updatedAt\":\"2026-11-02T00:00:01Z\"$path}"
    }
}
