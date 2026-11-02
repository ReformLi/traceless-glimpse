package com.hpu.browseguard.data.history

import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.data.backend.isLocalTaskId
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/**
 * Room 落地的历史仓库。
 *
 * 时间戳只在这里生成，测试可注入固定时钟；路径清理严格限制在 [sandboxRoot] 下，
 * 引擎侧路径（电脑上的文件）永远不会被手机端误删。
 */
class RoomHistoryRepository(
    private val dao: DownloadRecordDao,
    private val sandboxRoot: String?,
    private val now: () -> Long = System::currentTimeMillis,
    /** SAF 文档删除；默认不删，JVM 单测没有 ContentResolver。 */
    private val deleteDocument: (String) -> Boolean = { false },
    private val audit: AuditLogger = NoopAuditLogger,
) : HistoryRepository {

    override val records: Flow<List<DownloadRecord>> = dao.observeAll()

    override suspend fun beginSniff(id: String, url: String) {
        val ts = now()
        dao.upsert(
            DownloadRecord(
                id = id,
                url = url,
                title = defaultHistoryTitle(url),
                status = RecordStatus.SNIFFING,
                createdAt = ts,
                updatedAt = ts,
            ),
        )
    }

    override suspend fun attachTask(id: String, taskId: String) {
        if (taskId.isBlank()) return
        mutate(id) { it.copy(taskId = taskId) }
    }

    override suspend fun markReady(id: String, format: String?) {
        mutate(id) {
            it.copy(
                status = RecordStatus.READY,
                format = format ?: it.format,
                errorCode = null,
            )
        }
    }

    override suspend fun markDownloading(
        id: String,
        taskId: String?,
        format: String?,
        outputPath: String?,
    ) {
        mutate(id) {
            it.copy(
                status = RecordStatus.DOWNLOADING,
                taskId = taskId ?: it.taskId,
                format = format ?: it.format,
                outputPath = outputPath ?: it.outputPath,
                errorCode = null,
            )
        }
    }

    override suspend fun markDone(id: String, outputPath: String?, format: String?) {
        mutate(id) {
            it.copy(
                status = RecordStatus.DONE,
                outputPath = outputPath ?: it.outputPath,
                format = format ?: it.format,
                errorCode = null,
            )
        }
    }

    override suspend fun markFailed(id: String, errorCode: String?) {
        if (keepDone(id, "failed")) return
        mutate(id) { it.copy(status = RecordStatus.FAILED, errorCode = errorCode) }
    }

    override suspend fun markCancelled(id: String) {
        if (keepDone(id, "cancelled")) return
        mutate(id) { it.copy(status = RecordStatus.CANCELLED) }
    }

    /**
     * 已完成是终态：后来的失败或取消不能把它改写成「没成」——产物还在盘上，
     * 历史页却说失败，用户只会以为文件丢了（MuMu 实测：撤销 Cookie 授权后重试被拒，
     * 上一次成功的记录被就地刷成 FAILED，而 `protected.mp4` 仍在 Download 目录里）。
     * 拒绝改写要留一行审计，不做静默降级。
     */
    private suspend fun keepDone(id: String, refused: String): Boolean {
        if (dao.findById(id)?.status != RecordStatus.DONE) return false
        audit.log("history_done_kept", mapOf("id" to id, "refused" to refused))
        return true
    }

    override suspend fun delete(id: String): Boolean {
        val record = dao.findById(id) ?: return false
        // 重试同一链接会复用产物名：别的记录还指着这个文件时，只删行不删文件。
        val shared = record.outputPath?.let { path ->
            dao.findByOutputPath(path).any { it.id != id }
        } ?: false
        val removed = if (shared) false else record.outputPath?.let(::deleteOutput) ?: false
        dao.deleteById(id)
        return removed
    }

    override suspend fun deleteAll(): Int {
        val all = dao.getAll()
        // 全部记录都要走，任何产物都不再存在第二份引用，可以逐个清理。
        all.mapNotNull { it.outputPath }.distinct().forEach(::deleteOutput)
        dao.deleteAll()
        return all.size
    }

    override suspend fun reconcile(
        engineReachable: Boolean,
        lookup: suspend (taskId: String) -> TaskLookup,
    ): ReconcileReport {
        val active = dao.findByStatuses(listOf(RecordStatus.SNIFFING, RecordStatus.DOWNLOADING))
        var interrupted = 0
        var recovered = 0
        var skipped = 0
        val ts = now()
        for (record in active) {
            val remoteId = record.taskId?.takeIf { it.isNotBlank() }?.takeUnless(::isLocalTaskId)
            if (remoteId == null) {
                // 本地句柄/还没拿到 taskId：进程死了就没有任何东西能继续推进，可直接判中断。
                val next = resolveColdStart(record, null, ts) ?: continue
                interrupted++
                dao.upsert(next)
                continue
            }
            if (!engineReachable) {
                // 引擎离线不等于任务消失：原样保留，等下次可达时再对账。
                skipped++
                continue
            }
            when (val verdict = runCatching { lookup(remoteId) }.getOrElse { TaskLookup.Unknown }) {
                is TaskLookup.Found -> {
                    val next = resolveColdStart(record, verdict.task, ts) ?: continue
                    if (next.status == RecordStatus.INTERRUPTED) interrupted++ else recovered++
                    dao.upsert(next)
                }

                TaskLookup.Missing -> {
                    interrupted++
                    dao.upsert(record.copy(status = RecordStatus.INTERRUPTED, updatedAt = ts))
                }

                TaskLookup.Unknown -> skipped++
            }
        }
        return ReconcileReport(
            scanned = active.size,
            interrupted = interrupted,
            recovered = recovered,
            skipped = skipped,
        )
    }

    private suspend fun mutate(id: String, transform: (DownloadRecord) -> DownloadRecord) {
        val current = dao.findById(id) ?: return
        dao.upsert(transform(current).copy(updatedAt = now()))
    }

    private fun deleteOutput(path: String): Boolean =
        if (path.startsWith("content://")) deleteDocument(path) else deleteInsideSandbox(path)

    private fun deleteInsideSandbox(path: String): Boolean {
        val target = insideSandbox(path) ?: return false
        val part = File(target.path + ".part")
        val partDeleted = part.isFile && insideSandbox(part.path) != null && part.delete()
        val fileDeleted = target.isFile && target.delete()
        return fileDeleted || partDeleted
    }

    private fun insideSandbox(path: String): File? {
        val root = sandboxRoot?.let(::File) ?: return null
        return try {
            val rootPath = root.canonicalPath
            val file = File(path).canonicalFile
            when {
                file.path == rootPath -> null
                file.path.startsWith(rootPath + File.separator) -> file
                else -> null
            }
        } catch (e: IOException) {
            null
        }
    }
}

/**
 * 冷启动对账的单条裁决。
 *
 * 进程重启后客户端已经没有轮询协程，因此：
 * - 引擎任务已 `done`：sniff 记录变「待下载」，download 记录变「已完成」并回填产物路径；
 * - 引擎任务明确失败/取消：如实落对应终态；
 * - 引擎明确回答任务不存在（或本地任务）：只能记「已中断」，绝不显示假进度；
 * - 引擎答不上来：不调用本函数，记录原样保留，避免把「离线」写成「中断」。
 */
internal fun resolveColdStart(record: DownloadRecord, task: Task?, now: Long): DownloadRecord? {
    if (record.status != RecordStatus.SNIFFING && record.status != RecordStatus.DOWNLOADING) return null
    if (task == null) return record.copy(status = RecordStatus.INTERRUPTED, updatedAt = now)
    return when (task.status) {
        TaskStatus.DONE -> if (record.status == RecordStatus.SNIFFING) {
            record.copy(status = RecordStatus.READY, errorCode = null, updatedAt = now)
        } else {
            record.copy(
                status = RecordStatus.DONE,
                outputPath = task.outputPath ?: record.outputPath,
                errorCode = null,
                updatedAt = now,
            )
        }

        TaskStatus.CANCELLED -> record.copy(status = RecordStatus.CANCELLED, updatedAt = now)
        TaskStatus.FAILED -> record.copy(
            status = RecordStatus.FAILED,
            errorCode = task.error?.code?.name,
            updatedAt = now,
        )

        else -> record.copy(status = RecordStatus.INTERRUPTED, updatedAt = now)
    }
}
