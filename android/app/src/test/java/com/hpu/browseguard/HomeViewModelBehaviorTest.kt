package com.hpu.browseguard

import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeBackendProvider
import com.hpu.browseguard.fakes.FakeEngineInfo
import com.hpu.browseguard.fakes.FakeEngineSettings
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.Connection
import com.hpu.browseguard.ui.DownloadPhase
import com.hpu.browseguard.ui.HomeViewModel
import com.hpu.browseguard.ui.SniffPhase
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
        assertEquals("裁决 2：嗅探仍固定走引擎", SniffExecutor.ENGINE, fakes.provider.lastSniffExecutor)
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
            DownloadEvent.Failed(LocalFailures.linkUnloadable(403)),
        )

        assertFalse("本地失败不得落成成功态", vm.state.value.download is DownloadPhase.Success)
        val failed = vm.state.value.download as DownloadPhase.Failed
        assertNull("站点 403 也不是引擎的码", failed.code)
        assertEquals(LocalFailures.linkUnloadable(403).message, failed.message)
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

    private class Fakes(
        val settings: FakeEngineSettings = FakeEngineSettings(),
        val provider: FakeBackendProvider = FakeBackendProvider(),
        val audit: FakeAuditLogger = FakeAuditLogger(),
    ) {
        fun connectedViewModel(): HomeViewModel {
            val vm = HomeViewModel(settings, provider, FakeEngineInfo(), audit)
            assertEquals(Connection.Connected::class.java, vm.state.value.connection.javaClass)
            return vm
        }

        suspend fun reachSniffSuccess(vm: HomeViewModel, results: List<SniffResult>) {
            vm.editSourceUrl("https://example.com/watch")
            vm.sniff()
            provider.sniffBackend.emit(SniffEvent.Results(results))
            assertEquals(SniffPhase.Success(results), vm.state.value.sniff)
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
    }
}
