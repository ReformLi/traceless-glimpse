package com.hpu.browseguard

import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
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
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.Connection
import com.hpu.browseguard.ui.DownloadPhase
import com.hpu.browseguard.ui.HomeViewModel
import com.hpu.browseguard.ui.SniffPhase
import com.hpu.browseguard.ui.localOutputPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val fakes = Fakes()
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
        val fakes = Fakes()
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
        val fakes = Fakes()
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
    fun downloadLocalFailureNeverReadsAsSuccessAndCarriesNoProtocolCode() = runTest(dispatcher) {
        val fakes = Fakes(settings = FakeEngineSettings(downloadExecutor = DownloadExecutor.LOCAL_DIRECT))
        val vm = fakes.connectedViewModel()
        val link = sniffResult()
        fakes.reachSniffSuccess(vm, listOf(link))

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
            // 401/403 现在先落在「等待授权」态（M6-5），这条钉的是纯本地失败的出口，所以用 503。
            DownloadEvent.Failed(LocalFailures.linkUnloadable(503)),
        )

        assertFalse("本地失败不得落成成功态", vm.state.value.download is DownloadPhase.Success)
        val failed = vm.state.value.download as DownloadPhase.Failed
        assertNull("站点 503 也不是引擎的码", failed.code)
        assertEquals(LocalFailures.linkUnloadable(503).message, failed.message)
    }

    @Test
    fun switchingToLocalClearsAndAuditsTheIgnoredMerge() = runTest(dispatcher) {
        val fakes = Fakes()
        val vm = fakes.connectedViewModel()
        assertTrue("引擎模式默认勾选合并", vm.state.value.mergeSelected)

        vm.selectDownloadExecutor(DownloadExecutor.LOCAL_DIRECT)

        assertFalse("切到本地后状态里不许再留着勾着的合并", vm.state.value.mergeSelected)
        val audit = fakes.audit.of("merge_ignored").single()
        assertEquals("executor_switch", audit.fields["reason"])
    }

    @Test
    fun theBlockedMergeToggleLeavesAnAuditRowAndNeverToggles() = runTest(dispatcher) {
        val fakes = Fakes(settings = FakeEngineSettings(downloadExecutor = DownloadExecutor.LOCAL_DIRECT))
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
        val fakes = Fakes()
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
        assertEquals("local_webview", row.fields["to"])
        assertEquals("local_direct", row.fields["download"])

        val results = listOf(sniffResult())
        fakes.provider.localSniffBackend.emit(SniffEvent.Results(results))
        assertEquals(SniffPhase.Success(results), vm.state.value.sniff)
    }

    /** 引擎嗅探超时只说明这一轮慢：降级嗅探，但下载仍可留给引擎。 */
    @Test
    fun engineTimeoutFallsBackButKeepsEngineForDownload() = runTest(dispatcher) {
        val fakes = Fakes()
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(SniffEvent.Failed(LocalFailures.sniffTimeout()))
        fakes.provider.sniffBackend.close()

        assertEquals(SniffExecutor.LOCAL_WEBVIEW, vm.state.value.sniffExecutor)
        assertEquals("超时不等于引擎不可用，下载先不换", DownloadExecutor.ENGINE, vm.state.value.downloadExecutor)
        assertTrue(vm.state.value.sniffNotice!!.contains("超时"))
        assertEquals("engine_timeout", fakes.audit.of("audit_sniff_fallback").single().fields["reason"])
    }

    /** 协议不符/传输层非连接问题不降级：换了腿也不会让协议对上。 */
    @Test
    fun nonConnectivityFailuresDoNotTriggerFallback() = runTest(dispatcher) {
        val fakes = Fakes()
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
            sniffExecutor = SniffExecutor.LOCAL_WEBVIEW,
            downloadExecutor = DownloadExecutor.LOCAL_DIRECT,
        )
        val provider = FakeBackendProvider()
        val vm = HomeViewModel(settings, provider, FakeEngineInfo(healthResult = null), FakeAuditLogger(), FakeHistoryRepository())

        assertEquals(Connection.Failed::class.java, vm.state.value.connection.javaClass)
        assertTrue("本机嗅探不需要引擎自检", vm.state.value.canSniff)

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()

        assertEquals(listOf(SniffExecutor.LOCAL_WEBVIEW), provider.sniffExecutors)
        provider.localSniffBackend.emit(SniffEvent.Results(listOf(sniffResult())))
        assertTrue(vm.state.value.sniff is SniffPhase.Success)
        assertTrue("本地直链的画质表不依赖引擎", vm.state.value.canPickFormats)
    }

    /** 开局引擎就不可达：默认「电脑」也得能点下去，否则自动降级没有入口。 */
    @Test
    fun offlineEngineStillLetsTheDefaultEngineModeStartAndFallBack() = runTest(dispatcher) {
        val provider = FakeBackendProvider()
        val audit = FakeAuditLogger()
        val vm = HomeViewModel(FakeEngineSettings(), provider, FakeEngineInfo(healthResult = null), audit, FakeHistoryRepository())

        assertEquals(Connection.Failed::class.java, vm.state.value.connection.javaClass)
        assertTrue("引擎不可达时嗅探按钮不能灰着：降级要靠这次点击触发", vm.state.value.canSniff)

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()

        assertEquals(listOf(SniffExecutor.LOCAL_WEBVIEW), provider.sniffExecutors)
        assertTrue("降级提示必须可见", vm.state.value.sniffNotice != null)
        assertEquals("pc_unreachable", audit.of("audit_sniff_fallback").single().fields["reason"])
    }

    /** 降级后本地也失败：提示必须升级为「检查网络或引擎」，不能只留一句本地超时。 */
    @Test
    fun aLocalFailureAfterFallbackSaysToCheckNetworkOrEngine() = runTest(dispatcher) {
        val fakes = Fakes()
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
        val fakes = Fakes()
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
        val fakes = Fakes()
        val vm = fakes.connectedViewModel()

        vm.editSourceUrl("https://example.com/watch")
        vm.sniff()
        fakes.provider.sniffBackend.emit(SniffEvent.Failed(LocalFailures.noSniffResults()))

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.FAILED, row.status)
        assertNull("客户端判定不得借 §5 协议码", row.errorCode)
    }

    /** 没选 SAF：本地产物留在应用目录，界面与历史都指向 staging，不显示假警告。 */
    @Test
    fun localSuccessWithoutSafKeepsStagingPath() = runTest(dispatcher) {
        val fakes = Fakes(settings = FakeEngineSettings(downloadExecutor = DownloadExecutor.LOCAL_DIRECT))
        val vm = fakes.connectedViewModel()
        val task = fakes.reachLocalSuccess(vm)

        val phase = vm.state.value.download as DownloadPhase.Success
        assertFalse("复制流程结束后不能再挡着打开文件", phase.publishing)
        assertNull(phase.publishedPath)
        assertNull(phase.publishWarning)

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals(localOutputPath(task), row.outputPath)
        assertEquals(localOutputPath(task), fakes.provider.publishRequests.single().path)
    }

    /** 选了 SAF 且复制成功：历史与界面都改成 content://，staging 不再是最终落点。 */
    @Test
    fun localSuccessPublishedToSafRecordsTheContentUri() = runTest(dispatcher) {
        val fakes = Fakes(settings = FakeEngineSettings(downloadExecutor = DownloadExecutor.LOCAL_DIRECT))
        fakes.provider.publishResult = OutputPublish.Published("content://tree/doc/clip.mp4", "clip.mp4")
        val vm = fakes.connectedViewModel()
        fakes.reachLocalSuccess(vm)

        val phase = vm.state.value.download as DownloadPhase.Success
        assertEquals("content://tree/doc/clip.mp4", phase.publishedPath)
        assertEquals("clip.mp4", phase.publishedName)
        assertFalse(phase.publishing)
        assertNull(phase.publishWarning)

        val row = fakes.history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals("content://tree/doc/clip.mp4", row.outputPath)
    }

    /** 复制失败不篡改下载结果：记录仍指向 staging，警告必须让用户看见。 */
    @Test
    fun localSuccessKeepsStagingAndWarnsWhenSafCopyFails() = runTest(dispatcher) {
        val fakes = Fakes(settings = FakeEngineSettings(downloadExecutor = DownloadExecutor.LOCAL_DIRECT))
        fakes.provider.publishResult = OutputPublish.Failed("所选文件夹的授权已失效，请在设置中重新选择")
        val vm = fakes.connectedViewModel()
        val task = fakes.reachLocalSuccess(vm)

        val phase = vm.state.value.download as DownloadPhase.Success
        assertFalse(phase.publishing)
        assertNull("复制失败不能冒充已保存", phase.publishedPath)
        assertTrue("警告原文必须透传：${phase.publishWarning}", phase.publishWarning!!.contains("授权已失效"))

        val row = fakes.history.rows.single()
        assertEquals("文件还在 staging，记录不能丢", RecordStatus.DONE, row.status)
        assertEquals(localOutputPath(task), row.outputPath)
    }

    private class Fakes(
        val settings: FakeEngineSettings = FakeEngineSettings(),
        val provider: FakeBackendProvider = FakeBackendProvider(),
        val audit: FakeAuditLogger = FakeAuditLogger(),
        val history: FakeHistoryRepository = FakeHistoryRepository(),
        val consent: FakeCookieConsentStore = FakeCookieConsentStore(),
    ) {
        fun connectedViewModel(): HomeViewModel {
            val vm = HomeViewModel(settings, provider, FakeEngineInfo(), audit, history, consent)
            assertEquals(Connection.Connected::class.java, vm.state.value.connection.javaClass)
            return vm
        }

        suspend fun reachSniffSuccess(vm: HomeViewModel, results: List<SniffResult>) {
            vm.editSourceUrl("https://example.com/watch")
            vm.sniff()
            provider.sniffBackend.emit(SniffEvent.Results(results))
            assertEquals(SniffPhase.Success(results), vm.state.value.sniff)
        }

        /** 本地直链一路走到成功终态；SAF 复制是否成功由 [FakeBackendProvider.publishResult] 决定。 */
        suspend fun reachLocalSuccess(vm: HomeViewModel): Task {
            val link = sniffResult()
            reachSniffSuccess(vm, listOf(link))
            vm.pickFormats()
            provider.downloadBackend.emitPrepare(
                DownloadEvent.Selecting("local", listOf(formatInfo(localTaskId(link.mediaUrl)))),
            )
            vm.startDownload()
            provider.downloadBackend.emitStart(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
            val terminal = task(TaskStatus.DONE)
            provider.downloadBackend.emitStart(DownloadEvent.Terminal(terminal))
            assertTrue(vm.state.value.download is DownloadPhase.Success)
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
            namingTemplate = "{title}-{height}p",
            createdAt = "2026-11-02T10:00:00Z",
            updatedAt = "2026-11-02T10:00:01Z",
            outputPath = outputPath,
        )
    }
}
