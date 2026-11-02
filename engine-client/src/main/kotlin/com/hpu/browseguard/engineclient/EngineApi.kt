package com.hpu.browseguard.engineclient

import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.EnginePayloadGuard
import com.hpu.browseguard.shared.Protocol
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorEnvelope
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatsResponse
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.SniffResultsResponse
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskListResponse
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/**
 * 纯 JVM 引擎 HTTP 客户端，供 CLI / MCP 使用。
 *
 * 所有序列化走 [:shared] 的 `BgvJson`（未知字段严格拒绝），上行载荷经
 * `EnginePayloadGuard` 出口检查；请求头只有 `Accept` / `Content-Type` /
 * `X-Protocol-Version`。引擎回包缺少版本头或版本不符一律视为传输失败，
 * 不降级成「已连接」。
 */
class EngineApi(
    baseUrl: String,
    private val http: HttpClient = defaultHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun health(): HealthResponse =
        call("GET", "/v1/health", null, HealthResponse.serializer())

    suspend fun selfcheck(): SelfcheckResponse =
        call("GET", "/v1/privacy/selfcheck", null, SelfcheckResponse.serializer())

    suspend fun createTask(request: TaskCreateRequest): Task =
        call(
            "POST",
            "/v1/tasks",
            encode(TaskCreateRequest.serializer(), request),
            Task.serializer(),
        )

    suspend fun listTasks(limit: Int? = null, cursor: String? = null): TaskListResponse {
        val params = listOfNotNull(
            limit?.let { "limit=$it" },
            cursor?.let { "cursor=${urlEncode(it)}" },
        )
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return call("GET", "/v1/tasks$query", null, TaskListResponse.serializer())
    }

    suspend fun task(taskId: String): Task =
        call("GET", taskPath(taskId), null, Task.serializer())

    suspend fun sniffResults(taskId: String): List<SniffResult> =
        call("GET", taskPath(taskId) + "/sniff-results", null, SniffResultsResponse.serializer()).items

    suspend fun formats(taskId: String): List<FormatInfo> =
        call("GET", taskPath(taskId) + "/formats", null, FormatsResponse.serializer()).items

    suspend fun startDownload(taskId: String, request: DownloadRequest): Task =
        call(
            "POST",
            taskPath(taskId) + "/download",
            encode(DownloadRequest.serializer(), request),
            Task.serializer(),
        )

    suspend fun cancelTask(taskId: String): Task =
        call("POST", taskPath(taskId) + "/cancel", null, Task.serializer())

    private fun taskPath(taskId: String) = "/v1/tasks/${urlEncode(taskId)}"

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        EnginePayloadGuard.check(BgvJson.encodeToString(serializer, value))

    private suspend fun <T> call(
        method: String,
        pathWithQuery: String,
        jsonBody: String?,
        serializer: KSerializer<T>,
    ): T {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + pathWithQuery))
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header(Protocol.PROTOCOL_HEADER, Protocol.PROTOCOL_VERSION)
            .apply {
                if (jsonBody != null) {
                    header("Content-Type", "application/json; charset=utf-8")
                    method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                } else {
                    method(method, HttpRequest.BodyPublishers.noBody())
                }
            }
            .build()

        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).await()
        } catch (e: CompletionException) {
            throw unreachable(e.cause ?: e)
        } catch (e: IOException) {
            throw unreachable(e)
        }

        val version = response.headers().firstValue(Protocol.PROTOCOL_HEADER).orElse(null)
        if (version != Protocol.PROTOCOL_VERSION) {
            throw EngineException(
                EngineFailure.Transport(
                    "协议版本不符：响应头为 ${version ?: "缺失"}，期望 ${Protocol.PROTOCOL_VERSION}",
                ),
            )
        }

        val body = response.body()
        if (response.statusCode() !in 200..299) {
            val error = runCatching { BgvJson.decodeFromString(ErrorEnvelope.serializer(), body) }
                .getOrNull()?.error
            throw EngineException(
                error?.let { EngineFailure.Protocol(response.statusCode(), it) }
                    ?: EngineFailure.Transport("HTTP ${response.statusCode()}，响应体不是协议错误对象"),
            )
        }

        return try {
            BgvJson.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw EngineException(
                EngineFailure.Transport(
                    "引擎响应不符合协议 v${Protocol.PROTOCOL_VERSION}：${e.message}",
                ),
            )
        }
    }

    private fun unreachable(cause: Throwable): EngineException =
        EngineException(
            EngineFailure.Transport(
                "无法连接引擎：${cause.message ?: cause::class.java.simpleName}",
                TransportKind.UNREACHABLE,
            ),
        )

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }
}
