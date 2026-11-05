package com.hpu.browseguard.data.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_records ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadRecord>

    @Query("SELECT * FROM download_records WHERE id = :id")
    suspend fun findById(id: String): DownloadRecord?

    @Upsert
    suspend fun upsert(record: DownloadRecord)

    @Query(
        "UPDATE download_records SET status = :status, errorCode = :errorCode, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateStatus(id: String, status: RecordStatus, errorCode: String?, updatedAt: Long)

    @Query("UPDATE download_records SET taskId = :taskId, status = :status, outputPath = :outputPath, " +
        "updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDownloadStarted(
        id: String,
        taskId: String,
        status: RecordStatus,
        outputPath: String?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM download_records WHERE status IN (:statuses)")
    suspend fun findByStatuses(statuses: List<RecordStatus>): List<DownloadRecord>

    /**
     * 进度只改三个数字和这一行当前那句提示，不碰 `updatedAt`：
     * 一条正在跑的任务不该因为每秒心跳就从「今天 18:02」一路漂到列表最上面。
     */
    @Query(
        "UPDATE download_records SET downloadedBytes = :downloadedBytes, bytesTotal = :bytesTotal, " +
            "speedBps = :speedBps, note = :note WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        bytesTotal: Long,
        speedBps: Long,
        note: String?,
    )

    /**
     * 产物换了名字或位置。
     *
     * `sizeBytes`/`title` 用 COALESCE：移动只换路径（大小与标题都不该被写空），
     * 重命名只换标题；传进来的 null 意思是「这一项保持原样」。
     */
    @Query(
        "UPDATE download_records SET outputPath = :outputPath, " +
            "sizeBytes = COALESCE(:sizeBytes, sizeBytes), title = COALESCE(:title, title), " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateArtifact(
        id: String,
        outputPath: String,
        sizeBytes: Long?,
        title: String?,
        updatedAt: Long,
    )

    @Query("SELECT * FROM download_records WHERE outputPath = :path")
    suspend fun findByOutputPath(path: String): List<DownloadRecord>

    @Query("DELETE FROM download_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_records")
    suspend fun deleteAll()
}
