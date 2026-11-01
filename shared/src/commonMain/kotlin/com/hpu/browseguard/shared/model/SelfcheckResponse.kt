package com.hpu.browseguard.shared.model

import kotlinx.serialization.Serializable

/**
 * `GET /v1/privacy/selfcheck` 响应。PROTOCOL §6.5。
 *
 * 客户端据此展示隐私姿态，并取 `outputDirAllowlist` 作为任务输出目录来源——
 * 客户端不自行拼路径，路径合法性始终由引擎侧白名单裁决。
 */
@Serializable
data class SelfcheckResponse(
    val listenAddress: String,
    val tlsEnabled: Boolean,
    val authEnabled: Boolean,
    val activeProxyIds: List<String>,
    val cookiePolicyDefault: CookiePolicy,
    val outputDirAllowlist: List<String>,
    val cacheDir: String,
    val cacheTtlHours: Int,
    val cacheSizeBytes: Long,
    val lastCleanupAt: String? = null,
    val dependencyVersions: Map<String, String> = emptyMap(),
)
