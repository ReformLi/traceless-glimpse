package com.hpu.browseguard.data

import com.hpu.browseguard.data.EngineFailure.Protocol
import com.hpu.browseguard.data.EngineFailure.Transport
import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.EnginePayloadGuard
import com.hpu.browseguard.shared.Protocol as BgvProtocol
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorEnvelope
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatsResponse
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.SniffResultsResponse
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 引擎调用失败的两类来源。 */
sealed class EngineFailure {
    /** 引擎按协议返回了 ErrorPayload（PROTOCOL §5）。 */
    data class Protocol(val httpStatus: Int, val error: ErrorPayload) : EngineFailure()

    /**
     * 连不上、超时、协议头不符或响应体不是协议 JSON。
     *
     * [kind] 区分「电脑根本不可达」与「引擎答了但不是协议」：前者允许界面
     * 自动降级到手机 WebView，后者降级没有意义（本地嗅探不会让协议版本对上）。
     */
    data class Transport(
        val reason: String,
        val kind: TransportKind = TransportKind.OTHER,
    ) : EngineFailure()
}

/** 传输失败的可判性分类。 */
enum class TransportKind {
    /** TCP 拒绝、DNS/连接超时、读超时：电脑这一侧根本不可达。 */
    UNREACHABLE,

    /** 连上了但答的不是协议：版本不符、响应体不是协议 JSON。 */
    OTHER,
}

class EngineException(val failure: EngineFailure) : Exception(failure.toString())

/**
 * 本地引擎 HTTP 客户端（协议版本见 [:shared] 的 `Protocol`）。
 *
 * 所有序列化走 [:shared] 的 `BgvJson`（未知字段严格拒绝），因此客户端不可能
 * 悄悄带上协议外的字段；请求头只有 `Accept` 与 `X-Protocol-Version`。
 */
class EngineClient(private val http: OkHttpClient = DEFAULT_HTTP) {

    suspend fun health(baseUrl: String): HealthResponse =
        decode(HealthResponse.serializer(), execute(baseUrl, "GET", listOf("v1", "health"), null))

    suspend fun selfcheck(baseUrl: String): SelfcheckResponse =
        decode(SelfcheckResponse.serializer(), execute(baseUrl, "GET", listOf("v1", "privacy", "selfcheck"), null))

    suspend fun createTask(baseUrl: String, request: TaskCreateRequest): Task =
        decode(Task.serializer(), execute(baseUrl, "POST", listOf("v1", "tasks"), encode(request, TaskCreateRequest.serializer())))

    suspend fun task(baseUrl: String, taskId: String): Task =
        decode(Task.serializer(), execute(baseUrl, "GET", taskPath(taskId), null))

    suspend fun sniffResults(baseUrl: String, taskId: String): List<SniffResult> =
        decode(SniffResultsResponse.serializer(), execute(baseUrl, "GET", taskPath(taskId) + "sniff-results", null)).items

    suspend fun formats(baseUrl: String, taskId: String): List<FormatInfo> =
        decode(FormatsResponse.serializer(), execute(baseUrl, "GET", taskPath(taskId) + "formats", null)).items

    /** §6.4：`202` 返回更新后的 Task（`status=downloading`）；同 formatId 重复调用幂等。 */
    suspend fun startDownload(baseUrl: String, taskId: String, request: DownloadRequest): Task =
        decode(Task.serializer(), execute(baseUrl, "POST", taskPath(taskId) + "download", encode(request, DownloadRequest.serializer())))

    /** §4：任意非终态 ──取消──► cancelled；终态 → `409 TASK_STATE_CONFLICT`。 */
    suspend fun cancelTask(baseUrl: String, taskId: String): Task =
        decode(Task.serializer(), execute(baseUrl, "POST", taskPath(taskId) + "cancel", null))

    private fun taskPath(taskId: String) = listOf("v1", "tasks", taskId)

    private fun <T> encode(value: T, serializer: KSerializer<T>): String =
        EnginePayloadGuard.check(BgvJson.encodeToString(serializer, value))

    private fun <T> decode(serializer: KSerializer<T>, body: String): T =
        try {
            BgvJson.decodeFromString(serializer, body)
        } catch (e: SerializationException) {
            throw EngineException(Transport("引擎响应不符合协议 v${BgvProtocol.PROTOCOL_VERSION}：${e.message}"))
        }

    private suspend fun execute(
        baseUrl: String,
        method: String,
        path: List<String>,
        jsonBody: String?,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(baseUrl, path))
            .header("Accept", "application/json")
            .header(PROTOCOL_HEADER, PROTOCOL_VERSION)
            .method(method, bodyFor(method, jsonBody))
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw EngineException(Transport("无法连接引擎：${e.message}", TransportKind.UNREACHABLE))
        }

        response.use { resp ->
            val version = resp.header(PROTOCOL_HEADER)
            if (version != PROTOCOL_VERSION) {
                throw EngineException(Transport("协议版本不符：响应头为 ${version ?: "缺失"}，期望 $PROTOCOL_VERSION"))
            }
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                throw EngineException(protocolFailure(resp.code, body))
            }
            body
        }
    }

    /** 取消端点无请求体，但 OkHttp 要求 POST 必须带 body：发零长度 JSON 体。 */
    private fun bodyFor(method: String, jsonBody: String?): RequestBody? = when {
        jsonBody != null -> jsonBody.toRequestBody(JSON)
        method == "POST" -> ByteArray(0).toRequestBody(JSON)
        else -> null
    }

    private fun protocolFailure(httpStatus: Int, body: String): EngineFailure {
        val error = runCatching { BgvJson.decodeFromString(ErrorEnvelope.serializer(), body) }
            .getOrNull()?.error
        return error?.let { Protocol(httpStatus, it) }
            ?: Transport("HTTP $httpStatus，响应体不是协议错误对象")
    }

    private fun buildUrl(baseUrl: String, path: List<String>) =
        baseUrl.toHttpUrl().newBuilder().apply {
            path.forEach { addPathSegment(it) }
        }.build()

    companion object {
        const val PROTOCOL_VERSION = BgvProtocol.PROTOCOL_VERSION
        const val PROTOCOL_HEADER = BgvProtocol.PROTOCOL_HEADER

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * 默认 HTTP 客户端：第一个应用拦截器就是 M19 的网络安检门。
         *
         * [allowedHttpHost] 每次请求求值一次——AppContainer 传「读当前设置里的
         * 引擎 Host」，设置页改地址立即生效；缺省为空白名单（只放行回环 + HTTPS），
         * JVM 测试与无 Context 场景不必拖 Android 运行时。
         */
        fun defaultHttp(allowedHttpHost: () -> String? = { null }): OkHttpClient =
            OkHttpClient.Builder()
                .addInterceptor(NetworkGuardInterceptor(allowedHttpHost))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()

        val DEFAULT_HTTP: OkHttpClient = defaultHttp()
    }
}
