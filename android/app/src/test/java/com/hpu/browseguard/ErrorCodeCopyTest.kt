package com.hpu.browseguard

import com.hpu.browseguard.shared.model.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 错误码文案对照表（收尾③第 2 类）：App 显示的一律是 PROTOCOL §5 原文。
 *
 * [PROTOCOL_MESSAGES] 逐条抄自 `docs/PROTOCOL.md` §5 的「用户可读文案」列，
 * 加上 `docs/PROTOCOL-CHANGELOG.md` M0 裁决的两条（`VALIDATION_ERROR` / `NOT_FOUND`），30 码全覆盖。
 * 任何一侧改措辞这条用例都会红——文案漂移不靠截图发现。
 */
class ErrorCodeCopyTest {

    private val PROTOCOL_MESSAGES = mapOf(
        "INVALID_URL" to "链接格式不正确",
        "UNSUPPORTED_SCHEME" to "仅支持 http/https 链接",
        "INVALID_OUTPUT_DIR" to "输出目录不合法",
        "INVALID_FORMAT_SELECTOR" to "画质选择参数不合法",
        "INVALID_POST_ACTION" to "不支持的后处理动作",
        "SSRF_BLOCKED" to "目标地址指向受限网络，已拒绝",
        "OUTPUT_DIR_REJECTED" to "输出目录不在允许范围内",
        "COOKIE_READ_DENIED" to "凭据读取被拒绝",
        "EXTENSION_NOT_ALLOWED" to "文件类型不在白名单",
        "AUTH_REQUIRED" to "需要有效的访问令牌",
        "UNSUPPORTED_SITE" to "暂不支持该站点",
        "DRM_PROTECTED" to "该内容受 DRM 保护，不支持下载",
        "NO_MEDIA_FOUND" to "未发现可下载的媒体流",
        "FORMAT_UNAVAILABLE" to "所选画质不可用或超出体积上限",
        "EXTRACTOR_FAILED" to "解析失败，可能站点已变更",
        "NETWORK_TIMEOUT" to "网络超时",
        "DNS_FAILURE" to "域名解析失败",
        "TLS_ERROR" to "安全连接失败",
        "NETWORK_HTTP_ERROR" to "目标站点返回错误",
        "RATE_LIMITED" to "请求过于频繁",
        "DISK_FULL" to "磁盘空间不足",
        "WRITE_PERMISSION_DENIED" to "无写入权限",
        "MERGE_FAILED" to "音视频合并失败",
        "TRANSCODE_FAILED" to "转码失败",
        "PARTIAL_DOWNLOAD" to "下载不完整",
        "ENGINE_BUSY" to "引擎繁忙，请稍后重试",
        "TASK_STATE_CONFLICT" to "任务当前状态不允许该操作",
        "UNKNOWN" to "未知错误",
        "VALIDATION_ERROR" to "请求参数不合法",
        "NOT_FOUND" to "请求的资源不存在",
    )

    @Test
    fun allThirtyCodesCarryProtocolMessage() {
        assertEquals(30, ErrorCode.values().size)
        assertEquals(30, PROTOCOL_MESSAGES.size)
        ErrorCode.values().forEach { code ->
            assertEquals(
                "${code.name} 的文案与协议 §5 不符",
                PROTOCOL_MESSAGES.getValue(code.name),
                code.message,
            )
        }
    }

    /** 枚举名即协议 code 值（无 @SerialName），两边按名字对齐才不会漏码。 */
    @Test
    fun codeNamesMatchProtocolTokens() {
        assertEquals(
            ErrorCode.values().map { it.name }.sorted(),
            PROTOCOL_MESSAGES.keys.sorted(),
        )
    }

    /** App 里两处本地兜底以前是自己编的中文，现在必须取码表原文。 */
    @Test
    fun locallySynthesisedFailuresUseProtocolText() {
        assertEquals("未发现可下载的媒体流", ErrorCode.NO_MEDIA_FOUND.message)
        assertEquals("所选画质不可用或超出体积上限", ErrorCode.FORMAT_UNAVAILABLE.message)
        assertEquals(true, ErrorCode.NO_MEDIA_FOUND.retryable)
        assertEquals(false, ErrorCode.FORMAT_UNAVAILABLE.retryable)
    }

    @Test
    fun retryableFollowsProtocolTable() {
        val expected = mapOf(
            ErrorCode.SSRF_BLOCKED to false,
            ErrorCode.DRM_PROTECTED to false,
            ErrorCode.EXTRACTOR_FAILED to true,
            ErrorCode.NETWORK_TIMEOUT to true,
            ErrorCode.DISK_FULL to false,
            ErrorCode.PARTIAL_DOWNLOAD to true,
            ErrorCode.ENGINE_BUSY to true,
            ErrorCode.UNKNOWN to false,
        )
        expected.forEach { (code, retryable) -> assertEquals(retryable, code.retryable) }
    }
}
