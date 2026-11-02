package com.hpu.browseguard.data.history

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryReconcileTest {

    private val now = 1_000_000L

    @Test
    fun missingEngineTaskMeansInterruptedNeverAFakeProgress() {
        val result = resolveColdStart(record(RecordStatus.DOWNLOADING, taskId = "t1"), null, now)

        assertEquals(RecordStatus.INTERRUPTED, result!!.status)
        assertEquals(now, result.updatedAt)
    }

    @Test
    fun doneDownloadRecoversOutputPath() {
        val result = resolveColdStart(
            record(RecordStatus.DOWNLOADING, taskId = "t1"),
            task(TaskStatus.DONE, outputPath = "E:\\Videos\\BGV\\clip.mp4"),
            now,
        )

        assertEquals(RecordStatus.DONE, result!!.status)
        assertEquals("E:\\Videos\\BGV\\clip.mp4", result.outputPath)
    }

    @Test
    fun doneSniffBecomesReadyInsteadOfDoneDownload() {
        val result = resolveColdStart(
            record(RecordStatus.SNIFFING, taskId = "t1"),
            task(TaskStatus.DONE),
            now,
        )

        assertEquals(RecordStatus.READY, result!!.status)
    }

    @Test
    fun failedAndCancelledTasksKeepTheirTruth() {
        val failed = resolveColdStart(
            record(RecordStatus.DOWNLOADING, taskId = "t1"),
            task(TaskStatus.FAILED, error = ErrorPayload.of(ErrorCode.NETWORK_TIMEOUT)),
            now,
        )
        val cancelled = resolveColdStart(
            record(RecordStatus.DOWNLOADING, taskId = "t1"),
            task(TaskStatus.CANCELLED),
            now,
        )

        assertEquals(RecordStatus.FAILED, failed!!.status)
        assertEquals("NETWORK_TIMEOUT", failed.errorCode)
        assertEquals(RecordStatus.CANCELLED, cancelled!!.status)
    }

    @Test
    fun engineStillRunningIsInterruptedBecauseTheClientCannotResumePolling() {
        val result = resolveColdStart(
            record(RecordStatus.DOWNLOADING, taskId = "t1"),
            task(TaskStatus.DOWNLOADING),
            now,
        )

        assertEquals(RecordStatus.INTERRUPTED, result!!.status)
    }

    @Test
    fun terminalRecordsAreLeftAlone() {
        assertNull(resolveColdStart(record(RecordStatus.DONE, taskId = "t1"), null, now))
        assertNull(resolveColdStart(record(RecordStatus.READY, taskId = "t1"), null, now))
    }

    private fun record(status: RecordStatus, taskId: String?) = DownloadRecord(
        id = "row",
        taskId = taskId,
        url = "https://example.com/watch",
        status = status,
        createdAt = 1,
        updatedAt = 2,
    )

    private fun task(
        status: TaskStatus,
        outputPath: String? = null,
        error: ErrorPayload? = null,
    ) = Task(
        id = "t1",
        sourceUrl = "https://example.com/watch",
        mode = TaskMode.DOWNLOAD,
        cookiePolicy = CookiePolicy.LAZY,
        outputDir = "E:\\Videos\\BGV",
        status = status,
        error = error,
        createdAt = "2026-11-02T10:00:00Z",
        updatedAt = "2026-11-02T10:00:01Z",
        outputPath = outputPath,
    )
}
