package com.hpu.browseguard.shared

/**
 * 协议常量单一来源：引擎、Android、CLI、MCP 共用同一份版本号与头名。
 *
 * 对端回包少头或多头都视为协议不符，调用方不可自行降级成「连上了」。
 */
object Protocol {
    const val PROTOCOL_VERSION = "0.2"
    const val PROTOCOL_HEADER = "X-Protocol-Version"
}
