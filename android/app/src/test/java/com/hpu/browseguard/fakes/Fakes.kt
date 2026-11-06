package com.hpu.browseguard.fakes

import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.EngineInfo
import com.hpu.browseguard.data.EngineSettings
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.BackendProvider
import com.hpu.browseguard.data.backend.DownloadBackend
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.PrepareRequest
import com.hpu.browseguard.data.backend.SniffBackend
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.cookie.CookieConsentStore
import com.hpu.browseguard.data.cookie.CookieVault
import com.hpu.browseguard.data.history.ArtifactPresence
import com.hpu.browseguard.data.history.DeleteChoice
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.PlaybackResume
import com.hpu.browseguard.data.history.ReconcileReport
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.TaskLookup
import com.hpu.browseguard.data.history.defaultHistoryTitle
import com.hpu.browseguard.data.media.ArtifactSnapshot
import com.hpu.browseguard.data.media.MediaProbe
import com.hpu.browseguard.data.output.ArtifactChange
import com.hpu.browseguard.data.output.ArtifactMover
import com.hpu.browseguard.data.output.MoveTarget
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.Task
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * 测试替身：每个后端把「收到什么」记下来，把「发什么事件」交给测试逐条手动触发。
 *
 * 用 `Channel(UNLIMITED)` 而不是 `MutableSharedFlow`：事件在收集前就能排进队列，
 * 测试可以先触发再 `advanceUntilIdle`，不必和收集协程抢时序。
 */
class FakeSniffBackend : SniffBackend {

    private val channel = Channel<SniffEvent>(Channel.UNLIMITED)
    val requests: MutableList<SniffRequest> = Collections.synchronizedList(mutableListOf())

    override fun sniff(request: SniffRequest): Flow<SniffEvent> = flow {
        requests += request
        for (event in channel) emit(event)
    }

    suspend fun emit(event: SniffEvent) {
        channel.send(event)
    }

    fun close() {
        channel.close()
    }
}

class FakeDownloadBackend : DownloadBackend {

    private val prepareChannel = Channel<DownloadEvent>(Channel.UNLIMITED)
    private val startChannel = Channel<DownloadEvent>(Channel.UNLIMITED)

    val prepareRequests: MutableList<PrepareRequest> = Collections.synchronizedList(mutableListOf())
    val startRequests: MutableList<StartRequest> = Collections.synchronizedList(mutableListOf())
    val cancelRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun prepare(request: PrepareRequest): Flow<DownloadEvent> = flow {
        prepareRequests += request
        for (event in prepareChannel) emit(event)
    }

    override fun start(request: StartRequest): Flow<DownloadEvent> = flow {
        startRequests += request
        for (event in startChannel) emit(event)
    }

    override suspend fun cancel(taskId: String): BackendFailure? {
        cancelRequests += taskId
        return null
    }

    suspend fun emitPrepare(event: DownloadEvent) {
        prepareChannel.send(event)
    }

    suspend fun emitStart(event: DownloadEvent) {
        startChannel.send(event)
    }

    fun close() {
        prepareChannel.close()
        startChannel.close()
    }
}

class FakeBackendProvider(
    val sniffBackend: FakeSniffBackend = FakeSniffBackend(),
    val localSniffBackend: FakeSniffBackend = FakeSniffBackend(),
    val downloadBackend: FakeDownloadBackend = FakeDownloadBackend(),
    override val localSandboxRoot: String? = DEFAULT_SANDBOX,
) : BackendProvider {

    val sniffExecutors: MutableList<SniffExecutor> = Collections.synchronizedList(mutableListOf())
    var lastSniffExecutor: SniffExecutor? = null
        private set
    var lastDownloadExecutor: DownloadExecutor? = null
        private set

    /** 默认未配置 SAF，保持接入前的「留在应用目录」行为；测试按需改成成功/失败。 */
    var publishResult: OutputPublish = OutputPublish.NotConfigured
    val publishRequests: MutableList<File> = Collections.synchronizedList(mutableListOf())

    override suspend fun publishOutput(source: File): OutputPublish {
        publishRequests += source
        return publishResult
    }

    override fun sniff(executor: SniffExecutor): SniffBackend {
        sniffExecutors += executor
        lastSniffExecutor = executor
        return when (executor) {
            SniffExecutor.ENGINE -> sniffBackend
            SniffExecutor.LOCAL_WEBVIEW -> localSniffBackend
        }
    }

    override fun download(executor: DownloadExecutor): DownloadBackend {
        lastDownloadExecutor = executor
        return downloadBackend
    }

    fun close() {
        sniffBackend.close()
        localSniffBackend.close()
        downloadBackend.close()
    }

    companion object {
        const val DEFAULT_SANDBOX = "/phone/Android/data/com.hpu.browseguard/files/Download"
    }
}

class FakeEngineSettings(
    override var baseUrl: String = "http://127.0.0.1:8730",
    override var downloadExecutor: DownloadExecutor = DownloadExecutor.ENGINE,
    override var sniffExecutor: SniffExecutor = SniffExecutor.ENGINE,
    override var showPageByDefault: Boolean = false,
    override var muteWhileSniffing: Boolean = true,
    var acceptSaves: Boolean = true,
) : EngineSettings {

    var savedRaw: String? = null
        private set

    override fun save(raw: String): Boolean {
        if (!acceptSaves) return false
        savedRaw = raw
        baseUrl = raw
        return true
    }
}

/** 连接态完全由测试摆布：置 null 就模拟「引擎离线」，且不发任何真实请求。 */
class FakeEngineInfo(
    var healthResult: HealthResponse? = HealthResponse(
        status = "ok",
        version = "0.1.0",
        protocolVersion = "0.2",
        uptimeSeconds = 12,
    ),
    var selfcheckResult: SelfcheckResponse? = SelfcheckResponse(
        listenAddress = "127.0.0.1:8730",
        tlsEnabled = false,
        authEnabled = false,
        activeProxyIds = emptyList(),
        cookiePolicyDefault = CookiePolicy.LAZY,
        outputDirAllowlist = listOf("/engine/out"),
        cacheDir = "/engine/cache",
        cacheTtlHours = 24,
        cacheSizeBytes = 0,
    ),
) : EngineInfo {

    override suspend fun health(baseUrl: String): HealthResponse =
        healthResult ?: throw EngineException(EngineFailure.Transport("引擎离线（测试）"))

    override suspend fun selfcheck(baseUrl: String): SelfcheckResponse =
        selfcheckResult ?: throw EngineException(EngineFailure.Transport("引擎离线（测试）"))
}

/** 审计流水。下载器的审计发生在 IO 线程，所以用同步列表兜住并发写。 */
class FakeAuditLogger : AuditLogger {

    data class Entry(val event: String, val fields: Map<String, String>)

    private val sink: MutableList<Entry> = Collections.synchronizedList(mutableListOf())

    val events: List<Entry>
        get() = synchronized(sink) { sink.toList() }

    override fun log(event: String, fields: Map<String, String>) {
        sink += Entry(event, fields)
    }

    fun of(event: String): List<Entry> = events.filter { it.event == event }

    fun has(event: String): Boolean = events.any { it.event == event }
}

/**
 * 授权表替身：`grant`/`revoke` 直接改内存集合，[flushUsage] 只计数。
 *
 * 「最近使用时间」在真实现里是内存改、边界写盘；这里用 [markUsedCount] 把调用次数
 * 露出来，让 D3 的节流能被断言，而不是只能靠读代码相信。
 */
class FakeCookieConsentStore(private val now: () -> Long = System::currentTimeMillis) : CookieConsentStore {

    private val granted: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val usage: MutableMap<String, Long> = Collections.synchronizedMap(mutableMapOf())

    var markUsedCount: Int = 0
        private set
    var flushCount: Int = 0
        private set

    override fun isGranted(host: String): Boolean = granted.contains(host)

    override fun grant(host: String) {
        granted += host
        usage[host] = now()
    }

    override fun revoke(host: String) {
        granted -= host
        usage.remove(host)
    }

    override fun grantedHosts(): List<String> = synchronized(granted) { granted.sorted() }

    override fun lastUsedAt(host: String): Long? = usage[host]

    override fun markUsed(host: String) {
        markUsedCount += 1
        if (isGranted(host)) usage[host] = now()
    }

    override fun flushUsage() {
        flushCount += 1
    }
}

/** Cookie 值容器替身：谁读了、读了几次、清了几次都留在记录里。 */
class FakeCookieVault(initial: Map<String, String> = emptyMap()) : CookieVault {

    private val values: MutableMap<String, String> = Collections.synchronizedMap(initial.toMutableMap())

    val readUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var clearCount: Int = 0
        private set
    var flushCount: Int = 0
        private set

    override fun read(url: String): String? {
        readUrls += url
        return values[url]?.takeIf { it.isNotBlank() }
    }

    override fun clearAll() {
        clearCount += 1
        synchronized(values) { values.clear() }
    }

    override fun flush() {
        flushCount += 1
    }
}

/**
 * 搬运工替身：只记「收到什么请求」，结果由测试逐条摆布。
 *
 * 默认全部成功——批量操作那些测试想看的是编排（谁被回填、谁被跳过、权限没抓住时
 * 是不是一个都没动），不是搬运本身；搬运自己的 `(1)` 追加与失败保原文件另有真机验收。
 */
class FakeArtifactMover : ArtifactMover {

    data class RenameCall(val recordId: String, val newBaseName: String)
    data class MoveCall(val recordId: String, val target: MoveTarget)

    val renameCalls: MutableList<RenameCall> = Collections.synchronizedList(mutableListOf())
    val moveCalls: MutableList<MoveCall> = Collections.synchronizedList(mutableListOf())
    val permittedUris: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** null 表示这个文件夹能用；否则原样回给用户的那句拒绝原因。 */
    var permitDenial: String? = null

    /** 改名/移动的结果，按第几次调用取用；用完回落到 [fallback]。 */
    val renameResults: MutableList<ArtifactChange> = Collections.synchronizedList(mutableListOf())
    val moveResults: MutableList<ArtifactChange> = Collections.synchronizedList(mutableListOf())
    var fallback: ArtifactChange = ArtifactChange.Changed("/phone/moved/clip.mp4", "clip.mp4", 2_048L)

    /** 非空时每次搬运都先等它：用来验证「一批还在跑时点第二下，不该开出第二批」。 */
    var holdOn: CompletableDeferred<Unit>? = null

    override suspend fun rename(record: DownloadRecord, newBaseName: String): ArtifactChange {
        renameCalls += RenameCall(record.id, newBaseName)
        holdOn?.await()
        return next(renameResults, renameCalls.size)
    }

    override suspend fun move(record: DownloadRecord, target: MoveTarget): ArtifactChange {
        moveCalls += MoveCall(record.id, target)
        holdOn?.await()
        return next(moveResults, moveCalls.size)
    }

    override fun permitTarget(treeUri: String): String? {
        permittedUris += treeUri
        return permitDenial
    }

    private fun next(results: List<ArtifactChange>, callCount: Int): ArtifactChange =
        results.getOrNull(callCount - 1) ?: fallback
}

/** 产物探测替身：把「界面有没有去问文件系统」记下来，读数由测试摆布。 */
class FakeMediaProbe(
    var snapshot: ArtifactSnapshot? = null,
    var snapshotForId: MutableMap<String, ArtifactSnapshot> = mutableMapOf(),
) : MediaProbe {

    val probed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun probe(record: DownloadRecord): ArtifactSnapshot {
        probed += record.id
        return snapshotForId[record.id]
            ?: snapshot
            ?: ArtifactSnapshot(record = record, presence = ArtifactPresence.PRESENT, fileName = "clip.mp4")
    }
}

/** 内存版历史仓库：让 JVM 单测能看见 HomeViewModel 到底写了什么。 */
class FakeHistoryRepository : HistoryRepository {

    private val state = MutableStateFlow<List<DownloadRecord>>(emptyList())

    override val records: Flow<List<DownloadRecord>> = state

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 界面传来的删除选择，用来验证「公开目录必须二选一」真的走到了仓库。 */
    val deleteChoices: MutableList<Pair<String, DeleteChoice>> =
        Collections.synchronizedList(mutableListOf())

    val rows: List<DownloadRecord>
        get() = state.value

    override suspend fun beginSniff(id: String, url: String) {
        calls += "beginSniff"
        val ts = System.currentTimeMillis()
        state.value = state.value + DownloadRecord(
            id = id,
            url = url,
            title = defaultHistoryTitle(url),
            status = RecordStatus.SNIFFING,
            createdAt = ts,
            updatedAt = ts,
        )
    }

    override suspend fun attachTask(id: String, taskId: String) {
        calls += "attachTask"
        update(id) { it.copy(taskId = taskId) }
    }

    override suspend fun markReady(id: String, format: String?) {
        calls += "markReady"
        update(id) { it.copy(status = RecordStatus.READY, format = format ?: it.format) }
    }

    override suspend fun markDownloading(id: String, taskId: String?, format: String?, outputPath: String?) {
        calls += "markDownloading"
        update(id) {
            it.copy(
                status = RecordStatus.DOWNLOADING,
                taskId = taskId ?: it.taskId,
                format = format ?: it.format,
                outputPath = outputPath ?: it.outputPath,
            )
        }
    }

    override suspend fun markProgress(
        id: String,
        downloadedBytes: Long,
        bytesTotal: Long,
        speedBps: Long,
        note: String?,
    ) {
        calls += "markProgress"
        update(id) {
            it.copy(
                downloadedBytes = downloadedBytes,
                bytesTotal = bytesTotal,
                speedBps = speedBps,
                note = note,
            )
        }
    }

    override suspend fun markDone(
        id: String,
        outputPath: String?,
        format: String?,
        sizeBytes: Long?,
        note: String?,
    ) {
        calls += "markDone"
        update(id) {
            it.copy(
                status = RecordStatus.DONE,
                outputPath = outputPath ?: it.outputPath,
                format = format ?: it.format,
                sizeBytes = sizeBytes ?: it.sizeBytes,
                note = note,
            )
        }
    }

    override suspend fun markFailed(id: String, errorCode: String?, errorMessage: String?) {
        calls += "markFailed"
        // 与 RoomHistoryRepository 同一条契约：已完成的记录不被后来的失败改写。
        if (state.value.any { it.id == id && it.status == RecordStatus.DONE }) return
        update(id) { it.copy(status = RecordStatus.FAILED, errorCode = errorCode, errorMessage = errorMessage) }
    }

    override suspend fun markCancelled(id: String) {
        calls += "markCancelled"
        if (state.value.any { it.id == id && it.status == RecordStatus.DONE }) return
        update(id) { it.copy(status = RecordStatus.CANCELLED) }
    }

    override suspend fun markPlayed(id: String, positionMs: Long, durationMs: Long) {
        calls += "markPlayed"
        val row = state.value.firstOrNull { it.id == id } ?: return
        // 与 RoomHistoryRepository 同一条契约：进度只落在已完成的行上，且写的是归一后的续播点。
        if (row.status != RecordStatus.DONE) return
        val (resume, total) = PlaybackResume.stored(positionMs, durationMs)
        update(id) { it.copy(lastPlayedPositionMs = resume, mediaDurationMs = total) }
    }

    override suspend fun abandon(id: String): Boolean {
        calls += "abandon"
        val row = state.value.firstOrNull { it.id == id } ?: return false
        if (row.status == RecordStatus.DONE) return false
        if (row.outputPath != null) {
            update(id) { it.copy(status = RecordStatus.CANCELLED) }
            return false
        }
        state.value = state.value.filterNot { it.id == id }
        return true
    }

    override suspend fun updateArtifact(id: String, outputPath: String, title: String?, sizeBytes: Long?) {
        calls += "updateArtifact"
        update(id) {
            it.copy(
                outputPath = outputPath,
                title = title ?: it.title,
                sizeBytes = sizeBytes ?: it.sizeBytes,
            )
        }
    }

    override suspend fun delete(id: String, choice: DeleteChoice): Boolean {
        calls += "delete"
        deleteChoices += id to choice
        state.value = state.value.filterNot { it.id == id }
        return false
    }

    override suspend fun deleteAll(): Int {
        calls += "deleteAll"
        val removed = state.value.size
        state.value = emptyList()
        return removed
    }

    override suspend fun reconcile(
        engineReachable: Boolean,
        lookup: suspend (taskId: String) -> TaskLookup,
    ): ReconcileReport = ReconcileReport(scanned = 0, interrupted = 0, recovered = 0)

    private fun update(id: String, transform: (DownloadRecord) -> DownloadRecord) {
        state.value = state.value.map { if (it.id == id) transform(it) else it }
    }
}
