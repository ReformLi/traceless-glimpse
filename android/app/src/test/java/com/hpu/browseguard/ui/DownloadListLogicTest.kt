package com.hpu.browseguard.ui

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.sourceHost
import com.hpu.browseguard.data.output.ArtifactChange
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载管理页的列表逻辑：过滤、排序、容量统计与进度文案。 */
class DownloadListLogicTest {

    @Test
    fun filtersGroupByWhatTheUserIsLookingFor() {
        val records = listOf(
            row("sniff", RecordStatus.SNIFFING, at = 1),
            row("down", RecordStatus.DOWNLOADING, at = 2),
            row("ready", RecordStatus.READY, at = 3),
            row("done", RecordStatus.DONE, at = 4),
            row("failed", RecordStatus.FAILED, at = 5),
            row("cancelled", RecordStatus.CANCELLED, at = 6),
            row("interrupted", RecordStatus.INTERRUPTED, at = 7),
        )

        assertEquals(7, filterRecords(records, DownloadFilter.ALL).size)
        assertEquals(listOf("down", "sniff"), filterRecords(records, DownloadFilter.ACTIVE).map { it.id })
        assertEquals(listOf("done"), filterRecords(records, DownloadFilter.DONE).map { it.id })
        assertEquals(
            "取消与中断都是「没成」，要能在失败页找到",
            listOf("interrupted", "cancelled", "failed"),
            filterRecords(records, DownloadFilter.FAILED).map { it.id },
        )
        assertEquals(
            "待下载哪一档都不算，只在全部里出现",
            listOf("ready"),
            filterRecords(records, DownloadFilter.ALL).map { it.id }.filter { it == "ready" },
        )
    }

    /** 一律按最近活动时间倒序：刚下完的老任务排在昨天就开始、刚才失败的新任务上面。 */
    @Test
    fun everythingIsSortedByMostRecentActivity() {
        val records = listOf(
            row("old-but-just-finished", RecordStatus.DONE, at = 100, created = 10),
            row("new-but-stalled", RecordStatus.FAILED, at = 50, created = 40),
        )

        assertEquals(
            listOf("old-but-just-finished", "new-but-stalled"),
            filterRecords(records, DownloadFilter.ALL).map { it.id },
        )
    }

    @Test
    fun capacityOnlyCountsFinishedArtifacts() {
        val records = listOf(
            row("done", RecordStatus.DONE, at = 1, size = 4_000),
            row("done-no-size", RecordStatus.DONE, at = 2),
            row("down", RecordStatus.DOWNLOADING, at = 3, size = 9_000),
            row("failed", RecordStatus.FAILED, at = 4, size = 8_000),
        )

        assertEquals("半成品与失败残留都不算占用", 4_000L, usedBytes(records))
        assertEquals(0L, usedBytes(emptyList()))
    }

    /** 容量与速度共用首页那一个 [formatBytes]，全 app 只有一套读法。 */
    @Test
    fun byteFormattingStaysReadable() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1_024))
        assertEquals("1.5 MB", formatBytes(1_536 * 1_024))
        assertEquals("1.00 GB", formatBytes(1_073_741_824))
    }

    @Test
    fun progressLabelOnlyShowsWhatItKnows() {
        assertEquals(
            "12.3 MB / 80.0 MB · 1.0 MB/s",
            progressLabel(downloadedBytes = 12_897_482, bytesTotal = 83_886_080, speedBps = 1_048_576),
        )
        assertEquals("总量未知时不编一个 0 B", "3.0 KB · 1.0 KB/s", progressLabel(3 * 1_024, 0, 1_024))
        assertEquals("速度未知时不写 0 B/s", "3.0 KB / 9.0 KB", progressLabel(3 * 1_024, 9 * 1_024, 0))
        assertEquals("完全没有进度可报", "0 B", progressLabel(0, 0, 0))
    }

    @Test
    fun fractionIsKnownOnlyWhenTheTotalIs() {
        assertEquals(0.5f, progressFraction(500, 1_000)!!, 0.0001f)
        assertEquals("超量下载也要收在 1", 1f, progressFraction(2_000, 1_000)!!, 0.0001f)
        assertNull(progressFraction(500, 0))
    }

    @Test
    fun sourceHostIsWhatTheRowShowsAsOrigin() {
        assertEquals("youtube.com", sourceHost("https://www.youtube.com/watch?v=abc"))
        assertEquals("cdn.example.com", sourceHost("https://CDN.example.com/a.m3u8"))
        assertEquals("", sourceHost("not a url"))
    }

    /** 改名/移动只对「手机上真有这个产物」的行开放，判定不碰文件系统。 */
    @Test
    fun onlyFinishedPhoneSideArtifactsAreFileOperable() {
        val sandbox = File(System.getProperty("java.io.tmpdir"), "bgv-sandbox").path

        assertTrue(fileOperable(row("done", RecordStatus.DONE, at = 1, path = File(sandbox, "a.mp4").path), sandbox))
        assertTrue(
            "SAF 公开目录同样能搬",
            fileOperable(row("public", RecordStatus.DONE, at = 1, path = "content://tree/doc/a.mp4"), sandbox),
        )
        assertFalse(
            "产物在电脑上，手机上没有文件可搬",
            fileOperable(row("engine", RecordStatus.DONE, at = 1, path = "E:\\Videos\\BGV\\a.mp4"), sandbox),
        )
        assertFalse("沙箱根拿不到时本地路径按引擎侧处理", fileOperable(row("nosbx", RecordStatus.DONE, at = 1, path = File(sandbox, "a.mp4").path), null))
        assertFalse("没下完的行没有产物", fileOperable(row("run", RecordStatus.DOWNLOADING, at = 1, path = File(sandbox, "a.part").path), sandbox))
        assertFalse("还没有产物的行没有可搬的东西", fileOperable(row("ready", RecordStatus.READY, at = 1), sandbox))
    }

    /** 汇总里成功/跳过/失败各说各的：失败原因原文上报，不二次加工。 */
    @Test
    fun operationReportsKeepSkippedAndFailedApart() {
        val changes = listOf(
            ArtifactChange.Changed("/phone/a.mp4", "a.mp4", 1L),
            ArtifactChange.Failed("目标文件夹已不可用"),
            ArtifactChange.Changed("/phone/b.mp4", "b.mp4", 2L),
        )

        assertEquals(OperationReport(OperationVerb.MOVE, succeeded = 2, skipped = 0, failures = listOf("目标文件夹已不可用")), reportOf(OperationVerb.MOVE, changes))
        assertEquals(
            "选中 5 行里有 2 行压根没有手机侧产物，那既不是搬成也不是失败",
            OperationReport(OperationVerb.RENAME, succeeded = 1, skipped = 2, failures = emptyList()),
            reportOf(OperationVerb.RENAME, listOf(ArtifactChange.Changed("/phone/a.mp4", "a.mp4", 1L)), skipped = 2),
        )
        assertEquals("空批次不编出成功", OperationReport(OperationVerb.MOVE, 0), reportOf(OperationVerb.MOVE, emptyList()))
    }

    /** 读不出来的项整个省略——写一个 `0:00` 会被读成「这是个一秒的大截图」。 */
    @Test
    fun mediaLabelsOmitWhatTheyCannotKnow() {
        assertEquals("0:45", durationLabel(45_000L))
        assertEquals("14:23", durationLabel(863_000L))
        assertEquals("1:02:03", durationLabel(3_723_000L))
        assertNull("平台读出 0 时长就是没读出来", durationLabel(0L))
        assertNull(durationLabel(null))

        assertEquals("1280×720", resolutionLabel(1_280, 720))
        assertNull("任何一边缺失就整个不写，绝不猜一个", resolutionLabel(1_280, null))
        assertNull(resolutionLabel(1_280, 0))
    }

    private fun row(
        id: String,
        status: RecordStatus,
        at: Long,
        created: Long = 0,
        size: Long? = null,
        path: String? = null,
    ) = DownloadRecord(
        id = id,
        url = "https://example.com/watch",
        status = status,
        outputPath = path,
        createdAt = created,
        updatedAt = at,
        sizeBytes = size,
    )
}
