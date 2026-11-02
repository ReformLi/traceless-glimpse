package com.hpu.browseguard.data.history

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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

        val removed = repo.delete("1")

        assertTrue("沙箱内文件应当被清理", removed)
        assertFalse(file.exists())
        assertFalse("断点残留也一并清理", part.exists())
        assertNull(dao.rows["1"])
    }

    @Test
    fun deleteNeverTouchesFilesOutsideSandboxAndStillRemovesTheRow() = runTest {        val sandbox = folder.newFolder("Download")
        val outside = folder.newFile("outside.mp4").apply { writeText("keep me") }
        val dao = FakeDao()
        dao.rows["1"] = record("1", RecordStatus.DONE, outside.absolutePath)
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath)

        val removed = repo.delete("1")

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
        val repo = RoomHistoryRepository(dao, sandbox.absolutePath)

        assertFalse("还有记录指着它，不许删文件", repo.delete("1"))
        assertTrue(file.exists())
        assertNull(dao.rows["1"])

        assertTrue("最后一条引用删掉后文件才清理", repo.delete("2"))
        assertFalse(file.exists())
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
        val repo = RoomHistoryRepository(dao, null, now = { 999 })

        val report = repo.reconcile(engineReachable = false) {
            error("本地句柄不得发往引擎")
        }

        assertEquals(2, report.interrupted)
        assertEquals(0, report.skipped)
        assertEquals(RecordStatus.INTERRUPTED, dao.rows["sniff"]!!.status)
        assertEquals(RecordStatus.INTERRUPTED, dao.rows["local"]!!.status)
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

        override fun observeAll(): Flow<List<DownloadRecord>> = flow

        override suspend fun getAll(): List<DownloadRecord> =
            rows.values.sortedByDescending { it.createdAt }

        override suspend fun findById(id: String): DownloadRecord? = rows[id]

        override suspend fun upsert(record: DownloadRecord) {
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
