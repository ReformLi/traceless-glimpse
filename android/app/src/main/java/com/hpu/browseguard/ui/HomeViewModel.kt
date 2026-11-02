package com.hpu.browseguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.EngineConfig
import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.HttpSniffer
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.data.backend.TERMINAL_STATUSES
import com.hpu.browseguard.data.backend.toBackendFailure
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.PostActionType
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 引擎连接状态。 */
sealed interface Connection {
    data object Idle : Connection
    data object Checking : Connection
    data class Connected(val health: HealthResponse, val selfcheck: SelfcheckResponse) : Connection
    data class Failed(val reason: String) : Connection
}

/** 嗅探流程阶段。 */
sealed interface SniffPhase {
    data object Idle : SniffPhase
    data class Running(val status: TaskStatus) : SniffPhase
    data class Success(val results: List<SniffResult>) : SniffPhase
    data class Failed(val message: String, val code: String?, val retryable: Boolean) : SniffPhase
}

/** 嗅探 → 选画质 → 下载 → 进度 / 取消 的下载流程阶段。 */
sealed interface DownloadPhase {
    data object Idle : DownloadPhase

    /** 已提交 `mode=download` 任务，等引擎进入 `awaiting_selection`。 */
    data class Preparing(val status: TaskStatus) : DownloadPhase

    /** 引擎给出可选格式表（PROTOCOL §6.3）。 */
    data class Selecting(val taskId: String, val formats: List<FormatInfo>) : DownloadPhase

    data class Running(val task: Task, val cancelRequested: Boolean = false) : DownloadPhase
    data class Success(val task: Task) : DownloadPhase
    data class Cancelled(val task: Task) : DownloadPhase
    data class Failed(val message: String, val code: String?, val retryable: Boolean) : DownloadPhase
}

data class HomeUiState(
    val engineUrl: String = "",
    val engineUrlDraft: String = "",
    val engineUrlInvalid: Boolean = false,
    val connection: Connection = Connection.Idle,
    val sourceUrl: String = "",
    val sniff: SniffPhase = SniffPhase.Idle,
    val download: DownloadPhase = DownloadPhase.Idle,
    val selectedFormatId: String? = null,
    val namingTemplate: String = "",
    val mergeSelected: Boolean = true,
) {
    /** 输出目录只能来自引擎自检白名单；拿不到就不允许发起任务。 */
    val canSniff: Boolean
        get() = engineReady && sniff !is SniffPhase.Running

    /** 嗅探成功后才谈得上选画质；下载流程自己也在跑时不允许重复发起。 */
    val canPickFormats: Boolean
        get() = engineReady &&
            sniff is SniffPhase.Success &&
            download !is DownloadPhase.Preparing &&
            download !is DownloadPhase.Selecting &&
            download !is DownloadPhase.Running

    val selectedFormat: FormatInfo?
        get() = (download as? DownloadPhase.Selecting)?.formats?.firstOrNull { it.formatId == selectedFormatId }

    val namingTemplateError: String?
        get() = FormatSelection.namingTemplateError(namingTemplate.trim())

    val canStartDownload: Boolean
        get() = download is DownloadPhase.Selecting && selectedFormat != null && namingTemplateError == null

    private val engineReady: Boolean
        get() = connection is Connection.Connected &&
            connection.selfcheck.outputDirAllowlist.isNotEmpty()
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val config = EngineConfig(app)
    private val client = EngineClient()

    private var sniffJob: Job? = null
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(HomeUiState(engineUrl = config.baseUrl, engineUrlDraft = config.baseUrl))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshConnection()
    }

    fun refreshConnection() {
        viewModelScope.launch { connect() }
    }

    fun editEngineUrl(value: String) {
        _state.update { it.copy(engineUrlDraft = value, engineUrlInvalid = false) }
    }

    fun saveEngineUrl() {
        if (!config.save(_state.value.engineUrlDraft)) {
            _state.update { it.copy(engineUrlInvalid = true) }
            return
        }
        _state.update { it.copy(engineUrl = config.baseUrl, engineUrlInvalid = false) }
        refreshConnection()
    }

    fun editSourceUrl(value: String) {
        _state.update {
            it.copy(
                sourceUrl = value,
                sniff = SniffPhase.Idle,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
            )
        }
    }

    fun sniff() {
        val snapshot = _state.value
        val connected = snapshot.connection as? Connection.Connected ?: return
        val sourceUrl = snapshot.sourceUrl.trim()
        val outputDir = connected.selfcheck.outputDirAllowlist.firstOrNull() ?: return
        if (sourceUrl.isEmpty() || snapshot.sniff is SniffPhase.Running) return

        sniffJob?.cancel()
        downloadJob?.cancel()
        _state.update { it.copy(sniff = SniffPhase.Running(TaskStatus.QUEUED), download = DownloadPhase.Idle, selectedFormatId = null) }
        val request = SniffRequest(
            sourceUrl = sourceUrl,
            cookiePolicy = connected.selfcheck.cookiePolicyDefault,
            outputDir = outputDir,
        )
        sniffJob = viewModelScope.launch {
            HttpSniffer(config.baseUrl, client).sniff(request).collect { event -> applySniffEvent(event) }
        }
    }

    /**
     * 事件 → 界面镜像。写入条件与改造前一致：
     * 只有中间态（`Running`）带「别覆盖更新的阶段」的守卫，终态一律落盘——
     * 原来 `_state.update { it.copy(sniff = phase) }` 就是无条件写的。
     */
    private fun applySniffEvent(event: SniffEvent) {
        when (event) {
            is SniffEvent.Status -> _state.update { state ->
                if (state.sniff !is SniffPhase.Running) return@update state
                state.copy(sniff = SniffPhase.Running(event.status))
            }

            is SniffEvent.Terminal -> _state.update { state ->
                if (event.task.status == TaskStatus.DONE) return@update state
                state.copy(sniff = event.task.toSniffFailure())
            }

            is SniffEvent.Results -> _state.update { it.copy(sniff = SniffPhase.Success(event.results)) }
            is SniffEvent.Failed -> _state.update { it.copy(sniff = event.failure.toSniffPhase()) }
        }
    }

    /** 嗅探结果出来后，另起一个 `mode=download` 任务向引擎要可选画质表。 */
    fun pickFormats() {
        val snapshot = _state.value
        val connected = snapshot.connection as? Connection.Connected ?: return
        val outputDir = connected.selfcheck.outputDirAllowlist.firstOrNull() ?: return
        val sourceUrl = snapshot.sourceUrl.trim()
        if (sourceUrl.isEmpty() || !snapshot.canPickFormats) return

        sniffJob?.cancel()
        downloadJob?.cancel()
        _state.update { it.copy(download = DownloadPhase.Preparing(TaskStatus.QUEUED), selectedFormatId = null) }
        downloadJob = viewModelScope.launch {
            val baseUrl = config.baseUrl
            val phase = try {
                val created = client.createTask(
                    baseUrl,
                    TaskCreateRequest(
                        sourceUrl = sourceUrl,
                        mode = TaskMode.DOWNLOAD,
                        cookiePolicy = connected.selfcheck.cookiePolicyDefault,
                        outputDir = outputDir,
                    ),
                )
                val ready = awaitAwaitingSelection(baseUrl, created)
                if (ready.status == TaskStatus.AWAITING_SELECTION) {
                    val formats = client.formats(baseUrl, ready.id)
                    if (formats.isEmpty()) {
                        LocalFailures.noFormats().toDownloadPhase()
                    } else {
                        DownloadPhase.Selecting(ready.id, formats)
                    }
                } else {
                    ready.toFinalPhase()
                }
            } catch (e: EngineException) {
                e.failure.toBackendFailure().toDownloadPhase()
            } catch (e: TimeoutCancellationException) {
                LocalFailures.prepareTimeout().toDownloadPhase()
            }
            _state.update { it.copy(download = phase) }
        }
    }

    fun selectFormat(formatId: String) {
        _state.update { it.copy(selectedFormatId = formatId) }
    }

    fun editNamingTemplate(value: String) {
        _state.update { it.copy(namingTemplate = value) }
    }

    fun toggleMerge(enabled: Boolean) {
        _state.update { it.copy(mergeSelected = enabled) }
    }

    fun startDownload() {
        val snapshot = _state.value
        val selecting = snapshot.download as? DownloadPhase.Selecting ?: return
        val format = snapshot.selectedFormat ?: return
        if (snapshot.namingTemplateError != null) return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val baseUrl = config.baseUrl
            try {
                val started = client.startDownload(
                    baseUrl,
                    selecting.taskId,
                    DownloadRequest(
                        formatSelector = FormatSelection.selectorFor(format),
                        namingTemplate = snapshot.namingTemplate.trim().takeIf { it.isNotEmpty() },
                        postActions = if (snapshot.mergeSelected) listOf(PostAction(PostActionType.MERGE)) else null,
                    ),
                )
                _state.update { it.copy(download = DownloadPhase.Running(started)) }
                val settled = awaitDownloadTerminal(baseUrl, started)
                _state.update { it.copy(download = settled.toFinalPhase()) }
            } catch (e: EngineException) {
                _state.update { it.copy(download = e.failure.toBackendFailure().toDownloadPhase()) }
            } catch (e: TimeoutCancellationException) {
                _state.update { it.copy(download = LocalFailures.downloadTimeout().toDownloadPhase()) }
            }
        }
    }

    /** 取消只发协议请求，终态仍由轮询循环写入——避免两处同时改状态。 */
    fun cancelDownload() {
        val running = _state.value.download as? DownloadPhase.Running ?: return
        if (running.cancelRequested) return
        _state.update { it.copy(download = running.copy(cancelRequested = true)) }
        viewModelScope.launch {
            try {
                client.cancelTask(config.baseUrl, running.task.id)
            } catch (e: EngineException) {
                _state.update { state ->
                    if (state.download is DownloadPhase.Running) {
                        state.copy(download = e.failure.toBackendFailure().toDownloadPhase())
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun resetDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(download = DownloadPhase.Idle, selectedFormatId = null) }
    }

    private suspend fun awaitAwaitingSelection(baseUrl: String, initial: Task): Task {
        var current = initial
        withTimeout(LocalFailures.PREPARE_MS) {
            while (current.status != TaskStatus.AWAITING_SELECTION && current.status !in TERMINAL_STATUSES) {
                delay(LocalFailures.POLL_INTERVAL_MS)
                current = client.task(baseUrl, current.id)
                // 与 awaitTerminal 同型：不回写则 Preparing.status 永远停在初始 QUEUED，
                // 界面读到的「画质解析中（queued）」是过时报数
                _state.update { state ->
                    if (state.download !is DownloadPhase.Preparing) return@update state
                    state.copy(download = DownloadPhase.Preparing(current.status))
                }
            }
        }
        return current
    }

    private suspend fun awaitDownloadTerminal(baseUrl: String, initial: Task): Task {
        var current = initial
        withTimeout(LocalFailures.DOWNLOAD_MS) {
            while (current.status !in TERMINAL_STATUSES) {
                delay(LocalFailures.DOWNLOAD_POLL_INTERVAL_MS)
                current = client.task(baseUrl, current.id)
                _state.update { state ->
                    val previous = state.download as? DownloadPhase.Running ?: return@update state
                    state.copy(download = DownloadPhase.Running(current, previous.cancelRequested))
                }
            }
        }
        return current
    }

    private suspend fun connect() {
        _state.update { it.copy(connection = Connection.Checking) }
        val baseUrl = config.baseUrl
        _state.update {
            it.copy(
                connection = try {
                    Connection.Connected(client.health(baseUrl), client.selfcheck(baseUrl))
                } catch (e: EngineException) {
                    Connection.Failed(e.failure.reason())
                },
            )
        }
    }
}

/**
 * 后端失败 → 界面阶段。
 *
 * 码只有一条来源：[BackendFailure.Protocol] 里的字段来自引擎响应的 `ErrorPayload`。
 * [BackendFailure.Local] 一律 `code = null`——超时、连不上、引擎回空数组都是
 * 「客户端自己判的」，借 §5 的码就是替引擎发言（屏上码与引擎审计行对不上、
 * 将来按码做重试策略时误判）。
 */
internal fun BackendFailure.toSniffPhase(): SniffPhase.Failed = when (this) {
    is BackendFailure.Protocol -> SniffPhase.Failed(message, code.name, retryable)
    is BackendFailure.Local -> SniffPhase.Failed(message, null, retryable)
}

internal fun BackendFailure.toDownloadPhase(): DownloadPhase.Failed = when (this) {
    is BackendFailure.Protocol -> DownloadPhase.Failed(message, code.name, retryable)
    is BackendFailure.Local -> DownloadPhase.Failed(message, null, retryable)
}

/** 引擎把失败写在任务的 `error` 里；没有 error 的终态只能说「结束于哪个状态」，不猜原因。 */
private fun Task.toSniffFailure(): SniffPhase.Failed = SniffPhase.Failed(
    message = error?.message ?: "任务结束于 ${status.displayWithWire()}",
    code = error?.code?.name,
    retryable = error?.retryable ?: false,
)

private fun Task.toFinalPhase(): DownloadPhase = when (status) {
    TaskStatus.DONE -> DownloadPhase.Success(this)
    TaskStatus.CANCELLED -> DownloadPhase.Cancelled(this)
    else -> DownloadPhase.Failed(
        message = error?.message ?: "任务结束于 ${status.displayWithWire()}",
        code = error?.code?.name,
        retryable = error?.retryable ?: false,
    )
}

private fun EngineFailure.reason(): String = when (this) {
    is EngineFailure.Protocol -> "HTTP $httpStatus ${error.code}：${error.message}"
    is EngineFailure.Transport -> reason
}
