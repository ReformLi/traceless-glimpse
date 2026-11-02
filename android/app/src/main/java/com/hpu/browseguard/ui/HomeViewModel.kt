package com.hpu.browseguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.EngineInfo
import com.hpu.browseguard.data.EngineSettings
import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.BackendProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.PrepareRequest
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
import com.hpu.browseguard.data.backend.toBackendFailure
import com.hpu.browseguard.shared.model.CookiePolicy
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
    /** 嗅探交给电脑引擎还是手机 WebView；默认电脑，界面可显式切换。 */
    val sniffExecutor: SniffExecutor = SniffExecutor.ENGINE,
    /** 自动降级/失败后的可见提示；`null` 表示没有要说的。 */
    val sniffNotice: String? = null,
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
    /**
     * 引擎嗅探要自检白名单；手机 WebView 嗅探不写引擎目录，离线也能发起。
     *
     * `Connection.Failed` 也放行「电脑」：这条不可达路径必须能点下去，
     * 否则自动降级没有入口——用户会看到一个永远灰着的嗅探按钮。
     */
    val canSniff: Boolean
        get() = sniff !is SniffPhase.Running &&
            (sniffExecutor == SniffExecutor.LOCAL_WEBVIEW || engineReady || connection is Connection.Failed)

    /**
     * 嗅探成功后才谈得上选画质；下载流程自己也在跑时不允许重复发起。
     *
     * 本地直链的「画质表」由嗅探结果合成，不需要引擎在线；引擎下载才要等自检。
     */
    val canPickFormats: Boolean
        get() = sniff is SniffPhase.Success &&
            download !is DownloadPhase.Preparing &&
            download !is DownloadPhase.Selecting &&
            download !is DownloadPhase.Running &&
            (downloadExecutor == DownloadExecutor.LOCAL_DIRECT || engineReady)

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
    private val engineInfo: EngineInfo,
    private val audit: AuditLogger,
) : ViewModel() {

    private var sniffJob: Job? = null
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow(
        HomeUiState(
            engineUrl = settings.baseUrl,
            engineUrlDraft = settings.baseUrl,
            downloadExecutor = settings.downloadExecutor,
            sniffExecutor = settings.sniffExecutor,
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
        // 切到本地会把用户勾着的合并静默取消，审计行要留下「为什么没合并」。
        if (executor == DownloadExecutor.LOCAL_DIRECT && snapshot.mergeSelected) {
            audit.log("merge_ignored", mapOf("reason" to "executor_switch"))
        }
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

    /**
     * 显式切换嗅探执行者。任务进行中不切：两条腿的结果不能拼成一张表；
     * 切换会作废上一条嗅探结果与画质表。
     */
    fun selectSniffExecutor(executor: SniffExecutor) {
        val snapshot = _state.value
        if (snapshot.sniffExecutor == executor) return
        if (snapshot.sniff is SniffPhase.Running || snapshot.downloadInFlight) return
        settings.sniffExecutor = executor
        _state.update {
            it.copy(
                sniffExecutor = executor,
                sniff = SniffPhase.Idle,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                sniffNotice = null,
            )
        }
    }

    fun dismissSniffNotice() {
        _state.update { it.copy(sniffNotice = null) }
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
        val sourceUrl = snapshot.sourceUrl.trim()
        if (sourceUrl.isEmpty() || !snapshot.canSniff) return

        sniffJob?.cancel()
        downloadJob?.cancel()
        _state.update { it.copy(sniff = SniffPhase.Running(TaskStatus.QUEUED), download = DownloadPhase.Idle, selectedFormatId = null) }

        val connected = snapshot.connection as? Connection.Connected
        val outputDir = connected?.selfcheck?.outputDirAllowlist?.firstOrNull()
        val request = SniffRequest(
            sourceUrl = sourceUrl,
            cookiePolicy = connected?.selfcheck?.cookiePolicyDefault ?: CookiePolicy.NONE,
            outputDir = outputDir.orEmpty(),
        )

        sniffJob = viewModelScope.launch {
            if (snapshot.sniffExecutor == SniffExecutor.LOCAL_WEBVIEW) {
                collectLocalSniff(request)
                return@launch
            }
            if (connected == null || outputDir == null) {
                // 引擎都没连上，没必要先等 90s 超时：直接降级，并留下可见提示与审计行。
                degradeToLocal("pc_unreachable", switchDownload = true)
                collectLocalSniff(request)
                return@launch
            }

            var unreachable: BackendFailure.Local? = null
            backends.sniff(SniffExecutor.ENGINE).sniff(request).collect { event ->
                if (event is SniffEvent.Failed && event.failure.isEngineUnreachable()) {
                    unreachable = event.failure as BackendFailure.Local
                } else {
                    applySniffEvent(event)
                }
            }
            val failure = unreachable ?: return@launch
            degradeToLocal(
                reason = if (failure.reason == LocalReason.TIMEOUT) "engine_timeout" else "pc_unreachable",
                // 超时说明引擎还活着，下载可以继续交给它；拒绝连接则本地下载也一并接管。
                switchDownload = failure.reason == LocalReason.ENGINE_UNREACHABLE,
            )
            collectLocalSniff(request)
        }
    }

    /** 只有「电脑不可达」与「引擎嗅探超时」值得换腿；协议不符/空结果换腿不会变好。 */
    private fun BackendFailure.isEngineUnreachable(): Boolean =
        this is BackendFailure.Local &&
            (reason == LocalReason.ENGINE_UNREACHABLE || reason == LocalReason.TIMEOUT)

    /**
     * 自动降级：切到手机 WebView，且**必须**让用户看见（[HomeUiState.sniffNotice]）
     * 并留下 `audit_sniff_fallback`——静默换执行者会让屏上结果与用户以为的那条链路对不上。
     */
    private fun degradeToLocal(reason: String, switchDownload: Boolean) {
        val hadMerge = _state.value.mergeSelected
        settings.sniffExecutor = SniffExecutor.LOCAL_WEBVIEW
        if (switchDownload) settings.downloadExecutor = DownloadExecutor.LOCAL_DIRECT
        _state.update {
            it.copy(
                sniffExecutor = SniffExecutor.LOCAL_WEBVIEW,
                downloadExecutor = if (switchDownload) DownloadExecutor.LOCAL_DIRECT else it.downloadExecutor,
                mergeSelected = if (switchDownload) false else it.mergeSelected,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                sniffNotice = if (switchDownload) {
                    "电脑不可达，已降级为手机本地嗅探与下载"
                } else {
                    "电脑嗅探超时，已降级为手机本地嗅探"
                },
            )
        }
        audit.log(
            "audit_sniff_fallback",
            mapOf(
                "reason" to reason,
                "from" to "engine",
                "to" to "local_webview",
                "download" to if (switchDownload) "local_direct" else "engine",
            ),
        )
        if (switchDownload && hadMerge) audit.log("merge_ignored", mapOf("reason" to "executor_switch"))
    }

    private suspend fun collectLocalSniff(request: SniffRequest) {
        backends.sniff(SniffExecutor.LOCAL_WEBVIEW).sniff(request).collect { event ->
            applySniffEvent(event)
            // 本地也失败要当场升级提示，不能等流关闭——WebView 失败后流可能就此挂住。
            if (event is SniffEvent.Failed) upgradeFallbackNotice()
        }
    }

    private fun upgradeFallbackNotice() {
        val snapshot = _state.value
        val note = snapshot.sniffNotice ?: return
        if (snapshot.sniff !is SniffPhase.Failed || note.contains(LOCAL_ALSO_FAILED)) return
        _state.update { it.copy(sniffNotice = "$note；$LOCAL_ALSO_FAILED") }
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
        val sourceUrl = snapshot.sourceUrl.trim()
        if (sourceUrl.isEmpty() || !snapshot.canPickFormats) return

        val connected = snapshot.connection as? Connection.Connected
        val outputDir = when (snapshot.downloadExecutor) {
            DownloadExecutor.ENGINE ->
                connected?.selfcheck?.outputDirAllowlist?.firstOrNull() ?: return

            // 本机直链的画质表由嗅探结果合成，不读这个目录；真写盘时用 ViaLocal 的沙箱根。
            DownloadExecutor.LOCAL_DIRECT -> snapshot.localSandboxRoot.orEmpty()
        }

        sniffJob?.cancel()
        downloadJob?.cancel()
        _state.update { it.copy(download = DownloadPhase.Preparing(TaskStatus.QUEUED), selectedFormatId = null) }
        downloadJob = viewModelScope.launch {
            backends.download(snapshot.downloadExecutor)
                .prepare(
                    PrepareRequest(
                        sourceUrl = sourceUrl,
                        cookiePolicy = connected?.selfcheck?.cookiePolicyDefault ?: CookiePolicy.NONE,
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
        if (!_state.value.mergeAvailable) {
            if (enabled) audit.log("merge_ignored", mapOf("reason" to "local_direct_locked"))
            return
        }
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
                    audit.log("merge_ignored", mapOf("reason" to "no_ffmpeg"))
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
                    Connection.Connected(engineInfo.health(baseUrl), engineInfo.selfcheck(baseUrl))
                } catch (e: EngineException) {
                    Connection.Failed(e.failure.reason())
                },
            )
        }
    }

    companion object {
        /** 降级后本地也失败时的提示后缀；测试与真机审计都认这几字。 */
        const val LOCAL_ALSO_FAILED = "手机本地嗅探也失败，请检查网络或电脑引擎"

        /** Compose 侧唯一的构造入口：依赖从组装根来，不在界面里 new 后端。 */
        fun factory(
            settings: EngineSettings,
            backends: BackendProvider,
            engineInfo: EngineInfo,
            audit: AuditLogger,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        "HomeViewModel.Factory 不认识的类型：$modelClass"
                    }
                    return HomeViewModel(settings, backends, engineInfo, audit) as T
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
