package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * 下载执行者的全局选择（设置存在 [com.hpu.browseguard.data.EngineConfig]）。
 *
 * 只切下载这一段：嗅探固定走引擎。
 */
enum class DownloadExecutor {
    /** 引擎 `mode=download` 任务：画质表、后处理、产物都归引擎。 */
    ENGINE,

    /** 本机直链：单条 HTTP GET + `.part` 续传，不跑 yt-dlp，也不跑 FFmpeg。 */
    LOCAL_DIRECT,
}

/**
 * 下载侧后端：画质表 → 开下 → 追进度 → 取消。两个实现各说各的事实——
 * [HttpDownloader] 交给引擎的 `mode=download` 任务，[LocalDirectDownloader] 交给本机 HTTP GET。
 *
 * 与 [SniffBackend] 分开是协议决定的：画质表不是嗅探的产物，而是另一个任务的
 * `awaiting_selection` 阶段输出（PROTOCOL §6.3）。
 */
interface DownloadBackend {

    /** 拿到「可下载的一行表」。引擎等 `awaiting_selection`，本地直接由嗅探结果合成。 */
    fun prepare(request: PrepareRequest): Flow<DownloadEvent>

    /** 选定那一行后真正开下，并把进度追到终态。 */
    fun start(request: StartRequest): Flow<DownloadEvent>

    /**
     * 请求取消。返回 `null` 表示取消已登记——终态仍由 [start] 那条流写出，
     * 两处同时改状态就会打架。失败返回后端失败本身，由界面决定要不要覆盖当前阶段。
     */
    suspend fun cancel(taskId: String): BackendFailure?
}

/** 发起一次画质解析需要的输入。 */
data class PrepareRequest(
    val sourceUrl: String,
    val cookiePolicy: CookiePolicy,
    val outputDir: String,
    /**
     * 嗅探结果表。本地直链实现用它合成「可选的一行」；引擎实现自己去 `GET /formats`，
     * 不读这个字段——画质表在协议里属于另一个任务（§6.3）。
     */
    val sniffResults: List<SniffResult> = emptyList(),
)

/**
 * 「交给引擎下」和「交给本地下」是两种事实，所以是两支类型而不是一个数据类里塞满对方用不到的字段。
 * 这样每个实现只可能收到自己那支，组错请求在编译期就暴露。
 */
sealed interface StartRequest {

    /** 引擎任务：`POST /v1/tasks/{taskId}/download`，进度由轮询得到。 */
    data class ViaEngine(
        val taskId: String,
        val formatSelector: FormatSelector,
        val namingTemplate: String?,
        val postActions: List<PostAction>?,
    ) : StartRequest

    /** 本机直链：单条 HTTP GET + `.part` 续传，不碰 yt-dlp，也不碰 FFmpeg。 */
    data class ViaLocal(val link: SniffResult, val outputDir: String) : StartRequest
}

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
