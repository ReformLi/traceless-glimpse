package com.hpu.browseguard.data.keepalive

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordNotes
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.defaultHistoryTitle

/**
 * 通知栏那一格要说的事实：全是数字，措辞留在 `strings.xml`。
 *
 * 单独一个文件是因为它读的是历史记录里那一秒一跳的心跳——那是**内容**的来源；
 * 而服务挂不挂的判据只看队列（见 [KeepAlivePolicy]），两者混在一处就会拿滞后的状态决定生死。
 */
internal data class KeepAliveSnapshot(
    /** 队列里还有几条流；等授权那一档也算，此刻它没有字节在动。 */
    val active: Int,
    /** 0..99；总量未知、或这一秒还没有一条真的在下，一律 null——不给通知栏一个假的百分比。 */
    val percent: Int?,
    /** 当前这一条的人读名；队列在跑但还没有一行进入「下载中」时为 null。 */
    val current: String?,
    /** 分片下完、正在转封装：那一段没有新字节，百分比会一路停住。 */
    val remuxing: Boolean,
)

internal object KeepAliveSnapshotOf {

    fun of(active: Int, records: List<DownloadRecord>): KeepAliveSnapshot {
        val running = records.filter { it.status == RecordStatus.DOWNLOADING }
        return KeepAliveSnapshot(
            active = active,
            percent = percentOf(running),
            // 最早开下的那一条：并发排队时它才是「此刻正在动」的那个名字。
            current = running.minByOrNull { it.createdAt }?.let { it.title ?: defaultHistoryTitle(it.url) },
            remuxing = running.any { it.note == RecordNotes.REMUXING },
        )
    }

    /**
     * 多条并发时按总字节合起来算，而不是把几个百分比平均。
     *
     * 封顶 99：某一秒确实可能已经收满最后一个字节但终态那一笔还没落库，
     * 这时候通知栏写 100% 就是在宣布一件还没发生的事。
     */
    private fun percentOf(running: List<DownloadRecord>): Int? {
        if (running.isEmpty()) return null
        val total = running.sumOf { it.bytesTotal }
        if (total <= 0L) return null
        val done = running.sumOf { it.downloadedBytes }
        return ((done * 100) / total).toInt().coerceIn(0, 99)
    }
}
