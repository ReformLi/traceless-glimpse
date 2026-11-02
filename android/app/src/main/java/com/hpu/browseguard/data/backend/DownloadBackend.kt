package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * 下载侧后端：`mode=download` 任务的「要画质表 → 开下 → 追进度 → 取消」。
 *
 * 与 [SniffBackend] 分开是协议决定的：画质表不是嗅探的产物，而是另一个任务的
 * `awaiting_selection` 阶段输出（PROTOCOL §6.3）。
 */
interface DownloadBackend {

    /** 提交下载任务并发出可选画质表；等不到 `awaiting_selection` 就自己判超时。 */
    fun prepare(request: PrepareRequest): Flow<DownloadEvent>

    /** 选定画质后真正开下，并把进度追到终态。 */
    fun start(request: StartRequest): Flow<DownloadEvent>

    /**
     * 请求取消。返回 `null` 表示引擎已接受——终态仍由 [start] 那条流写出，
     * 两处同时改状态就会打架。失败返回后端失败本身，由界面决定要不要覆盖当前阶段。
     */
    suspend fun cancel(taskId: String): BackendFailure?
}

/** 发起一次画质解析需要的输入。 */
data class PrepareRequest(
    val sourceUrl: String,
    val cookiePolicy: CookiePolicy,
    val outputDir: String,
)

/** 一次下载请求的全部输入；`formatSelector` 已由界面按 §6.3 的映射算好。 */
data class StartRequest(
    val taskId: String,
    val formatSelector: FormatSelector,
    val namingTemplate: String?,
    val postActions: List<PostAction>?,
)

/**
 * 下载流程事件。谁可以覆盖谁写在界面那边，这里只保证「事件说的是什么阶段」。
 */
sealed interface DownloadEvent {

    /** 轮询读到的非 `awaiting_selection`、非终态任务态。 */
    data class Preparing(val taskId: String, val status: TaskStatus) : DownloadEvent

    /** 引擎给出可选格式表（PROTOCOL §6.3）。 */
    data class Selecting(val taskId: String, val formats: List<FormatInfo>) : DownloadEvent

    /** `POST /v1/tasks/{id}/download` 的 `202` 响应：这是下载阶段的第一个事件。 */
    data class Started(val task: Task) : DownloadEvent

    /** 轮询读到的进行中任务；界面只在已经是 Running 时采纳。 */
    data class Progress(val task: Task) : DownloadEvent

    /**
     * 任务落到终态。`done` → 成功、`cancelled` → 已取消，其余由界面从
     * `task.error` 解释——那是引擎写的载荷，客户端不替它编码。
     */
    data class Terminal(val task: Task) : DownloadEvent

    data class Failed(val failure: BackendFailure) : DownloadEvent
}
