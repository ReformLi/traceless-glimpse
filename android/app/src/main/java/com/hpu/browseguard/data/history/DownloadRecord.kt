package com.hpu.browseguard.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** 历史条目状态。`INTERRUPTED` 只由冷启动对账写入，运行期不会主动产生。 */
enum class RecordStatus {
    SNIFFING,

    /** 嗅探完成、但用户还没点下载；不会显示成假进度，点条目可回首页重下。 */
    READY,
    DOWNLOADING,
    DONE,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

/**
 * 一条下载/嗅探历史。
 * [id] 是本地行主键（嗅探阶段还没有引擎 taskId，只能先落一行），[taskId] 在下载真正开始时回填。
 *
 * [downloadedBytes]/[bytesTotal]/[speedBps] 由下载协调器按秒节流写入：
 * 下载 Tab 只 observe 这一张表，进度就不再依赖任何一个界面还活着。
 * [sizeBytes] 是产物落定后的字节数，顶部容量统计只汇总它，不去遍历 SAF 树。
 *
 * [errorCode] 只放引擎写的 §5 码；本机失败没有码，原因落在 [errorMessage]，
 * 下载管理页才有话可说（错误码本身对用户是噪音）。
 *
 * [note] 是这一行**当前那件需要单独说明的事**（键见 [RecordNotes]，界面按键取文案）：
 * 下载中的「分片下完了、正在转封装」和完成时的「产物没转成 MP4」都不算错误，
 * 但没有这一句，用户看到的就是一个卡在 100% 的任务和一个打不开的文件。
 *
 * [lastPlayedPositionMs]/[mediaDurationMs] 是内置播放器留下的**观看事实**：
 * 前者永远是「可以直接从这里接着看」的位置（看完与起播抖动的归一见 [PlaybackResume]），
 * 后者是播放器自己那条总时长，0 表示还不知道。
 * 这两个数只该出现在已完成的行上——没下完的文件没有可看的东西。
 */
@Entity(tableName = "download_records")
data class DownloadRecord(
    @PrimaryKey val id: String,
    val taskId: String? = null,
    val url: String,
    val title: String? = null,
    val format: String? = null,
    val status: RecordStatus,
    val outputPath: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val sizeBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val bytesTotal: Long = 0,
    val speedBps: Long = 0,
    val lastPlayedPositionMs: Long = 0,
    val mediaDurationMs: Long = 0,
)

/**
 * [DownloadRecord.note] 的取值表：库里只存 key，措辞留在 `strings.xml`。
 *
 * 存句子就会出现两份会各自漂移的文案，而且旧记录里那句改不了；存 key 让界面随时能换措辞。
 */
object RecordNotes {
    /** 分片下完了但还在转封装：这一段没有字节心跳，界面需要一个「没卡住」的说法。 */
    const val REMUXING = "remuxing"

    /** HLS 合并出的 `.ts` 没能转成 MP4：文件完好，但系统播放器可能不认这个容器。 */
    const val REMUX_FALLBACK = "remux_fallback"

    /** 同上，且原因是音轨不可用——保留原格式正是为了不把有声内容下成哑巴。 */
    const val REMUX_AUDIO_FALLBACK = "remux_audio_fallback"
}

internal class RecordConverters {
    @TypeConverter
    fun statusToString(value: RecordStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): RecordStatus = RecordStatus.valueOf(value)
}
