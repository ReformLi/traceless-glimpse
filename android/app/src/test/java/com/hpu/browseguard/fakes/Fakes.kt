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
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.ReconcileReport
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.TaskLookup
import com.hpu.browseguard.data.history.defaultHistoryTitle
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.Task
import java.io.File
import java.util.Collections
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

/** 内存版历史仓库：让 JVM 单测能看见 HomeViewModel 到底写了什么。 */
class FakeHistoryRepository : HistoryRepository {

    private val state = MutableStateFlow<List<DownloadRecord>>(emptyList())

    override val records: Flow<List<DownloadRecord>> = state

    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

    override suspend fun markDone(id: String, outputPath: String?, format: String?) {
        calls += "markDone"
        update(id) {
            it.copy(
                status = RecordStatus.DONE,
                outputPath = outputPath ?: it.outputPath,
                format = format ?: it.format,
            )
        }
    }

    override suspend fun markFailed(id: String, errorCode: String?) {
        calls += "markFailed"
        update(id) { it.copy(status = RecordStatus.FAILED, errorCode = errorCode) }
    }

    override suspend fun markCancelled(id: String) {
        calls += "markCancelled"
        update(id) { it.copy(status = RecordStatus.CANCELLED) }
    }

    override suspend fun delete(id: String): Boolean {
        calls += "delete"
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
