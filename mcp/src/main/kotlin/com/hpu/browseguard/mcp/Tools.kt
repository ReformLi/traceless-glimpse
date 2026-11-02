package com.hpu.browseguard.mcp

import com.hpu.browseguard.engineclient.EngineApi
import com.hpu.browseguard.engineclient.EngineException
import com.hpu.browseguard.engineclient.EngineFailure
import com.hpu.browseguard.engineclient.TransportKind
import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatsResponse
import com.hpu.browseguard.shared.model.SniffResultsResponse
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** `sniff` 的组合输出：与 CLI 的 --json 信封保持一致。 */
@Serializable
private data class SniffToolOutput(
    val taskId: String,
    val task: Task,
    val sniffResults: SniffResultsResponse,
    val formats: FormatsResponse,
)

private class ToolTimeoutException(val localCode: String) : RuntimeException()

/**
 * 五个 MCP 工具的实现。
 *
 * 成功 → `isError=false` + 协议 JSON；引擎协议错误 → `isError=true` + 原样 ErrorPayload；
 * 本地失败只使用 `localError` 命名空间，绝不借用 §5 协议码。
 */
class McpToolset(
    private val config: McpConfig = McpConfig.fromEnv(),
    private val apiFactory: (String) -> EngineApi = { EngineApi(it) },
) {

    suspend fun call(name: String, arguments: JsonObject?): CallToolResult = when (name) {
        "sniff" -> sniff(arguments)
        "probe" -> probe(arguments)
        "download" -> download(arguments)
        "status" -> status(arguments)
        "cancel" -> cancel(arguments)
        else -> localFailure("unknown_tool", "未知工具：$name")
    }

    suspend fun sniff(arguments: JsonObject?): CallToolResult = execute {
        val sourceUrl = requireHttpUrl(arguments.requiredText("url"))
        val api = api(arguments)
        val outputDir = arguments.text("outputDir")?.takeIf { it.isNotBlank() }
            ?: api.selfcheck().outputDirAllowlist.firstOrNull()
            ?: throw ToolArgumentException("引擎输出目录白名单为空，请显式提供 outputDir")

        val created = api.createTask(
            TaskCreateRequest(
                sourceUrl = sourceUrl,
                mode = TaskMode.DOWNLOAD,
                cookiePolicy = CookiePolicy.NONE,
                outputDir = outputDir,
            ),
        )
        val settled = awaitStatus(
            api,
            created.id,
            config.sniffTimeoutMs,
            created,
            timeoutCode = "sniff_timeout",
        ) { it.status == TaskStatus.AWAITING_SELECTION || it.status.isTerminal() }

        when (settled.status) {
            TaskStatus.FAILED -> failedTask(settled)
            TaskStatus.CANCELLED -> localFailure("task_cancelled")
            else -> {
                val sniffResults = api.sniffResults(settled.id)
                val formats = if (settled.status == TaskStatus.AWAITING_SELECTION) {
                    api.formats(settled.id)
                } else {
                    emptyList()
                }
                successJson(
                    encode(
                        SniffToolOutput.serializer(),
                        SniffToolOutput(
                            taskId = settled.id,
                            task = settled,
                            sniffResults = SniffResultsResponse(sniffResults),
                            formats = FormatsResponse(settled.id, formats),
                        ),
                    ),
                )
            }
        }
    }

    suspend fun probe(arguments: JsonObject?): CallToolResult = execute {
        val taskId = arguments.requiredText("taskId")
        val api = api(arguments)
        successJson(encode(FormatsResponse.serializer(), FormatsResponse(taskId, api.formats(taskId))))
    }

    suspend fun download(arguments: JsonObject?): CallToolResult = execute {
        val taskId = arguments.requiredText("taskId")
        val selector = parseFormatSelector(arguments.text("format")?.takeIf { it.isNotBlank() } ?: "best")
        val timeoutMs = downloadTimeoutMs(arguments)
        val api = api(arguments)

        val started = api.startDownload(taskId, DownloadRequest(selector))
        val finalTask = if (started.status.isTerminal()) {
            started
        } else {
            awaitStatus(api, taskId, timeoutMs, started, timeoutCode = "download_timeout") {
                it.status.isTerminal()
            }
        }
        finishTask(finalTask)
    }

    suspend fun status(arguments: JsonObject?): CallToolResult = execute {
        val taskId = arguments.requiredText("taskId")
        val task = api(arguments).task(taskId)
        successJson(encode(Task.serializer(), task))
    }

    suspend fun cancel(arguments: JsonObject?): CallToolResult = execute {
        val taskId = arguments.requiredText("taskId")
        val task = api(arguments).cancelTask(taskId)
        successJson(encode(Task.serializer(), task))
    }

    private fun api(arguments: JsonObject?): EngineApi {
        val url = arguments.text("engineUrl")?.takeIf { it.isNotBlank() }?.let(::requireHttpUrl)
            ?: config.defaultEngineUrl
        return apiFactory(url)
    }

    private fun downloadTimeoutMs(arguments: JsonObject?): Long {
        val seconds = arguments.int("timeoutSeconds") ?: return config.downloadTimeoutMs
        if (seconds !in 1..86_400) {
            throw ToolArgumentException("timeoutSeconds 必须在 1..86400 之间：$seconds")
        }
        return seconds * 1_000L
    }

    private suspend fun awaitStatus(
        api: EngineApi,
        taskId: String,
        timeoutMs: Long,
        initial: Task? = null,
        timeoutCode: String,
        stop: (Task) -> Boolean,
    ): Task {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var task = initial ?: api.task(taskId)
        while (!stop(task)) {
            if (System.nanoTime() >= deadline) throw ToolTimeoutException(timeoutCode)
            delay(config.pollIntervalMs)
            task = api.task(taskId)
        }
        return task
    }

    private fun finishTask(task: Task): CallToolResult = when (task.status) {
        TaskStatus.DONE -> successJson(encode(Task.serializer(), task))
        TaskStatus.FAILED -> failedTask(task)
        TaskStatus.CANCELLED -> localFailure("task_cancelled")
        else -> localFailure("internal_error", "任务未进入终态：${task.status}")
    }

    private fun failedTask(task: Task): CallToolResult =
        task.error?.let(::protocolFailure)
            ?: localFailure("engine_error", "任务失败但引擎未提供错误详情")

    private suspend fun execute(block: suspend () -> CallToolResult): CallToolResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolArgumentException) {
        localFailure("invalid_argument", e.message)
    } catch (e: ToolTimeoutException) {
        localFailure(e.localCode)
    } catch (e: EngineException) {
        when (val failure = e.failure) {
            is EngineFailure.Protocol -> protocolFailure(failure.error)
            is EngineFailure.Transport -> if (failure.kind == TransportKind.UNREACHABLE) {
                // 引擎不可达是本地事实，不使用 NETWORK_TIMEOUT 等协议码。
                localFailure("engine_unreachable")
            } else {
                localFailure("engine_protocol_mismatch", failure.reason)
            }
        }
    } catch (e: Exception) {
        localFailure("internal_error", e.message ?: e::class.java.simpleName)
    }

    private fun protocolFailure(error: ErrorPayload): CallToolResult =
        failureJson(encode(ErrorPayload.serializer(), error))

    private fun localFailure(code: String, message: String? = null): CallToolResult {
        val payload = buildJsonObject {
            put("localError", code)
            if (message != null) put("message", message)
        }
        return failureJson(payload.toString())
    }

    private fun successJson(json: String): CallToolResult = buildResult(json, isError = false)

    private fun failureJson(json: String): CallToolResult = buildResult(json, isError = true)

    private fun buildResult(json: String, isError: Boolean): CallToolResult {
        val element = BgvJson.parseToJsonElement(json)
        return CallToolResult(
            content = listOf(TextContent(json)),
            isError = isError,
            structuredContent = element as? JsonObject,
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        BgvJson.encodeToString(serializer, value)
}

private fun TaskStatus.isTerminal(): Boolean =
    this == TaskStatus.DONE || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED

private fun JsonObject?.text(name: String): String? =
    ((this?.get(name)) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.requiredText(name: String): String =
    text(name)?.takeIf { it.isNotBlank() } ?: throw ToolArgumentException("缺少参数 $name")

private fun JsonObject?.int(name: String): Int? {
    val element = this?.get(name) ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        ?: throw ToolArgumentException("$name 必须是整数")
}

/** 注册五个白名单工具；工具名固定，参数 schema 与 CLI 语法一致。 */
fun registerTools(server: Server, toolset: McpToolset) {
    server.addTool(
        name = "sniff",
        description = "嗅探页面链接并返回候选媒体流与格式表。任务停在 awaiting_selection，随后可用 probe/download。",
        inputSchema = schema(
            required = listOf("url"),
            properties = buildJsonObject {
                put("url", stringProperty("页面链接（http/https）"))
                put("outputDir", stringProperty("任务输出目录；缺省取引擎白名单首项"))
                put("engineUrl", stringProperty("引擎地址；缺省取 BGV_ENGINE_URL"))
            },
        ),
    ) { request -> toolset.call(request.name, request.arguments) }

    server.addTool(
        name = "probe",
        description = "查询已嗅探任务（awaiting_selection）的格式表。",
        inputSchema = schema(
            required = listOf("taskId"),
            properties = buildJsonObject {
                put("taskId", stringProperty("任务 ID"))
                put("engineUrl", stringProperty("引擎地址；缺省取 BGV_ENGINE_URL"))
            },
        ),
    ) { request -> toolset.call(request.name, request.arguments) }

    server.addTool(
        name = "download",
        description = "为已嗅探任务下发下载并轮询到终态（done / failed / cancelled），支持超时。",
        inputSchema = schema(
            required = listOf("taskId"),
            properties = buildJsonObject {
                put("taskId", stringProperty("任务 ID"))
                put(
                    "format",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "格式选择器：best / audio_only / max_height=720 / exact=<formatId>",
                        )
                        put("default", "best")
                    },
                )
                put(
                    "timeoutSeconds",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "轮询超时（秒），默认 21600")
                        put("minimum", 1)
                        put("maximum", 86_400)
                    },
                )
                put("engineUrl", stringProperty("引擎地址；缺省取 BGV_ENGINE_URL"))
            },
        ),
    ) { request -> toolset.call(request.name, request.arguments) }

    server.addTool(
        name = "status",
        description = "查询单任务当前状态；失败任务也按正常结果返回 Task JSON。",
        inputSchema = schema(
            required = listOf("taskId"),
            properties = buildJsonObject {
                put("taskId", stringProperty("任务 ID"))
                put("engineUrl", stringProperty("引擎地址；缺省取 BGV_ENGINE_URL"))
            },
        ),
    ) { request -> toolset.call(request.name, request.arguments) }

    server.addTool(
        name = "cancel",
        description = "取消非终态任务。",
        inputSchema = schema(
            required = listOf("taskId"),
            properties = buildJsonObject {
                put("taskId", stringProperty("任务 ID"))
                put("engineUrl", stringProperty("引擎地址；缺省取 BGV_ENGINE_URL"))
            },
        ),
    ) { request -> toolset.call(request.name, request.arguments) }
}

private fun schema(required: List<String>, properties: JsonObject): ToolSchema =
    ToolSchema(properties = properties, required = required)

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}
