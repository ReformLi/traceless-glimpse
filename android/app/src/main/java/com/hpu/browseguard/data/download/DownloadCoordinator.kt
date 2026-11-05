package com.hpu.browseguard.data.download

import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.BackendProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.REMUX_PHASE
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.cookie.CookieConsentStore
import com.hpu.browseguard.data.cookie.DenyAllCookieConsent
import com.hpu.browseguard.data.cookie.cookieHostOf
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.RecordNotes
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一条已经交给协调器的下载。
 *
 * 界面在「开始下载」那一刻就把这一包塞进队列，之后它的进度、终态、SAF 复制都与界面活不活着无关；
 * [needsSniffReplay] 记录的是发起那一刻的事实：离屏 WebView 嗅探那一腿，Cookie 还没进容器，
 * 授权之后必须重放一次页面嗅探才可能拿到，直接重下只会再吃一次 403。
 */
data class QueuedTask(
    val recordId: String,
    val executor: DownloadExecutor,
    val request: StartRequest,
    /** 只用于回填记录里的人读画质描述，如 `720p · mp4`。 */
    val formatSummary: String?,
    /** 授权续跑时要挑回哪条画质；只有重放嗅探那一腿用得上。 */
    val formatId: String?,
    val needsSniffReplay: Boolean,
)

/**
 * 差一次 Cookie 授权，不是失败。
 *
 * 协调器把它挂在这里等界面点头：下载页那一行仍是「下载中」，主页弹出原有的授权对话框。
 * [recordId] 是唯一的钥匙——同意哪一条，就只续那一条。
 */
data class ConsentWait(
    val recordId: String,
    val host: String,
    val status: Int,
    val formatId: String?,
    val needsSniffReplay: Boolean,
)

/**
 * 应用级下载队列：`start` 那条流由这里收集，进度与终态只往 [HistoryRepository] 写。
 *
 * 之所以不放在界面 ViewModel：主页瘦身后界面当场复位回到输入状态，
 * 而下载还要跑几十秒到几分钟——进度必须有一个不跟着页面一起消失的落点。
 * 底层下载器一行都不改，这里只是它的另一个订阅者。
 */
class DownloadCoordinator(
    private val backends: BackendProvider,
    private val history: HistoryRepository,
    private val scope: CoroutineScope,
    private val audit: AuditLogger = NoopAuditLogger,
    private val consent: CookieConsentStore = DenyAllCookieConsent,
    private val now: () -> Long = System::currentTimeMillis,
    /** 心跳写库的最小间隔：本机下载器约每 800ms 发一次，逐条写库会把 Room 打满。 */
    private val progressIntervalMs: Long = 1_000L,
) {

    private val queued = ConcurrentHashMap<String, QueuedTask>()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 后端侧 taskId：取消要拿它去点名，界面已经不持有这个事实了。 */
    private val backendTaskIds = ConcurrentHashMap<String, String>()

    private val _consentWaits = MutableStateFlow<List<ConsentWait>>(emptyList())

    /** 待授权的任务；界面取第一条来问，问完由 [resume] / [abandonForSniffReplay] / [deny] 收尾。 */
    val consentWaits: StateFlow<List<ConsentWait>> = _consentWaits.asStateFlow()

    /** 入队即接管。同一个 recordId 再次入队（授权后原地重下）会先掐掉上一条流。 */
    fun enqueue(task: QueuedTask) {
        jobs.remove(task.recordId)?.cancel()
        clearConsent(task.recordId)
        queued[task.recordId] = task
        jobs[task.recordId] = scope.launch { run(task) }
    }

    /**
     * 同意之后原地续跑：Cookie 已经在容器里（浏览器页、引擎嗅探那两腿），
     * 用同一个请求、同一行记录再来一次。
     */
    fun resume(recordId: String) {
        clearConsent(recordId)
        queued[recordId]?.let(::enqueue)
    }

    /**
     * 授权了，但这一腿得先重放一次页面嗅探才拿得到 Cookie：这条记录就此作废。
     *
     * 作废等于**删行**，不是写成「已取消」：界面紧接着会另起一行新的嗅探记录，
     * 旧行留着就是下载管理里一条没有产物、也没有协程在推进的幽灵。
     * 真写了产物路径的行由仓库自己兜住，退成「已取消」而不是消失。
     */
    suspend fun abandonForSniffReplay(recordId: String) {
        clearConsent(recordId)
        stop(recordId)
        history.abandon(recordId)
    }

    /** 用户点了拒绝：不是「什么都没发生」，这一行确实停在了 401/403 上。 */
    suspend fun deny(recordId: String, status: Int) {
        clearConsent(recordId)
        stop(recordId)
        history.markFailed(recordId, null, "站点拒绝了这个直链（HTTP $status），未授权使用页面 Cookie")
    }

    /**
     * 取消一条在跑的任务。
     *
     * 还没拿到后端 taskId（刚入队、或卡在等授权）就直接掐协程；已经开下的要把取消发给后端，
     * 终态仍由那条流写——两处同时改状态就会打架。
     */
    suspend fun cancel(recordId: String) {
        clearConsent(recordId)
        val taskId = backendTaskIds[recordId]
        val task = queued[recordId]
        if (taskId == null || task == null) {
            stop(recordId)
            history.markCancelled(recordId)
            return
        }
        backends.download(task.executor).cancel(taskId)?.let { failure ->
            history.markFailed(recordId, failure.protocolCode(), failure.message)
        }
    }

    /** 进程内还在跑（含等授权）的任务数。 */
    fun activeCount(): Int = jobs.size

    private fun stop(recordId: String) {
        detachStream(recordId)
        queued.remove(recordId)
    }

    /**
     * 只掐这一条订阅，队列里那一包留着。
     *
     * 等待授权的任务用得上：请求、画质、执行者都在 [QueuedTask] 里，
     * 点「同意并继续」要拿同一个请求原地重下，不能在这一步把它一起扔掉。
     */
    private fun detachStream(recordId: String) {
        jobs.remove(recordId)?.cancel()
        backendTaskIds.remove(recordId)
    }

    private fun clearConsent(recordId: String) {
        _consentWaits.update { waits -> waits.filterNot { it.recordId == recordId } }
    }

    /**
     * 一条任务的一生：`start` 流 → 进度心跳 → 终态落库。
     *
     * `Preparing`/`Selecting` 属于画质表那一段，仍由界面收集；这里只可能收到开下以后的事件。
     */
    private suspend fun run(task: QueuedTask) {
        try {
            var lastProgressWrite = 0L
            backends.download(task.executor).start(task.request).collect { event ->
                when (event) {
                    is DownloadEvent.Started -> {
                        backendTaskIds[task.recordId] = event.task.id
                        history.markDownloading(
                            id = task.recordId,
                            taskId = event.task.id,
                            format = task.formatSummary,
                            // 本机直链的产物路径现在就能算出来（`.part` 也在同目录）；
                            // 引擎产物在电脑上，只有 done 时才带落盘路径。
                            outputPath = if (task.executor == DownloadExecutor.LOCAL_DIRECT) {
                                localArtifactPath(event.task)
                            } else {
                                null
                            },
                        )
                    }

                    is DownloadEvent.Progress -> {
                        val ts = now()
                        if (ts - lastProgressWrite < progressIntervalMs) return@collect
                        lastProgressWrite = ts
                        val progress = event.task.progress
                        history.markProgress(
                            task.recordId,
                            downloadedBytes = progress?.bytesDownloaded ?: 0L,
                            bytesTotal = progress?.bytesTotal ?: 0L,
                            speedBps = progress?.speedBps ?: 0L,
                            // 分片下完之后转封装那段没有新事件，这一句是那一行唯一「还在干活」的证据。
                            note = if (progress?.phase == REMUX_PHASE) RecordNotes.REMUXING else null,
                        )
                    }

                    is DownloadEvent.Terminal -> writeTerminal(task, event)

                    is DownloadEvent.Failed -> writeFailure(task, event.failure)

                    is DownloadEvent.Preparing, is DownloadEvent.Selecting -> Unit
                }
            }
        } finally {
            jobs.remove(task.recordId)
        }
    }

    /**
     * 终态落库。
     *
     * 本地产物先过一次可选的 SAF 复制：复制成功记录写 `content://`，失败就把 staging 路径
     * 留在记录里并让审计说话——文件还在盘上，绝不能因为没存进所选文件夹就写成失败。
     *
     * 转封装降级是「下载成功但产物不是 MP4」的事实，只活在那一次事件里；不写进记录，
     * 用户下次打开下载页看到的就是一个干净的完成行，系统播放器打不开 `.ts` 时无从对账。
     */
    private suspend fun writeTerminal(task: QueuedTask, event: DownloadEvent.Terminal) {
        val recordId = task.recordId
        val terminal = event.task
        when (terminal.status) {
            TaskStatus.DONE -> {
                val note = remuxNote(event)
                if (task.executor == DownloadExecutor.LOCAL_DIRECT) {
                    val staging = localArtifactPath(terminal)
                    val publish = if (staging == null) {
                        OutputPublish.Failed("本地产物路径缺失，未保存")
                    } else {
                        backends.publishOutput(File(staging))
                    }
                    history.markDone(
                        id = recordId,
                        outputPath = if (publish is OutputPublish.Published) publish.uri else staging,
                        format = task.formatSummary,
                        sizeBytes = if (publish is OutputPublish.Published) {
                            publish.sizeBytes
                        } else {
                            artifactSize(staging)
                        },
                        note = note,
                    )
                    if (publish is OutputPublish.Failed) {
                        audit.log("audit_publish_failed", mapOf("id" to recordId, "reason" to publish.reason))
                    }
                } else {
                    history.markDone(recordId, terminal.outputPath, task.formatSummary, null, note)
                }
            }

            TaskStatus.CANCELLED -> history.markCancelled(recordId)

            else -> history.markFailed(
                recordId,
                terminal.error?.code?.name,
                terminal.error?.message ?: "任务结束于 ${terminal.status.name.lowercase()}，后端没有给出原因",
            )
        }
        stop(recordId)
    }

    /** 音频降级是通用降级的细分，两者同时为真时只留细分那一句。 */
    private fun remuxNote(event: DownloadEvent.Terminal): String? = when {
        !event.remuxFallback -> null
        event.remuxAudioFallback -> RecordNotes.REMUX_AUDIO_FALLBACK
        else -> RecordNotes.REMUX_FALLBACK
    }

    private suspend fun writeFailure(task: QueuedTask, failure: BackendFailure) {
        consentWaitFor(failure, task)?.let { wait ->
            // 差一次同意，不是失败：历史行原样留着，`.part` 本来也没写过。
            _consentWaits.update { waits -> waits.filterNot { it.recordId == wait.recordId } + wait }
            audit.log("cookie_consent_required", mapOf("host" to wait.host, "status" to wait.status.toString()))
            // 只掐这一条订阅：`queued` 里那一包必须留着，点「同意」后要拿同一个请求重下。
            detachStream(task.recordId)
            return
        }
        history.markFailed(task.recordId, failure.protocolCode(), failure.message)
        stop(task.recordId)
    }

    /**
     * 这一条 401/403 是不是「只是没授权」？三个条件同时成立才算：
     * 下载交给本机、这一站还没授权、站点确实回了需要凭据的两个状态码。
     *
     * 已经授权过的站点不在此列——那时 403 是真的 403，不能再拿弹窗糊过去。
     */
    private fun consentWaitFor(failure: BackendFailure, task: QueuedTask): ConsentWait? {
        if (task.executor != DownloadExecutor.LOCAL_DIRECT) return null
        val request = task.request as? StartRequest.ViaLocal ?: return null
        val local = failure as? BackendFailure.Local ?: return null
        if (local.reason != LocalReason.LINK_UNLOADABLE) return null
        val status = local.status ?: return null
        if (status != HTTP_UNAUTHORIZED && status != HTTP_FORBIDDEN) return null
        val host = cookieHostOf(request.link.mediaUrl)
        if (host.isBlank() || consent.isGranted(host)) return null
        return ConsentWait(task.recordId, host, status, task.formatId, task.needsSniffReplay)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/** §5 的码只在引擎真的这么答时才有；本机失败一律 null，界面上不借码发言。 */
private fun BackendFailure.protocolCode(): String? = (this as? BackendFailure.Protocol)?.code?.name

/**
 * 本机直链的产物路径：`outputDir + namingTemplate`。
 *
 * 本地快照把产物名放在模板槽位里（见 `LocalDirectDownloader` 的 `localSnapshot`），
 * 所以任务一报进度就能算出文件在哪，不用等终态。
 */
private fun localArtifactPath(task: Task): String? {
    val name = task.namingTemplate?.takeIf { it.isNotBlank() } ?: return null
    val dir = task.outputDir.takeIf { it.isNotBlank() } ?: return null
    return File(dir, name).absolutePath
}

/**
 * 产物大小就地 stat 一次。
 *
 * `content://` 在 JVM 与手机上都不能当文件读，引擎侧路径更是根本不在手机上——
 * 这两种情况返回 null，让记录保留上一次的数字，而不是写进去一个 0。
 */
private fun artifactSize(path: String?): Long? {
    if (path == null || path.startsWith("content://")) return null
    return runCatching { File(path).length().takeIf { it > 0L } }.getOrNull()
}
