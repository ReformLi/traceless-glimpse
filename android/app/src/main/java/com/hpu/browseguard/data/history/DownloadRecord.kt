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
    val createdAt: Long,
    val updatedAt: Long,
)

internal class RecordConverters {
    @TypeConverter
    fun statusToString(value: RecordStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): RecordStatus = RecordStatus.valueOf(value)
}
