package com.hpu.browseguard.data.media

import com.hpu.browseguard.data.history.ArtifactPresence
import com.hpu.browseguard.data.history.DownloadRecord

/** 平台探测读出来的媒体事实；每一项都可能读不到，读不到就是 null。 */
data class MediaFacts(
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * 把 retriever 交回的字符串解析成事实。
 *
 * 平台对不认的容器返回 null、空串、甚至 `"0"`：任何一项解析不出来就是 null，
 * 详情面板据此省略那一行。写成 `0:00` 或 `0×0` 比不写更糟——用户会以为
 * 这是一个「一秒的大截图」，而真相只是没能读出来。
 */
fun parseMediaFacts(durationMs: String?, width: String?, height: String?): MediaFacts = MediaFacts(
    durationMs = durationMs?.toLongOrNull()?.takeIf { it > 0L },
    width = width?.toIntOrNull()?.takeIf { it > 0 },
    height = height?.toIntOrNull()?.takeIf { it > 0 },
)

/** 一次产物探测的结论：文件在不在、读到了什么、读不出来是为什么。 */
data class ArtifactSnapshot(
    val record: DownloadRecord,
    val presence: ArtifactPresence,
    val facts: MediaFacts? = null,
    /** 此刻的文件名：SAF 文档的名字由系统给，可能与记录里的标题早就对不上了。 */
    val fileName: String? = null,
    /** 此刻实际的字节数；读不到就留 null，由调用方回落到记录里那个数字。 */
    val sizeBytes: Long? = null,
    /** 给人看的失败原因；null 表示读成功了，或压根没去读（文件不在）。 */
    val unreadableReason: String? = null,
)

/**
 * 产物实时探测。
 *
 * 放在接口后面是为了两件事：详情面板必须报**此刻**的文件属性（改名、移动、
 * 外部删除之后记录里的数字会滞后），而 JVM 单测碰不到 `MediaMetadataRetriever`，
 * 于是真读数在真机验收，判定与解析这一半在单测里钉死。
 */
interface MediaProbe {
    suspend fun probe(record: DownloadRecord): ArtifactSnapshot
}
