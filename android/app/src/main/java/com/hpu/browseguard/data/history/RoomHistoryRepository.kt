package com.hpu.browseguard.data.history

import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.data.backend.isLocalTaskId
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同一页面可以复用哪几种状态的行。
 *
 * 不含 [RecordStatus.DONE]：下过的视频允许再下一份（文件可能被删、也可能想要别的清晰度）。
 * 不含 [RecordStatus.DOWNLOADING]：正在下的那一条由调用方拦截提示，绝不能再插一行。
 * 不含 [RecordStatus.INTERRUPTED]：冷启动对账留下的行通常还攥着半截产物，交给用户自己清理。
 *
 * 另外只知道产物路径的行也不会被复用（过滤在 DAO 的查询里）——复用会清空 `outputPath`，
 * 那条路径上的文件就成了没人认领的孤儿。
 */
private val REUSABLE_STATUSES = listOf(
    RecordStatus.SNIFFING,
    RecordStatus.READY,
    RecordStatus.FAILED,
    RecordStatus.CANCELLED,
)

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

    /**
     * 一行的「读旧值 → 改 → 写回」必须整体串行。
     *
     * 授权续跑会把嗅探腿（`viewModelScope`）与下载腿（应用级作用域）的写入压在同几十毫秒里：
     * 不加锁时嗅探腿拿的是下载开始前的旧快照、却最后才落回，把已经写好的结尾连产物路径
     * 一起抹掉——真机实测文件已在盘上，下载页却永远停在「嗅探中」。
     */
    private val rowGate = Mutex()

    /** 进程启动时刻：比它新的文件属于本次运行，孤儿扫描一律不碰。 */
    private val startedAtMs = now()

    override val records: Flow<List<DownloadRecord>> = dao.observeAll()

    override suspend fun beginSniff(id: String, url: String, title: String?): String = rowGate.withLock {
        val ts = now()
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: defaultHistoryTitle(url)
        val reusable = dao.findReusable(url, REUSABLE_STATUSES)
        if (reusable != null) {
            // 同一页面只留一行：沿用旧 id，把上次那趟留下的进度、错误、产物线索全部清掉，
            // 时间戳刷新成这一趟的（列表按 createdAt 倒序，用户点完就能看见它在最上面）。
            dao.upsert(
                reusable.copy(
                    title = resolvedTitle,
                    status = RecordStatus.SNIFFING,
                    createdAt = ts,
                    updatedAt = ts,
                    taskId = null,
                    format = null,
                    outputPath = null,
                    errorCode = null,
                    errorMessage = null,
                    note = null,
                    sizeBytes = null,
                    downloadedBytes = 0,
                    bytesTotal = 0,
                    speedBps = 0,
                    lastPlayedPositionMs = 0,
                    mediaDurationMs = 0,
                ),
            )
            audit.log(
                "download_reuse",
                mapOf("id" to reusable.id, "from" to reusable.status.name.lowercase()),
            )
            return@withLock reusable.id
        }
        dao.upsert(
            DownloadRecord(
                id = id,
                url = url,
                title = resolvedTitle,
                status = RecordStatus.SNIFFING,
                createdAt = ts,
                updatedAt = ts,
            ),
        )
        id
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
                errorMessage = null,
            )
        }
    }

    override suspend fun markDownloading(
        id: String,
        taskId: String?,
        format: String?,
        outputPath: String?,
    ) {
        rowGate.withLock {
            val current = dao.findById(id) ?: return@withLock
            // 终态不许被迟到的 Started 事件拉回「下载中」：DONE 是原有防线；
            // CANCELLED 是封板审计补上的——取消后若被覆写回 DOWNLOADING，这一行
            // 会永远停在假「下载中」且再也点不动取消（幽灵行）。
            if (current.status == RecordStatus.DONE) {
                audit.log("history_done_kept", mapOf("id" to id, "refused" to "downloading"))
                return@withLock
            }
            if (current.status == RecordStatus.CANCELLED) {
                audit.log(
                    "history_cancelled_kept",
                    mapOf("id" to id, "refused" to "downloading"),
                )
                return@withLock
            }
            dao.upsert(
                current.copy(
                    status = RecordStatus.DOWNLOADING,
                    taskId = taskId ?: current.taskId,
                    format = format ?: current.format,
                    outputPath = outputPath ?: current.outputPath,
                    errorCode = null,
                    errorMessage = null,
                    updatedAt = now(),
                ),
            )
        }
    }

    override suspend fun markDone(id: String, outputPath: String?, format: String?, sizeBytes: Long?, note: String?) {
        mutate(id) {
            it.copy(
                status = RecordStatus.DONE,
                outputPath = outputPath ?: it.outputPath,
                format = format ?: it.format,
                sizeBytes = sizeBytes ?: it.sizeBytes,
                errorCode = null,
                errorMessage = null,
                note = note,
            )
        }
    }

    override suspend fun markProgress(
        id: String,
        downloadedBytes: Long,
        bytesTotal: Long,
        speedBps: Long,
        note: String?,
    ) = rowGate.withLock {
        // 迟到的心跳不该在完成行上留下假进度或「正在转封装」。
        if (dao.findById(id)?.status == RecordStatus.DONE) return@withLock
        dao.updateProgress(id, downloadedBytes, bytesTotal, speedBps, note)
    }

    override suspend fun updateArtifact(id: String, outputPath: String, title: String?, sizeBytes: Long?) {
        dao.updateArtifact(id, outputPath, sizeBytes, title, now())
    }

    override suspend fun markFailed(id: String, errorCode: String?, errorMessage: String?) {
        mutate(id) { it.copy(status = RecordStatus.FAILED, errorCode = errorCode, errorMessage = errorMessage) }
    }

    override suspend fun markCancelled(id: String) {
        mutate(id) { it.copy(status = RecordStatus.CANCELLED) }
    }

    /**
     * 观看进度只认已完成的行：别的状态没有可看的产物，写进去就是给用户一个假续播点。
     * 归一放在这个写入边界做一次，任何调用方都绕不过 [PlaybackResume]。
     */
    override suspend fun markPlayed(id: String, positionMs: Long, durationMs: Long) = rowGate.withLock {
        val current = dao.findById(id) ?: return@withLock
        if (current.status != RecordStatus.DONE) {
            audit.log("playback_progress_refused", mapOf("id" to id, "status" to current.status.name.lowercase()))
            return@withLock
        }
        val (resume, total) = PlaybackResume.stored(positionMs, durationMs)
        dao.updatePlayback(id, resume, total)
    }

    override suspend fun abandon(id: String): Boolean = rowGate.withLock {
        val current = dao.findById(id) ?: return@withLock false
        if (current.status == RecordStatus.DONE) {
            audit.log("history_done_kept", mapOf("id" to id, "refused" to "abandon"))
            return@withLock false
        }
        val path = current.outputPath
        if (path != null) {
            // 这一行已经写过产物落点（沙盒、公开目录或电脑上的引擎产物）：
            // 退成「已取消」而不是消失，用户才知道那个文件是谁留下的。
            dao.upsert(current.copy(status = RecordStatus.CANCELLED, updatedAt = now()))
            audit.log("history_abandon_kept", mapOf("id" to id, "reason" to "artifact_written"))
            return@withLock false
        }
        dao.deleteById(id)
        audit.log("history_abandon_dropped", mapOf("id" to id, "status" to current.status.name.lowercase()))
        true
    }

    /**
     * 删除的落点判断只信 [DeletionPolicy]：公开目录要不要连文件一起删是用户点的头，
     * 不是这里替他决定的。想删却没删成（还有别的记录指着同一份产物、授权已失效）
     * 必须留一行审计——用户以为文件没了、其实还占着空间，比反过来更容易被误会。
     */
    override suspend fun delete(id: String, choice: DeleteChoice): Boolean {
        val record = dao.findById(id) ?: return false
        val path = record.outputPath
        val location = locationOf(path, sandboxRoot)
        val shared = path != null && dao.findByOutputPath(path).any { it.id != id }
        val wantFile = DeletionPolicy.deletesFile(choice, location)
        val removed = wantFile && !shared && path != null && deleteOutput(path)
        dao.deleteById(id)
        if (wantFile && !removed) {
            audit.log(
                "history_file_kept",
                mapOf("id" to id, "reason" to if (shared) "shared_reference" else "file_not_removed"),
            )
        }
        return removed
    }

    override suspend fun deleteAll(): Int {
        val all = dao.getAll()
        // 只有应用专属目录里的产物能被「清空历史」带走；SAF 公开目录一律原样留着。
        all.asSequence()
            .filter { it.outputPath != null && locationOf(it.outputPath, sandboxRoot) == RecordLocation.PRIVATE }
            .mapNotNull { it.outputPath }
            .distinct()
            .forEach(::deleteOutput)
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
        val ghostsRemoved = dropArtifactlessCancellations()
        return ReconcileReport(
            scanned = active.size,
            interrupted = interrupted,
            recovered = recovered,
            skipped = skipped,
            orphansRemoved = sweepOrphans(dao.getAll()),
            ghostsRemoved = ghostsRemoved,
        )
    }

    /**
     * 清掉旧版本留下的「已取消 + 无产物」幽灵行。
     *
     * 现在的 [abandon] 对没有产物的行直接删行，但这条规矩立起来之前，取消动作会把空行
     * 永久留在下载列表里：没有文件、没有信息，用户只会看到一排毫无意义的「已取消」。
     * 放在冷启动对账顺手做，比写一次性迁移稳——用户从老版本升上来第一次打开就干净了。
     */
    private suspend fun dropArtifactlessCancellations(): Int {
        val ghosts = dao.findByStatuses(listOf(RecordStatus.CANCELLED))
            .filter { it.outputPath.isNullOrBlank() }
        ghosts.forEach { record ->
            dao.deleteById(record.id)
            audit.log("history_ghost_removed", mapOf("id" to record.id))
        }
        return ghosts.size
    }

    /**
     * 孤儿文件清扫：应用专属目录里存在、却没有任何记录引用它的产物，直接物理删除。
     *
     * 「下载管理器」得替用户把沙盒管好——串行闸修好之前那几支被抹掉结尾的下载，
     * 文件一直静静占着空间，界面上连一行指它的记录都没有。三条边界：
     * - 只碰沙盒根目录这一层的普通文件，公开目录（SAF）与电脑上的引擎产物一律不动；
     * - 只认下载会写的后缀（含 `.part` 断点），别的文件不是我们生成的，不猜；
     * - 只删本次进程启动之前就躺在那儿的文件：正在下的那一支可能刚写出 `.part`，
     *   而它的行要到 `Started` 事件才回填路径，靠时间戳把它挡在扫描之外。
     */
    private fun sweepOrphans(all: List<DownloadRecord>): Int {
        val root = sandboxRoot?.let(::File) ?: return 0
        val listed = root.listFiles()?.toList() ?: return 0
        val referenced = all.mapNotNull { record -> record.outputPath?.let(::insideSandbox)?.path }.toSet()
        var removed = 0
        listed
            .filter { it.isFile && it.extension.lowercase() in ORPHAN_SUFFIXES }
            .filter { canonical(it) !in referenced }
            .filter { it.lastModified() < startedAtMs }
            .forEach { file ->
                // 体积要在 delete() 之前取：删完再读只会回 0，「清了多少字节」就成了假账。
                val bytes = file.length()
                if (file.delete()) {
                    removed++
                    audit.log("history_orphan_removed", mapOf("file" to file.name, "bytes" to bytes.toString()))
                }
            }
        return removed
    }

    private fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.path)

    private suspend fun mutate(id: String, transform: (DownloadRecord) -> DownloadRecord) =
        rowGate.withLock {
            val current = dao.findById(id) ?: return@withLock
            val next = transform(current).copy(updatedAt = now())
            // 已完成是终态：任何「把这一行退回中间态」的迟到写入都不许落回。
            // 只改 taskId 这类不动状态的回填照常通过，因为它读的是刚取到的行。
            if (current.status == RecordStatus.DONE && next.status != RecordStatus.DONE) {
                audit.log("history_done_kept", mapOf("id" to id, "refused" to next.status.name.lowercase()))
                return@withLock
            }
            dao.upsert(next)
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

    private companion object {
        /** 本机下载只往沙盒里写这些后缀（`.part` 是断点），其余文件不是我们生成的，不猜。 */
        val ORPHAN_SUFFIXES = setOf("mp4", "ts", "mkv", "webm", "mov", "m4v", "mp3", "m4a", "part")
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
            errorMessage = task.error?.message,
            updatedAt = now,
        )

        else -> record.copy(status = RecordStatus.INTERRUPTED, updatedAt = now)
    }
}
