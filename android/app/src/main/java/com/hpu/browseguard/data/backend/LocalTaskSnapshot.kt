package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskProgress
import com.hpu.browseguard.shared.model.TaskStatus

/**
 * 本地直链下载的 `Task` 快照。
 *
 * 下载卡片（`HomeScreen.DownloadCard`）看的是协议 `Task` 的形状——状态、进度、输出目录、产物名。
 * 本地链路没有引擎任务，于是这里用**本地真实事实**拼一个同形状的界面镜像：
 * `sourceUrl` 就是正在下的那条直链、`progress` 就是本机字节数与速度、`namingTemplate` 位置放的是
 * 本地产物文件名（界面只把它当「名字」显示）。
 *
 * 三条边界，写死在这里：
 * - `error` 恒为 `null`：本地失败一律走 [BackendFailure.Local]，不借用引擎的 §5 码；
 * - `id` 带 `local_` 前缀，界面上「任务 ID」一眼可辨不是引擎任务，且 [HttpDownloader] 入口
 *   会对这个前缀直接抛错（下游防泄漏，不靠调用方自觉）；
 * - 这个对象**只进 `_state`，绝不上行**——`EngineClient` 的请求体只有
 *   `TaskCreateRequest` 与 `DownloadRequest`，`TaskNeverGoesOnTheWireTest` 钉住这一点。
 */
internal fun localTask(
    link: SniffResult,
    outputDir: String,
    productName: String,
    status: TaskStatus,
    progress: TaskProgress?,
): Task {
    val now = System.currentTimeMillis().toString()
    return Task(
        id = localTaskId(link.mediaUrl),
        sourceUrl = link.mediaUrl,
        mode = TaskMode.DOWNLOAD,
        namingTemplate = productName,
        postActions = emptyList(),
        cookiePolicy = CookiePolicy.NONE,
        outputDir = outputDir,
        status = status,
        progress = progress,
        createdAt = now,
        updatedAt = now,
    )
}

/**
 * 本地任务 id 的命名空间。
 *
 * 下游不是靠约定而是靠这道前缀防「本地句柄泄漏给远端」：[HttpDownloader] 的
 * `start` / `cancel` 入口对带此前缀的 id 直接抛错（`LocalHandleIsolationTest` 钉住）。
 */
internal const val LOCAL_TASK_PREFIX = "local_"

/** 取消要能找回同一条在跑的下载：id 由直链本身决定，不靠调用方传。 */
internal fun localTaskId(mediaUrl: String): String =
    LOCAL_TASK_PREFIX + mediaUrl.hashCode().toUInt().toString(16)

/** 发往引擎之前必须过这一关：本地句柄没有远端对应物。 */
internal fun isLocalTaskId(taskId: String): Boolean = taskId.startsWith(LOCAL_TASK_PREFIX)

/** 进度对象：`phase` 用本地命名，`percent` 由字节数算（拿不到总长时留 0，界面自会显示「暂无进度」口径）。 */
internal fun localProgress(
    bytesDownloaded: Long,
    bytesTotal: Long,
    speedBps: Long,
): TaskProgress = TaskProgress(
    phase = LOCAL_PHASE,
    bytesDownloaded = bytesDownloaded,
    bytesTotal = bytesTotal,
    speedBps = speedBps,
    etaSeconds = if (bytesTotal > 0L && speedBps > 0L) {
        ((bytesTotal - bytesDownloaded) / speedBps).toInt().coerceAtLeast(0)
    } else {
        0
    },
    percent = if (bytesTotal > 0L) bytesDownloaded * 100.0 / bytesTotal else 0.0,
)

/**
 * HLS 的进度按「已落盘段数」算：总字节数在拼接前不可知，百分比却已经确定。
 * `bytesTotal` 留 0，界面据此走不确定进度条，但百分比/段数照样能显示。
 */
internal fun localHlsProgress(
    bytesDownloaded: Long,
    segmentsDone: Int,
    segmentsTotal: Int,
    speedBps: Long,
): TaskProgress = TaskProgress(
    phase = "hls $segmentsDone/$segmentsTotal",
    bytesDownloaded = bytesDownloaded,
    bytesTotal = 0L,
    speedBps = speedBps,
    etaSeconds = 0,
    percent = if (segmentsTotal > 0) segmentsDone * 100.0 / segmentsTotal else 0.0,
)

private const val LOCAL_PHASE = "local_direct"
