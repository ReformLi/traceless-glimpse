package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.EngineFailure

/**
 * `EngineFailure` → `BackendFailure` 的**唯一**转换点。
 *
 * `BackendFailure.Protocol` 在这里、也只在这里被构造：它的输入是引擎响应里的
 * `ErrorPayload`，所以「协议码上屏」这件事必然意味着引擎真的这么答过。
 * 传输层失败不是引擎的诊断，降级成 `Local(TRANSPORT)`（沿用改造前的屏显口径：
 * 有文案、无码、可重试）。
 */
internal fun EngineFailure.toBackendFailure(): BackendFailure = when (this) {
    is EngineFailure.Protocol -> BackendFailure.Protocol(
        code = error.code,
        retryable = error.retryable,
        message = error.message,
        detail = error.detail,
    )

    is EngineFailure.Transport -> LocalFailures.transport(reason)
}
