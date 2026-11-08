package com.hpu.browseguard

import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.RunMode
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
import com.hpu.browseguard.data.download.ConsentWait
import com.hpu.browseguard.data.download.DownloadCoordinator
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeBackendProvider
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeEngineInfo
import com.hpu.browseguard.fakes.FakeEngineSettings
import com.hpu.browseguard.fakes.FakeHistoryRepository
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.Connection
import com.hpu.browseguard.ui.DeepSniffPhase
import com.hpu.browseguard.ui.DownloadPhase
import com.hpu.browseguard.ui.HomeViewModel
import com.hpu.browseguard.ui.SniffPhase
import com.hpu.browseguard.ui.localOutputPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首页状态机的行为单测——用 FakeBackend 精确摆布事件，不再靠真引擎/真网络。
 *
 * 钉住三件事：
 * 1. `sniffing` 与 `format_preparing` 两个中间态确实把后端状态写进界面；
 * 2. 本地失败一律 `code == null`，且永远落 `Failed`，不会被误读成成功；
 * 3. 被挡下的「合并」与下载器剥掉的 `Range`/敏感头都留下审计行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelBehaviorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sniffingStatusIsMirroredIntoTheUi() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()

        assertEquals(SniffPhase.Running(TaskStatus.QUEUED), vm.state.value.sniff)
        assertEquals("默认嗅探方式仍是电脑引擎", SniffExecutor.ENGINE, fakes.provider.lastSniffExecutor)
        val request = fakes.provider.sniffBackend.requests.single()
        assertEquals("https://example.com/watch", request.sourceUrl)
        assertEquals(CookiePolicy.LAZY, request.cookiePolicy)
        assertEquals("/engine/out", request.outputDir)

        fakes.provider.sniffBackend.emit(SniffEvent.Status("01a1", TaskStatus.SNIFFING))

        assertEquals(SniffPhase.Running(TaskStatus.SNIFFING), vm.state.value.sniff)
    }

    @Test
    fun formatPreparingStatusIsMirroredAndASingleRowIsSelected() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        val sniffed = listOf(sniffResult())
        fakes.reachSniffSuccess(vm, sniffed)

        vm.pickFormats()

        assertEquals(DownloadPhase.Preparing(TaskStatus.QUEUED), vm.state.value.download)
        assertEquals(DownloadExecutor.ENGINE, fakes.provider.lastDownloadExecutor)
        assertEquals(sniffed, fakes.provider.downloadBackend.prepareRequests.single().sniffResults)

        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Preparing("01a1", TaskStatus.SNIFFING),
        )
        assertEquals(DownloadPhase.Preparing(TaskStatus.SNIFFING), vm.state.value.download)

        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("01a1", listOf(formatInfo("137"))),
        )
        assertEquals("137", vm.state.value.selectedFormatId)
        assertTrue("单行表应当能直接开下", vm.state.value.canStartDownload)
    }

    @Test
    fun sniffLocalFailureNeverReadsAsSuccessAndCarriesNoProtocolCode() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(SniffEvent.Failed(LocalFailures.noSniffResults()))

        assertFalse("本地失败不得落成成功态", vm.state.value.sniff is SniffPhase.Success)
        val failed = vm.state.value.sniff as SniffPhase.Failed
        assertNull("客户端本地判定不得借 §5 协议码", failed.code)
        assertEquals(LocalFailures.noSniffResults().message, failed.message)
    }

    @Test
    fun localDownloadFailureLandsOnTheHistoryRowWithoutAProtocolCode() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        val link = sniffResult()
        fakes.reachLocalSniffSuccess(vm, link)

        vm.pickFormats()
        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("local", listOf(formatInfo(localTaskId(link.mediaUrl)))),
        )
        vm.startDownload()

        val started = fakes.provider.downloadBackend.startRequests.single()
        assertTrue(started is StartRequest.ViaLocal)
        val local = started as StartRequest.ViaLocal
        assertEquals(FakeBackendProvider.DEFAULT_SANDBOX, local.outputDir)
        assertEquals(listOf(FakeBackendProvider.DEFAULT_SANDBOX), local.allowedRoots)

        fakes.provider.downloadBackend.emitStart(
            // 401/403 先走「等待授权」那条出口，这条钉的是纯本地失败的归宿，所以用 503。
            DownloadEvent.Failed(LocalFailures.linkUnloadable(503)),
        )

        val row = fakes.history.rows.single()
        assertEquals("失败要写在下载页那一行上", RecordStatus.FAILED, row.status)
        assertNull("客户端本地判定不得借 §5 协议码", row.errorCode)
        assertEquals(LocalFailures.linkUnloadable(503).message, row.errorMessage)
        assertEquals(
            "主页在触发那一刻就复位了：失败不再回到这张页面",
            DownloadPhase.Idle,
            vm.state.value.download,
        )
    }

    @Test
    fun audioOnlySendsBestAudioCarriesTheTitleAndDropsMerge() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        fakes.reachSniffSuccess(vm, listOf(sniffResult()))
        vm.pickFormats()
        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("01a1", listOf(formatInfo("137"))),
        )

        vm.editOutputName("我的电影")
        vm.toggleAudioOnly(true)

        assertTrue("纯音频与合并互斥：没有视频轨", !vm.state.value.mergeSelected)
        assertTrue("勾了纯音频就不用选画质行", vm.state.value.canStartDownload)

        vm.startDownload()

        val started =
            fakes.provider.downloadBackend.startRequests.single() as StartRequest.ViaEngine
        assertEquals(
            "纯音频必须走 audio_only，而不是用户点的那条画质",
            SelectionStrategy.AUDIO_ONLY,
            started.formatSelector.strategy,
        )
        assertNull("纯音频没有视频轨，不该把 merge 发出去", started.postActions)
        assertEquals("视频标题命名要原样发给引擎（后缀由引擎补）", "我的电影", started.namingTemplate)
    }

    @Test
    fun blankOutputNameSendsNoOverrideSoTheSiteTitleWins() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        fakes.reachSniffSuccess(vm, listOf(sniffResult()))
        vm.pickFormats()
        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("01a1", listOf(formatInfo("137"))),
        )

        vm.startDownload()

        val started =
            fakes.provider.downloadBackend.startRequests.single() as StartRequest.ViaEngine
        assertNull("留空 = 用视频原标题", started.namingTemplate)
    }

    @Test
    fun audioOnlyIsLockedInLocalMode() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()

        assertFalse("本机没有抽音轨执行者", vm.state.value.audioOnlyAvailable)

        vm.toggleAudioOnly(true)

        assertFalse("状态里不许出现勾着却不生效的纯音频", vm.state.value.audioOnly)
    }

    @Test
    fun switchingToLocalClearsAndAuditsTheIgnoredMerge() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        assertTrue("引擎模式默认勾选合并", vm.state.value.mergeSelected)

        vm.selectMode(RunMode.LOCAL)

        assertFalse("切到本地后状态里不许再留着勾着的合并", vm.state.value.mergeSelected)
        val audit = fakes.audit.of("merge_ignored").single()
        assertEquals("executor_switch", audit.fields["reason"])
    }

    @Test
    fun theBlockedMergeToggleLeavesAnAuditRowAndNeverToggles() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()

        vm.toggleMerge(true)

        assertFalse("本地模式下编程调用也勾不上", vm.state.value.mergeSelected)
        val audit = fakes.audit.of("merge_ignored").single()
        assertEquals("local_direct_locked", audit.fields["reason"])
    }

    /**
     * 电脑不可达：自动降级到手机 WebView，**并且**一起切到本地下载——
     * 电脑都连不上，引擎下载这条腿不可能走通。提示与审计都必须留下。
     */
    @Test
    fun engineConnectFailureFallsBackToLocalSniffAndDownload() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        assertEquals(listOf(SniffExecutor.ENGINE), fakes.provider.sniffExecutors)

        fakes.provider.sniffBackend.emit(
            SniffEvent.Failed(LocalFailures.engineUnreachable("无法连接引擎：Connection refused")),
        )
        fakes.provider.sniffBackend.close()

        assertEquals(
            "降级必须真的换腿并重试",
            listOf(SniffExecutor.ENGINE, SniffExecutor.LOCAL_WEBVIEW),
            fakes.provider.sniffExecutors,
        )
        assertEquals(SniffExecutor.LOCAL_WEBVIEW, vm.state.value.sniffExecutor)
        assertEquals(DownloadExecutor.LOCAL_DIRECT, vm.state.value.downloadExecutor)
        assertTrue("用户必须看得见降级：${vm.state.value.sniffNotice}", vm.state.value.sniffNotice!!.contains("电脑不可达"))
        assertEquals("设置也要落盘，下一次启动不再先撞南墙", SniffExecutor.LOCAL_WEBVIEW, fakes.settings.sniffExecutor)
        assertEquals(DownloadExecutor.LOCAL_DIRECT, fakes.settings.downloadExecutor)
        val row = fakes.audit.of("audit_sniff_fallback").single()
        assertEquals("pc_unreachable", row.fields["reason"])
        assertEquals("local", row.fields["to"])
        assertEquals("local_direct", row.fields["download"])

        val results = listOf(sniffResult())
        fakes.provider.localSniffBackend.emit(SniffEvent.Results(results))
        assertEquals(SniffPhase.Success(results), vm.state.value.sniff)
    }

    /** 引擎嗅探超时只说明这一轮慢：降级嗅探，但下载仍可留给引擎。 */
    @Test
    fun engineTimeoutFallsBackToLocalForBothHalves() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(SniffEvent.Failed(LocalFailures.sniffTimeout()))
        fakes.provider.sniffBackend.close()

        assertEquals(SniffExecutor.LOCAL_WEBVIEW, vm.state.value.sniffExecutor)
        assertEquals(
            "M23：超时也整条切本机——半侧组合正是「以为本机在干活、任务却发到电脑」的源头",
            DownloadExecutor.LOCAL_DIRECT,
            vm.state.value.downloadExecutor,
        )
        assertTrue(vm.state.value.sniffNotice!!.contains("本机"))
        assertEquals("engine_timeout", fakes.audit.of("audit_sniff_fallback").single().fields["reason"])
    }

    /** 协议不符/传输层非连接问题不降级：换了腿也不会让协议对上。 */
    @Test
    fun nonConnectivityFailuresDoNotTriggerFallback() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(SniffEvent.Failed(LocalFailures.transport("协议版本不符：响应头为 9.9")))
        fakes.provider.sniffBackend.close()

        assertEquals(listOf(SniffExecutor.ENGINE), fakes.provider.sniffExecutors)
        assertTrue(vm.state.value.sniff is SniffPhase.Failed)
        assertNull("这不是本地判定，也没有协议码", (vm.state.value.sniff as SniffPhase.Failed).code)
        assertNull(vm.state.value.sniffNotice)
        assertTrue(fakes.audit.of("audit_sniff_fallback").isEmpty())
    }

    /** 显式选「本机」且引擎离线：嗅探仍可发起，画质表也能本地合成。 */
    @Test
    fun localSniffWorksWithoutAnyEngineConnection() = runTest(dispatcher) {
        val settings = FakeEngineSettings(
            mode = RunMode.LOCAL,
        )
        val provider = FakeBackendProvider()
        val history = FakeHistoryRepository()
        val audit = FakeAuditLogger()
        val vm = HomeViewModel(
            settings,
            provider,
            FakeEngineInfo(healthResult = null),
            audit,
            history,
            FakeCookieConsentStore(),
            DownloadCoordinator(provider, history, this, audit),
            heartbeatIntervalMs = null,
        )

        assertEquals(Connection.Failed::class.java, vm.state.value.connection.javaClass)
        assertTrue("本机嗅探不需要引擎自检", vm.state.value.canSniff)

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()

        assertEquals(listOf(SniffExecutor.LOCAL_WEBVIEW), provider.sniffExecutors)
        provider.localSniffBackend.emit(SniffEvent.Results(listOf(sniffResult())))
        assertTrue(vm.state.value.sniff is SniffPhase.Success)
        assertTrue("本地直链的画质表不依赖引擎", vm.state.value.canPickFormats)
    }

    /**
     * 引擎嗅探成功、但任务已经送不进去了（自检白名单变空）：画质这一步整条回落到本机，
     * 按钮不能卡死在灰色里。换腿要留提示与审计，设置同步落盘。
     *
     * M23 起模式是整体的：回落也意味着「这次活儿改在手机上做」，不能留下
     * 「手机这边出结果、任务却还在往电脑发」的半侧残留。
     */
    @Test
    fun engineDownloadWithUnreadyEngineFallsBackToLocalAtPickFormats() = runTest(dispatcher) {
        val settings = FakeEngineSettings()   // 默认就是电脑引擎模式
        val provider = FakeBackendProvider()
        val history = FakeHistoryRepository()
        val audit = FakeAuditLogger()
        val info = FakeEngineInfo()
        val vm = HomeViewModel(
            settings,
            provider,
            info,
            audit,
            history,
            FakeCookieConsentStore(),
            DownloadCoordinator(provider, history, this, audit),
            heartbeatIntervalMs = null,
        )

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        provider.sniffBackend.emit(SniffEvent.Results(listOf(sniffResult())))
        assertTrue(vm.state.value.sniff is SniffPhase.Success)
        assertTrue("引擎在线时画质按钮可点", vm.state.value.canPickFormats)

        // 嗅探之后白名单空了：引擎还活着，但没有任何可写目录，任务送不进去。
        info.selfcheckResult = info.selfcheckResult!!.copy(outputDirAllowlist = emptyList())
        vm.refreshConnection()
        advanceUntilIdle()
        assertFalse("白名单空了就不算就绪", vm.state.value.engineReady)

        vm.pickFormats()

        assertEquals("画质表改由本机直链合成", DownloadExecutor.LOCAL_DIRECT, provider.lastDownloadExecutor)
        assertEquals("回落要落盘", DownloadExecutor.LOCAL_DIRECT, settings.downloadExecutor)
        assertEquals(RunMode.LOCAL, vm.state.value.mode)
        assertEquals(
            "换腿必须让用户看见：${vm.state.value.sniffNotice}",
            HomeViewModel.ENGINE_UNAVAILABLE_LOCAL_HINT,
            vm.state.value.sniffNotice,
        )
        assertTrue("回落要留审计行", audit.has("download_fallback"))
        assertTrue("被丢弃的合并选择也要留痕", audit.has("merge_ignored"))
        val prepare = provider.downloadBackend.prepareRequests.single()
        assertEquals("本机画质表的 outputDir 是手机沙箱根，不是引擎目录", FakeBackendProvider.DEFAULT_SANDBOX, prepare.outputDir)
    }

    /**
     * 开局引擎就不可达：M20 起首连/心跳已实时标「离线」，点「嗅探」当场给红色失败卡，
     * 不发引擎请求、也不再静默降级——切回手机嗅探是显式选择（离线提示卡有直达入口）。
     * 按钮本身仍可点：永远灰着的按钮只会让用户以为 App 坏了。
     */
    @Test
    fun offlineEngineSniffIsBlockedWithARedHintInsteadOfFallingBack() = runTest(dispatcher) {
        val provider = FakeBackendProvider()
        val audit = FakeAuditLogger()
        val history = FakeHistoryRepository()
        val vm = HomeViewModel(
            FakeEngineSettings(),
            provider,
            FakeEngineInfo(healthResult = null),
            audit,
            history,
            FakeCookieConsentStore(),
            DownloadCoordinator(provider, history, this, audit),
            heartbeatIntervalMs = null,
        )

        assertEquals(Connection.Failed::class.java, vm.state.value.connection.javaClass)
        assertTrue("离线也要能点下去，提示由状态机当场给出", vm.state.value.canSniff)

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()

        val failed = vm.state.value.sniff as SniffPhase.Failed
        assertEquals(
            "提示要说清电脑离线：${failed.message}",
            HomeViewModel.ENGINE_OFFLINE_HINT,
            failed.message,
        )
        assertTrue("失败可重试：修好电脑后原按钮就能再点", failed.retryable)
        assertNull("前端拦截是本地判定，不借 §5 码", failed.code)
        assertEquals("拦截不得发出任何引擎请求", emptyList<SniffExecutor>(), provider.sniffExecutors)
        assertEquals("不再静默降级，降级审计不该出现", emptyList<FakeAuditLogger.Entry>(), audit.of("audit_sniff_fallback"))
    }

    /** 降级后本地也失败：提示必须升级为「检查网络或引擎」，不能只留一句本地超时。 */
    @Test
    fun aLocalFailureAfterFallbackSaysToCheckNetworkOrEngine() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(
            SniffEvent.Failed(LocalFailures.engineUnreachable("无法连接引擎：Connection refused")),
        )
        fakes.provider.sniffBackend.close()
        fakes.provider.localSniffBackend.emit(SniffEvent.Failed(LocalFailures.webViewNoMedia()))

        val failed = vm.state.value.sniff as SniffPhase.Failed
        assertNull("本地判定不得借 §5 码", failed.code)
        assertTrue(
            "提示要引导排查而不是停在半句话：${vm.state.value.sniffNotice}",
            vm.state.value.sniffNotice!!.contains(HomeViewModel.LOCAL_ALSO_FAILED),
        )
    }

    /** 一次用户动作只写一行历史：嗅探 → 待下载 → 下载中 → 完成，状态全程跟得上。 */
    @Test
    fun sniffAndDownloadWriteOneHistoryRowThroughAllStages() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        fakes.reachSniffSuccess(vm, listOf(sniffResult()))

        val afterSniff = fakes.history.rows.single()
        assertEquals(RecordStatus.READY, afterSniff.status)
        assertEquals("example.com · watch", afterSniff.title)

        vm.pickFormats()
        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("01a1", listOf(formatInfo("137"))),
        )
        vm.startDownload()
        fakes.provider.downloadBackend.emitStart(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))

        val running = fakes.history.rows.single()
        assertEquals(RecordStatus.DOWNLOADING, running.status)
        assertEquals("01a1", running.taskId)
        assertEquals("720p · mp4", running.format)

        fakes.provider.downloadBackend.emitStart(
            DownloadEvent.Terminal(task(TaskStatus.DONE, outputPath = "E:\\Videos\\BGV\\clip.mp4")),
        )
        val done = fakes.history.rows.single()
        assertEquals(RecordStatus.DONE, done.status)
        assertEquals("E:\\Videos\\BGV\\clip.mp4", done.outputPath)
    }

    /** 本地失败没有协议码：历史里也不能借一个来写。 */
    @Test
    fun localSniffFailureIsPersistedWithoutAProtocolCode() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(SniffEvent.Failed(LocalFailures.noSniffResults()))

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.FAILED, row.status)
        assertNull("客户端判定不得借 §5 协议码", row.errorCode)
    }

    /** 没选 SAF：本地产物留在应用目录，历史指向 staging，不产生假警告。 */
    @Test
    fun localSuccessWithoutSafKeepsStagingPath() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        val task = fakes.reachLocalSuccess(vm)

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals(localOutputPath(task), row.outputPath)
        assertEquals(localOutputPath(task), fakes.provider.publishRequests.single().path)
        assertTrue("没复制就不该有复制失败的审计行", fakes.audit.of("audit_publish_failed").isEmpty())
        assertEquals(DownloadPhase.Idle, vm.state.value.download)
    }

    /** 选了 SAF 且复制成功：记录改成 content://，产物大小一并回填。 */
    @Test
    fun localSuccessPublishedToSafRecordsTheContentUriAndSize() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        fakes.provider.publishResult = OutputPublish.Published("content://tree/doc/clip.mp4", "clip.mp4", 4_096L)
        val vm = fakes.connectedViewModel()
        fakes.reachLocalSuccess(vm)

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals("content://tree/doc/clip.mp4", row.outputPath)
        assertEquals("完成时要顺手回填真实大小", 4_096L, row.sizeBytes)
    }

    /** 复制失败不篡改下载结果：记录仍指向 staging，原因留在审计行里。 */
    @Test
    fun localSuccessKeepsStagingAndAuditsWhenSafCopyFails() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        fakes.provider.publishResult = OutputPublish.Failed("所选文件夹的授权已失效，请在设置中重新选择")
        val vm = fakes.connectedViewModel()
        val task = fakes.reachLocalSuccess(vm)

        val row = fakes.history.rows.single()
        assertEquals("文件还在 staging，记录不能丢", RecordStatus.DONE, row.status)
        assertEquals(localOutputPath(task), row.outputPath)
        val audit = fakes.audit.of("audit_publish_failed").single()
        assertTrue("原因要能查：${audit.fields["reason"]}", audit.fields["reason"]!!.contains("授权已失效"))
        assertEquals(row.id, audit.fields["id"])
    }

    /**
     * 401/403 且这一站还没授权 = 差一次点头，不是失败。
     * 任务停在等待授权态：画质表留着、历史不动、执行者开关锁住。
     */
    @Test
    fun a403FromAnUngrantedHostWaitsForConsentInsteadOfFailing() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        val link = sniffResult()
        fakes.reachLocalSelecting(vm, link)

        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        val awaiting = vm.state.value.consentWait
            ?: error("403 且未授权必须挂出一次授权等待：${vm.state.value.download}")
        assertEquals("cdn.example.com", awaiting.host)
        assertEquals(403, awaiting.status)
        assertEquals(
            "续跑要挑回哪条画质，等待态自己就得带着它",
            localTaskId(link.mediaUrl),
            awaiting.formatId,
        )
        assertTrue(
            "M23：本机模式连嗅探也在手机上——离屏那页没被允许存 Cookie，同意后得重放",
            awaiting.needsSniffReplay,
        )
        assertEquals("差一次同意不是失败：历史不能落成 FAILED", RecordStatus.READY, fakes.history.rows.single().status)
        assertFalse(fakes.history.calls.contains("markFailed"))
        assertFalse("没点头不许偷偷记授权", fakes.consent.isGranted("cdn.example.com"))
        assertEquals("403", fakes.audit.of("cookie_consent_required").single().fields["status"])
    }

    /** 401 与 403 是同一条出路：都说明这一站要先有登录状态。 */
    @Test
    fun a401IsTheSameMissingLoginAsA403() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        fakes.reachLocalSelecting(vm, sniffResult())

        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(401)))

        val awaiting = vm.state.value.consentWait
            ?: error("401 也该等一次授权：${vm.state.value.download}")
        assertEquals(401, awaiting.status)
    }

    /** 点「拒绝」：这一行如实写失败，措辞说清是「没给授权」，且不带 §5 码。 */
    @Test
    fun denyingWritesTheRefusalIntoTheRecordWithoutBorrowingACode() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        fakes.reachLocalSelecting(vm, sniffResult())
        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        vm.denyCookieConsent()

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.FAILED, row.status)
        assertNull("站点 403 不是引擎的码", row.errorCode)
        assertTrue("原因要说清是没授权：${row.errorMessage}", row.errorMessage!!.contains("未授权使用页面 Cookie"))
        assertNull("问过了就不能再挂着", vm.state.value.consentWait)
        assertFalse("拒绝就是拒绝：不许留下授权", fakes.consent.isGranted("cdn.example.com"))
        assertEquals("cdn.example.com", fakes.audit.of("cookie_consent_denied").single().fields["host"])
    }

    /**
     * 成功之后再来一次并被拒：那次成功的记录不动——
     * 产物还在盘上，下载页指着一份完好的文件说「失败」是把用户往错的方向带。
     */
    @Test
    fun aDeclinedRetryNeverRewritesACompletedRecord() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        val task = fakes.reachLocalSuccess(vm)
        val doneId = fakes.history.rows.single().id

        fakes.reachLocalSelecting(vm, sniffResult())
        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))
        vm.denyCookieConsent()

        val done = fakes.history.rows.single { it.id == doneId }
        assertEquals("重试被拒不许改写已完成的记录", RecordStatus.DONE, done.status)
        assertEquals(localOutputPath(task), done.outputPath)
        assertEquals("被拒的那一行仍要如实写失败", 1, fakes.history.rows.count { it.status == RecordStatus.FAILED })
    }

    /**
     * 点「同意并继续」= 一次点击跑完三步：重放本机嗅探（这次才允许页面存 Cookie）
     * → 画质表回来 → 自动开下。中间不能再问用户第二次。
     */
    @Test
    fun agreeingReRunsTheLocalSniffAndStartsTheDownloadWithoutAskingAgain() = runTest(dispatcher) {
        val fakes = Fakes(
            this,
            settings = FakeEngineSettings(
                mode = RunMode.LOCAL,
            ),
        )
        val vm = fakes.connectedViewModel()
        val link = sniffResult()
        fakes.reachLocalSniffSuccess(vm, link)
        fakes.reachLocalSelectingAfter(vm, link)
        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))
        assertEquals(
            "离屏那一页没被允许存 Cookie：同意之后必须先重放嗅探",
            true,
            vm.state.value.consentWait?.needsSniffReplay,
        )

        vm.grantCookieConsent()

        assertTrue("同意即记账", fakes.consent.isGranted("cdn.example.com"))
        assertEquals(
            "本机嗅探这一页必须重放一次：Cookie 是页面加载时写进容器的",
            2,
            fakes.provider.localSniffBackend.requests.size,
        )

        fakes.provider.localSniffBackend.emit(SniffEvent.Results(listOf(link)))
        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("local", listOf(formatInfo(localTaskId(link.mediaUrl)))),
        )

        val starts = fakes.provider.downloadBackend.startRequests
        assertEquals("画质表回来后直接续上下载", 2, starts.size)
        val local = starts.last() as? StartRequest.ViaLocal
            ?: error("续跑的仍是本机直链那一条：${starts.last()}")
        assertEquals("重放时替用户点掉的就是原来那条画质", link.mediaUrl, local.link.mediaUrl)
        assertTrue("作废那一行走的是「删行」而不是挂个已取消", "abandon" in fakes.history.calls)
        assertEquals(
            "续跑后列表里只剩重放这一条：没有产物、没有新信息的幽灵行不该留给用户看",
            1,
            fakes.history.rows.size,
        )
        val revived = fakes.history.rows.single()
        assertTrue(
            "活下来的必须是重放出来的那一行",
            revived.status == RecordStatus.READY || revived.status == RecordStatus.DOWNLOADING,
        )
    }

    /** 已经授权过的站点再回 403 是真的读不到：不能再拿弹窗糊第二次。 */
    @Test
    fun anAlreadyGrantedHostGetsThePlainFailureNotAnotherDialog() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        fakes.consent.grant("cdn.example.com")
        val vm = fakes.connectedViewModel()
        fakes.reachLocalSelecting(vm, sniffResult())

        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        assertTrue("授权过了就是真失败", fakes.history.rows.single().status == RecordStatus.FAILED)
        assertNull(vm.state.value.consentWait)
        assertTrue(fakes.audit.of("cookie_consent_required").isEmpty())
        assertTrue(fakes.history.calls.contains("markFailed"))
    }

    /** 授权管的是「手机自己直连」那一条；交给电脑引擎时不问，也不该问。 */
    @Test
    fun theEngineExecutorNeverAsksForCookieConsent() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        fakes.reachSniffSuccess(vm, listOf(sniffResult()))
        vm.pickFormats()
        fakes.provider.downloadBackend.emitPrepare(DownloadEvent.Selecting("01a1", listOf(formatInfo("137"))))
        vm.startDownload()

        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        assertEquals("引擎下载这一支不弹窗", null, vm.state.value.consentWait)
        assertTrue(fakes.audit.of("cookie_consent_required").isEmpty())
        assertEquals(RecordStatus.FAILED, fakes.history.rows.single().status)
    }

    /** 等待授权时那一条任务是按旧执行者排进队列的：换执行者必须被挡下。 */
    @Test
    fun switchingTheExecutorIsRefusedWhileWaitingForConsent() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        fakes.reachLocalSelecting(vm, sniffResult())
        fakes.provider.downloadBackend.emitStart(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))
        assertNotNull(vm.state.value.consentWait)

        vm.selectMode(RunMode.ENGINE)

        assertEquals(DownloadExecutor.LOCAL_DIRECT, vm.state.value.downloadExecutor)
    }

    /** 冷启动才消费「默认直接显示网页」：关着时停在输入页，开着才直接进浏览器。 */
    @Test
    fun coldStartOnlyOpensTheWebViewWhenThePreferenceSaysSo() = runTest(dispatcher) {
        val hidden = Fakes(
            this,
            settings = FakeEngineSettings(mode = RunMode.LOCAL, showPageByDefault = false),
        ).connectedViewModel()
        assertFalse("偏好关闭时停在输入地址的主页面", hidden.state.value.isWebViewVisible)

        val shown = Fakes(
            this,
            settings = FakeEngineSettings(mode = RunMode.LOCAL, showPageByDefault = true),
        ).connectedViewModel()
        assertTrue("偏好开启时冷启动直接进浏览器", shown.state.value.isWebViewVisible)
        assertTrue("页面也要直接露出", shown.state.value.browserPageVisible)
    }

    /** 退出网页回主页面；「显示网页」能再进；电脑 → 本机必须回输入页，不被默认偏好污染。 */
    @Test
    fun leavingTheWebViewAndSwitchingBackToLocalAlwaysLandsOnTheInputPage() = runTest(dispatcher) {
        val fakes = Fakes(
            this,
            settings = FakeEngineSettings(
                mode = RunMode.LOCAL,
                showPageByDefault = true,
            ),
        )
        val vm = fakes.connectedViewModel()
        assertTrue(vm.state.value.isWebViewVisible)

        vm.exitBrowser()
        assertFalse("退出后必须回到主页面", vm.state.value.isWebViewVisible)

        vm.openBrowser(showPage = true)
        assertTrue(vm.state.value.isWebViewVisible)
        assertTrue("「显示网页」进入时页面直接露出", vm.state.value.browserPageVisible)

        vm.exitBrowser()
        vm.selectMode(RunMode.ENGINE)
        assertFalse(vm.state.value.isWebViewVisible)
        vm.selectMode(RunMode.LOCAL)

        assertFalse("切回本机不能被冷启动偏好直接拉进网页", vm.state.value.isWebViewVisible)
        assertEquals(SniffExecutor.LOCAL_WEBVIEW, vm.state.value.sniffExecutor)
    }

    @Test
    fun openingTheWebViewIsIgnoredOutsideLocalMode() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()

        vm.openBrowser(showPage = true)

        assertFalse(vm.state.value.isWebViewVisible)
    }

    /** 简单嗅探空手而归 → 自动起隐藏 WebView 继续深嗅，主页仍可见。 */
    @Test
    fun aSimpleSniffWithoutResultsEscalatesToAHiddenWebView() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()

        fakes.provider.localSniffBackend.emit(SniffEvent.Failed(LocalFailures.webViewNoMedia()))
        fakes.provider.localSniffBackend.close()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals("没抓到就该换真实网页再试", DeepSniffPhase.Running, state.deepSniff)
        assertTrue("隐藏会话要立起来", state.browserSessionActive)
        assertFalse("深嗅不上屏", state.isWebViewVisible)
        assertTrue("失败文案先让位给深嗅进度", state.sniff is SniffPhase.Running)
    }

    /** 隐藏深嗅抓到直链：结果回填主页，会话标记为已就绪。 */
    @Test
    fun hiddenDeepSniffCandidatesBecomeHomeResults() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        val link = sniffResult()
        enterHiddenDeepSniff(fakes, vm)

        vm.onDeepSniffCandidates(listOf(link))

        assertEquals(DeepSniffPhase.Ready, vm.state.value.deepSniff)
        assertEquals(SniffPhase.Success(listOf(link)), vm.state.value.sniff)
    }

    /** 隐藏深嗅也没有命中：停在主页，等用户把页面露出来点播放。 */
    @Test
    fun hiddenDeepSniffWithoutCandidatesAsksForTheVisiblePage() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        enterHiddenDeepSniff(fakes, vm)

        vm.onDeepSniffNoResult()

        assertEquals(DeepSniffPhase.NoResult, vm.state.value.deepSniff)
        assertTrue("失败态由深嗅无结果这一层给出", vm.state.value.sniff is SniffPhase.Failed)
        assertTrue("会话留着，点「显示网页」不用重新加载", vm.state.value.browserSessionActive)
    }

    /** 隐藏深嗅点了下载：任务移交下载流之后，WebView 会话立刻回收。 */
    @Test
    fun startingADownloadFromTheHiddenSessionDropsTheWebView() = runTest(dispatcher) {
        val fakes = Fakes(
            this,
            settings = FakeEngineSettings(
                mode = RunMode.LOCAL,
            ),
        )
        val vm = fakes.connectedViewModel()
        val link = sniffResult()
        enterHiddenDeepSniff(fakes, vm)
        vm.onDeepSniffCandidates(listOf(link))

        vm.pickFormats()
        fakes.provider.downloadBackend.emitPrepare(
            DownloadEvent.Selecting("local", listOf(formatInfo(localTaskId(link.mediaUrl)))),
        )
        vm.startDownload()

        assertFalse("直链已经交出去，WebView 不该再留着", vm.state.value.browserSessionActive)
        assertEquals(DeepSniffPhase.Off, vm.state.value.deepSniff)
        assertTrue("下载本身由独立的流继续", fakes.provider.downloadBackend.startRequests.isNotEmpty())
    }

    /** 隐藏深嗅进行中允许切到电脑引擎，切完会话立刻结束（WebView 由界面销毁）。 */
    @Test
    fun switchingToTheEngineClosesTheHiddenSession() = runTest(dispatcher) {
        val fakes = Fakes(this, settings = FakeEngineSettings(mode = RunMode.LOCAL))
        val vm = fakes.connectedViewModel()
        enterHiddenDeepSniff(fakes, vm)

        vm.selectMode(RunMode.ENGINE)

        assertEquals(SniffExecutor.ENGINE, vm.state.value.sniffExecutor)
        assertFalse(vm.state.value.browserSessionActive)
        assertEquals(DeepSniffPhase.Off, vm.state.value.deepSniff)
        assertTrue("深嗅的结果不跟着换腿", vm.state.value.sniff !is SniffPhase.Success)
    }

    @Test
    fun theMutePreferenceIsPersistedAndMirrored() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        assertTrue("默认静音", vm.state.value.muteWhileSniffing)

        vm.setMuteWhileSniffing(false)

        assertFalse(vm.state.value.muteWhileSniffing)
        assertFalse(fakes.settings.muteWhileSniffing)
    }

    private suspend fun TestScope.enterHiddenDeepSniff(fakes: Fakes, vm: HomeViewModel) {
        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.localSniffBackend.emit(SniffEvent.Failed(LocalFailures.webViewNoMedia()))
        fakes.provider.localSniffBackend.close()
        advanceUntilIdle()
        assertEquals(DeepSniffPhase.Running, vm.state.value.deepSniff)
    }

    @Test
    fun downloadingRowShowsAsHomeBanner() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        fakes.history.beginSniff("r1", "https://example.com/watch")
        fakes.history.markDownloading("r1", "t1", "1080p", outputPath = null)
        fakes.history.markProgress("r1", downloadedBytes = 512, bytesTotal = 1_024, speedBps = 64)
        advanceUntilIdle()

        val active = vm.state.value.activeDownload
        assertNotNull("下载中的行应该出现在主页提示里", active)
        assertEquals("r1", active!!.recordId)
        assertTrue("引擎任务下载中还没有本地产物路径", active.onEngine)
        assertEquals(1_024L, active.bytesTotal)
    }

    @Test
    fun finishedRowClearsHomeBanner() = runTest(dispatcher) {
        val fakes = Fakes(this)
        val vm = fakes.connectedViewModel()
        fakes.history.beginSniff("r1", "https://example.com/watch")
        fakes.history.markDownloading("r1", "t1", null, outputPath = null)
        advanceUntilIdle()
        assertNotNull(vm.state.value.activeDownload)

        fakes.history.markDone("r1", outputPath = "/sdcard/Movies/x.mp4", format = "1080p")
        advanceUntilIdle()
        assertNull("完成后主页不该再挂一条「正在下载」", vm.state.value.activeDownload)
    }

    private class Fakes(
        private val scope: TestScope,
        val settings: FakeEngineSettings = FakeEngineSettings(),
        val provider: FakeBackendProvider = FakeBackendProvider(),
        val audit: FakeAuditLogger = FakeAuditLogger(),
        val history: FakeHistoryRepository = FakeHistoryRepository(),
        val consent: FakeCookieConsentStore = FakeCookieConsentStore(),
    ) {
        /**
         * 真的协调器，不是替身。
         *
         * 主页瘦身后它是进度与终态的唯一写入者，也接管了 401/403 的授权等待；
         * 用假对象就等于把这条腿的接线全部跳过了。
         * 挂在 `backgroundScope` 上：一条没下完的流本来就该活过这一次点击，
         * 放在 TestScope 里会让 runTest 等到天荒地老。
         */
        val downloads: DownloadCoordinator =
            DownloadCoordinator(provider, history, scope.backgroundScope, audit, consent, progressIntervalMs = 0L)

        fun connectedViewModel(): HomeViewModel {
            // 心跳是无限周期任务：advanceUntilIdle 会把虚拟时间推到地老天荒，测试一律关掉。
            val vm = HomeViewModel(
                settings,
                provider,
                FakeEngineInfo(),
                audit,
                history,
                consent,
                downloads,
                heartbeatIntervalMs = null,
            )
            assertEquals(Connection.Connected::class.java, vm.state.value.connection.javaClass)
            return vm
        }

        suspend fun reachSniffSuccess(vm: HomeViewModel, results: List<SniffResult>) {
            vm.editSourceUrl("https://example.com/watch")
            vm.sniff()
            provider.sniffBackend.emit(SniffEvent.Results(results))
            assertEquals(SniffPhase.Success(results), vm.state.value.sniff)
        }

        /** 本机嗅探成功：走 [FakeBackendProvider.localSniffBackend] 那一条腿。 */
        suspend fun reachLocalSniffSuccess(vm: HomeViewModel, link: SniffResult) {
            vm.editSourceUrl("https://example.com/watch")
            vm.sniff()
            provider.localSniffBackend.emit(SniffEvent.Results(listOf(link)))
            assertEquals(SniffPhase.Success(listOf(link)), vm.state.value.sniff)
        }

        /** 画质表就位、`startDownload()` 已发出——403 之类的开局都从这里起步。 */
        suspend fun reachLocalSelectingAfter(vm: HomeViewModel, link: SniffResult) {
            vm.pickFormats()
            provider.downloadBackend.emitPrepare(
                DownloadEvent.Selecting("local", listOf(formatInfo(localTaskId(link.mediaUrl)))),
            )
            vm.startDownload()
            assertEquals("开下后主页立刻回到输入态", DownloadPhase.Idle, vm.state.value.download)
            assertEquals(HomeViewModel.QUEUED_HINT, vm.state.value.queuedHint)
        }

        /** 嗅探（电脑引擎那条腿）→ 画质表 → 开下，一次铺完。 */
        suspend fun reachLocalSelecting(vm: HomeViewModel, link: SniffResult) {
            // M23：本机模式连嗅探也走本机腿——不再有「引擎嗅探 + 本机下载」的半侧组合。
            reachLocalSniffSuccess(vm, link)
            reachLocalSelectingAfter(vm, link)
        }

        /**
         * 本地直链一路走到成功终态；SAF 复制是否成功由 [FakeBackendProvider.publishResult] 决定。
         *
         * 断言落在历史行上——终态已不归主页状态机，界面上此时只剩一句轻提示。
         */
        suspend fun reachLocalSuccess(vm: HomeViewModel): Task {
            val link = sniffResult()
            reachLocalSniffSuccess(vm, link)
            vm.pickFormats()
            provider.downloadBackend.emitPrepare(
                DownloadEvent.Selecting("local", listOf(formatInfo(localTaskId(link.mediaUrl)))),
            )
            vm.startDownload()
            provider.downloadBackend.emitStart(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
            val terminal = task(TaskStatus.DONE)
            provider.downloadBackend.emitStart(DownloadEvent.Terminal(terminal))
            assertEquals(RecordStatus.DONE, history.rows.single().status)
            return terminal
        }
    }

    private companion object {
        fun sniffResult(url: String = "https://cdn.example.com/v/clip.mp4") = SniffResult(
            taskId = "01a1",
            mediaUrl = url,
            type = MediaStreamType.MP4,
            container = "mp4",
            estimatedQuality = EstimatedQuality(height = 720, bitrateKbps = 2500, label = "720p"),
            drmDetected = false,
            discoveredAt = "2026-11-02T10:00:00Z",
        )

        fun formatInfo(formatId: String) = FormatInfo(
            formatId = formatId,
            container = "mp4",
            resolution = Resolution(width = 1280, height = 720),
            bitrateKbps = 2500,
            codec = Codec(video = "h264", audio = "aac"),
            hasAudio = true,
            hasVideo = true,
            fileSizeEstimateBytes = 0,
            source = FormatSource.SNIFF,
        )

        fun task(status: TaskStatus, outputPath: String? = null) = Task(
            id = "01a1",
            sourceUrl = "https://example.com/watch",
            mode = TaskMode.DOWNLOAD,
            cookiePolicy = CookiePolicy.LAZY,
            outputDir = "E:\\Videos\\BGV",
            status = status,
            namingTemplate = "我的电影",
            createdAt = "2026-11-02T10:00:00Z",
            updatedAt = "2026-11-02T10:00:01Z",
            outputPath = outputPath,
        )
    }
}
