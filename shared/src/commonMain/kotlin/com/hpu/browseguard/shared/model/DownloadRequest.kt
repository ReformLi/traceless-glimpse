package com.hpu.browseguard.shared.model

import kotlinx.serialization.Serializable

/** `POST /v1/tasks/{id}/download` 请求体。PROTOCOL §6.4。 */
@Serializable
data class DownloadRequest(
    val formatSelector: FormatSelector,
    /**
     * 产物主名（PROTOCOL §3.6）：不是模板，不含后缀，留空 = 用站点标题。
     *
     * 键名沿用 `namingTemplate` 是合同约束——客户端严格解析响应，改名会让旧引擎/旧客户端
     * 之间的响应解析整条失败；语义在 M24 收紧，键名不动。
     */
    val namingTemplate: String? = null,
    val postActions: List<PostAction>? = null,
    /**
     * HLS 分段并发（M25）：默认 3、范围 1~8，越界由引擎回 422。
     *
     * `null` = 省略该键，让引擎按「环境变量 > 默认」回落；`encodeDefaults=false`
     * 保证「不传」与「传 null」在协议上是同一件事。直链不消费这个字段。
     */
    val hlsConcurrency: Int? = null,
)
