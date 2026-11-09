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
import com.hpu.browseguard.data.backend.RunMode
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
import com.hpu.browseguard.data.backend.toBackendFailure
import com.hpu.browseguard.data.cookie.CookieConsentStore
import com.hpu.browseguard.data.cookie.DenyAllCookieConsent
import com.hpu.browseguard.data.cookie.cookieHostOf
import com.hpu.browseguard.data.download.ConsentWait
import com.hpu.browseguard.data.download.DownloadCoordinator
import com.hpu.browseguard.data.download.HlsConcurrency
import com.hpu.browseguard.data.download.QueuedTask
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.formatSummary
import com.hpu.browseguard.data.history.sourceHost
import com.hpu.browseguard.data.keepalive.KeepAliveStarter
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.PostActionType
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID

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

/**
 * 本机嗅探的分层。
 *
 * 简单嗅探（离屏）没抓到直链时，自动换成真实 WebView 在隐藏状态继续深嗅；
 * 隐藏深嗅也没有结果，就请用户把页面露出来点播放（有些站点点了播放才发流）。
 */
enum class DeepSniffPhase {
    Off,

    /** 隐藏 WebView 正在加载/观察，主页显示「深度嗅探中」。 */
    Running,

    /** 隐藏深嗅已经抓到可下载直链，结果已回填主页。 */
    Ready,

    /** 隐藏深嗅也没抓到：主页给出「显示页面再嗅探」的入口。 */
    NoResult,
}

/** 画质表已就绪、等用户点「开始下载」的两个阶段共用的形状。 */
interface FormatPickable {
    val taskId: String
    val formats: List<FormatInfo>
}

/**
 * 画质表阶段。
 *
 * 主页只管到「把这条任务交给下载队列」为止：真正的下载进度与终态由
 * [DownloadCoordinator] 写库，下载管理页读那张表——所以这里没有 Running/Success 了。
 */
sealed interface DownloadPhase {
    data object Idle : DownloadPhase

    /** 已提交 `mode=download` 任务，等引擎进入 `awaiting_selection`。 */
    data class Preparing(val status: TaskStatus) : DownloadPhase

    /** 引擎给出可选格式表（PROTOCOL §6.3）。 */
    data class Selecting(
        override val taskId: String,
        override val formats: List<FormatInfo>,
    ) : DownloadPhase, FormatPickable

    data class Failed(val message: String, val code: String?, val retryable: Boolean) : DownloadPhase
}

/**
 * 主页顶部那条「下载中」提示的数据。
 *
 * 进度直接来自历史表（协调器每秒写一次），所以它和下载管理页永远是同一份数字；
 * [onEngine] 只用来把「电脑在下载」和「手机在下载」分开说——两条腿的等待感受完全不同。
 */
data class ActiveDownloadInfo(
    val recordId: String,
    val title: String,
    val downloadedBytes: Long,
    val bytesTotal: Long,
    val speedBps: Long,
    val onEngine: Boolean,
)

data class HomeUiState(
    val engineUrl: String = "",
    val engineUrlDraft: String = "",
    val engineUrlInvalid: Boolean = false,
    val connection: Connection = Connection.Idle,
    val sourceUrl: String = "",
    val sniff: SniffPhase = SniffPhase.Idle,
    val download: DownloadPhase = DownloadPhase.Idle,
    val selectedFormatId: String? = null,
    /** 用户填的产物主名（M24）：留空 = 用站点标题；后缀一律由系统补。 */
    val outputName: String = "",
    val mergeSelected: Boolean = true,
    /**
     * 纯音频下载（M24）：只提取声音、丢掉画面，后缀由引擎按真实音轨决定。
     *
     * 与 [mergeSelected] 互斥——没有视频轨就没有可合并的东西。
     */
    val audioOnly: Boolean = false,
    /**
     * 嗅探与下载的统一模式（M23）：两步必须同侧，不再有独立的「下载执行者」。
     *
     * 这一条是状态里的唯一真值；[sniffExecutor] / [downloadExecutor] 都由它派生，
     * 所以界面不可能再出现「嗅探说本机、下载跑去电脑」的自相矛盾。
     */
    val mode: RunMode = RunMode.LOCAL,
    /** 下载还在跑时挂前台服务保活；默认开，切后台/灭屏也不许被系统冻住。 */
    val keepAliveWhileDownloading: Boolean = true,
    /** HLS 分段并发（M25）：引擎模式随下载请求上行，本机模式注入 HLS 下载器。 */
    val hlsConcurrency: Int = HlsConcurrency.DEFAULT,
    /** 浏览器里默认直接露出网页；只在 App 冷启动时决定初值。 */
    val showPageByDefault: Boolean = false,
    /**
     * 是否显示内置浏览器（地址栏 + WebView + 嗅探球）。
     *
     * 只有用户显式选了本机嗅探才可能为真；电脑不可达时的自动降级仍走离屏嗅探，
     * 免得浏览器停在空白页、而结果落在看不见的另一条腿上。
     */
    val isWebViewVisible: Boolean = false,
    /** 浏览器里的网页是否露出；关闭时 WebView 仍在后台正常渲染，只盖一层提示。 */
    val browserPageVisible: Boolean = false,
    /**
     * 浏览器 WebView 是否已创建（可见浏览或隐藏深嗅）。
     *
     * 全 App 同一时刻只允许一个 WebView：置 false 时界面层会立刻销毁实例。
     */
    val browserSessionActive: Boolean = false,
    /** 隐藏深嗅阶段；[DeepSniffPhase.Off] 表示没有在跑。 */
    val deepSniff: DeepSniffPhase = DeepSniffPhase.Off,
    /** 嗅探时强制静音网页；默认开启，隐藏深嗅绝不能出声。 */
    val muteWhileSniffing: Boolean = true,
    /** 自动降级/失败后的可见提示；`null` 表示没有要说的。 */
    val sniffNotice: String? = null,
    /**
     * 差一次 Cookie 授权的下载任务。
     *
     * 下载已经交给队列，这一条是队列回过头来问主页的：主页复位回输入状态之后，
     * 仍然要能替用户接住这一句「同意吗」。
     */
    val consentWait: ConsentWait? = null,
    /** 交给下载队列后的一次性轻提示；界面读完就该由 [HOME_HINT_CONSUMED] 那次更新清掉。 */
    val queuedHint: String? = null,
    /** 入队被拒这类必须让用户立刻看见的错误：红色横幅，不能用轻提示混过去。 */
    val downloadError: String? = null,
    /**
     * 手机侧唯一允许的写盘根（应用专属外部存储的 Download 目录）。
     *
     * 与 [outputDir] 不是一回事：那份是引擎进程在电脑上解析的白名单，只能授权电脑侧写盘。
     * `null` 表示外部存储不可用——本地模式会据此直接拒绝，不会改用别的目录。
     */
    val localSandboxRoot: String? = null,
    /**
     * 正在下载的那一行：任务入队后主页只留这一条提示，进度与下载管理页同源。
     * 没有进行中的任务时为 null——历史页的旧记录不会跑到这里来。
     */
    val activeDownload: ActiveDownloadInfo? = null,
) {
    /** 嗅探侧执行者，由 [mode] 派生；只读——要换就换整个模式。 */
    val sniffExecutor: SniffExecutor
        get() = mode.sniffExecutor

    /** 下载侧执行者，由 [mode] 派生；只读——要换就换整个模式。 */
    val downloadExecutor: DownloadExecutor
        get() = mode.downloadExecutor

    /** 引擎侧输出目录：只给 `mode=download` 的引擎任务用，本地直链不得拿它当写盘路径。 */
    val outputDir: String?
        get() = (connection as? Connection.Connected)?.selfcheck?.outputDirAllowlist?.firstOrNull()

    /**
     * 有任务正跑着（等画质表、下载中、等授权）：换执行者会把两条链路的事实搅在一起，
     * 界面据此禁用开关。等授权也算在内——那张画质表本身就是重试要用的东西。
     */
    /**
     * 画质表这一段还在跑：换执行者会把两条链路的事实搅在一起，界面据此禁用开关。
     *
     * 已经交给下载队列的任务不算在内——它已经不归这个页面管了。
     */
    val downloadInFlight: Boolean
        get() = download is DownloadPhase.Preparing

    /** 合并需要 FFmpeg，本地直链这一支没有——不是「暂不支持」，是压根没有执行者。 */
    val mergeAvailable: Boolean
        get() = downloadExecutor == DownloadExecutor.ENGINE

    /**
     * 纯音频要「抽音轨」，本机直链只搬字节、没有 FFmpeg/MediaExtractor 这一层，
     * 与合并同一口径：只有电脑引擎能落地。
     */
    val audioOnlyAvailable: Boolean
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
     * 嗅探成功后才谈得上选画质；画质表正在拉时不允许重复发起。
     *
     * 不再按执行器卡门：本地直链的画质表由嗅探结果合成，引擎下载才要等自检——
     * 设置选了「电脑下载」但引擎没就绪时，[pickFormats] 会回落到本机直链，
     * 把按钮卡死在灰色只会把手机明明能下的链接堵在门外。
     * 等授权时不再让重挑画质——那张表本身就是重试要用的东西。
     */
    val canPickFormats: Boolean
        get() = sniff is SniffPhase.Success &&
            download !is DownloadPhase.Preparing &&
            download !is FormatPickable

    val selectedFormat: FormatInfo?
        get() = (download as? FormatPickable)?.formats?.firstOrNull { it.formatId == selectedFormatId }

    val outputNameError: String?
        get() = FormatSelection.outputNameError(outputName.trim())

    val canStartDownload: Boolean
        get() = download is FormatPickable &&
            (audioOnly || selectedFormat != null) &&
            outputNameError == null

    /** 引擎下载的门槛：连上且自检白名单非空。画质表回落判定也认它。 */
    val engineReady: Boolean
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
    private val history: HistoryRepository,
    /** 只有域名与授权位，Cookie 值永远不进这一层（也不进界面对象）。 */
    private val consent: CookieConsentStore = DenyAllCookieConsent,
    /** 按下「开始下载」之后，这条任务就归它了：进度、终态、SAF 复制都不回这个页面。 */
    private val downloads: DownloadCoordinator,
    /**
     * 任务进队列那一刻给保活服务打的卡。
     *
     * 只有这一条路径知道「用户此刻还在这个 App 里」——Android 12 起后台不能起前台服务，
     * 所以这张卡必须由界面动作打，不能由谁在后台观察队列观察出来。
     */
    private val keepAlive: KeepAliveStarter = KeepAliveStarter.NOOP,
    /**
     * 引擎心跳周期（毫秒）；`null` = 关闭心跳。
     *
     * 心跳是 `while + delay` 的无限周期任务——JVM 测试的 `advanceUntilIdle` 会把
     * 虚拟时间推到天荒地老都不肯停，所以测试必须显式传 `null` 关掉它。
     */
    private val heartbeatIntervalMs: Long? = HEARTBEAT_INTERVAL_MS,
) : ViewModel() {

    private var sniffJob: Job? = null
    private var downloadJob: Job? = null

    /** 引擎模式专属的存活检测定时器；切回本机或自动降级即停。 */
    private var heartbeatJob: Job? = null

    /** 心跳连续失败计数：一拍波动不判离线，连续 [HEARTBEAT_FAILURES_TO_OFFLINE] 拍才判。 */
    private var heartbeatFailures = 0

    /**
     * 授权之后要自动接着做下去的画质 id。
     *
     * 「同意即重试」会让嗅探 → 挑画质 → 下载这三步在用户只点一次的情况下连着跑完，
     * 中途没有任何一处能再问用户「还要不要这条」——所以答案必须在进入等待态时记下来。
     */
    private var resumeFormatId: String? = null

    /** 当前这次嗅探/下载在历史里的行 id；一次用户动作只写一行。 */
    private var historyId: String? = null
    private var historyTaskId: String? = null

    /** 浏览器面板点「下载」后：画质表一回来就直接开下，中间不再让用户按第二次。 */
    private var autoStartSelection = false

    /**
     * 这次等待授权来自内置浏览器：同意的只是「读 Cookie」，页面一直活着、Cookie 也已经在容器里，
     * 所以续跑直接开下，不再像离屏嗅探那样重放一次页面。
     */
    private var browserDownloadActive = false

    /** 浏览器页标题，只给本地产物起名；引擎任务不接受这个字段。 */
    private var browserProductName: String? = null

    /**
     * 本次嗅探是不是电脑做的。
     *
     * 只在「引擎嗅探成功、但下载那一步回落到本机直链」时才被读到：那时直链已经在手，
     * 手机这边从没打开过那一页，为 Cookie 重放嗅探毫无意义（重放也拿不到引擎的结果）。
     */
    private var sniffRanOnEngine = false

    /** 离开浏览器这条腿：自动开下、授权续跑与产物名都不该跟着跑到下一次离屏嗅探上。 */
    private fun clearBrowserSession() {
        autoStartSelection = false
        browserDownloadActive = false
        browserProductName = null
    }

    private val _state = MutableStateFlow(
        HomeUiState(
            engineUrl = settings.baseUrl,
            engineUrlDraft = settings.baseUrl,
            mode = settings.mode,
            keepAliveWhileDownloading = settings.keepAliveWhileDownloading,
            hlsConcurrency = settings.hlsConcurrency,
            showPageByDefault = settings.showPageByDefault,
            // 冷启动才消费「默认直接显示网页」；本机模式之外没有浏览器可显示。
            isWebViewVisible = settings.mode == RunMode.LOCAL &&
                settings.showPageByDefault,
            browserPageVisible = settings.mode == RunMode.LOCAL &&
                settings.showPageByDefault,
            browserSessionActive = settings.mode == RunMode.LOCAL &&
                settings.showPageByDefault,
            muteWhileSniffing = settings.muteWhileSniffing,
            // 本机模式没有执行合并的后端；默认勾选在这里也必须落回 false，不能留着「勾着但无效」。
            mergeSelected = settings.mode == RunMode.ENGINE,
            localSandboxRoot = backends.localSandboxRoot,
        ),
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refreshConnection()
        // 冷启动就是引擎模式：心跳立即上岗，让「电脑关机了」在 10 秒内反映到状态胶囊上。
        if (settings.mode == RunMode.ENGINE) startHeartbeat()
        // 队列里排队等授权的任务由这里接住：主页已经复位，只有这个页面还能问用户「同意吗」。
        viewModelScope.launch {
            downloads.consentWaits.collect { waits -> applyConsentWaits(waits.firstOrNull()) }
        }
        // 主页的「下载中」条：直接读历史表，与下载管理页共用同一份进度。
        viewModelScope.launch {
            history.records.collect { rows -> applyActiveDownload(rows) }
        }
    }

    private fun applyConsentWaits(wait: ConsentWait?) {
        _state.update {
            if (it.consentWait == wait) it else it.copy(consentWait = wait)
        }
    }

    /**
     * 历史表 → 主页那条「下载中」提示。
     *
     * 只认 [RecordStatus.DOWNLOADING]：嗅探中与等画质都不算下载，混进来会让首页
     * 在用户只是点了一下「嗅探」时就显示一条进度条。
     * 值相同就不写状态，免得每秒一次的心跳把整页重组一遍。
     */
    private fun applyActiveDownload(rows: List<DownloadRecord>) {
        val row = rows
            .filter { it.status == RecordStatus.DOWNLOADING }
            .maxByOrNull { it.updatedAt }
        val info = row?.let {
            ActiveDownloadInfo(
                recordId = it.id,
                title = it.title ?: sourceHost(it.url).ifBlank { it.url },
                downloadedBytes = it.downloadedBytes,
                bytesTotal = it.bytesTotal,
                speedBps = it.speedBps,
                // 引擎产物的落盘路径只有 done 才有；下载中还是 null，说明文件不在手机上。
                onEngine = it.outputPath == null,
            )
        }
        _state.update { if (it.activeDownload == info) it else it.copy(activeDownload = info) }
    }

    /** Snackbar 读过一次就该消失，别让下一次回到主页再播一遍。 */
    fun consumeQueuedHint() {
        _state.update { it.copy(queuedHint = null) }
    }

    /** 红色错误横幅由用户手动收起；下一次成功入队也会自动清掉。 */
    fun dismissDownloadError() {
        _state.update { it.copy(downloadError = null) }
    }

    fun refreshConnection() {
        viewModelScope.launch { connect() }
    }

    /**
     * 启动引擎心跳：每 [heartbeatIntervalMs] 一拍，向 `GET /v1/health` 打点。
     *
     * 已在跑就不叠第二层——重复定时器会让失败计数互相踩。挂靠 `viewModelScope`，
     * `onCleared` 随作用域一起安全取消；与本页的嗅探/下载协程互不相干，
     * 心跳超时只取消自己的请求，碰不到正在下载的任务流。
     */
    private fun startHeartbeat() {
        val interval = heartbeatIntervalMs ?: return
        if (heartbeatJob?.isActive == true) return
        heartbeatFailures = 0
        heartbeatJob = viewModelScope.launch {
            while (isActive) {
                delay(interval)
                heartbeatTick()
            }
        }
    }

    /** 切回本机嗅探（或自动降级）即停：本机模式没有要盯的电脑。 */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        heartbeatFailures = 0
    }

    /**
     * 一拍心跳：3 秒超时内答上来算活着。
     *
     * 成功 → 失败计数清零；若当前已是「离线」则完整重建连接（health + selfcheck），
     * 状态胶囊自动回绿——`Checking` 态说明用户正点着「测试连接」，让那一次 connect
     * 自己说话，心跳不抢。失败 → 计数 +1；连续 [HEARTBEAT_FAILURES_TO_OFFLINE] 拍
     * 且当前仍是「在线」才落「离线」，一次网络波动不值得让状态闪红。
     */
    private suspend fun heartbeatTick() {
        val alive = try {
            withTimeout(HEARTBEAT_TIMEOUT_MS) { engineInfo.health(settings.baseUrl) }
            true
        } catch (e: EngineException) {
            false
        } catch (e: TimeoutCancellationException) {
            false
        }
        if (alive) {
            heartbeatFailures = 0
            if (_state.value.connection is Connection.Failed) connect()
        } else {
            heartbeatFailures++
            if (heartbeatFailures >= HEARTBEAT_FAILURES_TO_OFFLINE &&
                _state.value.connection is Connection.Connected
            ) {
                _state.update { it.copy(connection = Connection.Failed(ENGINE_OFFLINE_REASON)) }
            }
        }
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
     * 切换「嗅探和下载模式」（M23：两个执行者已合并成一条）。
     *
     * 有任务在跑时**不切**：旧实现会 `downloadJob?.cancel()` + 把阶段打回 `Idle`，
     * 等于用户点一下开关就把正在下载的任务连状态一起丢掉。现在界面把两颗按钮禁用并给出原因，
     * 这里再做第二道门（界面之外还有代码路径会调它）。
     * 没有在跑的任务时，切换会把 `Selecting` 的画质表一并作废——表的内容本来就属于某条腿。
     */
    fun selectMode(mode: RunMode) {
        val snapshot = _state.value
        if (snapshot.mode == mode) return
        if (snapshot.downloadInFlight) return
        // 等一次 Cookie 授权时也不切：那一条任务已经按旧模式排进队列，
        // 点头之后要按原样续跑，中途换腿会让「同意并继续」答非所问。
        if (snapshot.consentWait != null) return
        // 嗅探正在跑（隐藏深嗅除外）也不切：两条腿的结果不能拼成一张表。
        val deepRunning = snapshot.deepSniff == DeepSniffPhase.Running
        if (snapshot.sniff is SniffPhase.Running && !deepRunning) return

        settings.mode = mode
        // 切到本机会把用户勾着的合并静默取消，审计行要留下「为什么没合并」。
        if (mode == RunMode.LOCAL && snapshot.mergeSelected) {
            audit.log("merge_ignored", mapOf("reason" to "executor_switch"))
        }
        if (mode == RunMode.LOCAL && snapshot.audioOnly) {
            audit.log("audio_only_ignored", mapOf("reason" to "executor_switch"))
        }
        autoStartSelection = false
        browserDownloadActive = false
        closeBrowserSession()
        // 心跳跟着模式走：选电脑盯电脑，回本机立刻停拍，别让定时器空转。
        if (mode == RunMode.ENGINE) startHeartbeat() else stopHeartbeat()
        _state.update {
            it.copy(
                mode = mode,
                sniff = SniffPhase.Idle,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                sniffNotice = null,
                // 切到本机时把合并取消掉：复选框会置灰，勾着却不会发生比不勾更误导。
                mergeSelected = if (mode == RunMode.LOCAL) false else it.mergeSelected,
                audioOnly = if (mode == RunMode.LOCAL) false else it.audioOnly,
            )
        }
    }

    /** 主页 / 浏览器左上角的「关闭」：退回输入地址的主页面，同时销毁 WebView。 */
    fun exitBrowser() = closeBrowserSession()

    /**
     * 结束浏览器会话：状态一落，界面层立即释放（destroy）唯一的 WebView。
     *
     * 关闭页面、切走底部 Tab、切换嗅探执行者都走这里——任何一条都不该让后台
     * 会话继续攥着 WebView 或继续播视频。
     */
    fun closeBrowserSession() {
        clearBrowserSession()
        _state.update {
            it.copy(
                isWebViewVisible = false,
                browserPageVisible = false,
                browserSessionActive = false,
                deepSniff = DeepSniffPhase.Off,
            )
        }
    }

    /** 主页「显示网页」入口：本机模式才展开 WebView；[showPage] 决定是否直接露出页面。 */
    fun openBrowser(showPage: Boolean) {
        val snapshot = _state.value
        if (snapshot.sniffExecutor != SniffExecutor.LOCAL_WEBVIEW) return
        clearBrowserSession()
        _state.update {
            it.copy(
                browserSessionActive = true,
                isWebViewVisible = true,
                browserPageVisible = showPage,
                deepSniff = DeepSniffPhase.Off,
            )
        }
    }

    /** 浏览器顶栏与覆盖层的显隐开关；WebView 本身始终活着。 */
    fun setBrowserPageVisible(visible: Boolean) {
        _state.update { it.copy(browserPageVisible = visible) }
    }

    /** 「嗅探时静音网页」开关：只影响后续创建的 WebView，不改当前会话。 */
    fun setMuteWhileSniffing(value: Boolean) {
        settings.muteWhileSniffing = value
        _state.update { it.copy(muteWhileSniffing = value) }
    }

    /** 隐身模式开关：只影响界面默认露不露网页，不改嗅探链路。 */
    fun setShowPageByDefault(value: Boolean) {
        settings.showPageByDefault = value
        _state.update { it.copy(showPageByDefault = value) }
    }

    /**
     * 「下载保活」开关：关掉后正在挂的服务等下一次心跳自己收摊，这里不直接碰 Service。
     *
     * 现场打开且真有活在跑时要立刻打一次卡，否则通知栏得等到下一次入队才亮起来；
     * 队列空着就不打卡，免得服务闪一下又自己退——那一闪只会写出「正在下载 0 项」。
     */
    fun setKeepAliveWhileDownloading(value: Boolean) {
        settings.keepAliveWhileDownloading = value
        _state.update { it.copy(keepAliveWhileDownloading = value) }
        if (value && downloads.activeCount() > 0) keepAlive.onRequestStart()
    }

    /**
     * 保存 HLS 分段并发：越界/非数字一律拒绝、保持原值并留审计。
     * 返回 false 由界面显示行内错误；夹取会静默改掉用户意图，这里不做。
     */
    fun saveHlsConcurrency(raw: String): Boolean {
        val value = HlsConcurrency.parse(raw)
        if (value == null) {
            audit.log(
                "hls_concurrency_rejected",
                mapOf("reason" to (HlsConcurrency.rejectReason(raw) ?: "invalid")),
            )
            return false
        }
        settings.hlsConcurrency = value
        _state.update { it.copy(hlsConcurrency = value) }
        return true
    }

    /**
     * 内置浏览器面板点了「下载」：接管这条直链，不再走离屏嗅探。
     *
     * 浏览器页面一直活着、Cookie 也已经在容器里，所以这里直接合成一张单行画质表，
     * 表一回来就自动开下（[autoStartSelection]），用户只按过一次「下载」。
     */
    fun downloadFromBrowser(result: SniffResult, pageUrl: String, pageTitle: String?) {
        val snapshot = _state.value
        if (snapshot.downloadInFlight) return
        // 浏览器这一腿全程本机：模式不是本机就整条切过来，绝不让下载单独留在电脑上。
        val switching = snapshot.mode != RunMode.LOCAL
        if (switching) {
            settings.mode = RunMode.LOCAL
            if (snapshot.mergeSelected) audit.log("merge_ignored", mapOf("reason" to "executor_switch"))
        }
        // 浏览器这一腿全程本机：心跳没有意义，停拍。
        stopHeartbeat()
        sniffJob?.cancel()
        downloadJob?.cancel()
        resumeFormatId = null
        autoStartSelection = true
        browserDownloadActive = true
        sniffRanOnEngine = false
        browserProductName = pageTitle?.trim()?.takeIf { it.isNotEmpty() }

        val recordId = UUID.randomUUID().toString()
        historyId = recordId
        historyTaskId = null
        _state.update {
            it.copy(
                mode = RunMode.LOCAL,
                mergeSelected = if (switching) false else it.mergeSelected,
                audioOnly = if (switching) false else it.audioOnly,
                isWebViewVisible = true,
                sourceUrl = pageUrl,
                sniff = SniffPhase.Success(listOf(result)),
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                sniffNotice = null,
            )
        }
        viewModelScope.launch {
            // 页面标题在这里是唯一能拿到的时机：播放页顶栏、下载卡片顶的都是它，
            // 缺了就退回 URL 派生标题（-beginSniff 内部处理）。
            history.beginSniff(recordId, pageUrl, browserProductName)
            history.markReady(recordId, format = null)
            // 历史行先落地，后面的 Started 才有东西可回填。
            pickFormats()
        }
    }

    fun dismissSniffNotice() {
        _state.update { it.copy(sniffNotice = null) }
    }

    fun editSourceUrl(value: String) {
        resumeFormatId = null
        autoStartSelection = false
        browserDownloadActive = false
        browserProductName = null
        // 换了网址，旧的隐藏深嗅会话就没有意义了：先把 WebView 释放掉再改状态。
        if (_state.value.browserSessionActive && !_state.value.isWebViewVisible) {
            closeBrowserSession()
        }
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
        // 用户主动重嗅 = 放弃「授权后续跑」那条线索，别再悄悄接着下别的任务。
        resumeFormatId = null
        autoStartSelection = false
        browserDownloadActive = false
        browserProductName = null
        // 重新嗅探前先回收上一轮的隐藏深嗅会话，避免同时存在两个 WebView。
        if (_state.value.browserSessionActive && !_state.value.isWebViewVisible) {
            closeBrowserSession()
        }
        startSniff()
    }

    private fun startSniff() {
        val snapshot = _state.value
        val sourceUrl = snapshot.sourceUrl.trim()
        if (sourceUrl.isEmpty() || !snapshot.canSniff) return

        // 心跳已判定电脑离线：红色失败卡当场给出，不发请求也不等超时。
        // 不自动降级——切回手机嗅探是显式选择，交给设置里的模式开关（离线提示卡有直达入口）。
        if (snapshot.sniffExecutor == SniffExecutor.ENGINE && snapshot.connection is Connection.Failed) {
            _state.update {
                it.copy(sniff = SniffPhase.Failed(ENGINE_OFFLINE_HINT, null, retryable = true))
            }
            return
        }

        // 被新一次嗅探取代的旧记录不能停在「嗅探中/下载中」：它已经没有任何协程在推进。
        if (snapshot.sniff is SniffPhase.Running || snapshot.downloadInFlight) {
            historyId?.let { replaced -> viewModelScope.launch { history.markCancelled(replaced) } }
        }

        val recordId = UUID.randomUUID().toString()
        historyId = recordId
        historyTaskId = null

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
            history.beginSniff(recordId, sourceUrl)
            if (snapshot.mode == RunMode.LOCAL) {
                sniffRanOnEngine = false
                finishLocalSniff(collectLocalSniff(request), sourceUrl)
                return@launch
            }
            if (connected == null || outputDir == null) {
                // 引擎都没连上，没必要先等 90s 超时：直接整条切本机，并留下可见提示与审计行。
                degradeToLocal("pc_unreachable")
                sniffRanOnEngine = false
                finishLocalSniff(collectLocalSniff(request), sourceUrl)
                return@launch
            }

            // 本次嗅探由引擎完成：万一后面下载回落到本机，凭它判断「直链已经在手、
            // 不需要为 Cookie 重放页面」（浏览器那边从没开过这一页）。
            sniffRanOnEngine = true
            var unreachable: BackendFailure.Local? = null
            backends.sniff(SniffExecutor.ENGINE).sniff(request).collect { event ->
                if (event is SniffEvent.Failed && event.failure.isEngineUnreachable()) {
                    unreachable = event.failure as BackendFailure.Local
                } else {
                    applySniffEvent(event)
                }
            }
            val failure = unreachable ?: return@launch
            degradeToLocal(if (failure.reason == LocalReason.TIMEOUT) "engine_timeout" else "pc_unreachable")
            sniffRanOnEngine = false
            finishLocalSniff(collectLocalSniff(request), sourceUrl)
        }
    }

    /**
     * 简单嗅探结束后的分层判定：只有「页面正常结束但没有媒体」与「超时」才值得换真实
     * WebView 再试；WebView 本身起不来（[LocalReason.WEBVIEW_UNAVAILABLE]）换一层也一样。
     */
    private fun finishLocalSniff(failure: BackendFailure.Local?, sourceUrl: String) {
        if (failure == null) return
        if (failure.reason != LocalReason.NO_SNIFF_RESULTS && failure.reason != LocalReason.TIMEOUT) return
        val snapshot = _state.value
        if (snapshot.sniffExecutor != SniffExecutor.LOCAL_WEBVIEW) return
        if (snapshot.sourceUrl.trim() != sourceUrl) return
        if (snapshot.isWebViewVisible || snapshot.browserSessionActive || snapshot.downloadInFlight) return
        startHiddenDeepSniff()
    }

    /** 简单嗅探没抓到：换真实 WebView 在主页覆盖层下面继续嗅探（全程静音）。 */
    private fun startHiddenDeepSniff() {
        _state.update {
            it.copy(
                browserSessionActive = true,
                isWebViewVisible = false,
                browserPageVisible = false,
                deepSniff = DeepSniffPhase.Running,
                // 简单嗅探的失败文案由「隐藏深嗅」接手；页面还在加载，不能停在失败态。
                sniff = SniffPhase.Running(TaskStatus.SNIFFING),
            )
        }
    }

    /** 隐藏深嗅抓到直链：合并进这次嗅探的结果，主页直接走原有的画质/下载流程。 */
    fun onDeepSniffCandidates(results: List<SniffResult>) {
        if (results.isEmpty()) return
        val known = (_state.value.sniff as? SniffPhase.Success)?.results.orEmpty().map { it.mediaUrl }.toSet()
        val fresh = results.filterNot { it.mediaUrl in known }
        if (fresh.isEmpty()) return
        var accepted = false
        _state.update { state ->
            if (state.deepSniff == DeepSniffPhase.Off) return@update state
            accepted = true
            val merged = LinkedHashMap<String, SniffResult>()
            (state.sniff as? SniffPhase.Success)?.results?.forEach { merged[it.mediaUrl] = it }
            fresh.forEach { merged[it.mediaUrl] = it }
            state.copy(
                deepSniff = DeepSniffPhase.Ready,
                sniff = SniffPhase.Success(merged.values.toList()),
            )
        }
        if (accepted) record { markReady(it, format = null) }
    }

    /** 隐藏深嗅也没有结果：停在主页，提示用户显示页面点一下播放。会话先留着，切过去不用重新加载。 */
    fun onDeepSniffNoResult() {
        if (_state.value.deepSniff != DeepSniffPhase.Running) return
        _state.update {
            it.copy(
                deepSniff = DeepSniffPhase.NoResult,
                sniff = LocalFailures.webViewNoMedia().toSniffPhase(),
            )
        }
        browserDownloadActive = false
    }

    /** 历史写入统一从这里走：一次动作只对应 [historyId] 这一行。 */
    private fun record(block: suspend HistoryRepository.(id: String) -> Unit) {
        val id = historyId ?: return
        viewModelScope.launch { history.block(id) }
    }

    private fun attachHistoryTask(taskId: String) {
        if (taskId.isBlank() || taskId == historyTaskId) return
        historyTaskId = taskId
        record { attachTask(it, taskId) }
    }

    /** 只有「电脑不可达」与「引擎嗅探超时」值得换腿；协议不符/空结果换腿不会变好。 */
    private fun BackendFailure.isEngineUnreachable(): Boolean =
        this is BackendFailure.Local &&
            (reason == LocalReason.ENGINE_UNREACHABLE || reason == LocalReason.TIMEOUT)

    /**
     * 自动降级：整条模式切到本机，且**必须**让用户看见（[HomeUiState.sniffNotice]）
     * 并留下 `audit_sniff_fallback`——静默换执行者会让屏上结果与用户以为的那条链路对不上。
     *
     * M23 起没有「只切一半」这回事：嗅探换腿就意味着下载也换腿，否则又会出现
     * 「手机在嗅探、任务却发给电脑」的错位。
     */
    private fun degradeToLocal(reason: String) {
        val hadMerge = _state.value.mergeSelected
        val hadAudioOnly = _state.value.audioOnly
        settings.mode = RunMode.LOCAL
        // 已经自动换到手机这条腿：电脑不再是要盯的对象，心跳停拍。
        stopHeartbeat()
        _state.update {
            it.copy(
                mode = RunMode.LOCAL,
                // 自动降级留在旧首页：离屏嗅探正在跑，换成空浏览器只会让结果无处可看。
                isWebViewVisible = false,
                browserPageVisible = false,
                browserSessionActive = false,
                deepSniff = DeepSniffPhase.Off,
                mergeSelected = false,
                audioOnly = false,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                sniffNotice = "电脑不可达，已切换为手机本机嗅探与下载",
            )
        }
        audit.log(
            "audit_sniff_fallback",
            mapOf(
                "reason" to reason,
                "from" to "engine",
                "to" to "local",
                "download" to "local_direct",
            ),
        )
        if (hadMerge) audit.log("merge_ignored", mapOf("reason" to "executor_switch"))
        if (hadAudioOnly) audit.log("audio_only_ignored", mapOf("reason" to "executor_switch"))
    }

    private suspend fun collectLocalSniff(request: SniffRequest): BackendFailure.Local? {
        var failure: BackendFailure.Local? = null
        backends.sniff(SniffExecutor.LOCAL_WEBVIEW).sniff(request).collect { event ->
            applySniffEvent(event)
            // 本地也失败要当场升级提示，不能等流关闭——WebView 失败后流可能就此挂住。
            if (event is SniffEvent.Failed) {
                failure = event.failure as? BackendFailure.Local
                upgradeFallbackNotice()
            }
        }
        return failure
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
            is SniffEvent.Status -> {
                attachHistoryTask(event.taskId)
                _state.update { state ->
                    if (state.sniff !is SniffPhase.Running) return@update state
                    state.copy(sniff = SniffPhase.Running(event.status))
                }
            }

            is SniffEvent.Terminal -> {
                attachHistoryTask(event.task.id)
                if (event.task.status != TaskStatus.DONE) {
                    val failure = event.task.toSniffFailure()
                    record { markFailed(it, failure.code) }
                }
                _state.update { state ->
                    if (event.task.status == TaskStatus.DONE) return@update state
                    state.copy(sniff = event.task.toSniffFailure())
                }
            }

            is SniffEvent.Results -> {
                record { markReady(it, format = null) }
                _state.update { it.copy(sniff = SniffPhase.Success(event.results)) }
                resumeAfterConsent()
            }

            is SniffEvent.Failed -> {
                // 续跑的那一步在这断了：授权后重放嗅探却没拿到结果，就别再挂着「待会自动开下」。
                resumeFormatId = null
                val phase = event.failure.toSniffPhase()
                record { markFailed(it, phase.code) }
                _state.update { it.copy(sniff = phase) }
            }
        }
    }

    /** 嗅探结果出来后，另起一个 `mode=download` 任务向引擎要可选画质表。 */
    fun pickFormats() {
        var snapshot = _state.value
        val sourceUrl = snapshot.sourceUrl.trim()
        if (sourceUrl.isEmpty() || !snapshot.canPickFormats) return

        // 模式是电脑引擎、但引擎这会儿没就绪（嗅探刚成功它掉了、或白名单空了）：
        // 整条回落到本机，别让按钮卡死在灰色里。手机本机对直链与 HLS 都是完整支持；
        // 换腿必须让用户看见（与 degradeToLocal 同一条铁律），审计也留下
        // 「链路为什么和设置里写的不一样」。此时直链已在手、那一页手机没开过，
        // 所以 sniffRanOnEngine 仍为 true，needsSniffReplay 会正确地判成「不用重放」。
        if (snapshot.mode == RunMode.ENGINE && !snapshot.engineReady) {
            val hadMerge = snapshot.mergeSelected
            val hadAudioOnly = snapshot.audioOnly
            settings.mode = RunMode.LOCAL
            stopHeartbeat()
            _state.update {
                it.copy(
                    mode = RunMode.LOCAL,
                    mergeSelected = false,
                    audioOnly = false,
                    sniffNotice = ENGINE_UNAVAILABLE_LOCAL_HINT,
                )
            }
            if (hadMerge) audit.log("merge_ignored", mapOf("reason" to "engine_unavailable"))
            if (hadAudioOnly) audit.log("audio_only_ignored", mapOf("reason" to "engine_unavailable"))
            audit.log("download_fallback", mapOf("reason" to "engine_unavailable"))
            snapshot = _state.value
        }

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
            is DownloadEvent.Preparing -> {
                attachHistoryTask(event.taskId)
                _state.update { state ->
                    if (state.download !is DownloadPhase.Preparing) return@update state
                    state.copy(download = DownloadPhase.Preparing(event.status))
                }
            }

            // 单行表直接选中：只有一条可选时再让用户点一次没有信息量，
            // 「开始下载」按钮随 selectedFormat 就绪自动变成可点的高亮态。
            is DownloadEvent.Selecting -> {
                attachHistoryTask(event.taskId)
                val resume = resumeFormatId
                val autoStart = autoStartSelection
                _state.update {
                    it.copy(
                        download = DownloadPhase.Selecting(event.taskId, event.formats),
                        selectedFormatId = event.formats.firstOrNull { f -> f.formatId == resume }?.formatId
                            ?: event.formats.singleOrNull()?.formatId,
                    )
                }
                // 授权续跑的第二步 / 浏览器点过一次「下载」：画质表回来后直接接上下载，中间不再问一次。
                if ((resume != null || autoStart) && _state.value.selectedFormat != null) {
                    if (autoStart) autoStartSelection = false
                    viewModelScope.launch { startDownload() }
                }
            }

            is DownloadEvent.Failed -> {
                autoStartSelection = false
                browserDownloadActive = false
                val phase = event.failure.toDownloadPhase()
                record { markFailed(it, phase.code, phase.message) }
                _state.update { it.copy(download = phase) }
            }

            // 开下以后的事件归下载队列；画质表这条流不会收到它们。
            is DownloadEvent.Started, is DownloadEvent.Progress, is DownloadEvent.Terminal -> Unit
        }
    }

    /** 授权续跑的第一步：重放嗅探拿到 Cookie 后，替用户把「挑画质」这一步点掉。 */
    private fun resumeAfterConsent() {
        if (resumeFormatId == null) return
        pickFormats()
    }

    /**
     * 用户点了「同意并继续」：记授权，然后把那一步接上。
     *
     * 内置浏览器那一页一直活着、Cookie 也已经在容器里，队列用同一个请求原地重下就行；
     * 离屏嗅探相反——那一页从没被允许存 Cookie，必须先重放一次嗅探（这次允许存），
     * 画质表出来后自动续上下载。两条路都只让用户点一次同意。
     */
    fun grantCookieConsent() {
        val wait = _state.value.consentWait ?: return
        consent.grant(wait.host)
        _state.update { it.copy(consentWait = null) }
        if (!wait.needsSniffReplay) {
            downloads.resume(wait.recordId)
            // 等授权那一段服务已经收摊了（没有字节在动就不占保活额度）；续跑要重新打一次卡。
            keepAlive.onRequestStart()
            return
        }
        resumeFormatId = wait.formatId
        viewModelScope.launch {
            downloads.abandonForSniffReplay(wait.recordId)
            startSniff()
        }
    }

    /** 用户点了「拒绝」：这一行如实写成失败，原因说清楚是「没给授权」而不是「下不动」。 */
    fun denyCookieConsent() {
        val wait = _state.value.consentWait ?: return
        resumeFormatId = null
        autoStartSelection = false
        browserDownloadActive = false
        audit.log("cookie_consent_denied", mapOf("host" to wait.host))
        _state.update { it.copy(consentWait = null) }
        viewModelScope.launch { downloads.deny(wait.recordId, wait.status) }
    }

    fun selectFormat(formatId: String) {
        _state.update { it.copy(selectedFormatId = formatId) }
    }

    /**
     * 「视频标题命名」：留空就用站点标题，填了就用填的，后缀由系统补（M24）。
     *
     * 名字不再是模板，所以这里只存字面量，不做任何变量展开。
     */
    fun editOutputName(value: String) {
        _state.update { it.copy(outputName = value) }
    }

    /**
     * 纯音频下载（M24）：只要声音、丢掉画面。
     *
     * 执行者口径与合并一致——本机直链这一支没有 FFmpeg、也没有抽轨实现，
     * 只有电脑引擎的 ``bestaudio`` 能落地；置灰之外再挡一次编程调用。
     * 勾上时顺手把合并取消：没有视频轨，合并没有意义。
     */
    fun toggleAudioOnly(enabled: Boolean) {
        if (!_state.value.audioOnlyAvailable) {
            if (enabled) audit.log("audio_only_ignored", mapOf("reason" to "local_direct_locked"))
            return
        }
        _state.update {
            it.copy(
                audioOnly = enabled,
                mergeSelected = if (enabled) false else it.mergeSelected,
            )
        }
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
        val picking = snapshot.download as? FormatPickable ?: return
        val format = snapshot.selectedFormat
        // 纯音频不看画质行选了什么；其余情况必须有一条选中的格式。
        if (!snapshot.audioOnly && format == null) return
        if (snapshot.outputNameError != null) return
        // 用户自己按下的「开始下载」：授权续跑的意图到此作废，别让它挂到下一条任务上。
        resumeFormatId = null
        // 画质表只能由一次嗅探带出来，那时一定已经落过一行；没有这一行就没有进度可写。
        val recordId = historyId ?: return

        downloadJob?.cancel()
        val request = when (snapshot.downloadExecutor) {
            DownloadExecutor.ENGINE -> StartRequest.ViaEngine(
                taskId = picking.taskId,
                // 纯音频走 bestaudio 策略：后缀由引擎按真实音轨决定（优先 m4a，不转码）。
                formatSelector = if (snapshot.audioOnly) {
                    FormatSelector(strategy = SelectionStrategy.AUDIO_ONLY)
                } else {
                    FormatSelection.selectorFor(format!!)
                },
                namingTemplate = snapshot.outputName.trim().takeIf { it.isNotEmpty() },
                // 纯音频没有视频轨，合并没有意义——勾着也不会发出去。
                postActions = if (snapshot.mergeSelected && !snapshot.audioOnly) {
                    listOf(PostAction(PostActionType.MERGE))
                } else {
                    null
                },
                // M25：两端用同一份设置，桌面/手机不会各按各的并发跑。
                hlsConcurrency = snapshot.hlsConcurrency,
            )

            DownloadExecutor.LOCAL_DIRECT -> {
                val link = (snapshot.sniff as? SniffPhase.Success)?.results
                    ?.firstOrNull { localTaskId(it.mediaUrl) == format!!.formatId }
                val sandboxRoot = snapshot.localSandboxRoot
                if (link == null) {
                    // 找不到对应直链 = 状态与画质表已脱节（中途重新嗅探过）。说清楚，不让按钮变哑。
                    record { markFailed(it, null, "嗅探结果已过期，请重新嗅探") }
                    _state.update {
                        it.copy(
                            download = LocalFailures.unsupportedLink("嗅探结果已过期，请重新嗅探")
                                .toDownloadPhase(),
                        )
                    }
                    return
                }
                if (sandboxRoot == null) {
                    record { markFailed(it, null, "手机外部存储不可用") }
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
                    // 「视频标题命名」填了就用填的（与引擎路径的 namingTemplate 同一语义）；
                    // 没填退回浏览器页标题；都没有才让下载器按 URL 末段自己起名。
                    productName = snapshot.outputName.trim().takeIf { it.isNotEmpty() }
                        ?: browserProductName,
                )
            }
        }
        val accepted = downloads.enqueue(
            QueuedTask(
                recordId = recordId,
                dedupeKey = when (request) {
                    is StartRequest.ViaLocal -> request.link.mediaUrl
                    is StartRequest.ViaEngine -> request.taskId
                },
                executor = snapshot.downloadExecutor,
                request = request,
                // 纯音频没有选画质：摘要直接写「纯音频」，formatId 用哨兵值，
                // 免得后续「重新选择画质」按 formatId 匹配到一条并不存在的画质行。
                formatSummary = if (snapshot.audioOnly) {
                    PURE_AUDIO_SUMMARY
                } else {
                    format!!.let(::formatSummary)
                },
                formatId = if (snapshot.audioOnly) PURE_AUDIO_FORMAT_ID else format!!.formatId,
                // 离屏 WebView 嗅探 + 本机直链：那一页没被允许存 Cookie，授权后得重放一次嗅探。
                // 嗅探是电脑做的（引擎嗅探后回落到本机下载）就不重放——手机从没开过那一页，
                // 重放也拿不到引擎那份结果，反而会把一条本来能下的任务堵死。
                needsSniffReplay = snapshot.mode == RunMode.LOCAL &&
                    !sniffRanOnEngine &&
                    !browserDownloadActive,
            ),
        )
        // 先入队再打卡：服务起来读的第一眼就得已经看得见这条流，否则它会以为自己无事可干。
        // 同一直链被拦截时不开活：失败已由协调器落库，再打卡会让保活计数永远退不回去。
        if (accepted) {
            keepAlive.onRequestStart()
        }
        // 主页到此为止：进度与终态归下载管理页，这个页面立刻回到输入状态等着贴下一条链接。
        autoStartSelection = false
        browserDownloadActive = false
        browserProductName = null
        historyId = null
        historyTaskId = null
        _state.update {
            it.copy(
                sniff = SniffPhase.Idle,
                download = DownloadPhase.Idle,
                selectedFormatId = null,
                // 被重复拦截时那条任务根本没启动，绝不能显示「已加入下载队列」。
                queuedHint = if (accepted) QUEUED_HINT else null,
                downloadError = if (accepted) null else DOWNLOAD_DUPLICATE_ERROR,
                // 隐藏深嗅已经把直链交出去：WebView 立刻回收，下载由队列那条流自己跑。
                browserSessionActive = if (it.isWebViewVisible) it.browserSessionActive else false,
                browserPageVisible = it.browserPageVisible && it.isWebViewVisible,
                deepSniff = DeepSniffPhase.Off,
            )
        }
    }

    /** 历史页「重新下载」：回到首页并填入原始链接，后续仍交给用户确认嗅探与画质。 */
    fun adoptSourceUrl(url: String) {
        editSourceUrl(url)
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

        /** 交给下载队列那一下的轻提示文案；测试与界面都认这一句。 */
        const val QUEUED_HINT = "已加入下载队列，请在下载管理查看"

        /** 重复拦截的红色提示：入队没成功，不能报「已加入队列」。 */
        const val DOWNLOAD_DUPLICATE_ERROR = "任务已在队列中"

        /** 心跳周期（毫秒）：引擎模式专属的存活检测节拍。 */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** 单拍健康检查超时：拔了网线也不能拖慢节拍，比 OkHttp 的连接超时更紧。 */
        const val HEARTBEAT_TIMEOUT_MS = 3_000L

        /** 连续失败几拍才判离线：一次网络波动不值得让状态胶囊闪红。 */
        const val HEARTBEAT_FAILURES_TO_OFFLINE = 2

        /** 心跳判离线时写进连接态的原因；界面据此点亮「离线」。 */
        const val ENGINE_OFFLINE_REASON = "电脑引擎已离线"

        /** 离线时点「嗅探」的前端拦截文案：红色失败卡当场给出，不等请求超时。 */
        const val ENGINE_OFFLINE_HINT = "电脑引擎已离线，请检查电脑，或在上方切到「本机」模式"

        /** 画质表回落提示：模式是电脑引擎但引擎没就绪，这一步整条改走手机本机。 */
        const val ENGINE_UNAVAILABLE_LOCAL_HINT = "电脑引擎不可用，已切到本机模式（嗅探和下载都在手机）"

        /** 纯音频任务在下载页 / 历史记录里的摘要与 formatId 哨兵（没有真实画质行可指）。 */
        const val PURE_AUDIO_SUMMARY = "纯音频"
        const val PURE_AUDIO_FORMAT_ID = "audio_only"

        /** Compose 侧唯一的构造入口：依赖从组装根来，不在界面里 new 后端。 */
        fun factory(
            settings: EngineSettings,
            backends: BackendProvider,
            engineInfo: EngineInfo,
            audit: AuditLogger,
            history: HistoryRepository,
            consent: CookieConsentStore,
            downloads: DownloadCoordinator,
            keepAlive: KeepAliveStarter,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        "HomeViewModel.Factory 不认识的类型：$modelClass"
                    }
                    return HomeViewModel(
                        settings, backends, engineInfo, audit, history, consent, downloads, keepAlive,
                    ) as T
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

private fun EngineFailure.reason(): String = when (this) {
    is EngineFailure.Protocol -> "HTTP $httpStatus ${error.code}：${error.message}"
    is EngineFailure.Transport -> reason
}
