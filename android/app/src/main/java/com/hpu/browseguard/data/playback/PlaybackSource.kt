package com.hpu.browseguard.data.playback

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordLocation
import com.hpu.browseguard.data.history.locationOf

/**
 * 内置播放器能拿到什么。
 *
 * 判定只看记录里的路径落在哪儿，不 stat 文件系统：清单每行都查一次盘会把滚动拖成 IO；
 * 文件真不在时播放器自己报错，界面再递给用户那句「用其他应用打开」。
 */
sealed interface PlaybackSource {
    /** 播放器播得了的那一种：[readTarget] 同时是给外部应用的地址。 */
    sealed interface Playable : PlaybackSource {
        val readTarget: String
    }

    /** 应用专属目录里的普通文件：绝对路径，播放器按 file:// 读。 */
    data class PhoneFile(override val readTarget: String) : Playable

    /** SAF 公开目录：手机侧没有真实路径，只能按 content:// 读。 */
    data class PhoneUri(override val readTarget: String) : Playable

    /** 这一行播不了，原因要给界面说清楚。 */
    data class Refused(val reason: RefuseReason) : PlaybackSource
}

enum class RefuseReason {
    /** 没有产物：还没下完，或者压根没落地。 */
    NO_ARTIFACT,

    /** 产物在电脑上（引擎侧），手机上没有这个文件。 */
    ON_ENGINE,
}

/** 该用哪种方式播这一行的产物；沙盒外的本地路径一律算引擎侧，播放器不碰。 */
fun playbackSourceOf(record: DownloadRecord, sandboxRoot: String?): PlaybackSource {
    val path = record.outputPath?.takeIf { it.isNotBlank() }
        ?: return PlaybackSource.Refused(RefuseReason.NO_ARTIFACT)
    return when (locationOf(path, sandboxRoot)) {
        RecordLocation.PRIVATE -> PlaybackSource.PhoneFile(path)
        RecordLocation.PUBLIC -> PlaybackSource.PhoneUri(path)
        RecordLocation.ENGINE -> PlaybackSource.Refused(RefuseReason.ON_ENGINE)
        RecordLocation.MISSING -> PlaybackSource.Refused(RefuseReason.NO_ARTIFACT)
    }
}
