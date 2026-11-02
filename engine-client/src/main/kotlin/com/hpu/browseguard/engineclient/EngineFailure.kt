package com.hpu.browseguard.engineclient

import com.hpu.browseguard.shared.model.ErrorPayload

/**
 * 引擎调用失败的两类来源。
 *
 * 与 Android 侧 OkHttp 客户端的同名模型语义一致，但这里独立实现：
 * 本轮不触碰 Android 已稳定的网络层。
 */
sealed class EngineFailure {
    /** 引擎按协议返回了 ErrorPayload（PROTOCOL §5）。 */
    data class Protocol(val httpStatus: Int, val error: ErrorPayload) : EngineFailure()

    /** 连不上、超时、协议头不符或响应体不是协议 JSON。 */
    data class Transport(
        val reason: String,
        val kind: TransportKind = TransportKind.OTHER,
    ) : EngineFailure()
}

/** 传输失败的可判性分类。 */
enum class TransportKind {
    /** TCP 拒绝、DNS/连接超时、读超时：电脑这一侧根本不可达。 */
    UNREACHABLE,

    /** 连上了但答的不是协议：版本不符、响应体不是协议 JSON。 */
    OTHER,
}

class EngineException(val failure: EngineFailure) : Exception(failure.toString())
