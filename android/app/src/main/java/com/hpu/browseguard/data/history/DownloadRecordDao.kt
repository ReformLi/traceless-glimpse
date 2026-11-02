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

    @Query("SELECT * FROM download_records WHERE outputPath = :path")
    suspend fun findByOutputPath(path: String): List<DownloadRecord>

    @Query("DELETE FROM download_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_records")
    suspend fun deleteAll()
}
