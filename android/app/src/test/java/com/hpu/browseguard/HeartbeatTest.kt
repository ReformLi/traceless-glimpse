package com.hpu.browseguard

import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.backend.RunMode
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.download.DownloadCoordinator
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeBackendProvider
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeEngineInfo
import com.hpu.browseguard.fakes.FakeEngineSettings
import com.hpu.browseguard.fakes.FakeHistoryRepository
import com.hpu.browseguard.ui.Connection
import com.hpu.browseguard.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * M20 引擎心跳的行为单测：用虚拟时间精确推进每一拍，验证状态胶囊的绿↔离线扭转。
 *
 * 心跳是 `while + delay` 的无限周期任务——这里**绝不能用 `advanceUntilIdle`**，
 * 它会把虚拟时间推到天荒地老都不肯停；只允许 `advanceTimeBy` + `runCurrent` 精确推拍。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartbeatTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 推进一拍心跳并跑完当前时刻的遗留任务。 */
    private fun TestScope.tick(times: Int = 1) {
        repeat(times) {
            advanceTimeBy(HomeViewModel.HEARTBEAT_INTERVAL_MS)
            runCurrent()
        }
    }

    private fun engineInfo() = FakeEngineInfo()

    private fun TestScope.viewModelWith(
        engineInfo: FakeEngineInfo,
        settings: FakeEngineSettings = FakeEngineSettings(),
    ): HomeViewModel {
        val provider = FakeBackendProvider()
        val history = FakeHistoryRepository()
        val audit = FakeAuditLogger()
        return HomeViewModel(
            settings,
            provider,
            engineInfo,
            audit,
            history,
            FakeCookieConsentStore(),
            DownloadCoordinator(provider, history, this, audit),
            heartbeatIntervalMs = HomeViewModel.HEARTBEAT_INTERVAL_MS,
        )
    }

    /** 电脑关机：连续两拍失败才判离线，一拍波动状态保持绿。 */
    @Test
    fun twoConsecutiveHeartbeatFailuresTurnTheEngineOffline() = runTest(dispatcher) {
        val engine = engineInfo()
        val vm = viewModelWith(engine)
        try {
            assertTrue("开局连上即在线", vm.state.value.connection is Connection.Connected)

            engine.healthResult = null // 模拟电脑关机/断网
            tick()

            assertTrue(
                "一次网络波动不判离线：${vm.state.value.connection}",
                vm.state.value.connection is Connection.Connected,
            )

            tick()

            assertEquals(
                Connection.Failed(HomeViewModel.ENGINE_OFFLINE_REASON),
                vm.state.value.connection,
            )
        } finally {
            // runTest 收尾会推进虚拟时间直到队列排空；心跳是无限周期任务，
            // 不显式取消它整个测试就会挂死（SIGTERM 验证过一次了）。
            vm.viewModelScope.cancel()
        }
    }

    /** 电脑重启：下一次心跳成功自动恢复「在线」，并重建完整的连接态（含自检白名单）。 */
    @Test
    fun aSuccessfulHeartbeatRecoversTheConnectionAutomatically() = runTest(dispatcher) {
        val engine = engineInfo()
        val vm = viewModelWith(engine)

        engine.healthResult = null
        tick(HomeViewModel.HEARTBEAT_FAILURES_TO_OFFLINE)
        assertEquals(Connection.Failed(HomeViewModel.ENGINE_OFFLINE_REASON), vm.state.value.connection)

        engine.healthResult = FakeEngineInfo().healthResult // 电脑回来了
        tick()

        val recovered = vm.state.value.connection as? Connection.Connected
        assertTrue("心跳成功必须自动回绿：${vm.state.value.connection}", recovered != null)
        assertEquals(
            "恢复要重建自检白名单，画质表才有 outputDir 可用",
            listOf("/engine/out"),
            recovered?.selfcheck?.outputDirAllowlist,
        )
        vm.viewModelScope.cancel()
    }

    /** 切回本机嗅探：心跳立刻停拍，health 调用计数不再增长。 */
    @Test
    fun switchingBackToLocalStopsTheHeartbeat() = runTest(dispatcher) {
        val engine = engineInfo()
        val vm = viewModelWith(engine)
        try {
            val before = engine.healthCalls // init 的首连已打过一次

            vm.selectMode(RunMode.LOCAL)
            tick(5)

            assertEquals("本机模式没有要盯的电脑，心跳必须停", before, engine.healthCalls)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** 切到电脑引擎：心跳上岗，每拍一次 health。 */
    @Test
    fun selectingEngineModeStartsBeating() = runTest(dispatcher) {
        val engine = engineInfo()
        val settings = FakeEngineSettings(mode = RunMode.LOCAL)
        val vm = viewModelWith(engine, settings)
        try {
            tick(3)
            val before = engine.healthCalls // 本机模式零心跳（首连那一次不算心跳）

            vm.selectMode(RunMode.ENGINE)
            tick(2)

            assertEquals("切到电脑后两拍各打一次 health", before + 2, engine.healthCalls)
            assertTrue(vm.state.value.connection is Connection.Connected)
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
