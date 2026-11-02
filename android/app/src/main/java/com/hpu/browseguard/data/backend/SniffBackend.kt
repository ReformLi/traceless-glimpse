package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/** PROTOCOL §4：这三条是不可再变终态，轮询环的出口条件归后端所有。 */
internal val TERMINAL_STATUSES = setOf(TaskStatus.DONE, TaskStatus.CANCELLED, TaskStatus.FAILED)

/** 发起一次嗅探需要的全部输入；地址不在这里——后端自己持有 `baseUrl`。 */
data class SniffRequest(
    val sourceUrl: String,
    val cookiePolicy: CookiePolicy,
    val outputDir: String,
)

sealed interface SniffEvent {
    /** 每一轮轮询读到的任务态（含最后一轮的终态），界面上的「任务状态：」就跟着它走。 */
    data class Status(val taskId: String, val status: TaskStatus) : SniffEvent

    /**
     * 任务落到终态。`done` 之后紧跟 [Results]（或空结果表时的 [Failed]）；
     * 其它终态由界面从 `task.error` 解释——那是引擎写的载荷，不是客户端的推测。
     */
    data class Terminal(val task: Task) : SniffEvent

    data class Results(val results: List<SniffResult>) : SniffEvent

    data class Failed(val failure: BackendFailure) : SniffEvent
}

/**
 * 嗅探后端。
 *
 * 只有一个方法是有生产者的：`mode=sniff` 任务的生命周期。画质表不属于这里——
 * 协议里它是另一个 `mode=download` 任务的产物（见 [DownloadBackend]）。
 */
interface SniffBackend {
    fun sniff(request: SniffRequest): Flow<SniffEvent>
}
