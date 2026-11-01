package com.hpu.browseguard.shared.model

import kotlinx.serialization.Serializable

/**
 * 响应信封。PROTOCOL §2.2 / §6.3。
 *
 * 引擎的 `sniff-results` 只回 items，`formats` 额外回 taskId，故两者分开建模，
 * 不做统一泛型信封——协议里没有的东西客户端就不假设它有。
 */
@Serializable
data class SniffResultsResponse(
    val items: List<SniffResult>,
)

@Serializable
data class FormatsResponse(
    val taskId: String,
    val items: List<FormatInfo>,
)

/** 失败响应：`4xx/5xx` + `{ "error": ErrorPayload }`。 */
@Serializable
data class ErrorEnvelope(
    val error: ErrorPayload,
)
