package com.hpu.browseguard.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 任务模式。PROTOCOL §3.1：sniff 仅嗅探；download 嗅探后下载。 */
@Serializable
enum class TaskMode {
    @SerialName("sniff") SNIFF,
    @SerialName("download") DOWNLOAD,
}

/** 任务状态机全集。PROTOCOL §4：queued → sniffing → (awaiting_selection) → downloading → merging → post_processing → done；任意非终态 → cancelled / failed。 */
@Serializable
enum class TaskStatus {
    @SerialName("queued") QUEUED,
    @SerialName("sniffing") SNIFFING,
    @SerialName("awaiting_selection") AWAITING_SELECTION,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("merging") MERGING,
    @SerialName("post_processing") POST_PROCESSING,
    @SerialName("done") DONE,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED,
}

/** Cookie 策略。PROTOCOL §1：协议层只有枚举，不存在 cookieFile / browser 字段。 */
@Serializable
enum class CookiePolicy {
    @SerialName("none") NONE,
    @SerialName("lazy") LAZY,
    @SerialName("explicit") EXPLICIT,
}

/** 下载进度。PROTOCOL §3.1 progress 对象。 */
@Serializable
data class TaskProgress(
    val phase: String,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val speedBps: Long,
    val etaSeconds: Int,
    val percent: Double,
)

/**
 * 任务。PROTOCOL §3.1，字段与协议完全一致。
 *
 * 注意：不存在 cookieFile / browser / userAgent / headers 字段——协议层安全约束，
 * 请求头由引擎按站点策略生成，客户端不可注入任意头。未知字段在反序列化侧被拒绝（见 BgvJson）。
 */
@Serializable
data class Task(
    val id: String,
    val sourceUrl: String,
    val mode: TaskMode,
    val formatSelector: FormatSelector? = null,
    val namingTemplate: String? = null,
    val postActions: List<PostAction> = emptyList(),
    val proxyId: String? = null,
    val cookiePolicy: CookiePolicy,
    val outputDir: String,
    val status: TaskStatus,
    val progress: TaskProgress? = null,
    val error: ErrorPayload? = null,
    val createdAt: String,
    val updatedAt: String,
)
