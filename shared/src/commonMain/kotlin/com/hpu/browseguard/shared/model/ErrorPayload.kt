package com.hpu.browseguard.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 错误类别。PROTOCOL §3.5。 */
@Serializable
enum class ErrorCategory {
    @SerialName("input") INPUT,
    @SerialName("network") NETWORK,
    @SerialName("security") SECURITY,
    @SerialName("content") CONTENT,
    @SerialName("storage") STORAGE,
    @SerialName("internal") INTERNAL,
}

/**
 * 错误码全集：PROTOCOL §5 全部 28 个 + 裁决新增 VALIDATION_ERROR（共 29 个）。
 *
 * 所有 code 值均为大写蛇形，与 Kotlin 常量名完全一致，因此无需 @SerialName。
 * 序列化按常量名输出，即协议 code 值。
 *
 * VALIDATION_ERROR 仅用于请求体校验失败（含未知字段），PROTOCOL v0.1 文本未含，
 * M0 结束后另记 changelog（已裁决）。
 */
@Serializable
enum class ErrorCode(
    val category: ErrorCategory,
    val retryable: Boolean,
    val message: String,
) {
    INVALID_URL(ErrorCategory.INPUT, false, "链接格式不正确"),
    UNSUPPORTED_SCHEME(ErrorCategory.INPUT, false, "仅支持 http/https 链接"),
    INVALID_OUTPUT_DIR(ErrorCategory.INPUT, false, "输出目录不合法"),
    INVALID_FORMAT_SELECTOR(ErrorCategory.INPUT, false, "画质选择参数不合法"),
    INVALID_POST_ACTION(ErrorCategory.INPUT, false, "不支持的后处理动作"),
    SSRF_BLOCKED(ErrorCategory.SECURITY, false, "目标地址指向受限网络，已拒绝"),
    OUTPUT_DIR_REJECTED(ErrorCategory.SECURITY, false, "输出目录不在允许范围内"),
    COOKIE_READ_DENIED(ErrorCategory.SECURITY, false, "凭据读取被拒绝"),
    EXTENSION_NOT_ALLOWED(ErrorCategory.SECURITY, false, "文件类型不在白名单"),
    AUTH_REQUIRED(ErrorCategory.SECURITY, false, "需要有效的访问令牌"),
    UNSUPPORTED_SITE(ErrorCategory.CONTENT, false, "暂不支持该站点"),
    DRM_PROTECTED(ErrorCategory.CONTENT, false, "该内容受 DRM 保护，不支持下载"),
    NO_MEDIA_FOUND(ErrorCategory.CONTENT, true, "未发现可下载的媒体流"),
    FORMAT_UNAVAILABLE(ErrorCategory.CONTENT, false, "所选画质不可用或超出体积上限"),
    EXTRACTOR_FAILED(ErrorCategory.CONTENT, true, "解析失败，可能站点已变更"),
    NETWORK_TIMEOUT(ErrorCategory.NETWORK, true, "网络超时"),
    DNS_FAILURE(ErrorCategory.NETWORK, true, "域名解析失败"),
    TLS_ERROR(ErrorCategory.NETWORK, false, "安全连接失败"),
    NETWORK_HTTP_ERROR(ErrorCategory.NETWORK, true, "目标站点返回错误"),
    RATE_LIMITED(ErrorCategory.NETWORK, true, "请求过于频繁"),
    DISK_FULL(ErrorCategory.STORAGE, false, "磁盘空间不足"),
    WRITE_PERMISSION_DENIED(ErrorCategory.STORAGE, false, "无写入权限"),
    MERGE_FAILED(ErrorCategory.INTERNAL, false, "音视频合并失败"),
    TRANSCODE_FAILED(ErrorCategory.INTERNAL, false, "转码失败"),
    PARTIAL_DOWNLOAD(ErrorCategory.INTERNAL, true, "下载不完整"),
    ENGINE_BUSY(ErrorCategory.INTERNAL, true, "引擎繁忙，请稍后重试"),
    TASK_STATE_CONFLICT(ErrorCategory.INTERNAL, false, "任务当前状态不允许该操作"),
    UNKNOWN(ErrorCategory.INTERNAL, false, "未知错误"),
    VALIDATION_ERROR(ErrorCategory.INPUT, false, "请求参数不合法"),
    NOT_FOUND(ErrorCategory.INPUT, false, "请求的资源不存在"),
}

/**
 * 错误载荷。PROTOCOL §3.5。
 *
 * detail 必须已脱敏：不含 Cookie 值、token、完整敏感查询串。
 */
@Serializable
data class ErrorPayload(
    val code: ErrorCode,
    val category: ErrorCategory,
    val message: String,
    val retryable: Boolean,
    val detail: JsonObject? = null,
) {
    companion object {
        /** 按错误码构造，category / message / retryable 自动取自 [ErrorCode] 属性。 */
        fun of(code: ErrorCode, detail: JsonObject? = null): ErrorPayload = ErrorPayload(
            code = code,
            category = code.category,
            message = code.message,
            retryable = code.retryable,
            detail = detail,
        )
    }
}
