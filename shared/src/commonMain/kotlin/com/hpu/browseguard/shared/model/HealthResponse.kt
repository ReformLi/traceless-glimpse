package com.hpu.browseguard.shared.model

import kotlinx.serialization.Serializable

/** `GET /v1/health` 响应。PROTOCOL §6.6。 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val protocolVersion: String,
    val uptimeSeconds: Long,
)
