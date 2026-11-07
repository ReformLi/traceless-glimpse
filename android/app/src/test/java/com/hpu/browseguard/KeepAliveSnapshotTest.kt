package com.hpu.browseguard

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordNotes
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.keepalive.KeepAliveSnapshotOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知栏那一格的事实怎么从记录里算出来（M13.2）。
 *
 * 通知在系统进程里画，看不见就得挨一句「你是不是没在下」；反过来，画出一个假数字更糟——
 * 所以这几条钉的都是「什么时候不许说」：字节收满但终态没落库、总量不知道、还没有一行真的在下。
 */
class KeepAliveSnapshotTest {

    @Test
    fun concurrentStreamsArePooledByBytesNotAveraged() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 2,
            records = listOf(
                downloading(id = "small", downloaded = 50L, total = 50L, createdAt = 1L),
                downloading(id = "big", downloaded = 0L, total = 150L, createdAt = 2L),
            ),
        )

        // 按总字节是 50/200 = 25%；把两个百分比平均会得到 50%，那是在替一条没动的流报进度。
        assertEquals(25, snapshot.percent)
    }

    @Test
    fun aSaturatedByteCountIsStillNotOneHundred() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 1,
            records = listOf(downloading(id = "a", downloaded = 100L, total = 100L, createdAt = 1L)),
        )

        assertEquals("终态那一笔还没落库，通知栏不许提前宣布下完了", 99, snapshot.percent)
    }

    /** 分块传、边下边探长度这些场景总量就是 0；宁可画不确定模式，也不报一个 0%。 */
    @Test
    fun anUnknownSizeGivesNoPercentage() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 1,
            records = listOf(downloading(id = "a", downloaded = 4_096_000L, total = 0L, createdAt = 1L)),
        )

        assertNull(snapshot.percent)
        assertEquals("条数照说：这一层没有骗人的东西", 1, snapshot.active)
    }

    @Test
    fun remuxingIsCalledOutBecauseThatLegMovesNoBytes() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 1,
            records = listOf(
                downloading(id = "a", downloaded = 100L, total = 100L, createdAt = 1L, note = RecordNotes.REMUXING),
            ),
        )

        assertTrue(snapshot.remuxing)
    }

    @Test
    fun finishedRemuxingNoteDoesNotStickOnTheNextRow() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 2,
            records = listOf(
                downloading(id = "a", downloaded = 10L, total = 100L, createdAt = 1L, note = RecordNotes.REMUX_FALLBACK),
                downloading(id = "b", downloaded = 10L, total = 100L, createdAt = 2L),
            ),
        )

        assertFalse("转封装完了就不该再挂在通知上", snapshot.remuxing)
    }

    @Test
    fun theOldestStreamOwnsTheName() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 2,
            records = listOf(
                downloading(id = "late", downloaded = 1L, total = 10L, createdAt = 900L, title = "后来排上的"),
                downloading(id = "early", downloaded = 1L, total = 10L, createdAt = 100L, title = "先开下的"),
            ),
        )

        assertEquals("先开下的", snapshot.current)
    }

    /** 嗅探阶段那一行常常还没有标题；通知栏不能因此空着一半。 */
    @Test
    fun aTitlelessRowIsNamedByWhereItCameFrom() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 1,
            records = listOf(
                downloading(id = "a", downloaded = 1L, total = 10L, createdAt = 1L, title = null)
                    .copy(url = "https://www.example.com/video/clip.mp4"),
            ),
        )

        assertEquals("example.com · clip.mp4", snapshot.current)
    }

    /**
     * 队列已经收下这条任务、但还没有一行进入「下载中」：
     * 服务该挂着（`active` 由队列说了算），文字只能说「正在准备」——没有名字也没有百分比。
     */
    @Test
    fun workQueuedButNotMovingYetSaysNothingItCannotProve() {
        val snapshot = KeepAliveSnapshotOf.of(
            active = 1,
            records = listOf(
                DownloadRecord(
                    id = "ready",
                    url = "https://example.com/video/clip.mp4",
                    title = "还停在嗅探结果",
                    status = RecordStatus.READY,
                    createdAt = 1L,
                    updatedAt = 1L,
                    downloadedBytes = 100L,
                    bytesTotal = 100L,
                ),
            ),
        )

        assertEquals(1, snapshot.active)
        assertNull(snapshot.percent)
        assertNull(snapshot.current)
        assertFalse(snapshot.remuxing)
    }

    private fun downloading(
        id: String,
        downloaded: Long,
        total: Long,
        createdAt: Long,
        note: String? = null,
        title: String? = "[$id]",
    ) = DownloadRecord(
        id = id,
        url = "https://example.com/$id.mp4",
        title = title,
        status = RecordStatus.DOWNLOADING,
        note = note,
        createdAt = createdAt,
        updatedAt = createdAt,
        downloadedBytes = downloaded,
        bytesTotal = total,
    )
}
