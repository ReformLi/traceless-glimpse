package com.hpu.browseguard.data.playback

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放来源判定：只看落点，不看文件系统。
 *
 * 与 `fileOperable` 同一条口径再过一遍：沙盒内的成品能播、SAF 里的按 content:// 播、
 * 电脑上（引擎侧）的不能播、还没落地产物的不能播。
 */
class PlaybackSourceTest {

    @Test
    fun sandboxArtifactIsPlayableByFilePath() {
        val path = File(SANDBOX, "clip.mp4").path

        assertEquals(PlaybackSource.PhoneFile(path), playbackSourceOf(record(path), SANDBOX))
    }

    @Test
    fun safArtifactIsPlayableByContentUri() {
        val uri = "content://tree/document/video%3A1"

        assertEquals(PlaybackSource.PhoneUri(uri), playbackSourceOf(record(uri), SANDBOX))
    }

    /** 引擎把产物写在电脑上，那个路径在手机上根本不存在——播放器拿到它只会报一句看不懂的错。 */
    @Test
    fun engineArtifactIsRefusedNotTreatedAsALocalFile() {
        val path = File(OUTSIDE, "clip.mp4").path

        assertEquals(PlaybackSource.Refused(RefuseReason.ON_ENGINE), playbackSourceOf(record(path), SANDBOX))
    }

    @Test
    fun rowWithoutAnArtifactIsRefused() {
        assertEquals(
            PlaybackSource.Refused(RefuseReason.NO_ARTIFACT),
            playbackSourceOf(record(null), SANDBOX),
        )
        assertEquals(
            "空白路径和 null 是同一种「没有」",
            PlaybackSource.Refused(RefuseReason.NO_ARTIFACT),
            playbackSourceOf(record("  "), SANDBOX),
        )
    }

    private fun record(path: String?) = DownloadRecord(
        id = "a",
        url = "https://example.com/watch",
        status = RecordStatus.DONE,
        outputPath = path,
        createdAt = 1,
        updatedAt = 2,
    )

    private companion object {
        val TMP = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val SANDBOX = File(TMP, "bgv-playback-sandbox").path
        val OUTSIDE = File(TMP, "bgv-playback-engine").path
    }
}
