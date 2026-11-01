package com.hpu.browseguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DrmSystem
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskProgress
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.theme.BgvTheme
import java.util.Locale

@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onRefresh = viewModel::refreshConnection,
        onEditEngineUrl = viewModel::editEngineUrl,
        onSaveEngineUrl = viewModel::saveEngineUrl,
        onEditSourceUrl = viewModel::editSourceUrl,
        onSniff = viewModel::sniff,
        onPickFormats = viewModel::pickFormats,
        onSelectFormat = viewModel::selectFormat,
        onEditNamingTemplate = viewModel::editNamingTemplate,
        onToggleMerge = viewModel::toggleMerge,
        onStartDownload = viewModel::startDownload,
        onCancelDownload = viewModel::cancelDownload,
        onResetDownload = viewModel::resetDownload,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onEditEngineUrl: (String) -> Unit,
    onSaveEngineUrl: () -> Unit,
    onEditSourceUrl: (String) -> Unit,
    onSniff: () -> Unit,
    onPickFormats: () -> Unit,
    onSelectFormat: (String) -> Unit,
    onEditNamingTemplate: (String) -> Unit,
    onToggleMerge: (Boolean) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onResetDownload: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            EngineStatusCard(state.connection, onRefresh)

            Spacer(Modifier.height(16.dp))
            EngineUrlCard(state, onEditEngineUrl, onSaveEngineUrl)

            val connected = state.connection as? Connection.Connected
            if (connected != null) {
                Spacer(Modifier.height(16.dp))
                SelfcheckCard(connected.selfcheck)
            }

            Spacer(Modifier.height(16.dp))
            SniffCard(state, onEditSourceUrl, onSniff, onPickFormats)

            if (state.download is DownloadPhase.Selecting) {
                Spacer(Modifier.height(16.dp))
                FormatCard(state, onSelectFormat, onEditNamingTemplate, onToggleMerge, onStartDownload)
            }

            if (state.download !is DownloadPhase.Idle && state.download !is DownloadPhase.Preparing &&
                state.download !is DownloadPhase.Selecting
            ) {
                Spacer(Modifier.height(16.dp))
                DownloadCard(state.download, onCancelDownload, onPickFormats)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.home_stage),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BgvCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EngineStatusCard(connection: Connection, onRefresh: () -> Unit) {
    BgvCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(connection.dotColor(), CircleShape),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.engine_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = connection.statusText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRefresh) {
                Text(stringResource(R.string.engine_reconnect))
            }
        }
        if (connection is Connection.Failed) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = connection.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EngineUrlCard(state: HomeUiState, onEdit: (String) -> Unit, onSave: () -> Unit) {
    BgvCard {
        CardTitle(stringResource(R.string.engine_url_label))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.engineUrlDraft,
                onValueChange = onEdit,
                singleLine = true,
                isError = state.engineUrlInvalid,
                label = { Text(stringResource(R.string.engine_url_field)) },
                supportingText = if (state.engineUrlInvalid) {
                    { Text(stringResource(R.string.engine_url_invalid)) }
                } else {
                    null
                },
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onSave) {
                Text(stringResource(R.string.engine_url_save))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.engine_url_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelfcheckCard(selfcheck: SelfcheckResponse) {
    BgvCard {
        CardTitle(stringResource(R.string.selfcheck_title))
        InfoRow(
            stringResource(R.string.selfcheck_cookie),
            selfcheck.cookiePolicyDefault.name.lowercase(Locale.ROOT),
        )
        InfoRow(
            stringResource(R.string.selfcheck_listen),
            selfcheck.listenAddress,
        )
        InfoRow(
            stringResource(R.string.selfcheck_tls),
            boolText(selfcheck.tlsEnabled) + " / " + boolText(selfcheck.authEnabled),
        )
        InfoRow(
            stringResource(R.string.selfcheck_output),
            selfcheck.outputDirAllowlist.ifEmpty { listOf(stringResource(R.string.selfcheck_none)) }.joinToString("\n"),
        )
        InfoRow(
            stringResource(R.string.selfcheck_cache),
            "${selfcheck.cacheDir} · ${selfcheck.cacheTtlHours}h · ${formatBytes(selfcheck.cacheSizeBytes)}",
        )
        InfoRow(
            stringResource(R.string.selfcheck_proxy),
            selfcheck.activeProxyIds.ifEmpty { listOf(stringResource(R.string.selfcheck_none)) }.joinToString(", "),
        )
        InfoRow(
            stringResource(R.string.selfcheck_deps),
            selfcheck.dependencyVersions.entries.joinToString(" · ") { "${it.key} ${it.value}" },
        )
    }
}

@Composable
private fun SniffCard(
    state: HomeUiState,
    onEditSourceUrl: (String) -> Unit,
    onSniff: () -> Unit,
    onPickFormats: () -> Unit,
) {
    val running = state.sniff is SniffPhase.Running
    BgvCard {
        CardTitle(stringResource(R.string.sniff_title))
        OutlinedTextField(
            value = state.sourceUrl,
            onValueChange = onEditSourceUrl,
            singleLine = true,
            enabled = !running,
            label = { Text(stringResource(R.string.sniff_field)) },
            placeholder = { Text(stringResource(R.string.sniff_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onSniff, enabled = state.canSniff) {
                Text(stringResource(R.string.sniff_action))
            }
            when (val phase = state.sniff) {
                is SniffPhase.Running -> {
                    Text(
                        text = stringResource(R.string.sniff_running) + " " + phase.status.name.lowercase(Locale.ROOT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(modifier = Modifier.width(96.dp))
                }
                is SniffPhase.Failed -> Text(
                    text = phase.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
        val failure = state.sniff as? SniffPhase.Failed
        if (failure != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOfNotNull(
                    failure.code,
                    stringResource(
                        if (failure.retryable) R.string.sniff_retryable else R.string.sniff_not_retryable,
                    ),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val connected = state.connection as? Connection.Connected
        if (connected != null && connected.selfcheck.outputDirAllowlist.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sniff_no_allowlist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        val results = (state.sniff as? SniffPhase.Success)?.results.orEmpty()
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.sniff_result_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                results.forEach { SniffResultRow(it) }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onPickFormats, enabled = state.canPickFormats) {
                    Text(stringResource(R.string.format_action))
                }
                val preparing = state.download as? DownloadPhase.Preparing
                if (preparing != null) {
                    Text(
                        text = stringResource(R.string.format_preparing) + " " + preparing.status.name.lowercase(Locale.ROOT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(modifier = Modifier.width(96.dp))
                }
            }
        }
    }
}

@Composable
private fun SniffResultRow(result: SniffResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = result.type.name.lowercase(Locale.ROOT) + " · " + result.estimatedQuality.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (result.drmDetected) R.string.sniff_drm else R.string.sniff_no_drm,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result.drmDetected) MaterialTheme.colorScheme.error else Color(0xFF16A34A),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(result.container)
                    append(" · ")
                    append(result.estimatedQuality.height).append("p")
                    append(" · ")
                    append(result.estimatedQuality.bitrateKbps).append(" kbps")
                    result.drmSystem?.let { append(" · ").append(it.name.lowercase(Locale.ROOT)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = result.mediaUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FormatCard(
    state: HomeUiState,
    onSelectFormat: (String) -> Unit,
    onEditNamingTemplate: (String) -> Unit,
    onToggleMerge: (Boolean) -> Unit,
    onStartDownload: () -> Unit,
) {
    val selecting = state.download as? DownloadPhase.Selecting ?: return
    BgvCard {
        CardTitle(stringResource(R.string.format_title))
        selecting.formats.forEach { format ->
            FormatOptionRow(
                format = format,
                selected = format.formatId == state.selectedFormatId,
                onSelect = { onSelectFormat(format.formatId) },
            )
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = state.namingTemplate,
            onValueChange = onEditNamingTemplate,
            singleLine = true,
            isError = state.namingTemplateError != null,
            label = { Text(stringResource(R.string.format_naming_field)) },
            placeholder = { Text("{title}-{height}p.{ext}") },
            supportingText = {
                Text(
                    text = state.namingTemplateError ?: stringResource(R.string.format_naming_hint),
                    color = if (state.namingTemplateError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleMerge(!state.mergeSelected) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.mergeSelected, onCheckedChange = onToggleMerge)
            Text(text = stringResource(R.string.format_merge_label), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onStartDownload, enabled = state.canStartDownload) {
            Text(stringResource(R.string.format_start))
        }
    }
}

@Composable
private fun FormatOptionRow(format: FormatInfo, selected: Boolean, onSelect: () -> Unit) {
    val kind = FormatSelection.kindOf(format)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = buildString {
                    append(formatKindLabel(format, kind))
                    append(" · ")
                    append(format.container)
                    append(" · ")
                    append(format.formatId)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatSummary(format, kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 首行文案 = 点下去实际会发生什么，取自 [FormatSelection.kindOf]，不再从 `hasVideo` 另推一套。 */
@Composable
private fun formatKindLabel(format: FormatInfo, kind: FormatSelection.Kind): String = when (kind) {
    FormatSelection.Kind.UNKNOWN_METADATA -> stringResource(R.string.format_kind_unknown)
    FormatSelection.Kind.AUDIO_ONLY -> stringResource(R.string.format_audio_only)
    FormatSelection.Kind.VIDEO_ONLY -> stringResource(R.string.format_kind_video_only)
    FormatSelection.Kind.VIDEO_AND_AUDIO -> {
        val codec = FormatSelection.preferCodecLabel(FormatSelection.preferCodecOf(format.codec.video))
        if (codec == null) "${format.resolution.height}p" else "${format.resolution.height}p · $codec"
    }
}

@Composable
private fun DownloadCard(phase: DownloadPhase, onCancel: () -> Unit, onRetry: () -> Unit) {
    BgvCard {
        when (phase) {
            is DownloadPhase.Running -> {
                val task = phase.task
                CardTitle(stringResource(R.string.download_running_title))
                Text(
                    text = task.status.name.lowercase(Locale.ROOT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val percent = ((task.progress?.percent ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat()
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    text = progressSummary(task.progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCancel, enabled = !phase.cancelRequested) {
                    Text(
                        stringResource(
                            if (phase.cancelRequested) R.string.download_cancelling else R.string.download_cancel,
                        ),
                    )
                }
            }
            is DownloadPhase.Success -> {
                CardTitle(stringResource(R.string.download_done_title))
                InfoRow(stringResource(R.string.download_output_dir), phase.task.outputDir)
                phase.task.namingTemplate?.let { InfoRow(stringResource(R.string.download_naming), it) }
                InfoRow(
                    stringResource(R.string.download_size),
                    formatBytes(phase.task.progress?.bytesDownloaded ?: 0L),
                )
            }
            is DownloadPhase.Cancelled -> {
                CardTitle(stringResource(R.string.download_cancelled_title))
                Spacer(Modifier.height(2.dp))
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.format_action)) }
            }
            is DownloadPhase.Failed -> {
                CardTitle(stringResource(R.string.download_failed_title))
                Text(
                    text = phase.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOfNotNull(
                        phase.code,
                        stringResource(if (phase.retryable) R.string.sniff_retryable else R.string.sniff_not_retryable),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.download_retry)) }
            }
            else -> Unit
        }
    }
}

/** 次行只列真实拿到的元数据；元数据未知时不再渲染 `0×0 · 0 kbps · none/none` 这类假精确。 */
@Composable
private fun formatSummary(format: FormatInfo, kind: FormatSelection.Kind): String {
    if (kind == FormatSelection.Kind.UNKNOWN_METADATA) {
        return stringResource(R.string.format_no_metadata)
    }
    val parts = buildList {
        if (format.resolution.height > 0) {
            add("${format.resolution.width}×${format.resolution.height}")
        }
        if (format.bitrateKbps > 0) {
            add("${format.bitrateKbps} kbps")
        }
        if (format.fileSizeEstimateBytes > 0L) {
            add("~${formatBytes(format.fileSizeEstimateBytes)}")
        }
        add("${format.codec.video}/${format.codec.audio}")
        if (kind == FormatSelection.Kind.VIDEO_ONLY) {
            add(stringResource(R.string.format_needs_merge))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun progressSummary(progress: TaskProgress?): String {
    if (progress == null) return stringResource(R.string.download_no_progress)
    return buildString {
        append(formatBytes(progress.bytesDownloaded))
        if (progress.bytesTotal > 0L) append(" / ").append(formatBytes(progress.bytesTotal))
        append(" · ")
        append(formatBytes(progress.speedBps)).append("/s")
        if (progress.etaSeconds > 0) {
            append(" · ")
            append(stringResource(R.string.download_eta, formatDuration(progress.etaSeconds)))
        }
    }
}

private fun formatDuration(seconds: Int): String = when {
    seconds >= 3600 -> "${seconds / 3600}h${"%02d".format(seconds % 3600 / 60)}m"
    seconds >= 60 -> "${seconds / 60}m${"%02d".format(seconds % 60)}s"
    else -> "${seconds}s"
}

private fun Connection.dotColor(): Color = when (this) {
    is Connection.Connected -> Color(0xFF16A34A)
    is Connection.Failed -> Color(0xFFDC2626)
    Connection.Checking -> Color(0xFFD97706)
    Connection.Idle -> Color(0xFF9CA3AF)
}

@Composable
private fun Connection.statusText(): String = when (this) {
    Connection.Idle -> stringResource(R.string.engine_status_idle)
    Connection.Checking -> stringResource(R.string.engine_status_checking)
    is Connection.Connected -> stringResource(R.string.engine_status_connected, health.version, health.protocolVersion)
    is Connection.Failed -> stringResource(R.string.engine_status_failed)
}

@Composable
private fun boolText(value: Boolean): String =
    stringResource(if (value) R.string.selfcheck_on else R.string.selfcheck_off)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1 shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun previewTask(status: TaskStatus, progress: TaskProgress?) = Task(
    id = "0192f3a4",
    sourceUrl = "https://example.com/watch?v=abc",
    mode = TaskMode.DOWNLOAD,
    cookiePolicy = CookiePolicy.LAZY,
    outputDir = "E:\\BGV-output",
    status = status,
    progress = progress,
    namingTemplate = "{title}-{height}p",
    createdAt = "2026-10-31T08:00:00Z",
    updatedAt = "2026-10-31T08:01:20Z",
)

private fun previewState(download: DownloadPhase = DownloadPhase.Idle) = HomeUiState(
    engineUrl = "http://127.0.0.1:8730",
    engineUrlDraft = "http://127.0.0.1:8730",
    connection = Connection.Connected(
        health = HealthResponse("ok", "0.1.0", "0.1", 128),
        selfcheck = SelfcheckResponse(
            listenAddress = "127.0.0.1:8730",
            tlsEnabled = false,
            authEnabled = false,
            activeProxyIds = emptyList(),
            cookiePolicyDefault = CookiePolicy.LAZY,
            outputDirAllowlist = listOf("E:\\BGV-output"),
            cacheDir = "C:\\Users\\me\\AppData\\Local\\BGV\\cache",
            cacheTtlHours = 48,
            cacheSizeBytes = 20_971_520,
            dependencyVersions = mapOf("yt-dlp" to "2026.10.26", "ffmpeg" to "7.1"),
        ),
    ),
    sourceUrl = "https://example.com/watch?v=abc",
    sniff = SniffPhase.Success(
        listOf(
            SniffResult(
                taskId = "0192f3a4",
                mediaUrl = "https://cdn.example.com/hls/master.m3u8",
                type = MediaStreamType.HLS,
                container = "m3u8",
                estimatedQuality = EstimatedQuality(1080, 4500, "1080p"),
                drmDetected = false,
                drmSystem = DrmSystem.UNKNOWN,
                discoveredAt = "2026-10-31T08:00:05Z",
            ),
        ),
    ),
    download = download,
    selectedFormatId = (download as? DownloadPhase.Selecting)?.formats?.firstOrNull()?.formatId,
)

private val previewFormats = listOf(
    FormatInfo(
        formatId = "137+140",
        container = "mp4",
        resolution = Resolution(1920, 1080),
        bitrateKbps = 4500,
        codec = Codec(video = "avc1.640028", audio = "mp4a.40.2"),
        hasAudio = true,
        hasVideo = true,
        fileSizeEstimateBytes = 524_288_000,
        source = FormatSource.YT_DLP,
    ),
    FormatInfo(
        formatId = "251",
        container = "webm",
        resolution = Resolution(0, 0),
        bitrateKbps = 160,
        codec = Codec(video = "none", audio = "opus"),
        hasAudio = true,
        hasVideo = false,
        fileSizeEstimateBytes = 18_874_368,
        source = FormatSource.MANIFEST,
    ),
)

@Preview(showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 2100)
@Composable
private fun HomeScreenSelectingPreview() {
    BgvTheme(darkTheme = false) {
        HomeScreen(
            state = previewState(DownloadPhase.Selecting("0192f3a4", previewFormats)),
            onRefresh = {},
            onEditEngineUrl = {},
            onSaveEngineUrl = {},
            onEditSourceUrl = {},
            onSniff = {},
            onPickFormats = {},
            onSelectFormat = {},
            onEditNamingTemplate = {},
            onToggleMerge = {},
            onStartDownload = {},
            onCancelDownload = {},
            onResetDownload = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 2100)
@Composable
private fun HomeScreenRunningPreview() {
    BgvTheme {
        HomeScreen(
            state = previewState(
                DownloadPhase.Running(
                    previewTask(
                        TaskStatus.DOWNLOADING,
                        TaskProgress("downloading", 104_857_600, 524_288_000, 5_242_880, 80, 20.0),
                    ),
                ),
            ),
            onRefresh = {},
            onEditEngineUrl = {},
            onSaveEngineUrl = {},
            onEditSourceUrl = {},
            onSniff = {},
            onPickFormats = {},
            onSelectFormat = {},
            onEditNamingTemplate = {},
            onToggleMerge = {},
            onStartDownload = {},
            onCancelDownload = {},
            onResetDownload = {},
        )
    }
}
