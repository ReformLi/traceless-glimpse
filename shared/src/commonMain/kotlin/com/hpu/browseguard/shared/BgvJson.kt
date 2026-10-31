package com.hpu.browseguard.shared

import kotlinx.serialization.json.Json

/**
 * shared 内唯一的 Json 配置对象，所有协议模型序列化/反序列化必须走它。
 *
 * - [ignoreUnknownKeys] = false：未知字段一律拒绝（协议层安全约束，PROTOCOL §1 / §11.2 裁决为严格拒绝）。
 *   Task 不存在 cookieFile / browser / userAgent / headers 字段，传入即抛 [kotlinx.serialization.SerializationException]。
 * - [encodeDefaults] = false：等于默认值的字段不输出。
 * - explicitNulls 保持默认 true：可空字段显式输出 null（与协议示例 `"proxyId": null` 一致）。
 */
val BgvJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = false
}
