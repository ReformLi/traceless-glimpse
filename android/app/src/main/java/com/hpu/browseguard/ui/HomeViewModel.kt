package com.hpu.browseguard.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.EngineConfig
import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorCode
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
        sniffJob = viewModelScope.launch {
            val baseUrl = config.baseUrl
            val phase = try {
                val created = client.createTask(
                    baseUrl,
                    TaskCreateRequest(
                        sourceUrl = sourceUrl,
                        mode = TaskMode.SNIFF,
                        cookiePolicy = connected.selfcheck.cookiePolicyDefault,
                        outputDir = outputDir,
                    ),
                )
                val settled = awaitTerminal(baseUrl, created)
                if (settled.status == TaskStatus.DONE) {
                    val results = client.sniffResults(baseUrl, settled.id)
                    if (results.isEmpty()) {
                        // 嗅探成功但列表为空：语义就是协议的 NO_MEDIA_FOUND，文案与可重试性都取自码表
                        SniffPhase.Failed(
                            ErrorCode.NO_MEDIA_FOUND.message,
                            ErrorCode.NO_MEDIA_FOUND.name,
                            ErrorCode.NO_MEDIA_FOUND.retryable,
                        )
                    } else {
                        SniffPhase.Success(results)
                    }
                } else {
                    SniffPhase.Failed(
                        message = settled.error?.message ?: "任务结束于 ${settled.status.displayWithWire()}",
                        code = settled.error?.code?.name,
                        retryable = settled.error?.retryable ?: false,
                    )
                }
            } catch (e: EngineException) {
                e.failure.toPhaseFailure()
            } catch (e: TimeoutCancellationException) {
                LocalTimeouts.sniff()
            }
            _state.update { it.copy(sniff = phase) }
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
                        // 原硬编码「引擎未给出可选画质」与协议 §5 措辞不一致，改为直接取码表原文
                        DownloadPhase.Failed(
                            ErrorCode.FORMAT_UNAVAILABLE.message,
                            ErrorCode.FORMAT_UNAVAILABLE.name,
                            ErrorCode.FORMAT_UNAVAILABLE.retryable,
                        )
                    } else {
                        DownloadPhase.Selecting(ready.id, formats)
                    }
                } else {
                    ready.toFinalPhase()
                }
            } catch (e: EngineException) {
                e.failure.toDownloadFailure()
            } catch (e: TimeoutCancellationException) {
                LocalTimeouts.prepare()
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
                _state.update { it.copy(download = e.failure.toDownloadFailure()) }
            } catch (e: TimeoutCancellationException) {
                _state.update { it.copy(download = LocalTimeouts.download()) }
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
                        state.copy(download = e.failure.toDownloadFailure())
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

    private suspend fun awaitTerminal(baseUrl: String, initial: Task): Task {
        var current = initial
        withTimeout(LocalTimeouts.SNIFF_MS) {
            while (current.status !in TERMINAL_STATUSES) {
                delay(POLL_INTERVAL_MS)
                current = client.task(baseUrl, current.id)
            }
        }
        return current
    }

    private suspend fun awaitAwaitingSelection(baseUrl: String, initial: Task): Task {
        var current = initial
        withTimeout(LocalTimeouts.PREPARE_MS) {
            while (current.status != TaskStatus.AWAITING_SELECTION && current.status !in TERMINAL_STATUSES) {
                delay(POLL_INTERVAL_MS)
                current = client.task(baseUrl, current.id)
            }
        }
        return current
    }

    private suspend fun awaitDownloadTerminal(baseUrl: String, initial: Task): Task {
        var current = initial
        withTimeout(LocalTimeouts.DOWNLOAD_MS) {
            while (current.status !in TERMINAL_STATUSES) {
                delay(DOWNLOAD_POLL_INTERVAL_MS)
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

    private companion object {
        const val POLL_INTERVAL_MS = 400L
        const val DOWNLOAD_POLL_INTERVAL_MS = 800L

        val TERMINAL_STATUSES = setOf(TaskStatus.DONE, TaskStatus.CANCELLED, TaskStatus.FAILED)
    }
}

/**
 * 客户端本地计时到点的三条失败（嗅探 / 画质解析 / 下载）。
 *
 * `code` 一律 `null`：PROTOCOL §5 的码只允许来自引擎响应。本地伪造一个协议码
 * （下载侧曾写死 `NETWORK_TIMEOUT`）有三个后果——用户把「客户端等不及」误读成网络故障、
 * 屏上码与引擎审计行对不上、将来按码做重试策略时误判。超时窗口写进文案，
 * 界面就能自证「这是本地计时，不是引擎报的错」。
 */
internal object LocalTimeouts {
    const val SNIFF_MS = 90_000L
    const val PREPARE_MS = 120_000L
    const val DOWNLOAD_MS = 30 * 60_000L

    fun sniff(): SniffPhase.Failed =
        SniffPhase.Failed("嗅探超时 · 客户端本地计时 ${seconds(SNIFF_MS)} 到点", null, true)

    fun prepare(): DownloadPhase.Failed =
        DownloadPhase.Failed("画质解析超时 · 客户端本地计时 ${seconds(PREPARE_MS)} 到点", null, true)

    fun download(): DownloadPhase.Failed =
        DownloadPhase.Failed(
            "下载超时 · 客户端本地计时 ${seconds(DOWNLOAD_MS)} 到点，任务仍在引擎侧运行",
            null,
            true,
        )

    private fun seconds(windowMs: Long): String = "${windowMs / 1000}s"
}

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

private fun EngineFailure.toPhaseFailure(): SniffPhase.Failed = when (this) {
    is EngineFailure.Protocol -> SniffPhase.Failed(error.message, error.code.name, error.retryable)
    is EngineFailure.Transport -> SniffPhase.Failed(reason, null, true)
}

private fun EngineFailure.toDownloadFailure(): DownloadPhase.Failed = when (this) {
    is EngineFailure.Protocol -> DownloadPhase.Failed(error.message, error.code.name, error.retryable)
    is EngineFailure.Transport -> DownloadPhase.Failed(reason, null, true)
}
