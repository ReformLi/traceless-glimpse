package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.ErrorCode
import kotlinx.serialization.json.JsonObject

/**
 * 失败的二元归属：协议码只在「引擎真的这么答」时出现，本地判断一律没有码。
 *
 * [Protocol] 只允许从引擎响应的 `ErrorPayload` 构造（唯一落点见
 * [toBackendFailure]）；客户端拿 `ErrorCode.X` 手工拼一个，屏上就会出现
 * 引擎审计行里查无此码的失败——§19.3 那三条伪造都是这么来的。
 */
sealed interface BackendFailure {

    /** 引擎按 PROTOCOL §5 回了错误对象，字段原样取自响应。 */
    data class Protocol(
        val code: ErrorCode,
        val retryable: Boolean,
        val message: String,
        val detail: JsonObject?,
    ) : BackendFailure

    /** 客户端本地判定：超时、连不上、引擎回空数组——协议里没有对应的码可言。 */
    data class Local(
        val reason: LocalReason,
        val message: String,
        val retryable: Boolean = true,
    ) : BackendFailure
}

/** 本地失败的种类。新增一条就意味着界面上多一种「引擎没说、是我们自己判的」情况。 */
enum class LocalReason {
    TIMEOUT,

    /** 连不上 / 协议版本不符 / 响应体不是协议 JSON：传输层观察，不是引擎的诊断。 */
    TRANSPORT,
    NO_SNIFF_RESULTS,
    NO_FORMATS,
}
