package com.hpu.browseguard.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.EngineSettings
import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.BackendProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.PrepareRequest
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffExecutor
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
    /**
     * 手机侧唯一允许的写盘根（应用专属外部存储的 Download 目录）。
     *
     * 与 [outputDir] 不是一回事：那份是引擎进程在电脑上解析的白名单，只能授权电脑侧写盘。
     * `null` 表示外部存储不可用——本地模式会据此直接拒绝，不会改用别的目录。
     */
    val localSandboxRoot: String? = null,
) {
    /** 引擎侧输出目录：只给 `mode=download` 的引擎任务用，本地直链不得拿它当写盘路径。 */
    val outputDir: String?
        get() = (connection as? Connection.Connected)?.selfcheck?.outputDirAllowlist?.firstOrNull()

    /** 有任务正跑着（等画质表或下载中）：换执行者会把两条链路的事实搅在一起，界面据此禁用开关。 */
    val downloadInFlight: Boolean
        get() = download is DownloadPhase.Preparing || download is DownloadPhase.Running

    /** 合并需要 FFmpeg，本地直链这一支没有——不是「暂不支持」，是压根没有执行者。 */
    val mergeAvailable: Boolean
        get() = downloadExecutor == DownloadExecutor.ENGINE
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

/**
 * 首页状态机。
 *
 * 设置与后端都从外部注入：这个类不再认识 `Application`、`SharedPreferences`
 * 或任何具体后端，JVM 单测可以用假设置 + FakeBackend 直接驱动状态流转。
 */
class HomeViewModel(
    private val settings: EngineSettings,
    private val backends: BackendProvider,
) : ViewModel() {

    private val client = EngineClient()

    private var sniffJob: Job? = null
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(
        HomeUiState(
            engineUrl = settings.baseUrl,
            engineUrlDraft = settings.baseUrl,
            downloadExecutor = settings.downloadExecutor,
            // 本地模式没有执行合并的后端；默认勾选在这里也必须落回 false，不能留着「勾着但无效」。
            mergeSelected = settings.downloadExecutor != DownloadExecutor.LOCAL_DIRECT,
            localSandboxRoot = backends.localSandboxRoot,
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
        if (!settings.save(_state.value.engineUrlDraft)) {
            _state.update { it.copy(engineUrlInvalid = true) }
            return
        }
        _state.update { it.copy(engineUrl = settings.baseUrl, engineUrlInvalid = false) }
        refreshConnection()
    }

    /**
     * 切换下载执行者。
     *
     * 有任务在跑时**不切**：旧实现会 `downloadJob?.cancel()` + 把阶段打回 `Idle`，
     * 等于用户点一下开关就把正在下载的任务连状态一起丢掉。现在界面把两颗按钮禁用并给出原因，
     * 这里再做第二道门（界面之外还有代码路径会调它）。
     * 没有在跑的任务时，切换会把 `Selecting` 的画质表一并作废——表的内容本来就属于某个执行者。
     */
    fun selectDownloadExecutor(executor: DownloadExecutor) {
        val snapshot = _state.value
        if (snapshot.downloadExecutor == executor) return
        if (snapshot.downloadInFlight) return
        settings.downloadExecutor = executor
        _state.update {
            it.copy(
                downloadExecutor = executor,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                // 切到本地时把合并取消掉：复选框会置灰，勾着却不会发生比不勾更误导。
                mergeSelected = if (executor == DownloadExecutor.LOCAL_DIRECT) false else it.mergeSelected,
            )
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
            // 裁决 2：嗅探固定走引擎；WebView 这条腿等第 3 步③接线后才可能被选中。
            backends.sniff(SniffExecutor.ENGINE).sniff(request).collect { event -> applySniffEvent(event) }
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
            backends.download(snapshot.downloadExecutor)
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

            // 单行表直接选中：只有一条可选时再让用户点一次没有信息量，
            // 「开始下载」按钮随 selectedFormat 就绪自动变成可点的高亮态。
            is DownloadEvent.Selecting -> _state.update {
                it.copy(
                    download = DownloadPhase.Selecting(event.taskId, event.formats),
                    selectedFormatId = event.formats.singleOrNull()?.formatId,
                )
            }

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
        // 本地模式没有合并的后端：界面已置灰，这里再挡一次编程调用，别让状态里出现勾着的空操作。
        if (!_state.value.mergeAvailable) return
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
                val sandboxRoot = snapshot.localSandboxRoot
                if (link == null) {
                    // 找不到对应直链 = 状态与画质表已脱节（中途重新嗅探过）。说清楚，不让按钮变哑。
                    _state.update {
                        it.copy(
                            download = LocalFailures.unsupportedLink("嗅探结果已过期，请重新嗅探")
                                .toDownloadPhase(),
                        )
                    }
                    return
                }
                if (sandboxRoot == null) {
                    _state.update {
                        it.copy(download = LocalFailures.outputDirRejected("手机外部存储不可用").toDownloadPhase())
                    }
                    return
                }
                if (snapshot.mergeSelected) {
                    // 勾了合并但这一支没有 FFmpeg：忽略并留审计行，绝不假装合并成功。
                    Log.w(TAG, "merge_ignored executor=local_direct reason=no_ffmpeg")
                }
                StartRequest.ViaLocal(
                    link = link,
                    outputDir = sandboxRoot,
                    allowedRoots = listOf(sandboxRoot),
                )
            }
        }
        downloadJob = viewModelScope.launch {
            backends.download(snapshot.downloadExecutor).start(request).collect { event -> applyDownloadEvent(event) }
        }
    }

    /** 取消只发协议请求，终态仍由 [startDownload] 那条流写入——避免两处同时改状态。 */
    fun cancelDownload() {
        val snapshot = _state.value
        val running = snapshot.download as? DownloadPhase.Running ?: return
        if (running.cancelRequested) return
        _state.update { it.copy(download = running.copy(cancelRequested = true)) }
        viewModelScope.launch {
            backends.download(snapshot.downloadExecutor).cancel(running.task.id)?.let { failure ->
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
        val baseUrl = settings.baseUrl
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

    companion object {
        /** Compose 侧唯一的构造入口：依赖从组装根来，不在界面里 new 后端。 */
        fun factory(settings: EngineSettings, backends: BackendProvider): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        "HomeViewModel.Factory 不认识的类型：$modelClass"
                    }
                    return HomeViewModel(settings, backends) as T
                }
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

/** 审计行专用：日志只描述客户端自己的决定，不冒充引擎。 */
private const val TAG = "HomeViewModel"
