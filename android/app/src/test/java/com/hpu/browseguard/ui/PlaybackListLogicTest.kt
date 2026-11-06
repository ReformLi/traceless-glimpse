package com.hpu.browseguard.ui

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 播放那一层的列表逻辑：能不能交给内置播放器，以及「已看多少」怎么说。 */
class PlaybackListLogicTest {

    @Test
    fun onlyFinishedPhoneSideArtifactsGoToTheBuiltInPlayer() {
        val path = File(SANDBOX, "clip.mp4").path

        assertTrue(playableInApp(row(RecordStatus.DONE, path), SANDBOX))
        assertTrue("SAF 公开目录里的成品同样能播", playableInApp(row(RecordStatus.DONE, CONTENT), SANDBOX))
        assertFalse(
            "正在下的那一支已经写了目标路径，但文件还是 .part",
            playableInApp(row(RecordStatus.DOWNLOADING, path), SANDBOX),
        )
        assertFalse("产物在电脑上，手机上没有这个文件", playableInApp(row(RecordStatus.DONE, File(OUTSIDE, "clip.mp4").path), SANDBOX))
        assertFalse("没有产物的行没有可播的东西", playableInApp(row(RecordStatus.DONE, null), SANDBOX))
    }

    /** 断点进度只在「看过、而且知道总长」时说话。 */
    @Test
    fun watchedProgressIsReportedOnlyWhenThereIsSomethingToReport() {
        assertEquals(30, watchedPercent(row(RecordStatus.DONE, null, watched = 36_000, total = 120_000)))
        assertEquals(0.30, watchedFraction(row(RecordStatus.DONE, null, watched = 36_000, total = 120_000))!!.toDouble(), 0.001)

        assertNull("归零的续播点说的是「从头播」，不是 0%", watchedPercent(row(RecordStatus.DONE, null, watched = 0, total = 120_000)))
        assertNull("总长还不知道就不猜百分比", watchedPercent(row(RecordStatus.DONE, null, watched = 36_000, total = 0)))
        assertNull("没下完的行不该有观看进度", watchedPercent(row(RecordStatus.DOWNLOADING, null, watched = 36_000, total = 120_000)))
        assertNull("刚起播那半秒不值得写一句「已看」", watchedPercent(row(RecordStatus.DONE, null, watched = 600, total = 120_000)))
    }

    private fun row(
        status: RecordStatus,
        path: String?,
        watched: Long = 0,
        total: Long = 0,
    ) = DownloadRecord(
        id = "a",
        url = "https://example.com/watch",
        status = status,
        outputPath = path,
        createdAt = 1,
        updatedAt = 2,
        lastPlayedPositionMs = watched,
        mediaDurationMs = total,
    )

    private companion object {
        val TMP = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val SANDBOX = File(TMP, "bgv-playback-list-sandbox").path
        val OUTSIDE = File(TMP, "bgv-playback-list-engine").path
        const val CONTENT = "content://tree/document/video%3A1"
    }
}
