package com.hpu.browseguard.data.history

import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomHistoryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun deleteRemovesRecordAndLocalFilePlusPartFile() = runTest {
        val sandbox = folder.newFolder("Download")
        val file = File(sandbox, "clip.mp4").apply { writeText("done") }
        val part = File(sandbox, "clip.mp4.part").apply { writeText("partial") }
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, file.absolutePath)
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath)

        val removed = repo.delete("1", DeleteChoice.RECORD_AND_FILE)

        assertTrue("沙箱内文件应当被清理", removed)
        assertFalse(file.exists())
        assertFalse("断点残留也一并清理", part.exists())
        assertNull(dao.rows["1"])
    }

    @Test
    fun deleteNeverTouchesFilesOutsideSandboxAndStillRemovesTheRow() = runTest {
        val sandbox = folder.newFolder("Download")
        val outside = folder.newFile("outside.mp4").apply { writeText("keep me") }
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, outside.absolutePath)
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath)

        val removed = repo.delete("1", DeleteChoice.RECORD_AND_FILE)

        assertFalse("沙箱外文件不得删除", removed)
        assertTrue(outside.exists())
        assertNull("记录行仍然要删掉", dao.rows["1"])
    }

    @Test
    fun deleteKeepsTheFileWhileAnotherRecordStillReferencesIt() = runTest {
        val sandbox = folder.newFolder("Download")
        val file = File(sandbox, "clip.mp4").apply { writeText("done") }
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.FAILED, file.absolutePath)
        dao.rows["2"] = record("2", RecordStatus.DONE, file.absolutePath)
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath, audit = audit)

        assertFalse("还有记录指着它，不许删文件", repo.delete("1", DeleteChoice.RECORD_AND_FILE))
        assertTrue(file.exists())
        assertNull(dao.rows["1"])
        assertEquals("想删却没删成必须留审计行", 1, audit.of("history_file_kept").size)

        assertTrue("最后一条引用删掉后文件才清理", repo.delete("2", DeleteChoice.RECORD_AND_FILE))
        assertFalse(file.exists())
    }

    /** 公开目录选「仅删除记录」时，连 SAF 删除回调都不许碰。 */
    @Test
    fun recordOnlyDeleteNeverCallsTheDocumentDeleter() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "content://tree/doc/clip.mp4")
        var deletions = 0
        val repo = RoomHistoryRepository(
            dao,
            null,
            deleteDocument = { deletions++; true },
        )

        val removed = repo.delete("1", DeleteChoice.RECORD_ONLY)

        assertFalse(removed)
        assertEquals("用户要留文件，一个字节都不能动", 0, deletions)
        assertNull(dao.rows["1"])
    }

    @Test
    fun recordAndFileDeleteUsesTheSafDocumentDeleter() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "content://tree/doc/clip.mp4")
        val deleted = mutableListOf<String>()
        val repo = RoomHistoryRepository(
            dao,
            null,
            deleteDocument = { deleted += it; true },
        )

        val removed = repo.delete("1", DeleteChoice.RECORD_AND_FILE)

        assertTrue(removed)
        assertEquals(listOf("content://tree/doc/clip.mp4"), deleted)
        assertNull(dao.rows["1"])
    }

    /** 引擎在电脑上的路径：手机上没有这个文件，两种选择都只删行。 */
    @Test
    fun engineArtifactIsNeverDeletedFromThePhone() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "E:\\Videos\\BGV\\clip.mp4")
        var deletions = 0
        val repo = RoomHistoryRepository(
            dao,
            folder.newFolder("Download").absolutePath,
            deleteDocument = { deletions++; true },
        )

        assertFalse(repo.delete("1", DeleteChoice.RECORD_AND_FILE))

        assertEquals(0, deletions)
        assertNull("记录行照删", dao.rows["1"])
    }

    @Test
    fun deleteAllRemovesEveryRowAndEverySandboxFile() = runTest {
        val sandbox = folder.newFolder("Download")
        val first = File(sandbox, "one.mp4").apply { writeText("one") }
        val second = File(sandbox, "two.mp4").apply { writeText("two") }
        val shared = File(sandbox, "shared.mp4").apply { writeText("shared") }
        val outside = folder.newFile("outside.mp4").apply { writeText("keep") }
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, first.absolutePath)
        dao.rows["2"] = record("2", RecordStatus.FAILED, second.absolutePath)
        dao.rows["3"] = record("3", RecordStatus.DONE, shared.absolutePath)
        dao.rows["4"] = record("4", RecordStatus.DONE, shared.absolutePath)
        dao.rows["5"] = record("5", RecordStatus.DONE, outside.absolutePath)
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath)

        val removed = repo.deleteAll()

        assertEquals(5, removed)
        assertTrue(dao.rows.isEmpty())
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertFalse("清空后不存在其他引用，共享产物也应清理", shared.exists())
        assertTrue("沙箱外文件不得删除", outside.exists())
    }

    /** 「清空历史」不该替用户删掉公开文件夹里的视频。 */
    @Test
    fun deleteAllLeavesSafDocumentsAlone() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "content://tree/doc/clip.mp4")
        var deletions = 0
        val repo = RoomHistoryRepository(
            dao,
            folder.newFolder("Download").absolutePath,
            deleteDocument = { deletions++; true },
        )

        assertEquals(1, repo.deleteAll())

        assertEquals("公开目录的文件一个都不动", 0, deletions)
        assertTrue(dao.rows.isEmpty())
    }

    /** 心跳只写进度：`updatedAt` 一旦被顶高，任务会沿列表一路往上跑。 */
    @Test
    fun markProgressMovesTheBytesButNotTheTimestamp() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DOWNLOADING, null)
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.markProgress("1", downloadedBytes = 4_096, bytesTotal = 16_384, speedBps = 1_024)

        val row = dao.rows.getValue("1")
        assertEquals(4_096L, row.downloadedBytes)
        assertEquals(16_384L, row.bytesTotal)
        assertEquals(1_024L, row.speedBps)
        assertEquals("心跳不得改动排序时间", 2L, row.updatedAt)
        assertNull("没带阶段提示的心跳要把上一句清掉", row.note)
    }

    /**
     * 提示键每次落定都重新判一遍：降级那一次写进去的 key，
     * 下一次成功转封装必须清掉，否则完成行会一直挂着上一轮的旧提醒。
     */
    @Test
    fun theNoteIsRewrittenByEveryTerminalWrite() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DOWNLOADING, null)
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.markDone("1", outputPath = "/sandbox/clip.ts", format = "720p", note = RecordNotes.REMUX_FALLBACK)
        assertEquals(RecordNotes.REMUX_FALLBACK, dao.rows.getValue("1").note)

        repo.markDone("1", outputPath = "/sandbox/clip.mp4", format = "720p")
        assertNull(dao.rows.getValue("1").note)
    }

    @Test
    fun markDoneBackfillsTheArtifactSize() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DOWNLOADING, null)
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.markDone("1", outputPath = "/sandbox/clip.mp4", format = "720p", sizeBytes = 20_480)

        assertEquals(20_480L, dao.rows.getValue("1").sizeBytes)
    }

    @Test
    fun aMissingSizeKeepsThePreviouslyRecordedOne() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "/sandbox/clip.mp4").copy(sizeBytes = 7L)
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.updateArtifact("1", outputPath = "/sandbox/renamed.mp4", title = null, sizeBytes = null)

        val row = dao.rows.getValue("1")
        assertEquals("/sandbox/renamed.mp4", row.outputPath)
        assertEquals("读不到大小就沿用旧值，不写 0", 7L, row.sizeBytes)
    }

    @Test
    fun updateArtifactRewritesPathTitleAndSizeTogether() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "/sandbox/clip.mp4")
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.updateArtifact("1", outputPath = "content://tree/doc/x.mp4", title = "新名字", sizeBytes = 128)

        val row = dao.rows.getValue("1")
        assertEquals("content://tree/doc/x.mp4", row.outputPath)
        assertEquals("新名字", row.title)
        assertEquals(128L, row.sizeBytes)
        assertEquals("改名换址是用户动作，要参与排序", 999L, row.updatedAt)
    }

    @Test
    fun engineOfflinePreservesRemoteRowsAndNeverCallsLookup() = runTest {
        val dao = FakeDao()
        dao.rows["sniff"] = record("sniff", RecordStatus.SNIFFING, null, taskId = "t1")
        dao.rows["download"] = record("download", RecordStatus.DOWNLOADING, null, taskId = "t2")
        dao.rows["done"] = record("done", RecordStatus.DONE, null, taskId = "t3")
        val repo = RoomHistoryRepository(dao, null, now = { 999 })
        var lookups = 0

        val report = repo.reconcile(engineReachable = false) {
            lookups++
            error("引擎离线时不得回查任务")
        }

        assertEquals(2, report.scanned)
        assertEquals("离线不是中断", 0, report.interrupted)
        assertEquals("远端行原样保留", 2, report.skipped)
        assertEquals(0, lookups)
        assertEquals(RecordStatus.SNIFFING, dao.rows["sniff"]!!.status)
        assertEquals(RecordStatus.DOWNLOADING, dao.rows["download"]!!.status)
        assertEquals("终态记录不参与对账", RecordStatus.DONE, dao.rows["done"]!!.status)
    }

    @Test
    fun engineOfflineStillInterruptsRowsWithoutARemoteHandle() = runTest {
        val dao = FakeDao()
        dao.rows["sniff"] = record("sniff", RecordStatus.SNIFFING, null, taskId = null)
        dao.rows["local"] = record("local", RecordStatus.DOWNLOADING, null, taskId = "local_abc")
        // 本机画质表的占位 id 也会被回填进 taskId：它同样不是引擎句柄，离线时必须能收敛。
        dao.rows["table"] = record("table", RecordStatus.SNIFFING, null, taskId = "local")
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        val report = repo.reconcile(engineReachable = false) {
            error("本地句柄不得发往引擎")
        }

        assertEquals(3, report.interrupted)
        assertEquals(0, report.skipped)
        assertEquals(RecordStatus.INTERRUPTED, dao.rows["sniff"]!!.status)
        assertEquals(RecordStatus.INTERRUPTED, dao.rows["local"]!!.status)
        assertEquals("占位 id 挂着的嗅探行不许永远停在「嗅探中」", RecordStatus.INTERRUPTED, dao.rows["table"]!!.status)
    }

    @Test
    fun missingTaskIsInterruptedButUnknownKeepsTheRow() = runTest {
        val dao = FakeDao()
        dao.rows["missing"] = record("missing", RecordStatus.DOWNLOADING, null, taskId = "t1")
        dao.rows["unknown"] = record("unknown", RecordStatus.DOWNLOADING, null, taskId = "t2")
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        val report = repo.reconcile(engineReachable = true) {
            if (it == "t1") TaskLookup.Missing else TaskLookup.Unknown
        }

        assertEquals(1, report.interrupted)
        assertEquals(0, report.recovered)
        assertEquals(1, report.skipped)
        assertEquals(RecordStatus.INTERRUPTED, dao.rows["missing"]!!.status)
        assertEquals("答不上来只代表状态未知", RecordStatus.DOWNLOADING, dao.rows["unknown"]!!.status)
    }

    @Test
    fun reconcileRecoversADownloadTheEngineFinishedWhileTheAppWasDead() = runTest {
        val dao = FakeDao()
        dao.rows["download"] = record("download", RecordStatus.DOWNLOADING, null, taskId = "t2")
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        val report = repo.reconcile(engineReachable = true) {
            TaskLookup.Found(task(TaskStatus.DONE, outputPath = "E:\\Videos\\BGV\\clip.mp4"))
        }

        assertEquals(1, report.recovered)
        val recovered = dao.rows["download"]!!
        assertEquals(RecordStatus.DONE, recovered.status)
        assertEquals("E:\\Videos\\BGV\\clip.mp4", recovered.outputPath)
    }

    /**
     * 一次嗅探会话只有一行，重试走的还是同一行：后来的失败/取消不能把
     * 「已经成功、产物还在盘上」的记录抹成失败（MuMu 实测过这条降级）。
     */
    @Test
    fun aCompletedRecordSurvivesLaterFailureAndCancellation() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DOWNLOADING, null)
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, null, now = { 999 }, audit = audit)

        repo.markDone("1", outputPath = "/sandbox/clip.mp4", format = "720p")
        repo.markFailed("1", errorCode = null)
        repo.markCancelled("1")

        val row = dao.rows.getValue("1")
        assertEquals("已完成是终态", RecordStatus.DONE, row.status)
        assertEquals("/sandbox/clip.mp4", row.outputPath)
        assertEquals("拒绝改写必须留审计行", 2, audit.of("history_done_kept").size)
    }

    /**
     * 授权续跑把嗅探腿和下载腿压进同几十毫秒：嗅探腿拿的是下载开始前的旧快照，
     * 一旦让它最后落回，就能把已经写好的 DONE 连产物路径一起抹掉（MuMu 实测的永久「嗅探中」）。
     * 这里用「READY 那一写故意迟到」复现交错，串行闸必须让它排到结尾之后。
     */
    @Test
    fun aSniffLegThatLagsBehindTheWholeDownloadCannotEraseTheEnding() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.SNIFFING, null)
        dao.beforeUpsert = { if (it.status == RecordStatus.READY) delay(50) }
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        val sniff = launch { repo.markReady("1", format = "720p") }
        yield()
        val download = launch {
            repo.markDownloading("1", taskId = "local_d728", format = "720p", outputPath = null)
            repo.markDone("1", outputPath = "/sandbox/clip.mp4", format = "720p", sizeBytes = 20_480)
        }
        sniff.join()
        download.join()

        val row = dao.rows.getValue("1")
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals("/sandbox/clip.mp4", row.outputPath)
        assertEquals(20_480L, row.sizeBytes)
    }

    /** 迟到的「嗅探完成」同样不能把已完成那一行退回中间态。 */
    @Test
    fun aLateReadyKeepsTheCompletedRecordCompleted() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "/sandbox/clip.mp4")
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, null, now = { 999 }, audit = audit)

        repo.markReady("1", format = "480p")

        val row = dao.rows.getValue("1")
        assertEquals(RecordStatus.DONE, row.status)
        assertEquals("/sandbox/clip.mp4", row.outputPath)
        assertEquals(1, audit.of("history_done_kept").size)
    }

    /** 迟到的心跳不该在完成行上留下假进度或上一阶段的「正在转封装」。 */
    @Test
    fun aLateHeartbeatLeavesTheCompletedRowAlone() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, "/sandbox/clip.ts").copy(note = RecordNotes.REMUX_FALLBACK)
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.markProgress("1", downloadedBytes = 4_096, bytesTotal = 16_384, speedBps = 1_024, note = RecordNotes.REMUXING)

        val row = dao.rows.getValue("1")
        assertEquals(0L, row.downloadedBytes)
        assertEquals(RecordNotes.REMUX_FALLBACK, row.note)
    }

    @Test
    fun aStillInProgressRecordIsTerminalizedByFailureAndCancellation() = runTest {
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DOWNLOADING, null)
        dao.rows["2"] = record("2", RecordStatus.SNIFFING, null)
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        repo.markFailed("1", errorCode = null)
        repo.markCancelled("2")

        assertEquals(RecordStatus.FAILED, dao.rows.getValue("1").status)
        assertEquals(RecordStatus.CANCELLED, dao.rows.getValue("2").status)
    }

    /**
     * 冷启动顺手清沙盒：没有一行记录引用的产物就是孤儿（串行闸修好前那几支被抹掉结尾的下载）。
     * 四条边界都要钉住——被引用的、本次运行刚写出的、非下载后缀的、沙盒外的，一律不许动。
     */
    @Test
    fun coldStartSweepRemovesOnlyFilesNoRecordReferences() = runTest {
        val sandbox = folder.newFolder("Download")
        val orphan = File(sandbox, "clip-b.mp4").apply { writeText("orphan"); setLastModified(1_000) }
        val stalePart = File(sandbox, "clip-c.mp4.part").apply { writeText("half"); setLastModified(1_000) }
        val referenced = File(sandbox, "clip-a.mp4").apply { writeText("kept"); setLastModified(1_000) }
        val running = File(sandbox, "clip-new.mp4.part").apply { writeText("live"); setLastModified(9_000) }
        val notOurs = File(sandbox, "notes.txt").apply { writeText("other"); setLastModified(1_000) }
        val outside = folder.newFile("outside.mp4").apply { writeText("keep"); setLastModified(1_000) }
        val dao = FakeDao()
        dao.rows["a"] = record("a", RecordStatus.DONE, referenced.absolutePath)
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath, now = { 5_000 }, audit = audit)
        val sizes = setOf(orphan.length().toString(), stalePart.length().toString())

        val report = repo.reconcile(engineReachable = false) { error("这一行是终态，不该回查") }

        assertEquals(2, report.orphansRemoved)
        assertFalse("没人引用的产物直接清掉", orphan.exists())
        assertFalse("断点残留同样清掉", stalePart.exists())
        assertTrue(referenced.exists())
        assertTrue("本次运行刚写出的文件属于正在下的那一支", running.exists())
        assertTrue("不是下载写出来的后缀不猜", notOurs.exists())
        assertTrue("沙盒外一个字节都不动", outside.exists())
        assertEquals("每个物理删除都要留审计行", 2, audit.of("history_orphan_removed").size)
        assertEquals(
            "字节数要在 delete() 之前取，删完再读只会是 0",
            sizes,
            audit.of("history_orphan_removed").map { it.fields.getValue("bytes") }.toSet(),
        )
    }

    /**
     * 老版本留下的「已取消 + 无产物」幽灵行，冷启动一次清干净。
     *
     * 有产物的取消行是那份文件的署名——它告诉用户「这个视频是哪次取消留下的」，不能跟着一起清。
     */
    @Test
    fun coldStartDropsGhostCancellationsButKeepsTheOnesThatSignedAFile() = runTest {
        val sandbox = folder.newFolder("Download")
        val signed = File(sandbox, "clip-d.mp4").apply { writeText("mine"); setLastModified(1_000) }
        val dao = FakeDao()
        dao.rows["ghost"] = record("ghost", RecordStatus.CANCELLED, null)
        dao.rows["blank"] = record("blank", RecordStatus.CANCELLED, "  ")
        dao.rows["signed"] = record("signed", RecordStatus.CANCELLED, signed.absolutePath)
        dao.rows["done"] = record("done", RecordStatus.DONE, "/sandbox/other.mp4")
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath, now = { 5_000 }, audit = audit)

        val report = repo.reconcile(engineReachable = false) { error("这几行都是终态，不该回查") }

        assertEquals(2, report.ghostsRemoved)
        assertNull(dao.rows["ghost"])
        assertNull("空白路径和 null 一样算没有产物", dao.rows["blank"])
        assertEquals(RecordStatus.CANCELLED, dao.rows.getValue("signed").status)
        assertTrue("被一行取消记录指着的文件不是孤儿", signed.exists())
        assertEquals(0, report.orphansRemoved)
        assertEquals("每一行幽灵都要留下为什么被删", 2, audit.of("history_ghost_removed").size)
    }

    /** 作废 = 删掉那条没有产物的幽灵行；写过产物落点的行只退成「已取消」。 */
    @Test
    fun abandoningDropsTheGhostRowButKeepsTheOneThatRecordedAnArtifact() = runTest {
        val dao = FakeDao()
        dao.rows["ghost"] = record("ghost", RecordStatus.SNIFFING, null)
        dao.rows["written"] = record("written", RecordStatus.DOWNLOADING, "/sandbox/clip.mp4")
        dao.rows["done"] = record("done", RecordStatus.DONE, "/sandbox/other.mp4")
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, null, now = { 999 }, audit = audit)

        assertTrue(repo.abandon("ghost"))
        assertFalse(repo.abandon("written"))
        assertFalse(repo.abandon("done"))

        assertNull(dao.rows["ghost"])
        assertEquals(RecordStatus.CANCELLED, dao.rows.getValue("written").status)
        assertEquals("已完成不因作废改写", RecordStatus.DONE, dao.rows.getValue("done").status)
        assertEquals(1, audit.of("history_abandon_dropped").size)
        assertEquals(1, audit.of("history_abandon_kept").size)
        assertEquals("终态守卫的审计口径不变", 1, audit.of("history_done_kept").size)
    }

    /** 观看进度只落在已完成的行上、写的是归一后的续播点，而且不许把记录顶到列表最上面。 */
    @Test
    fun playbackProgressLandsOnlyOnCompletedRowsAndNeverMovesTheRow() = runTest {
        val dao = FakeDao()
        dao.rows["done"] = record("done", RecordStatus.DONE, "/sandbox/clip.mp4")
        dao.rows["running"] = record("running", RecordStatus.DOWNLOADING, null)
        val audit = FakeAuditLogger()
        val repo = RoomHistoryRepository(dao, null, now = { 9_000 }, audit = audit)

        repo.markPlayed("done", 45_000, 120_000)
        repo.markPlayed("running", 45_000, 120_000)
        repo.markPlayed("missing", 45_000, 120_000)

        val done = dao.rows.getValue("done")
        assertEquals(45_000L, done.lastPlayedPositionMs)
        assertEquals(120_000L, done.mediaDurationMs)
        assertEquals("看片不该改「什么时候下的」那一次排序", 2L, done.updatedAt)
        assertEquals("没下完的行没有可看的产物", 0L, dao.rows.getValue("running").lastPlayedPositionMs)
        assertEquals("被拒绝的回写得说清楚为什么", 1, audit.of("playback_progress_refused").size)
    }

    /** 看完与刚起播都归零：库里的数永远是「打开就该从这里播」。 */
    @Test
    fun playbackWriteStoresNothingToResumeForFinishedAndJustStartedPositions() = runTest {
        val dao = FakeDao()
        dao.rows["a"] = record("a", RecordStatus.DONE, "/sandbox/a.mp4")
        dao.rows["b"] = record("b", RecordStatus.DONE, "/sandbox/b.mp4")
        dao.rows["c"] = record("c", RecordStatus.DONE, "/sandbox/c.mp4")
        val repo = RoomHistoryRepository(dao, null, now = { 9_000 })

        repo.markPlayed("a", 118_000, 120_000)
        repo.markPlayed("b", 900, 120_000)
        repo.markPlayed("c", 1_000, 0)

        assertEquals("剩两秒等于看完了", 0L, dao.rows.getValue("a").lastPlayedPositionMs)
        assertEquals("起播抖动不当成用户看过的地方", 0L, dao.rows.getValue("b").lastPlayedPositionMs)
        assertEquals("时长都不知道时不猜看完", 0L, dao.rows.getValue("c").mediaDurationMs)
    }

    private fun record(
        id: String,
        status: RecordStatus,
        outputPath: String?,
        taskId: String? = null,
    ) = DownloadRecord(
        id = id,
        taskId = taskId,
        url = "https://example.com/watch",
        status = status,
        outputPath = outputPath,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun task(status: TaskStatus, outputPath: String? = null) = Task(
        id = "t2",
        sourceUrl = "https://example.com/watch",
        mode = TaskMode.DOWNLOAD,
        cookiePolicy = CookiePolicy.LAZY,
        outputDir = "E:\\Videos\\BGV",
        status = status,
        createdAt = "2026-11-02T10:00:00Z",
        updatedAt = "2026-11-02T10:00:01Z",
        outputPath = outputPath,
    )

    private class FakeDao : DownloadRecordDao {
        val rows = linkedMapOf<String, DownloadRecord>()
        private val flow = MutableStateFlow<List<DownloadRecord>>(emptyList())

        /** 交错测试用的挂点：让某一写在意想不到的时刻迟到。 */
        var beforeUpsert: suspend (DownloadRecord) -> Unit = {}

        override fun observeAll(): Flow<List<DownloadRecord>> = flow

        override suspend fun getAll(): List<DownloadRecord> =
            rows.values.sortedByDescending { it.createdAt }

        override suspend fun findById(id: String): DownloadRecord? = rows[id]

        override suspend fun upsert(record: DownloadRecord) {
            beforeUpsert(record)
            rows[record.id] = record
            publish()
        }

        override suspend fun updateStatus(
            id: String,
            status: RecordStatus,
            errorCode: String?,
            updatedAt: Long,
        ) {
            rows[id] = rows[id]!!.copy(status = status, errorCode = errorCode, updatedAt = updatedAt)
            publish()
        }

        override suspend fun markDownloadStarted(
            id: String,
            taskId: String,
            status: RecordStatus,
            outputPath: String?,
            updatedAt: Long,
        ) {
            rows[id] = rows[id]!!.copy(
                taskId = taskId,
                status = status,
                outputPath = outputPath,
                updatedAt = updatedAt,
            )
            publish()
        }

        override suspend fun updateProgress(
            id: String,
            downloadedBytes: Long,
            bytesTotal: Long,
            speedBps: Long,
            note: String?,
        ) {
            rows[id] = rows[id]!!.copy(
                downloadedBytes = downloadedBytes,
                bytesTotal = bytesTotal,
                speedBps = speedBps,
                note = note,
            )
            publish()
        }

        override suspend fun updateArtifact(
            id: String,
            outputPath: String,
            sizeBytes: Long?,
            title: String?,
            updatedAt: Long,
        ) {
            val current = rows[id] ?: return
            rows[id] = current.copy(
                outputPath = outputPath,
                sizeBytes = sizeBytes ?: current.sizeBytes,
                title = title ?: current.title,
                updatedAt = updatedAt,
            )
            publish()
        }

        override suspend fun updatePlayback(id: String, positionMs: Long, durationMs: Long) {
            rows[id] = rows[id]!!.copy(lastPlayedPositionMs = positionMs, mediaDurationMs = durationMs)
            publish()
        }

        override suspend fun findByStatuses(statuses: List<RecordStatus>): List<DownloadRecord> =
            rows.values.filter { it.status in statuses }

        override suspend fun findByOutputPath(path: String): List<DownloadRecord> =
            rows.values.filter { it.outputPath == path }

        override suspend fun deleteById(id: String) {
            rows.remove(id)
            publish()
        }

        override suspend fun deleteAll() {
            rows.clear()
            publish()
        }

        private fun publish() {
            flow.value = rows.values.sortedByDescending { it.updatedAt }
        }
    }
}
