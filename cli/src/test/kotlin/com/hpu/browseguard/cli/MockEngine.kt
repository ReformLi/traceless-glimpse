package com.hpu.browseguard.cli

import com.hpu.browseguard.shared.Protocol
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.DrmSystem
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorEnvelope
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.FormatsResponse
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.SniffResultsResponse
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskListResponse
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskProgress
import com.hpu.browseguard.shared.model.TaskStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.serialization.SerializationException

internal data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String?,
    val body: String,
)

/**
 * 按协议语义推进的环回 Mock Engine。
 *
 * 每次 GET 任务都前进一步：queued → sniffing → awaiting_selection / downloading → done，
 * 用最少的状态让 CLI 的轮询与退出码映射可确定地复现。
 */
internal class MockEngine : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mock-engine").apply { isDaemon = true }
    }
    private val lock = Any()
    private val tasks = LinkedHashMap<String, TaskState>()
    private val recorded = mutableListOf<RecordedRequest>()
    private var nextId = 1

    var protocolVersion: String? = Protocol.PROTOCOL_VERSION
    var allowlist = listOf("/tmp/bgv-out")
    var drm = false
    var failCreateWith: ErrorCode? = null
    var failDownloadWith: ErrorCode? = null
    var cancelDuringDownload = false
    var completeDownloadOnSubmit = false

    private class TaskState(val id: String, val request: TaskCreateRequest) {
        var status: TaskStatus = TaskStatus.QUEUED
        var selector: FormatSelector? = request.formatSelector
        var error: ErrorPayload? = null
    }

    init {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    val requests: List<RecordedRequest>
        get() = synchronized(lock) { recorded.toList() }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val path = exchange.requestURI.path
        val query = exchange.requestURI.query
        synchronized(lock) {
            recorded += RecordedRequest(exchange.requestMethod, path, query, body)
        }
        val response = try {
            route(exchange.requestMethod, path, query, body)
        } catch (e: SerializationException) {
            error(422, ErrorCode.VALIDATION_ERROR)
        } catch (e: Exception) {
            error(500, ErrorCode.UNKNOWN)
        }
        respond(exchange, response.first, response.second)
    }

    private fun route(method: String, path: String, query: String?, body: String): Pair<Int, String> =
        synchronized(lock) {
            when {
                method == "GET" && path == "/v1/health" ->
                    200 to encode(HealthResponse.serializer(), HealthResponse("ok", "mock", "0.2", 1))

                method == "GET" && path == "/v1/privacy/selfcheck" ->
                    200 to encode(
                        SelfcheckResponse.serializer(),
                        SelfcheckResponse(
                            listenAddress = "127.0.0.1",
                            tlsEnabled = false,
                            authEnabled = false,
                            activeProxyIds = emptyList(),
                            cookiePolicyDefault = CookiePolicy.NONE,
                            outputDirAllowlist = allowlist,
                            cacheDir = "/tmp/bgv-cache",
                            cacheTtlHours = 24,
                            cacheSizeBytes = 0,
                        ),
                    )

                method == "POST" && path == "/v1/tasks" -> createTask(body)

                method == "GET" && path == "/v1/tasks" -> listTasks(query)

                method == "GET" && path == "/v1/_debug/download-counter" ->
                    200 to """{"downloadCount":0}"""

                else -> taskRoute(method, path, body)
            }
        }

    private fun createTask(body: String): Pair<Int, String> {
        failCreateWith?.let { return error(403, it) }
        val request = CliJson.decodeFromString(TaskCreateRequest.serializer(), body)
        val state = TaskState("t-${nextId++}", request)
        tasks[state.id] = state
        return 201 to encode(Task.serializer(), state.toTask())
    }

    private fun listTasks(query: String?): Pair<Int, String> {
        val limit = query
            ?.split("&")
            ?.firstOrNull { it.startsWith("limit=") }
            ?.removePrefix("limit=")
            ?.toIntOrNull()
            ?: 50
        val items = tasks.values.take(limit.coerceIn(0, 200)).map { it.toTask() }
        return 200 to encode(TaskListResponse.serializer(), TaskListResponse(items))
    }

    private fun taskRoute(method: String, path: String, body: String): Pair<Int, String> {
        val parts = path.trim('/').split('/')
        if (parts.size < 3 || parts[0] != "v1" || parts[1] != "tasks") {
            return error(404, ErrorCode.NOT_FOUND)
        }
        val state = tasks[parts[2]] ?: return error(404, ErrorCode.NOT_FOUND)
        val action = parts.getOrNull(3)
        return when {
            method == "GET" && action == null -> {
                advance(state)
                200 to encode(Task.serializer(), state.toTask())
            }

            method == "GET" && action == "sniff-results" ->
                200 to encode(
                    SniffResultsResponse.serializer(),
                    SniffResultsResponse(listOf(sniffResult(state))),
                )

            method == "GET" && action == "formats" -> {
                if (state.status != TaskStatus.AWAITING_SELECTION) {
                    error(409, ErrorCode.TASK_STATE_CONFLICT)
                } else {
                    200 to encode(FormatsResponse.serializer(), FormatsResponse(state.id, formats()))
                }
            }

            method == "POST" && action == "download" -> startDownload(state, body)

            method == "POST" && action == "cancel" -> {
                if (state.status.isTerminal()) {
                    error(409, ErrorCode.TASK_STATE_CONFLICT)
                } else {
                    state.status = TaskStatus.CANCELLED
                    200 to encode(Task.serializer(), state.toTask())
                }
            }

            else -> error(404, ErrorCode.NOT_FOUND)
        }
    }

    private fun startDownload(state: TaskState, body: String): Pair<Int, String> {
        if (state.status.isTerminal()) return error(409, ErrorCode.TASK_STATE_CONFLICT)
        if (state.status != TaskStatus.AWAITING_SELECTION) {
            return error(409, ErrorCode.TASK_STATE_CONFLICT)
        }
        if (drm) return error(422, ErrorCode.DRM_PROTECTED)
        val request = CliJson.decodeFromString(DownloadRequest.serializer(), body)
        state.selector = request.formatSelector
        state.status = if (completeDownloadOnSubmit) TaskStatus.DONE else TaskStatus.DOWNLOADING
        return 202 to encode(Task.serializer(), state.toTask())
    }

    private fun advance(state: TaskState) {
        when (state.status) {
            TaskStatus.QUEUED -> state.status = TaskStatus.SNIFFING
            TaskStatus.SNIFFING -> state.status = when {
                state.request.mode == TaskMode.SNIFF -> TaskStatus.DONE
                drm || state.selector == null -> TaskStatus.AWAITING_SELECTION
                else -> TaskStatus.DOWNLOADING
            }
            TaskStatus.DOWNLOADING -> {
                val failure = failDownloadWith
                when {
                    cancelDuringDownload -> state.status = TaskStatus.CANCELLED
                    failure != null -> {
                        state.status = TaskStatus.FAILED
                        state.error = ErrorPayload.of(failure)
                    }
                    else -> state.status = TaskStatus.DONE
                }
            }
            else -> Unit
        }
    }

    private fun TaskState.toTask(): Task = Task(
        id = id,
        sourceUrl = request.sourceUrl,
        mode = request.mode,
        formatSelector = selector,
        namingTemplate = null,
        postActions = emptyList(),
        cookiePolicy = request.cookiePolicy,
        outputDir = request.outputDir,
        status = status,
        progress = progress(),
        error = error,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        outputPath = if (status == TaskStatus.DONE) "/tmp/bgv-out/$id.mp4" else null,
    )

    private fun TaskState.progress(): TaskProgress? = when (status) {
        TaskStatus.DOWNLOADING, TaskStatus.MERGING -> TaskProgress("downloading", 500, 1000, 100, 5, 50.0)
        TaskStatus.DONE -> TaskProgress("done", 1000, 1000, 0, 0, 100.0)
        else -> null
    }

    private fun sniffResult(state: TaskState): SniffResult = SniffResult(
        taskId = state.id,
        mediaUrl = "https://media.test/video.m3u8",
        type = MediaStreamType.HLS,
        container = "m3u8",
        estimatedQuality = EstimatedQuality(1080, 4500, "1080p"),
        drmDetected = drm,
        drmSystem = if (drm) DrmSystem.WIDEVINE else null,
        headersHint = emptyMap(),
        discoveredAt = "2026-01-01T00:00:00Z",
    )

    private fun formats(): List<FormatInfo> = listOf(
        FormatInfo(
            formatId = "137",
            container = "mp4",
            resolution = Resolution(1920, 1080),
            bitrateKbps = 4500,
            codec = Codec(video = "avc1.640028", audio = "none"),
            hasAudio = false,
            hasVideo = true,
            fileSizeEstimateBytes = 150_000_000,
            source = FormatSource.YT_DLP,
        ),
        FormatInfo(
            formatId = "140",
            container = "m4a",
            resolution = Resolution(0, 0),
            bitrateKbps = 128,
            codec = Codec(video = "none", audio = "mp4a.40.2"),
            hasAudio = true,
            hasVideo = false,
            fileSizeEstimateBytes = 4_000_000,
            source = FormatSource.YT_DLP,
        ),
    )

    private fun error(status: Int, code: ErrorCode): Pair<Int, String> =
        status to encode(ErrorEnvelope.serializer(), ErrorEnvelope(ErrorPayload.of(code)))

    private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        CliJson.encodeToString(serializer, value)

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        protocolVersion?.let { exchange.responseHeaders.add(Protocol.PROTOCOL_HEADER, it) }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
