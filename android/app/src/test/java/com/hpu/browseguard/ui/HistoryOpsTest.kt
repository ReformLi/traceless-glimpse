package com.hpu.browseguard.ui

import com.hpu.browseguard.data.download.DownloadCoordinator
import com.hpu.browseguard.data.history.ArtifactPresence
import com.hpu.browseguard.data.history.DeleteChoice
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.media.ArtifactSnapshot
import com.hpu.browseguard.data.media.MediaFacts
import com.hpu.browseguard.data.output.ArtifactChange
import com.hpu.browseguard.data.output.MoveTarget
import com.hpu.browseguard.fakes.FakeArtifactMover
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeBackendProvider
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeHistoryRepository
import com.hpu.browseguard.fakes.FakeMediaProbe
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 下载管理页的操作编排：删除、改名、移动、详情面板各自「先问谁、后写谁、失败怎么说」。
 *
 * 这里钉的是顺序与写回，不是搬运本身——`(1)` 追加、失败保留原文件那些细节属于
 * SafFileMover 与真机验收；单测只看界面这一层有没有把编排走歪。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryOpsTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 删一条还在跑的行：必须先取消，否则行没了、协调器还在往没人认领的 `.part` 里写字节。 */
    @Test
    fun deletingARunningRowCancelsItBeforeTheRowGoes() = runTest(dispatcher) {
        val f = Fix(this)
        f.seed("run", RecordStatus.DOWNLOADING, f.privatePath("run.part"))
        f.seed("done", RecordStatus.DONE, f.privatePath("done.mp4"))

        f.vm.delete(listOf("run", "done"), DeleteChoice.RECORD_ONLY)
        advanceUntilIdle()

        assertEquals(
            "在跑的那行先是 markCancelled、再是 delete；已完成那行只有 delete",
            listOf("beginSniff", "markDownloading", "beginSniff", "markDone", "markCancelled", "delete", "delete"),
            f.history.calls,
        )
        assertEquals(
            "两行都按界面问出来的 choice 原样交给仓库",
            listOf("run" to DeleteChoice.RECORD_ONLY, "done" to DeleteChoice.RECORD_ONLY),
            f.history.deleteChoices,
        )
        assertEquals(OperationReport(OperationVerb.DELETE, succeeded = 2), f.vm.report.value)
    }

    /** 一批删完不该留下「已选 2 项」的顶栏，也不该留着一个指向已删行的详情面板。 */
    @Test
    fun aBatchDeleteLeavesNoSelectionAndNoOpenPanel() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.seedDone("b", f.privatePath("b.mp4"))
        f.vm.toggleSelect("a")
        f.vm.toggleSelect("b")
        f.vm.openDetails("a")
        advanceUntilIdle()
        assertTrue(f.vm.state.value.details is DetailsState.Shown)

        f.vm.deleteSelected(DeleteChoice.RECORD_AND_FILE)
        advanceUntilIdle()

        assertEquals(emptySet<String>(), f.vm.state.value.selection)
        assertEquals(DetailsState.Hidden, f.vm.state.value.details)
        assertEquals(OperationReport(OperationVerb.DELETE, succeeded = 2), f.vm.report.value)
        assertEquals(
            listOf("a" to DeleteChoice.RECORD_AND_FILE, "b" to DeleteChoice.RECORD_AND_FILE),
            f.history.deleteChoices,
        )
    }

    /** 「打开」撞到文件已经没了：只补删记录，不再拿「要不要连文件一起删」消遣用户。 */
    @Test
    fun droppingAMissingRowDeletesOnlyTheRecord() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("gone", f.privatePath("gone.mp4"))

        f.vm.dropMissingRecord("gone")
        advanceUntilIdle()

        assertEquals(listOf("gone" to DeleteChoice.RECORD_ONLY), f.history.deleteChoices)
        assertTrue(f.history.rows.isEmpty())
    }

    /** 改名先搬文件、成了才回填；失败就一行都不写，汇总里带上搬运工那句原因。 */
    @Test
    fun renameWritesBackOnlyWhenTheFileActuallyMoved() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.mover.renameResults += ArtifactChange.Changed(f.privatePath("第一集.mp4"), "第一集.mp4", 2_048L)

        f.vm.rename("a", "第一集")
        advanceUntilIdle()

        val row = f.history.rows.single()
        assertEquals("清单要跟盘上同名", "第一集", row.title)
        assertEquals(f.privatePath("第一集.mp4"), row.outputPath)
        assertEquals(2_048L, row.sizeBytes!!)
        assertEquals(OperationReport(OperationVerb.RENAME, succeeded = 1), f.vm.report.value)

        f.mover.renameResults += ArtifactChange.Failed("目标位置已经有同名的文件了")
        f.vm.rename("a", "第二集")
        advanceUntilIdle()

        assertEquals("搬不动就不回填：清单里那个名字得是真的", "第一集", f.history.rows.single().title)
        assertEquals(
            OperationReport(OperationVerb.RENAME, 0, failures = listOf("目标位置已经有同名的文件了")),
            f.vm.report.value,
        )
    }

    /** 移动只改落点：标题是用户起的名字，不跟着目录一起换。 */
    @Test
    fun aMoveNeverRewritesTheTitle() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"), title = "我的片子")
        f.vm.toggleSelect("a")
        f.mover.moveResults += ArtifactChange.Changed("content://tree/doc/a.mp4", "a.mp4", 4_096L)

        f.vm.moveToFolder("content://tree/doc")
        advanceUntilIdle()

        val row = f.history.rows.single()
        assertEquals("我的片子", row.title)
        assertEquals("content://tree/doc/a.mp4", row.outputPath)
        assertEquals(4_096L, row.sizeBytes!!)
        assertEquals(
            listOf("a" to MoveTarget.PublicTree("content://tree/doc")),
            f.mover.moveCalls.map { it.recordId to it.target },
        )
    }

    /** 目标文件夹的授权抓不住时一个都不搬——搬一半才发现没权限，剩下那半的落点没法交代。 */
    @Test
    fun aDeniedFolderMovesNothingAtAll() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.seedDone("b", f.privatePath("b.mp4"))
        f.vm.toggleSelect("a")
        f.vm.toggleSelect("b")
        f.mover.permitDenial = "那个文件夹的授权已经失效"

        f.vm.moveToFolder("content://tree/old")
        advanceUntilIdle()

        assertTrue("整批都没动", f.mover.moveCalls.isEmpty())
        assertEquals(
            OperationReport(OperationVerb.MOVE, 0, failures = listOf("那个文件夹的授权已经失效")),
            f.vm.report.value,
        )
        assertEquals("选中集要留着，让用户重选一个文件夹再试", setOf("a", "b"), f.vm.state.value.selection)
    }

    /** 移回应用目录不问 SAF 授权，落点由搬运工给的新路径决定。 */
    @Test
    fun movingBackToTheAppFolderNeedsNoTreePermission() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", "content://tree/doc/a.mp4")
        f.vm.toggleSelect("a")
        f.mover.moveResults += ArtifactChange.Changed(f.privatePath("a.mp4"), "a.mp4", 512L)

        f.vm.moveToAppFolder()
        advanceUntilIdle()

        assertTrue("私有目录不需要 tree 授权", f.mover.permittedUris.isEmpty())
        assertEquals(listOf("a" to MoveTarget.PrivateSandbox), f.mover.moveCalls.map { it.recordId to it.target })
        assertEquals(OperationReport(OperationVerb.MOVE, succeeded = 1), f.vm.report.value)
    }

    /** 引擎侧任务与没下完的行既没搬成也不算失败：单列为「跳过」，否则汇总会把数字说歪。 */
    @Test
    fun rowsWithoutAPhoneArtifactAreCountedAsSkipped() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("local", f.privatePath("local.mp4"))
        f.seedDone("engine", "E:\\Videos\\BGV\\clip.mp4")
        f.seed("run", RecordStatus.DOWNLOADING, f.privatePath("run.part"))
        listOf("local", "engine", "run").forEach(f.vm::toggleSelect)

        f.vm.moveToAppFolder()
        advanceUntilIdle()

        assertEquals("只有手机侧已完成的产物能搬", listOf("local"), f.mover.moveCalls.map { it.recordId })
        assertEquals(OperationReport(OperationVerb.MOVE, succeeded = 1, skipped = 2), f.vm.report.value)
    }

    /** 一批正在跑时点第二下不该开出第二个批次：SAF 同名去重并发会撞出两份同名。 */
    @Test
    fun aSecondBatchWaitsWhileTheFirstIsStillRunning() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.seedDone("b", f.privatePath("b.mp4"))
        f.vm.toggleSelect("a")
        val gate = CompletableDeferred<Unit>()
        f.mover.holdOn = gate

        f.vm.moveToAppFolder()
        runCurrent()
        assertEquals("第一批已经压在搬运工手里", 1, f.mover.moveCalls.size)
        assertTrue("这一批还在跑，界面要显示「正在处理…」", f.vm.state.value.busy)

        f.vm.toggleSelect("b")
        f.vm.moveToAppFolder()
        advanceUntilIdle()
        assertEquals("第二批被拒：一次只跑一批", 1, f.mover.moveCalls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(f.vm.state.value.busy)
        assertEquals(OperationReport(OperationVerb.MOVE, succeeded = 1), f.vm.report.value)
    }

    /** 行没了就不该还留在选中集里：清空历史之后顶栏不该再显示「已选 3 项」。 */
    @Test
    fun selectionOnlyKeepsRowsThatStillExist() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.seedDone("b", f.privatePath("b.mp4"))
        f.vm.toggleSelect("a")
        f.vm.toggleSelect("b")

        f.history.delete("b", DeleteChoice.RECORD_ONLY)
        advanceUntilIdle()

        assertEquals(setOf("a"), f.vm.state.value.selection)
    }

    /** 全选跟着当前过滤档走：在「已完成」里全选不该把失败那档也圈进来。 */
    @Test
    fun selectAllFollowsTheCurrentFilter() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.seedDone("b", f.privatePath("b.mp4"))
        f.seed("bad", RecordStatus.FAILED, null)

        f.vm.setFilter(DownloadFilter.DONE)
        f.vm.selectAllShown()
        advanceUntilIdle()
        assertEquals(setOf("a", "b"), f.vm.state.value.selection)

        f.vm.selectAllShown()
        advanceUntilIdle()
        assertEquals("已经是全集就清空", emptySet<String>(), f.vm.state.value.selection)

        f.vm.setFilter(DownloadFilter.ALL)
        f.vm.selectAllShown()
        advanceUntilIdle()
        assertEquals(setOf("a", "b", "bad"), f.vm.state.value.selection)
    }

    /** 详情面板报此刻的读数；改名之后面板还开着就要重探一次，否则用户看到的还是旧文件名。 */
    @Test
    fun theDetailsPanelReProbesAfterARenameAndClosesWithItsRow() = runTest(dispatcher) {
        val f = Fix(this)
        f.seedDone("a", f.privatePath("a.mp4"))
        f.probe.snapshot = ArtifactSnapshot(
            record = f.history.rows.single(),
            presence = ArtifactPresence.PRESENT,
            facts = MediaFacts(durationMs = 90_000L, width = 1_280, height = 720),
            fileName = "a.mp4",
            sizeBytes = 1_024L,
        )

        f.vm.openDetails("a")
        advanceUntilIdle()
        val shown = f.vm.state.value.details as DetailsState.Shown
        assertEquals("1:30", durationLabel(shown.snapshot.facts?.durationMs))
        assertEquals(listOf("a"), f.probe.probed)

        f.mover.renameResults += ArtifactChange.Changed(f.privatePath("新名字.mp4"), "新名字.mp4", 2_048L)
        f.vm.rename("a", "新名字")
        advanceUntilIdle()

        assertEquals("改名后面板要重读，不能留着旧文件名", 2, f.probe.probed.count { it == "a" })
        assertTrue(f.vm.state.value.details is DetailsState.Shown)

        f.vm.delete("a", DeleteChoice.RECORD_ONLY)
        advanceUntilIdle()
        assertEquals("行没了，面板跟着收起来", DetailsState.Hidden, f.vm.state.value.details)
    }

    /** 仓库还没开口之前不能报「历史是空的」：冷启动闪一句假话比多等一百毫秒糟得多。 */
    @Test
    fun theListStaysInLoadingUntilTheRepositorySpeaks() = runTest(dispatcher) {
        val silent = SilentHistoryRepository()
        val vm = HistoryViewModel(
            history = silent,
            downloads = DownloadCoordinator(FakeBackendProvider(), silent, backgroundScope),
            mover = FakeArtifactMover(),
            probe = FakeMediaProbe(),
            sandboxRoot = null,
        )
        backgroundScope.launch { vm.state.collect { } }
        advanceUntilIdle()

        assertTrue(vm.state.value.loading)
        assertEquals(0, vm.state.value.recordCount)
    }

    /** 只把「先不发射」这一件事换掉，其余仓库方法仍走那份内存实现。 */
    private class SilentHistoryRepository : HistoryRepository by FakeHistoryRepository() {
        private val sink = Channel<List<DownloadRecord>>(Channel.UNLIMITED)
        override val records: Flow<List<DownloadRecord>> = sink.receiveAsFlow()
    }

    private class Fix(testScope: TestScope) {
        private val scope = testScope
        val provider = FakeBackendProvider()
        val history = FakeHistoryRepository()
        val audit = FakeAuditLogger()
        val mover = FakeArtifactMover()
        val probe = FakeMediaProbe()
        val sandboxRoot: String = SANDBOX

        val downloads = DownloadCoordinator(
            provider,
            history,
            scope.backgroundScope,
            audit,
            FakeCookieConsentStore(),
            progressIntervalMs = 0L,
        )

        val vm: HistoryViewModel

        init {
            vm = HistoryViewModel(history, downloads, mover, probe, sandboxRoot)
            // stateIn(WhileSubscribed) 在没人订阅时永远停在初值，测的是界面看到的那一份状态，所以先接上；
            // 挂在 backgroundScope——这条永不结束的收集协程留在测试体里会把用例拖到超时。
            scope.backgroundScope.launch { vm.state.collect { } }
        }

        fun privatePath(name: String): String = File(sandboxRoot, name).path

        suspend fun seed(id: String, status: RecordStatus, outputPath: String?) {
            history.beginSniff(id, URL)
            when (status) {
                RecordStatus.DONE -> history.markDone(id, outputPath, "720p · mp4", 1_024L, null)
                RecordStatus.DOWNLOADING -> history.markDownloading(id, null, null, outputPath)
                RecordStatus.FAILED -> history.markFailed(id, null, "站点拒绝了这个直链")
                else -> Unit
            }
            // 仓库的发射要等测试体让出线程才会落到 allRecords，铺完数据先推一把。
            scope.advanceUntilIdle()
        }

        suspend fun seedDone(id: String, outputPath: String, title: String? = id) {
            seed(id, RecordStatus.DONE, outputPath)
            history.updateArtifact(id, outputPath, title, 1_024L)
            scope.advanceUntilIdle()
        }
    }

    private companion object {
        const val URL = "https://example.com/watch"
        val SANDBOX = File(File(System.getProperty("java.io.tmpdir")), "bgv-sandbox").path
    }
}
