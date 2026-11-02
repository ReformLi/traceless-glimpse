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
import com.hpu.browseguard.data.backend.DownloadBackend
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.HttpDownloader
import com.hpu.browseguard.data.backend.HttpSniffer
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.PrepareRequest
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
import com.hpu.browseguard.data.backend.toBackendFailure
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.PostActionType
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** 下载交给引擎还是本机直链——全局开关，只切下载这一段。 */
    val downloadExecutor: DownloadExecutor = DownloadExecutor.ENGINE,
) {
    /** 输出目录只能来自引擎自检白名单；本地下載也用它，授权口径不因执行者而变。 */
    val outputDir: String?
        get() = (connection as? Connection.Connected)?.selfcheck?.outputDirAllowlist?.firstOrNull()
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

    private val _state = MutableStateFlow(
        HomeUiState(
            engineUrl = config.baseUrl,
            engineUrlDraft = config.baseUrl,
            downloadExecutor = config.downloadExecutor,
        ),
    )
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

    /**
     * 切换下载执行者。切完把下载流程清零：换了执行者还接着跑，
     * 「本机 `.part`」和「引擎任务」就会各说一半。
     */
    fun selectDownloadExecutor(executor: DownloadExecutor) {
        if (_state.value.downloadExecutor == executor) return
        config.downloadExecutor = executor
        downloadJob?.cancel()
        _state.update {
            it.copy(downloadExecutor = executor, download = DownloadPhase.Idle, selectedFormatId = null)
        }
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
            downloadBackend()
                .prepare(
                    PrepareRequest(
                        sourceUrl = sourceUrl,
                        cookiePolicy = connected.selfcheck.cookiePolicyDefault,
                        outputDir = outputDir,
                        sniffResults = (snapshot.sniff as? SniffPhase.Success)?.results.orEmpty(),
                    ),
                )
                .collect { event -> applyDownloadEvent(event) }
        }
    }

    /** 界面按全局开关选后端，两条流程对界面是同一条——画质表那一屏的形状不因执行者而变。 */
    private fun downloadBackend(): DownloadBackend = when (_state.value.downloadExecutor) {
        DownloadExecutor.ENGINE -> HttpDownloader(config.baseUrl, client)
        DownloadExecutor.LOCAL_DIRECT -> LocalDirectDownloader()
    }

    /**
     * 事件 → 界面镜像。写入条件与改造前一致：中间态各有一句「别覆盖更新的阶段」，
     * 终态与失败一律落盘。
     */
    private fun applyDownloadEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Preparing -> _state.update { state ->
                if (state.download !is DownloadPhase.Preparing) return@update state
                state.copy(download = DownloadPhase.Preparing(event.status))
            }

            is DownloadEvent.Selecting ->
                _state.update { it.copy(download = DownloadPhase.Selecting(event.taskId, event.formats)) }

            is DownloadEvent.Started -> _state.update { it.copy(download = DownloadPhase.Running(event.task)) }

            // 只有已经是 Running 才采纳轮询结果，并把界面上的「取消已请求」标记带过去
            is DownloadEvent.Progress -> _state.update { state ->
                val previous = state.download as? DownloadPhase.Running ?: return@update state
                state.copy(download = DownloadPhase.Running(event.task, previous.cancelRequested))
            }

            is DownloadEvent.Terminal -> _state.update { it.copy(download = event.task.toFinalPhase()) }
            is DownloadEvent.Failed -> _state.update { it.copy(download = event.failure.toDownloadPhase()) }
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
        val request = when (snapshot.downloadExecutor) {
            DownloadExecutor.ENGINE -> StartRequest.ViaEngine(
                taskId = selecting.taskId,
                formatSelector = FormatSelection.selectorFor(format),
                namingTemplate = snapshot.namingTemplate.trim().takeIf { it.isNotEmpty() },
                postActions = if (snapshot.mergeSelected) listOf(PostAction(PostActionType.MERGE)) else null,
            )

            DownloadExecutor.LOCAL_DIRECT -> {
                val link = (snapshot.sniff as? SniffPhase.Success)?.results
                    ?.firstOrNull { localTaskId(it.mediaUrl) == format.formatId }
                val outputDir = snapshot.outputDir
                if (link == null || outputDir == null) {
                    // 找不到对应直链 = 状态与画质表已脱节（中途重新嗅探过）。说清楚，不让按钮变哑。
                    _state.update {
                        it.copy(
                            download = LocalFailures.unsupportedLink("嗅探结果已过期，请重新嗅探")
                                .toDownloadPhase(),
                        )
                    }
                    return
                }
                StartRequest.ViaLocal(link = link, outputDir = outputDir)
            }
        }
        downloadJob = viewModelScope.launch {
            downloadBackend().start(request).collect { event -> applyDownloadEvent(event) }
        }
    }

    /** 取消只发协议请求，终态仍由 [startDownload] 那条流写入——避免两处同时改状态。 */
    fun cancelDownload() {
        val running = _state.value.download as? DownloadPhase.Running ?: return
        if (running.cancelRequested) return
        _state.update { it.copy(download = running.copy(cancelRequested = true)) }
        viewModelScope.launch {
            downloadBackend().cancel(running.task.id)?.let { failure ->
                _state.update { state ->
                    if (state.download is DownloadPhase.Running) {
                        state.copy(download = failure.toDownloadPhase())
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
