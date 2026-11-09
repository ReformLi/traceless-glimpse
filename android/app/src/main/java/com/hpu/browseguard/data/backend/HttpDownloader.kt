package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/**
 * 远端下载：把 `HomeViewModel` 里那两条轮询环（`awaitAwaitingSelection` /
 * `awaitDownloadTerminal`）搬进来，界面不再持有 `baseUrl`、轮询节奏和超时窗口。
 *
 * 行为与原实现逐点对齐（第 2 步的验收线就是「界面零变化」）：
 * - 初值 `queued` 仍由界面在点击那一刻写，这里不补发；
 * - 超时窗口各自只套在自己的轮询环上，`POST /download` 本身不受 `DOWNLOAD_MS` 管；
 * - 取消只发协议请求，终态仍由 [start] 的轮询环写——两条流抢同一状态就会打架。
 */
class HttpDownloader(
    private val baseUrl: String,
    private val client: EngineClient = EngineClient(),
) : DownloadBackend {

    override fun prepare(request: PrepareRequest): Flow<DownloadEvent> = flow {
        try {
            val created = client.createTask(
                baseUrl,
                TaskCreateRequest(
                    sourceUrl = request.sourceUrl,
                    mode = TaskMode.DOWNLOAD,
                    cookiePolicy = request.cookiePolicy,
                    outputDir = request.outputDir,
                ),
            )
            var current = created
            try {
                withTimeout(LocalFailures.PREPARE_MS) {
                    while (current.status != TaskStatus.AWAITING_SELECTION && current.status !in TERMINAL_STATUSES) {
                        delay(LocalFailures.POLL_INTERVAL_MS)
                        current = client.task(baseUrl, current.id)
                        emit(DownloadEvent.Preparing(current.id, current.status))
                    }
                }
            } catch (e: TimeoutCancellationException) {
                emit(DownloadEvent.Failed(LocalFailures.prepareTimeout()))
                return@flow
            }
            if (current.status != TaskStatus.AWAITING_SELECTION) {
                emit(DownloadEvent.Terminal(current))
                return@flow
            }

            val formats = client.formats(baseUrl, current.id)
            emit(
                if (formats.isEmpty()) {
                    DownloadEvent.Failed(LocalFailures.noFormats())
                } else {
                    DownloadEvent.Selecting(current.id, formats)
                },
            )
        } catch (e: EngineException) {
            emit(DownloadEvent.Failed(e.failure.toBackendFailure()))
        }
    }

    override fun start(request: StartRequest): Flow<DownloadEvent> = flow {
        val engine = when (request) {
            is StartRequest.ViaEngine -> request
            is StartRequest.ViaLocal ->
                error("HttpDownloader 收到本地直链请求：调用方组错了 StartRequest")
        }
        engine.taskId.requireRemoteTaskId()
        try {
            val started = client.startDownload(
                baseUrl,
                engine.taskId,
                DownloadRequest(
                    formatSelector = engine.formatSelector,
                    namingTemplate = engine.namingTemplate,
                    postActions = engine.postActions,
                    hlsConcurrency = engine.hlsConcurrency,
                ),
            )
            emit(DownloadEvent.Started(started))
            var current = started
            try {
                withTimeout(LocalFailures.DOWNLOAD_MS) {
                    while (current.status !in TERMINAL_STATUSES) {
                        delay(LocalFailures.DOWNLOAD_POLL_INTERVAL_MS)
                        current = client.task(baseUrl, current.id)
                        emit(DownloadEvent.Progress(current))
                    }
                }
            } catch (e: TimeoutCancellationException) {
                emit(DownloadEvent.Failed(LocalFailures.downloadTimeout()))
                return@flow
            }
            emit(DownloadEvent.Terminal(current))
        } catch (e: EngineException) {
            emit(DownloadEvent.Failed(e.failure.toBackendFailure()))
        }
    }

    override suspend fun cancel(taskId: String): BackendFailure? {
        taskId.requireRemoteTaskId()
        return try {
            client.cancelTask(baseUrl, taskId)
            null
        } catch (e: EngineException) {
            e.failure.toBackendFailure()
        }
    }

    /**
     * 本地句柄不许发给引擎：`local_` 前缀的任务在远端根本没有对象，
     * 放过去只会换来一个 `404 TASK_NOT_FOUND`，却把「客户端拿错了句柄」伪装成「引擎找不到任务」。
     */
    private fun String.requireRemoteTaskId() {
        require(!isLocalTaskId(this)) { "本地任务句柄不得发往引擎：$this（HTTP 入口守卫）" }
    }
}
