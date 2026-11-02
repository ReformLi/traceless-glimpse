package com.hpu.browseguard.cli

import com.hpu.browseguard.engineclient.EngineFailure
import com.hpu.browseguard.engineclient.TransportKind
import com.hpu.browseguard.shared.model.ErrorCategory
import com.hpu.browseguard.shared.model.TaskStatus

/** 命令主动结束进程时携带的退出码。 */
class CliExit(val code: Int) : RuntimeException()

/** 参数/输入不合法：runner 统一映射为退出码 2。 */
class CliUsageException(message: String) : RuntimeException(message)

/**
 * 轮询参数。测试注入更短的间隔与超时，生产默认值面向真实下载。
 */
data class CliRuntime(
    val pollIntervalMs: Long = 250,
    val sniffTimeoutMs: Long = 60_000,
    val downloadTimeoutMs: Long = 6 * 60 * 60 * 1000,
)

object ExitCodes {
    const val OK = 0
    const val INPUT = 2
    const val SECURITY = 3
    const val CONTENT = 4
    const val NETWORK = 5
    const val STORAGE = 6
    const val INTERNAL = 7

    /** 用户主动取消（Ctrl+C / 先发了 cancel）：本地语义，不映射任何协议码。 */
    const val USER_CANCELLED = 8
}

fun exitCodeFor(category: ErrorCategory): Int = when (category) {
    ErrorCategory.INPUT -> ExitCodes.INPUT
    ErrorCategory.SECURITY -> ExitCodes.SECURITY
    ErrorCategory.CONTENT -> ExitCodes.CONTENT
    ErrorCategory.NETWORK -> ExitCodes.NETWORK
    ErrorCategory.STORAGE -> ExitCodes.STORAGE
    ErrorCategory.INTERNAL -> ExitCodes.INTERNAL
}

/**
 * 引擎失败 → 退出码。
 *
 * 不可达走本地 5，绝不把「连不上」伪装成协议错误码；
 * 连上了但答的不是协议（版本头不符/响应体损坏）归内部错误 7。
 */
fun exitCodeFor(failure: EngineFailure): Int = when (failure) {
    is EngineFailure.Protocol -> exitCodeFor(failure.error.category)
    is EngineFailure.Transport ->
        if (failure.kind == TransportKind.UNREACHABLE) ExitCodes.NETWORK else ExitCodes.INTERNAL
}

fun TaskStatus.isTerminal(): Boolean =
    this == TaskStatus.DONE || this == TaskStatus.FAILED || this == TaskStatus.CANCELLED
