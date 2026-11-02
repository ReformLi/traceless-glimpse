package com.hpu.browseguard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.output.OutputAdoptResult
import com.hpu.browseguard.data.output.OutputLocation
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.ui.components.ConfirmDialog
import com.hpu.browseguard.ui.theme.BgvTheme

@Composable
fun SettingsRoute(container: AppContainer, homeViewModel: HomeViewModel) {
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    // 与历史页共用同一个 Activity 级 ViewModel：计数和清空都走同一条 Room Flow。
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(container.history),
    )
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    val clearing by historyViewModel.clearing.collectAsStateWithLifecycle()
    val outputLocation by container.outputs.location.collectAsStateWithLifecycle()
    var outputError by remember { mutableStateOf<String?>(null) }

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            outputError = when (val result = container.outputs.adopt(uri)) {
                is OutputAdoptResult.Adopted -> null
                is OutputAdoptResult.Rejected -> result.reason
            }
        }
    }

    SettingsScreen(
        state = homeState,
        history = historyState,
        clearingHistory = clearing,
        outputLocation = outputLocation,
        outputError = outputError,
        onEditEngineUrl = homeViewModel::editEngineUrl,
        onSaveEngineUrl = homeViewModel::saveEngineUrl,
        onRefreshConnection = homeViewModel::refreshConnection,
        onSelectSniffExecutor = homeViewModel::selectSniffExecutor,
        onSelectDownloadExecutor = homeViewModel::selectDownloadExecutor,
        onPickOutputDirectory = {
            outputError = null
            directoryPicker.launch(null)
        },
        onResetOutputDirectory = {
            outputError = null
            container.outputs.forget()
        },
        onClearHistory = historyViewModel::clearAll,
    )
}

/**
 * 设置页第一屏：引擎地址与连通性、执行方式、历史维护。
 *
 * 状态全部复用 [HomeViewModel]——首页的快捷切换与这里必须读写同一份偏好，
 * 否则两个 Tab 会各自显示一套「当前执行者」。输出目录在 SAF 接入后补进本页。
 */
@Composable
fun SettingsScreen(
    state: HomeUiState,
    history: HistoryUiState,
    clearingHistory: Boolean,
    outputLocation: OutputLocation,
    outputError: String?,
    onEditEngineUrl: (String) -> Unit,
    onSaveEngineUrl: () -> Unit,
    onRefreshConnection: () -> Unit,
    onSelectSniffExecutor: (SniffExecutor) -> Unit,
    onSelectDownloadExecutor: (DownloadExecutor) -> Unit,
    onPickOutputDirectory: () -> Unit,
    onResetOutputDirectory: () -> Unit,
    onClearHistory: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        EngineSection(
            state = state,
            onEditEngineUrl = onEditEngineUrl,
            onSaveEngineUrl = onSaveEngineUrl,
            onRefreshConnection = onRefreshConnection,
        )

        Spacer(Modifier.height(16.dp))
        ExecutionSection(
            state = state,
            onSelectSniffExecutor = onSelectSniffExecutor,
            onSelectDownloadExecutor = onSelectDownloadExecutor,
        )

        Spacer(Modifier.height(16.dp))
        OutputSection(
            location = outputLocation,
            sandboxRoot = state.localSandboxRoot,
            error = outputError,
            onPick = onPickOutputDirectory,
            onReset = onResetOutputDirectory,
        )

        Spacer(Modifier.height(16.dp))
        HistorySection(
            history = history,
            clearing = clearingHistory,
            onRequestClear = { confirmingClear = true },
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.home_stage),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmingClear) {
        ConfirmDialog(
            title = stringResource(R.string.settings_history_clear_title),
            message = stringResource(R.string.settings_history_clear_body),
            confirmLabel = stringResource(R.string.settings_history_clear_confirm),
            dismissLabel = stringResource(R.string.settings_history_clear_cancel),
            onConfirm = {
                confirmingClear = false
                onClearHistory()
            },
            onDismiss = { confirmingClear = false },
            destructive = true,
        )
    }
}

@Composable
private fun EngineSection(
    state: HomeUiState,
    onEditEngineUrl: (String) -> Unit,
    onSaveEngineUrl: () -> Unit,
    onRefreshConnection: () -> Unit,
) {
    val connection = state.connection
    SettingsCard(stringResource(R.string.settings_engine_section)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(connection.dotColor(), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = connection.statusText(),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (connection is Connection.Connected) {
                TextButton(onClick = onRefreshConnection) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_engine_test))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.engineUrlDraft,
            onValueChange = onEditEngineUrl,
            singleLine = true,
            isError = state.engineUrlInvalid,
            label = { Text(stringResource(R.string.engine_url_field)) },
            supportingText = if (state.engineUrlInvalid) {
                { Text(stringResource(R.string.engine_url_invalid)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        FilledTonalButton(
            onClick = onSaveEngineUrl,
            enabled = connection !is Connection.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_engine_save_test))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.engine_url_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (connection is Connection.Failed) {
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_engine_failed_reason, connection.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_engine_offline_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (connection is Connection.Connected) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.selfcheck_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow(
                stringResource(R.string.selfcheck_cookie),
                connection.selfcheck.cookiePolicyDefault.cookieText(),
            )
            InfoRow(
                stringResource(R.string.selfcheck_output),
                connection.selfcheck.outputDirAllowlist
                    .ifEmpty { listOf(stringResource(R.string.selfcheck_none)) }
                    .joinToString("\n"),
            )
            InfoRow(
                stringResource(R.string.selfcheck_cache),
                "${connection.selfcheck.cacheDir} · ${formatBytes(connection.selfcheck.cacheSizeBytes)}",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExecutionSection(
    state: HomeUiState,
    onSelectSniffExecutor: (SniffExecutor) -> Unit,
    onSelectDownloadExecutor: (DownloadExecutor) -> Unit,
) {
    val sniffRunning = state.sniff is SniffPhase.Running
    val sniffLocked = sniffRunning || state.downloadInFlight
    val downloadLocked = state.downloadInFlight

    SettingsCard(stringResource(R.string.settings_execution_section)) {
        Text(
            text = stringResource(R.string.sniff_mode_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.sniffExecutor == SniffExecutor.ENGINE,
                onClick = { onSelectSniffExecutor(SniffExecutor.ENGINE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !sniffLocked,
                label = { Text(stringResource(R.string.sniff_mode_engine)) },
            )
            SegmentedButton(
                selected = state.sniffExecutor == SniffExecutor.LOCAL_WEBVIEW,
                onClick = { onSelectSniffExecutor(SniffExecutor.LOCAL_WEBVIEW) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !sniffLocked,
                label = { Text(stringResource(R.string.sniff_mode_local)) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.sniff_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.download_executor_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.downloadExecutor == DownloadExecutor.ENGINE,
                onClick = { onSelectDownloadExecutor(DownloadExecutor.ENGINE) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !downloadLocked,
                label = { Text(stringResource(R.string.download_executor_engine)) },
            )
            SegmentedButton(
                selected = state.downloadExecutor == DownloadExecutor.LOCAL_DIRECT,
                onClick = { onSelectDownloadExecutor(DownloadExecutor.LOCAL_DIRECT) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !downloadLocked,
                label = { Text(stringResource(R.string.download_executor_local)) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (downloadLocked) {
                    R.string.settings_execution_busy
                } else {
                    R.string.download_executor_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (downloadLocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun OutputSection(
    location: OutputLocation,
    sandboxRoot: String?,
    error: String?,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_output_section)) {
        Text(
            text = if (location.configured) {
                stringResource(
                    R.string.settings_output_saf,
                    location.displayName ?: stringResource(R.string.settings_output_default),
                )
            } else {
                stringResource(R.string.settings_output_default)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (location.configured) {
                stringResource(R.string.settings_output_copy_hint)
            } else {
                sandboxRoot ?: stringResource(R.string.settings_output_missing)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onPick, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (location.configured) {
                            R.string.settings_output_change
                        } else {
                            R.string.settings_output_pick
                        },
                    ),
                )
            }
            if (location.configured) {
                OutlinedButton(onClick = onReset) {
                    Text(stringResource(R.string.settings_output_reset))
                }
            }
        }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_output_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_output_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistorySection(
    history: HistoryUiState,
    clearing: Boolean,
    onRequestClear: () -> Unit,
) {
    val records = history.groups.flatMap { it.records }
    val done = records.count { it.status == RecordStatus.DONE }
    SettingsCard(stringResource(R.string.settings_history_section)) {
        Text(
            text = when {
                history.loading -> stringResource(R.string.settings_history_loading)
                records.isEmpty() -> stringResource(R.string.settings_history_empty)
                else -> stringResource(R.string.settings_history_count, records.size, done)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onRequestClear,
            enabled = records.isNotEmpty() && !clearing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (clearing) {
                    stringResource(R.string.settings_history_clearing)
                } else {
                    stringResource(R.string.settings_history_clear)
                },
                color = if (records.isNotEmpty() && !clearing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_history_clear_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Connection.statusText(): String = when (this) {
    Connection.Idle -> stringResource(R.string.engine_status_idle)
    Connection.Checking -> stringResource(R.string.engine_status_checking)
    is Connection.Connected -> stringResource(
        R.string.engine_status_connected,
        health.version,
        health.protocolVersion,
    )

    is Connection.Failed -> stringResource(R.string.engine_status_failed)
}

private fun Connection.dotColor(): Color = when (this) {
    is Connection.Connected -> Color(0xFF16A34A)
    is Connection.Failed -> Color(0xFFDC2626)
    Connection.Checking -> Color(0xFFD97706)
    Connection.Idle -> Color(0xFF9CA3AF)
}

@Composable
private fun CookiePolicy.cookieText(): String = stringResource(
    when (this) {
        CookiePolicy.NONE -> R.string.settings_cookie_none
        CookiePolicy.LAZY -> R.string.settings_cookie_lazy
        CookiePolicy.EXPLICIT -> R.string.settings_cookie_explicit
    },
)

@Preview(showBackground = true, backgroundColor = 0xFFF6F8FA)
@Composable
private fun SettingsScreenPreview() {
    BgvTheme {
        SettingsScreen(
            state = HomeUiState(
                engineUrl = "http://127.0.0.1:8730",
                engineUrlDraft = "http://127.0.0.1:8730",
                connection = Connection.Connected(
                    health = HealthResponse(
                        status = "ok",
                        version = "0.2.0",
                        protocolVersion = "0.2",
                        uptimeSeconds = 3720,
                    ),
                    selfcheck = SelfcheckResponse(
                        listenAddress = "127.0.0.1:8730",
                        tlsEnabled = false,
                        authEnabled = false,
                        activeProxyIds = emptyList(),
                        cookiePolicyDefault = CookiePolicy.LAZY,
                        outputDirAllowlist = listOf("D:/BgvOutput"),
                        cacheDir = "D:/BgvCache",
                        cacheTtlHours = 24,
                        cacheSizeBytes = 64L * 1024 * 1024,
                    ),
                ),
                sniffExecutor = SniffExecutor.ENGINE,
                downloadExecutor = DownloadExecutor.ENGINE,
            ),
            history = HistoryUiState(
                groups = listOf(
                    HistoryGroup(
                        label = HistoryGroupLabel.TODAY,
                        records = listOf(
                            DownloadRecord(
                                id = "preview",
                                url = "https://example.com/watch/abc",
                                title = "example.com",
                                status = RecordStatus.DONE,
                                createdAt = 0,
                                updatedAt = 0,
                            ),
                        ),
                    ),
                ),
                loading = false,
            ),
            clearingHistory = false,
            outputLocation = OutputLocation(treeUri = "content://preview/tree", displayName = "Download"),
            outputError = null,
            onEditEngineUrl = {},
            onSaveEngineUrl = {},
            onRefreshConnection = {},
            onSelectSniffExecutor = {},
            onSelectDownloadExecutor = {},
            onPickOutputDirectory = {},
            onResetOutputDirectory = {},
            onClearHistory = {},
        )
    }
}
