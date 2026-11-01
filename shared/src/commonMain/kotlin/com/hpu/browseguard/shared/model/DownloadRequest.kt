package com.hpu.browseguard.shared.model

import kotlinx.serialization.Serializable

/** `POST /v1/tasks/{id}/download` 请求体。PROTOCOL §6.4。 */
@Serializable
data class DownloadRequest(
    val formatSelector: FormatSelector,
    val namingTemplate: String? = null,
    val postActions: List<PostAction>? = null,
)
