package com.hpu.browseguard.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.hpu.browseguard.engineclient.EngineApi
import com.hpu.browseguard.engineclient.EngineException
import com.hpu.browseguard.engineclient.EngineFailure
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorEnvelope
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatsResponse
import com.hpu.browseguard.shared.model.SniffResultsResponse
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskListResponse
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** `sniff` 的 --json 输出：一个任务 + 两个协议信封，字段名与协议一致。 */
@Serializable
data class SniffOutput(
    val taskId: String,
    val task: Task,
    val sniffResults: SniffResultsResponse,
    val formats: FormatsResponse,
)

abstract class EngineCommand(name: String, protected val app: AppContext) : CliktCommand(name) {

    private val localJson by option(
        "--json",
        help = "输出协议 JSON；stdout 只有 JSON，提示与进度全部走 stderr",
    ).flag()

    private val localEngine by option(
        "--engine",
        help = "引擎地址（默认 $DEFAULT_ENGINE_URL）",
    ).default("")

    protected val json: Boolean get() = localJson || app.json
    protected val engineUrl: String get() = localEngine.ifBlank { app.engine }
    protected val style: Style get() = app.env.style(json)

    protected fun <T> withApi(block: suspend (EngineApi) -> T): T = runBlocking {
        val api = app.apiFactory(engineUrl)
        try {
            block(api)
        } catch (e: EngineException) {
            failEngine(e.failure)
        }
    }

    protected fun <T> emitJson(serializer: KSerializer<T>, value: T) {
        app.env.out.println(CliJson.encodeToString(serializer, value))
    }

    protected fun failEngine(failure: EngineFailure): Nothing {
        when (failure) {
            is EngineFailure.Protocol -> emitFailure(failure.error, "HTTP ${failure.httpStatus}")
            is EngineFailure.Transport -> app.env.err.println(style.red("引擎错误：${failure.reason}"))
        }
        throw CliExit(exitCodeFor(failure))
    }

    /** 任务已落 failed：按协议错误类别映射退出码，不另造错误码。 */
    protected fun failTask(task: Task): Nothing {
        val error = task.error
        if (error != null) {
            emitFailure(error, "任务 ${task.id}")
            throw CliExit(exitCodeFor(error.category))
        }
        app.env.err.println(style.red("任务 ${task.id} 失败（引擎未提供错误详情）"))
        throw CliExit(ExitCodes.INTERNAL)
    }

    protected fun failCancelled(task: Task): Nothing {
        app.env.err.println(style.yellow("任务已取消：${task.id}"))
        throw CliExit(ExitCodes.USER_CANCELLED)
    }

    private fun emitFailure(error: ErrorPayload, context: String) {
        if (json) {
            app.env.err.println(CliJson.encodeToString(ErrorEnvelope.serializer(), ErrorEnvelope(error)))
        } else {
            app.env.err.println(
                style.red("[$context] ${error.code.name}（${error.category}）：${error.message}"),
            )
        }
    }

    protected suspend fun resolveOutputDir(api: EngineApi, explicit: String?): String {
        if (!explicit.isNullOrBlank()) return explicit
        val selfcheck = try {
            api.selfcheck()
        } catch (e: EngineException) {
            failEngine(e.failure)
        }
        return selfcheck.outputDirAllowlist.firstOrNull()
            ?: throw CliUsageException("未提供 --output，且引擎输出目录白名单为空")
    }

    /**
     * 轮询直到 [stop] 成立；[watch] 为真时把进度写 stderr（--json 也不污染 stdout）。
     */
    protected suspend fun pollTask(
        api: EngineApi,
        taskId: String,
        timeoutMs: Long,
        watch: Boolean = false,
        initial: Task? = null,
        stop: (Task) -> Boolean,
    ): Task {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var lastKey = if (watch && initial != null) printProgress(initial, "") else ""
        while (true) {
            val task = api.task(taskId)
            if (watch) lastKey = printProgress(task, lastKey)
            if (stop(task)) return task
            if (System.nanoTime() >= deadline) {
                app.env.err.println(style.red("等待任务超时（${timeoutMs / 1000}s）：$taskId"))
                throw CliExit(ExitCodes.INTERNAL)
            }
            delay(app.runtime.pollIntervalMs)
        }
    }

    protected fun reportProgress(task: Task) {
        printProgress(task, "")
    }

    private fun printProgress(task: Task, lastKey: String): String {
        val key = "${task.status.wire()}|${task.progress?.percent?.toInt() ?: -1}"
        if (key == lastKey) return key
        val progress = task.progress
        val percent = progress?.let { String.format(Locale.ROOT, "%5.1f%%", it.percent) } ?: "  -  "
        val bytes = progress?.let {
            "${formatBytes(it.bytesDownloaded)} / ${formatBytes(it.bytesTotal)}"
        } ?: "-"
        val speed = progress?.let { "${formatBytes(it.speedBps)}/s" } ?: "-"
        val eta = progress?.let { "ETA ${it.etaSeconds}s" } ?: ""
        val line = "$percent  $bytes  $speed  $eta  [${task.status.wire()}]"
        app.env.err.println(style.dim(line))
        return key
    }

    protected fun finish(task: Task) {
        when (task.status) {
            TaskStatus.DONE -> {
                if (json) emitJson(Task.serializer(), task)
                else printTerminalSummary(app.env, style, task)
            }
            TaskStatus.FAILED -> failTask(task)
            TaskStatus.CANCELLED -> failCancelled(task)
            else -> {
                app.env.err.println(style.red("任务未进入终态：${task.status.wire()}"))
                throw CliExit(ExitCodes.INTERNAL)
            }
        }
    }
}

class SniffCommand(app: AppContext) : EngineCommand("sniff", app) {

    private val url by argument("url", help = "页面链接（http/https）")
    private val output by option("--output", help = "任务输出目录；缺省取引擎白名单首项")

    override fun help(context: Context): String =
        "嗅探链接并返回候选格式（不下载）；任务停在 awaiting_selection"

    override fun run() {
        val sourceUrl = requireHttpUrl(url)
        val result = withApi { api ->
            val outputDir = resolveOutputDir(api, output)
            val created = api.createTask(
                TaskCreateRequest(
                    sourceUrl = sourceUrl,
                    mode = TaskMode.DOWNLOAD,
                    cookiePolicy = CookiePolicy.NONE,
                    outputDir = outputDir,
                ),
            )
            val settled = pollTask(
                api,
                created.id,
                app.runtime.sniffTimeoutMs,
                stop = { it.status == TaskStatus.AWAITING_SELECTION || it.status.isTerminal() },
            )
            when (settled.status) {
                TaskStatus.FAILED -> failTask(settled)
                TaskStatus.CANCELLED -> failCancelled(settled)
                else -> Unit
            }
            val sniffResults = api.sniffResults(settled.id)
            val formats = if (settled.status == TaskStatus.AWAITING_SELECTION) {
                api.formats(settled.id)
            } else {
                emptyList()
            }
            Triple(settled, sniffResults, formats)
        }
        val (task, sniffResults, formats) = result
        if (json) {
            emitJson(
                SniffOutput.serializer(),
                SniffOutput(task.id, task, SniffResultsResponse(sniffResults), FormatsResponse(task.id, formats)),
            )
        } else {
            app.env.out.println("${style.bold("任务")} ${task.id}  ${style.status(task.status)}")
            app.env.out.println()
            app.env.out.println(style.bold("候选媒体流"))
            renderSniffResults(app.env, style, sniffResults)
            app.env.out.println()
            app.env.out.println(style.bold("可选格式"))
            renderFormats(app.env, style, formats)
            app.env.out.println()
            app.env.out.println(style.dim("下一步：bgv download ${task.id} --format=best"))
        }
    }
}

class FormatsCommand(app: AppContext) : EngineCommand("formats", app) {

    private val taskId by argument("taskId", help = "任务 ID")

    override fun help(context: Context): String =
        "查看 awaiting_selection 任务的格式表"

    override fun run() {
        val response = withApi { api -> FormatsResponse(taskId, api.formats(taskId)) }
        if (json) emitJson(FormatsResponse.serializer(), response)
        else {
            app.env.out.println("${style.bold("任务")} ${response.taskId}")
            renderFormats(app.env, style, response.items)
        }
    }
}

class DownloadCommand(app: AppContext) : EngineCommand("download", app) {

    private val taskId by argument("taskId", help = "任务 ID（来自 sniff）")
    private val format by option("--format", "-f", help = "格式选择器").required()
    private val watch by option("--watch", "-w", help = "下载期间向 stderr 打印进度").flag()

    override fun help(context: Context): String =
        "为已嗅探任务下发下载，并轮询直到 done / failed"

    override fun helpEpilog(context: Context): String =
        "格式选择器：best / audio_only / max_height=720 / exact=<formatId>；" +
            "任务被取消时退出码 8（本地判定）"

    override fun run() {
        val selector = parseFormatSelector(format)
        val task = withApi { api ->
            val started = api.startDownload(taskId, DownloadRequest(selector))
            if (started.status.isTerminal()) {
                if (watch) reportProgress(started)
                started
            } else {
                pollTask(
                    api,
                    taskId,
                    app.runtime.downloadTimeoutMs,
                    watch,
                    initial = started,
                ) { it.status.isTerminal() }
            }
        }
        finish(task)
    }
}

class DownloadUrlCommand(app: AppContext) : EngineCommand("download-url", app) {

    private val url by argument("url", help = "页面链接（http/https）")
    private val format by option("--format", "-f", help = "格式选择器，默认 best").default("best")
    private val output by option("--output", help = "任务输出目录；缺省取引擎白名单首项")
    private val watch by option("--watch", "-w", help = "下载期间向 stderr 打印进度").flag()

    override fun help(context: Context): String =
        "一步到位：嗅探 + 选格式 + 下载 + 等待完成"

    override fun helpEpilog(context: Context): String =
        "格式选择器：best / audio_only / max_height=720 / exact=<formatId>"

    override fun run() {
        val sourceUrl = requireHttpUrl(url)
        val selector = parseFormatSelector(format)
        val task = withApi { api ->
            val outputDir = resolveOutputDir(api, output)
            val created = api.createTask(
                TaskCreateRequest(
                    sourceUrl = sourceUrl,
                    mode = TaskMode.DOWNLOAD,
                    cookiePolicy = CookiePolicy.NONE,
                    outputDir = outputDir,
                    formatSelector = selector,
                ),
            )
            var settled = pollTask(
                api,
                created.id,
                app.runtime.sniffTimeoutMs,
                watch,
                initial = created,
                stop = { it.status == TaskStatus.AWAITING_SELECTION || it.status.isTerminal() },
            )
            if (settled.status == TaskStatus.AWAITING_SELECTION) {
                // 引擎未自动领取（如嗅探到 DRM）：显式下发，DRM 会在这里按协议拒绝。
                val started = api.startDownload(settled.id, DownloadRequest(selector))
                settled = if (started.status.isTerminal()) {
                    if (watch) reportProgress(started)
                    started
                } else {
                    pollTask(
                        api,
                        settled.id,
                        app.runtime.downloadTimeoutMs,
                        watch,
                        initial = started,
                    ) { it.status.isTerminal() }
                }
            }
            settled
        }
        finish(task)
    }
}

class CancelCommand(app: AppContext) : EngineCommand("cancel", app) {

    private val taskId by argument("taskId", help = "任务 ID")

    override fun help(context: Context): String = "取消非终态任务"

    override fun run() {
        val task = withApi { api -> api.cancelTask(taskId) }
        if (json) emitJson(Task.serializer(), task)
        else printTaskDetails(app.env, style, task)
    }
}

class StatusCommand(app: AppContext) : EngineCommand("status", app) {

    private val taskId by argument("taskId", help = "任务 ID")

    override fun help(context: Context): String = "查询单任务状态"

    override fun run() {
        val task = withApi { api -> api.task(taskId) }
        if (json) emitJson(Task.serializer(), task)
        else printTaskDetails(app.env, style, task)
    }
}

class TasksCommand(app: AppContext) : EngineCommand("tasks", app) {

    private val limit by option("--limit", "-n", help = "最多返回条数（1..200，默认 50）")
        .int()
        .default(50)

    override fun help(context: Context): String = "列出任务"

    override fun run() {
        if (limit !in 1..200) throw CliUsageException("--limit 必须在 1..200 之间：$limit")
        val response = withApi { api -> api.listTasks(limit) }
        if (json) emitJson(TaskListResponse.serializer(), response)
        else renderTaskList(app.env, style, response.items)
    }
}
