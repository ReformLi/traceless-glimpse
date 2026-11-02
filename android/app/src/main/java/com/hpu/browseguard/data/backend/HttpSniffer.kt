package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.EngineException
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/**
 * 远端嗅探：`POST /v1/tasks(mode=sniff)` → 轮询 `GET /v1/tasks/{id}` → `GET /sniff-results`。
 *
 * 改造前这段是 `HomeViewModel.awaitTerminal()`：地址、轮询节奏、超时窗口和「等不到就自己判」
 * 全部写在界面层。搬进来之后界面只消费事件，不再持有 `baseUrl`，也没有资格替引擎发码。
 *
 * 行为与原实现逐点对齐（第 2 步①的验收线就是「界面零变化」）：
 * - 初值 `queued` 由界面在点击那一刻写，这里不补发——原实现也没发过创建响应里的状态；
 * - 只给轮询环套超时，创建请求本身不受窗口管；
 * - 超时只发 [LocalFailures.sniffTimeout]，不发 §5 的 `NETWORK_TIMEOUT`。
 */
class HttpSniffer(
    private val baseUrl: String,
    private val client: EngineClient = EngineClient(),
) : SniffBackend {

    override fun sniff(request: SniffRequest): Flow<SniffEvent> = flow {
        try {
            val created = client.createTask(
                baseUrl,
                TaskCreateRequest(
                    sourceUrl = request.sourceUrl,
                    mode = TaskMode.SNIFF,
                    cookiePolicy = request.cookiePolicy,
                    outputDir = request.outputDir,
                ),
            )
            var current = created
            try {
                withTimeout(LocalFailures.SNIFF_MS) {
                    while (current.status !in TERMINAL_STATUSES) {
                        delay(LocalFailures.POLL_INTERVAL_MS)
                        current = client.task(baseUrl, current.id)
                        emit(SniffEvent.Status(current.id, current.status))
                    }
                }
            } catch (e: TimeoutCancellationException) {
                emit(SniffEvent.Failed(LocalFailures.sniffTimeout()))
                return@flow
            }
            emit(SniffEvent.Terminal(current))
            if (current.status != TaskStatus.DONE) return@flow

            val results = client.sniffResults(baseUrl, current.id)
            emit(
                if (results.isEmpty()) {
                    SniffEvent.Failed(LocalFailures.noSniffResults())
                } else {
                    SniffEvent.Results(results)
                },
            )
        } catch (e: EngineException) {
            emit(SniffEvent.Failed(e.failure.toBackendFailure()))
        }
    }
}
