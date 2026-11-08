package com.hpu.browseguard.data.download

import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.REMUX_PHASE
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.history.RecordNotes
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeBackendProvider
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeHistoryRepository
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskProgress
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载队列自己那一层的契约：主页瘦身后它是进度与终态的唯一写入者。
 *
 * 界面测的是「点下去之后有没有把任务交出去」，这里测的是「交出去之后发生什么」：
 * 心跳节流、产物回填、授权等待、取消归谁。行的创建仍归嗅探那一步（`beginSniff`），
 * 所以每条测试先铺好一行再入队——协调器从不新建记录。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val provider = FakeBackendProvider()
    private val history = FakeHistoryRepository()
    private val audit = FakeAuditLogger()
    private val consent = FakeCookieConsentStore()

    /** 心跳太密会把 Room 打满：只有到点的那些才写库，最后一条数字必须留住。 */
    @Test
    fun progressHeartbeatsAreThrottledToTheConfiguredInterval() = runTest(dispatcher) {
        var now = 10_000L
        val coordinator = coordinator(this, now = { now }, progressIntervalMs = 1_000L)
        seedRow()
        coordinator.enqueue(engineTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))

        now = 10_000L
        push(DownloadEvent.Progress(task(TaskStatus.DOWNLOADING).withProgress(300L)))
        assertEquals(1, history.calls.count { it == "markProgress" })

        now = 10_400L
        push(DownloadEvent.Progress(task(TaskStatus.DOWNLOADING).withProgress(400L)))
        assertEquals("间隔未到，这一条只该被丢弃", 1, history.calls.count { it == "markProgress" })

        now = 11_000L
        push(DownloadEvent.Progress(task(TaskStatus.DOWNLOADING).withProgress(500L)))
        assertEquals(2, history.calls.count { it == "markProgress" })

        val row = history.rows.single()
        assertEquals(500L, row.downloadedBytes)
        assertEquals(4_096L, row.bytesTotal)
        assertEquals(1_024L, row.speedBps)
    }

    /**
     * 转封装阶段整段只发一个事件，之后进度数字就不再动了。
     * 这一行必须靠那句提示证明自己还在干活，而不是停在最后一个百分比上看着像卡死。
     */
    @Test
    fun theRemuxPhaseIsWrittenIntoTheRowAndTheNextHeartbeatClearsIt() = runTest(dispatcher) {
        var now = 10_000L
        val coordinator = coordinator(this, now = { now }, progressIntervalMs = 1_000L)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))

        now = 11_000L
        push(DownloadEvent.Progress(localSnapshot(TaskStatus.DOWNLOADING).withProgress(4_096L, REMUX_PHASE)))
        assertEquals(RecordNotes.REMUXING, history.rows.single().note)

        now = 12_000L
        push(DownloadEvent.Progress(localSnapshot(TaskStatus.DOWNLOADING).withProgress(4_096L)))
        assertNull("阶段过去了，那句提示不该继续留在行上", history.rows.single().note)
    }

    /** 降级是「下完了但产物没变成 MP4」：不留在行上，用户打不开文件时就是一句「明明显示已完成」。 */
    @Test
    fun aRemuxFallbackDoneRowCarriesTheFallbackNote() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Terminal(localSnapshot(TaskStatus.DONE), remuxFallback = true))

        val row = history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals(RecordNotes.REMUX_FALLBACK, row.note)
    }

    /**
     * 音频类降级同时带着两个旗标，但只有细分那句说得清「为什么故意保留原格式」；
     * 通用那句会把人引向「格式有问题」，而真正的答案是「转成 MP4 就没声音了」。
     */
    @Test
    fun theAudioFallbackTakesTheNarrowerNoteOverTheGenericOne() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))
        push(
            DownloadEvent.Terminal(
                localSnapshot(TaskStatus.DONE),
                remuxFallback = true,
                remuxAudioFallback = true,
            ),
        )

        assertEquals(RecordNotes.REMUX_AUDIO_FALLBACK, history.rows.single().note)
    }

    /** 转封装成功的那一次不能留着上一阶段的「正在转封装」：完成行只说产物本身的事。 */
    @Test
    fun aCleanDoneRowDropsTheRemuxingNote() = runTest(dispatcher) {
        var now = 10_000L
        val coordinator = coordinator(this, now = { now }, progressIntervalMs = 1_000L)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))
        now = 11_000L
        push(DownloadEvent.Progress(localSnapshot(TaskStatus.DOWNLOADING).withProgress(4_096L, REMUX_PHASE)))
        push(DownloadEvent.Terminal(localSnapshot(TaskStatus.DONE)))

        assertEquals(RecordStatus.DONE, history.rows.single().status)
        assertNull(history.rows.single().note)
    }

    /** 本机直链的产物名在 `Started` 那一刻就定得下来：进度行不必等终态。 */
    @Test
    fun theStartedEventMovesTheRowIntoDownloadingWithTheLocalArtifactPath() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))

        val row = history.rows.single()
        assertEquals(RecordStatus.DOWNLOADING, row.status)
        assertEquals("01a1", row.taskId)
        assertEquals("720p · mp4", row.format)
        assertEquals(File(FakeBackendProvider.DEFAULT_SANDBOX, "clip-720p.mp4").absolutePath, row.outputPath)
    }

    /** 引擎产物在电脑上：路径照抄，大小 stat 不到就留空，绝不写 0。 */
    @Test
    fun anEngineDoneRowKeepsTheEnginePathAndGivesUpOnSize() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(engineTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Terminal(task(TaskStatus.DONE).copy(outputPath = "E:\\Videos\\BGV\\clip.mp4")))

        val row = history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals("E:\\Videos\\BGV\\clip.mp4", row.outputPath)
        assertNull(row.sizeBytes)
    }

    @Test
    fun aLocalDoneRowFollowsThePublishResult() = runTest(dispatcher) {
        provider.publishResult = OutputPublish.Published("content://tree/doc/clip.mp4", "clip.mp4", 4_096L)
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Terminal(localSnapshot(TaskStatus.DONE)))

        val row = history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals("content://tree/doc/clip.mp4", row.outputPath)
        assertEquals(4_096L, row.sizeBytes)
    }

    /** 复制没成不篡改下载结果：审计行记下原因，记录仍指向 staging。 */
    @Test
    fun aPublishFailureLeavesTheStagingPathAndAuditsTheReason() = runTest(dispatcher) {
        provider.publishResult = OutputPublish.Failed("所选文件夹的授权已失效")
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(localSnapshot(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Terminal(localSnapshot(TaskStatus.DONE)))

        val row = history.rows.single()
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals(File(FakeBackendProvider.DEFAULT_SANDBOX, "clip-720p.mp4").absolutePath, row.outputPath)
        val auditRow = audit.of("audit_publish_failed").single()
        assertEquals("所选文件夹的授权已失效", auditRow.fields["reason"])
        assertEquals(RECORD_ID, auditRow.fields["id"])
    }

    /** §5 的码只在引擎真的这么答时才有；这里原样搬运，不自己编码。 */
    @Test
    fun aTerminalWithErrorCarriesTheEngineCodeVerbatim() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(engineTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(
            DownloadEvent.Terminal(
                task(TaskStatus.FAILED).copy(error = ErrorPayload.of(ErrorCode.NETWORK_TIMEOUT)),
            ),
        )

        val row = history.rows.single()
        assertEquals(RecordStatus.FAILED, row.status)
        assertEquals("NETWORK_TIMEOUT", row.errorCode)
        assertEquals(ErrorPayload.of(ErrorCode.NETWORK_TIMEOUT).message, row.errorMessage)
    }

    @Test
    fun aTerminalWithNoReasonSaysSoInsteadOfInventingOne() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(engineTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Terminal(task(TaskStatus.FAILED)))

        val row = history.rows.single()
        assertEquals(RecordStatus.FAILED, row.status)
        assertNull("引擎没给错误对象，就没有码可言", row.errorCode)
        assertTrue("要如实说没给原因：${row.errorMessage}", row.errorMessage!!.contains("后端没有给出原因"))
    }

    /** 差一次点头不是失败：任务挂在队列里等，历史行一步都不动。 */
    @Test
    fun anUngrantedLocal403ParksTheTaskInsteadOfFailingTheRow() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        val wait = coordinator.consentWaits.value.single()
        assertEquals(RECORD_ID, wait.recordId)
        assertEquals("cdn.example.com", wait.host)
        assertEquals(403, wait.status)
        assertEquals("挂着等点头的行仍算在跑", RecordStatus.DOWNLOADING, history.rows.single().status)
        assertTrue("差一次同意不是失败", history.calls.none { it == "markFailed" })
        assertEquals("403", audit.of("cookie_consent_required").single().fields["status"])
    }

    /**
     * 同意之后原地续跑：同一个请求、同一行记录再来一次。
     *
     * 这条钉的是「等待授权时那一包必须留着」：请求、画质、执行者都在 `QueuedTask` 里，
     * 掐掉那条流只是断开订阅，不能把这一包一起扔掉。
     */
    @Test
    fun resumingReSendsTheSameRequestIntoTheSameRow() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))
        consent.grant("cdn.example.com")

        coordinator.resume(RECORD_ID)

        assertEquals(2, provider.downloadBackend.startRequests.size)
        assertTrue(coordinator.consentWaits.value.isEmpty())
        assertEquals("续跑不新开一行", 1, history.rows.size)
    }

    @Test
    fun denyingWritesTheRefusalIntoThatRow() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Failed(LocalFailures.linkUnloadable(401)))

        coordinator.deny(RECORD_ID, 401)

        val row = history.rows.single()
        assertEquals(RecordStatus.FAILED, row.status)
        assertNull("未授权是本地事件，不借 §5 码", row.errorCode)
        assertTrue("原因要带状态码：${row.errorMessage}", row.errorMessage!!.contains("401"))
    }

    /** 已经开下的任务：取消发给后端，终态仍由那条流写——两处同时改状态就会打架。 */
    @Test
    fun cancellingAStartedTaskPointsAtTheBackendTaskId() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(engineTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))

        coordinator.cancel(RECORD_ID)

        assertEquals(listOf("01a1"), provider.downloadBackend.cancelRequests)
        assertEquals("后端还没承认之前，行仍留在下载中", RecordStatus.DOWNLOADING, history.rows.single().status)

        push(DownloadEvent.Terminal(task(TaskStatus.CANCELLED)))
        assertEquals(RecordStatus.CANCELLED, history.rows.single().status)
    }

    /** 还没拿到后端句柄（刚入队）：协程归本地管，行就地写取消。 */
    @Test
    fun cancellingBeforeTheBackendHandleEndsTheRowHere() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())

        coordinator.cancel(RECORD_ID)

        assertTrue(provider.downloadBackend.cancelRequests.isEmpty())
        assertEquals(RecordStatus.CANCELLED, history.rows.single().status)
        assertEquals(0, coordinator.activeCount())
    }

    /**
     * 授权了但这一腿得先重放页面嗅探：旧行没有产物，就该整行消失。
     * 界面紧接着会另起一行嗅探记录，留着旧行等于在下载管理里挂一条幽灵。
     */
    @Test
    fun abandoningForSniffReplayDeletesTheArtifactlessGhostRow() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        coordinator.abandonForSniffReplay(RECORD_ID)

        assertTrue("作废行不许留在列表里骗人：${history.rows.map { it.status }}", history.rows.isEmpty())
        assertTrue("待授权队列也要跟着清空", coordinator.consentWaits.value.isEmpty())
        assertTrue("走的是作废而不是写取消", "abandon" in history.calls)
    }

    /** 已经把产物路径写进过记录的那一行作废时只退回「已取消」：文件在手机上，线索不能抹。 */
    @Test
    fun abandoningKeepsTheRowThatAlreadyRecordedAnArtifact() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow()
        coordinator.enqueue(localTask())
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))

        coordinator.abandonForSniffReplay(RECORD_ID)

        assertEquals(RecordStatus.CANCELLED, history.rows.single().status)
        assertTrue(coordinator.consentWaits.value.isEmpty())
    }

    /** M24 回归：同一页面先下视频、再勾纯音频，是两条不同资源，绝不能按页面 URL 判重。 */
    @Test
    fun samePageVideoAndPureAudioAreNotDuplicates() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow(RECORD_ID)
        seedRow("rec-2")
        // 同一页面两次嗅探 → 两个不同引擎任务（旧实现共用页面 URL，第二条会被拦死）。
        assertEquals(true, coordinator.enqueue(engineTask(recordId = RECORD_ID, taskId = "01a1")))
        assertEquals(true, coordinator.enqueue(engineTask(recordId = "rec-2", taskId = "01a2")))
        assertEquals(2, coordinator.activeCount())
    }

    /** 拦截仍然有效：真正指向同一条直链的两条任务只留一条。 */
    @Test
    fun sameDirectLinkIsStillBlocked() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow(RECORD_ID)
        seedRow("rec-2")
        assertEquals(true, coordinator.enqueue(localTask(recordId = RECORD_ID)))
        assertEquals(false, coordinator.enqueue(localTask(recordId = "rec-2")))
        advanceUntilIdle()
        assertEquals(RecordStatus.FAILED, history.rows.single { it.id == "rec-2" }.status)
        assertEquals("enqueue", audit.of("download_duplicate_blocked").single().fields["origin"])
    }

    /** 授权续跑被拦：等待不能丢、不能偷偷起新流、更不能把待授权的行标失败。 */
    @Test
    fun resumeBlockedByDuplicateKeepsTheConsentWait() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow("rec-2")
        seedRow(RECORD_ID)
        // B 先因 403 挂起（detachStream 后它不再是活跃流）
        coordinator.enqueue(localTask(recordId = "rec-2"))
        push(DownloadEvent.Started(task(TaskStatus.DOWNLOADING)))
        push(DownloadEvent.Failed(LocalFailures.linkUnloadable(403)))
        assertEquals(true, coordinator.consentWaits.value.any { it.recordId == "rec-2" })

        // A 随后占住同一条直链
        assertEquals(true, coordinator.enqueue(localTask(recordId = RECORD_ID)))
        val startsBefore = provider.downloadBackend.startRequests.size
        consent.grant("cdn.example.com")
        coordinator.resume("rec-2")
        advanceUntilIdle()

        assertEquals(true, coordinator.consentWaits.value.any { it.recordId == "rec-2" })
        assertEquals("被拦不能偷偷起一条新流", startsBefore, provider.downloadBackend.startRequests.size)
        assertEquals(true, history.calls.none { it == "markFailed" })
        assertEquals(1, audit.of("download_resume_blocked").size)
    }

    /** 两行各归各：点名取消只动那一行。 */
    @Test
    fun twoTasksInFlightAreCancelledIndividually() = runTest(dispatcher) {
        val coordinator = coordinator(this)
        seedRow(RECORD_ID)
        seedRow("rec-2")
        coordinator.enqueue(engineTask())
        coordinator.enqueue(engineTask(recordId = "rec-2", taskId = "01a2"))
        assertEquals(2, coordinator.activeCount())

        coordinator.cancel("rec-2")

        assertEquals(RecordStatus.CANCELLED, history.rows.single { it.id == "rec-2" }.status)
        assertEquals(1, coordinator.activeCount())
        assertEquals(RecordStatus.SNIFFING, history.rows.single { it.id == RECORD_ID }.status)
    }

    private fun coordinator(
        scope: TestScope,
        now: () -> Long = System::currentTimeMillis,
        progressIntervalMs: Long = 1_000L,
    ): DownloadCoordinator = DownloadCoordinator(
        provider,
        history,
        scope.backgroundScope,
        audit,
        consent,
        now,
        progressIntervalMs,
    )

    private suspend fun seedRow(id: String = RECORD_ID) {
        history.beginSniff(id, SOURCE_URL)
    }

    /** 发一个事件并把这一条推进到底：协调器收到的顺序就是这里发的顺序。 */
    private suspend fun TestScope.push(event: DownloadEvent) {
        provider.downloadBackend.emitStart(event)
        advanceUntilIdle()
    }

    private fun Task.withProgress(downloaded: Long, phase: String = "downloading") = copy(
        progress = TaskProgress(
            phase = phase,
            bytesDownloaded = downloaded,
            bytesTotal = 4_096L,
            speedBps = 1_024L,
            etaSeconds = 4,
            percent = downloaded.toDouble() / 4_096.0,
        ),
    )

    private fun engineTask(recordId: String = RECORD_ID, taskId: String = "01a1") = QueuedTask(
        recordId = recordId,
        // 引擎侧的防踩踏键是后端任务号：同页「视频 + 纯音频」是两条不同任务。
        dedupeKey = taskId,
        executor = DownloadExecutor.ENGINE,
        request = StartRequest.ViaEngine(taskId, FormatSelector(SelectionStrategy.EXACT, "137"), null, null),
        formatSummary = "720p · mp4",
        formatId = "137",
        needsSniffReplay = false,
    )

    private fun localTask(
        recordId: String = RECORD_ID,
        mediaUrl: String = "https://cdn.example.com/v/clip.mp4",
    ) = QueuedTask(
        recordId = recordId,
        // 本机直链：真正会被写成 .part 的那条 URL，不是页面地址。
        dedupeKey = mediaUrl,
        executor = DownloadExecutor.LOCAL_DIRECT,
        request = StartRequest.ViaLocal(
            link = SniffResult(
                taskId = "01a1",
                mediaUrl = mediaUrl,
                type = MediaStreamType.MP4,
                container = "mp4",
                estimatedQuality = EstimatedQuality(height = 720, bitrateKbps = 2500, label = "720p"),
                drmDetected = false,
                discoveredAt = CREATED_AT,
            ),
            outputDir = FakeBackendProvider.DEFAULT_SANDBOX,
            allowedRoots = listOf(FakeBackendProvider.DEFAULT_SANDBOX),
        ),
        formatSummary = "720p · mp4",
        formatId = "local-1",
        needsSniffReplay = false,
    )

    private fun task(status: TaskStatus) = Task(
        id = "01a1",
        sourceUrl = SOURCE_URL,
        mode = TaskMode.DOWNLOAD,
        cookiePolicy = CookiePolicy.LAZY,
        outputDir = FakeBackendProvider.DEFAULT_SANDBOX,
        status = status,
        namingTemplate = "{title}-{height}p",
        createdAt = CREATED_AT,
        updatedAt = UPDATED_AT,
    )

    /**
     * 本机直链的任务快照：真实产物名放在模板槽位里（见 `LocalDirectDownloader.localSnapshot`），
     * 所以 `Started` 与终态带的是同一个名字——进度行据此就能算出文件在哪。
     */
    private fun localSnapshot(status: TaskStatus) = task(status).copy(namingTemplate = "clip-720p.mp4")

    private companion object {
        const val RECORD_ID = "rec-1"
        const val SOURCE_URL = "https://example.com/watch"
        const val CREATED_AT = "2026-11-02T10:00:00Z"
        const val UPDATED_AT = "2026-11-02T10:00:01Z"
    }
}
