package com.hpu.browseguard.mcp

import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test

class McpToolsetTest {

    private lateinit var engine: MockEngine
    private lateinit var toolset: McpToolset

    private val config = McpConfig(
        pollIntervalMs = 1,
        sniffTimeoutMs = 2_000,
        downloadTimeoutMs = 2_000,
    )

    @Before
    fun setUp() {
        engine = MockEngine()
        toolset = McpToolset(config)
    }

    @After
    fun tearDown() = engine.close()

    private fun CallToolResult.json(): JsonObject =
        BgvJson.parseToJsonElement((content.single() as TextContent).text).jsonObject

    private fun string(value: Any): String = value.toString()

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is Int -> put(key, value)
                else -> put(key, string(value))
            }
        }
    }

    private suspend fun sniffTaskId(): String {
        val result = toolset.sniff(args("url" to "https://example.com/watch", "engineUrl" to engine.baseUrl))
        assertFalse(result.isError == true, result.json().toString())
        return result.json()["taskId"]!!.jsonPrimitive.content
    }

    @Test
    fun sniffCreatesDownloadTaskWithoutSelectorAndReturnsFormats() = runBlocking {
        val result = toolset.sniff(
            args("url" to " https://example.com/watch ", "engineUrl" to engine.baseUrl),
        )

        assertEquals(false, result.isError)
        val output = result.json()
        assertEquals("awaiting_selection", output["task"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(2, output["formats"]!!.jsonObject["items"]!!.jsonArray.size)
        assertEquals(1, output["sniffResults"]!!.jsonObject["items"]!!.jsonArray.size)

        val create = engine.requests.single { it.method == "POST" && it.path == "/v1/tasks" }
        val request = BgvJson.decodeFromString(TaskCreateRequest.serializer(), create.body)
        assertEquals("https://example.com/watch", request.sourceUrl)
        assertEquals(TaskMode.DOWNLOAD, request.mode)
        assertNull(request.formatSelector)
        assertEquals("/tmp/bgv-out", request.outputDir)
    }

    @Test
    fun probeReturnsProtocolFormatTable() = runBlocking {
        val taskId = sniffTaskId()

        val result = toolset.probe(args("taskId" to taskId, "engineUrl" to engine.baseUrl))

        assertEquals(false, result.isError)
        val output = result.json()
        assertEquals(taskId, output["taskId"]!!.jsonPrimitive.content)
        assertEquals(2, output["items"]!!.jsonArray.size)
    }

    @Test
    fun downloadPollsUntilDoneAndReturnsFinalTask() = runBlocking {
        val taskId = sniffTaskId()

        val result = toolset.download(
            args("taskId" to taskId, "format" to "max_height=720", "engineUrl" to engine.baseUrl),
        )

        assertEquals(false, result.isError)
        val task = BgvJson.decodeFromString(Task.serializer(), result.json().toString())
        assertEquals(TaskStatus.DONE, task.status)
        assertNotNull(task.outputPath)

        val start = engine.requests.single { it.method == "POST" && it.path.endsWith("/download") }
        val request = BgvJson.decodeFromString(DownloadRequest.serializer(), start.body)
        assertEquals(
            FormatSelector(SelectionStrategy.MAX_HEIGHT, maxHeight = 720),
            request.formatSelector,
        )
    }

    @Test
    fun downloadDrmReturnsRawProtocolErrorPayload() = runBlocking {
        engine.drm = true
        val taskId = sniffTaskId()

        val result = toolset.download(args("taskId" to taskId, "engineUrl" to engine.baseUrl))

        assertEquals(true, result.isError)
        val text = (result.content.single() as TextContent).text
        val error = BgvJson.decodeFromString(ErrorPayload.serializer(), text)
        assertEquals(ErrorCode.DRM_PROTECTED, error.code)
        assertEquals("content", BgvJson.parseToJsonElement(text).jsonObject["category"]!!.jsonPrimitive.content)
        assertFalse(text.contains("localError"))
    }

    @Test
    fun downloadTimeoutIsLocalCode() = runBlocking {
        val taskId = sniffTaskId()
        engine.stallDownloading = true

        val result = toolset.download(
            args("taskId" to taskId, "timeoutSeconds" to 1, "engineUrl" to engine.baseUrl),
        )

        assertEquals(true, result.isError)
        assertEquals("download_timeout", result.json()["localError"]!!.jsonPrimitive.content)
    }

    @Test
    fun sniffTimeoutIsLocalCode() = runBlocking {
        engine.stallSniffing = true
        val fastTimeout = McpToolset(config.copy(sniffTimeoutMs = 100))

        val result = fastTimeout.sniff(args("url" to "https://example.com/watch", "engineUrl" to engine.baseUrl))

        assertEquals(true, result.isError)
        assertEquals("sniff_timeout", result.json()["localError"]!!.jsonPrimitive.content)
    }

    @Test
    fun unreachableReturnsOnlyLocalErrorWithoutProtocolCode() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = toolset.status(
            args("taskId" to "t-1", "engineUrl" to "http://127.0.0.1:$closedPort"),
        )

        assertEquals(true, result.isError)
        val text = (result.content.single() as TextContent).text
        assertEquals("""{"localError":"engine_unreachable"}""", text)
        assertFalse(text.contains("NETWORK_TIMEOUT"))
    }

    @Test
    fun protocolVersionMismatchIsLocalNotEngineCode() = runBlocking {
        engine.protocolVersion = "9.9"

        val result = toolset.status(args("taskId" to "t-1", "engineUrl" to engine.baseUrl))

        assertEquals(true, result.isError)
        val text = (result.content.single() as TextContent).text
        assertEquals("engine_protocol_mismatch", result.json()["localError"]!!.jsonPrimitive.content)
        assertFalse(text.contains("NETWORK_TIMEOUT"))
    }

    @Test
    fun failedTaskReturnsEngineErrorPayload() = runBlocking {
        val taskId = sniffTaskId()
        engine.failDownloadWith = ErrorCode.DISK_FULL

        val result = toolset.download(args("taskId" to taskId, "engineUrl" to engine.baseUrl))

        assertEquals(true, result.isError)
        val error = BgvJson.decodeFromString(ErrorPayload.serializer(), (result.content.single() as TextContent).text)
        assertEquals(ErrorCode.DISK_FULL, error.code)
        assertEquals("storage", BgvJson.parseToJsonElement(
            (result.content.single() as TextContent).text,
        ).jsonObject["category"]!!.jsonPrimitive.content)
    }

    @Test
    fun cancelledDownloadReturnsLocalTaskCancelled() = runBlocking {
        val taskId = sniffTaskId()
        engine.cancelDuringDownload = true

        val result = toolset.download(args("taskId" to taskId, "engineUrl" to engine.baseUrl))

        assertEquals(true, result.isError)
        assertEquals("task_cancelled", result.json()["localError"]!!.jsonPrimitive.content)
    }

    @Test
    fun statusAndCancelReturnTaskJson() = runBlocking {
        val taskId = sniffTaskId()

        val status = toolset.status(args("taskId" to taskId, "engineUrl" to engine.baseUrl))
        assertEquals(false, status.isError)
        assertEquals(TaskStatus.AWAITING_SELECTION, BgvJson.decodeFromString(Task.serializer(), status.json().toString()).status)

        val cancelled = toolset.cancel(args("taskId" to taskId, "engineUrl" to engine.baseUrl))
        assertEquals(false, cancelled.isError)
        assertEquals(TaskStatus.CANCELLED, BgvJson.decodeFromString(Task.serializer(), cancelled.json().toString()).status)
    }

    @Test
    fun invalidInputsReturnLocalInvalidArgument() = runBlocking {
        val badUrl = toolset.sniff(args("url" to "ftp://example.com/x", "engineUrl" to engine.baseUrl))
        assertEquals(true, badUrl.isError)
        assertEquals("invalid_argument", badUrl.json()["localError"]!!.jsonPrimitive.content)

        val badFormat = toolset.download(
            args("taskId" to "t-1", "format" to "-f best", "engineUrl" to engine.baseUrl),
        )
        assertEquals("invalid_argument", badFormat.json()["localError"]!!.jsonPrimitive.content)

        val missing = toolset.status(args("engineUrl" to engine.baseUrl))
        assertEquals("invalid_argument", missing.json()["localError"]!!.jsonPrimitive.content)

        val badTimeout = toolset.download(
            args("taskId" to "t-1", "timeoutSeconds" to 0, "engineUrl" to engine.baseUrl),
        )
        assertEquals("invalid_argument", badTimeout.json()["localError"]!!.jsonPrimitive.content)
    }
}
