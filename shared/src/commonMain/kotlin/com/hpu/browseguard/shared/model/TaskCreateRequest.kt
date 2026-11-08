package com.hpu.browseguard.shared.model

import kotlinx.serialization.Serializable

/**
 * `POST /v1/tasks` 请求体。PROTOCOL §6.2，字段与 engine/models.py:TaskCreateRequest 一致。
 *
 * 同样不存在 cookieFile / browser / userAgent / headers：客户端在协议层无法指定凭据来源或注入请求头。
 */
@Serializable
data class TaskCreateRequest(
    val sourceUrl: String,
    val mode: TaskMode,
    val cookiePolicy: CookiePolicy,
    val outputDir: String,
    val formatSelector: FormatSelector? = null,
    val namingTemplate: String? = null,
    val postActions: List<PostAction> = emptyList(),
)
