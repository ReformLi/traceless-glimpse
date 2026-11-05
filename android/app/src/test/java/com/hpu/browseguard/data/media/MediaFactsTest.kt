package com.hpu.browseguard.data.media

import com.hpu.browseguard.data.history.ArtifactPresence
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 平台读数的解析：读不出就是 null，不替用户编一个数字。
 *
 * `MediaMetadataRetriever` 对不认的容器可能抛异常、也可能回 null/空串/`"0"`，
 * 真读数只能在设备上验，这里钉的是「界面能拿到的那一半结论」。
 */
class MediaFactsTest {

    @Test
    fun zeroIsTreatedAsNotReadRatherThanAsARealValue() {
        assertEquals(MediaFacts(durationMs = 45_000L, width = 1_280, height = 720), parseMediaFacts("45000", "1280", "720"))

        val nothing = parseMediaFacts("0", "0", "0")
        assertNull("0 时长会被读成「这是个一秒的截图」", nothing.durationMs)
        assertNull(nothing.width)
        assertNull(nothing.height)

        val garbage = parseMediaFacts(null, "", "  ")
        assertEquals(MediaFacts(), garbage)
        assertEquals(MediaFacts(), parseMediaFacts("abc", "12.5", "-1"))
    }

    /** 只有三个键全空才叫「读不出来」；读到一项就值得展示那一项。 */
    @Test
    fun aPartialReadStillCountsAsARead() {
        val facts = parseMediaFacts(durationMs = "62000", width = null, height = null)
        assertEquals(62_000L, facts.durationMs!!)
        assertNull(facts.width)
    }

    /** 详情面板的口径：文件不在就不去读，`unreadableReason` 只在真去读了却读不出时才有话。 */
    @Test
    fun snapshotCarriesItsOwnRecordAndPresence() {
        val record = DownloadRecord(
            id = "a",
            url = "https://example.com/watch",
            status = RecordStatus.DONE,
            outputPath = "/phone/Download/a.mp4",
            createdAt = 1,
            updatedAt = 1,
        )
        val snapshot = ArtifactSnapshot(
            record = record,
            presence = ArtifactPresence.GONE,
            unreadableReason = "系统没能读出这个容器的属性",
        )

        assertEquals(record, snapshot.record)
        assertEquals(ArtifactPresence.GONE, snapshot.presence)
        assertNull("文件不在时没有读数，也不该有文件名", snapshot.fileName)
        assertNull("记录里那个大小不由探测层伪造", snapshot.sizeBytes)
    }
}
