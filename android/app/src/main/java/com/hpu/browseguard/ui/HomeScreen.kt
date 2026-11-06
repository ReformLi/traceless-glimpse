package com.hpu.browseguard.ui

import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.download.ConsentWait
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DrmSystem
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.components.BgvButton
import com.hpu.browseguard.ui.components.BgvButtonStyle
import com.hpu.browseguard.ui.components.BgvCard
import com.hpu.browseguard.ui.components.BgvCardTitle
import com.hpu.browseguard.ui.components.BgvInputField
import com.hpu.browseguard.ui.components.BgvTopBar
import com.hpu.browseguard.ui.components.ErrorCard
import com.hpu.browseguard.ui.components.StateView
import com.hpu.browseguard.ui.components.StateViewKind
import com.hpu.browseguard.ui.theme.BgvDimens
import com.hpu.browseguard.ui.theme.BgvTheme
import com.hpu.browseguard.ui.theme.bgvStatusColors
import java.net.URI
import java.util.Locale

@Composable
fun HomeRoute(
    container: AppContainer,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            container.settings,
            container,
            container.engineInfo,
            container.audit,
            container.history,
            container.cookieConsents,
            container.downloads,
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 隐藏深嗅时 WebView 仍在组合树里渲染（只是被主页盖住）；可见浏览时主页让位。
    // 会话一结束 browserSessionActive 就落 false，组合退出即触发 WebView 销毁。
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.browserSessionActive) {
            BrowserRoute(container, viewModel)
        }
        if (!state.isWebViewVisible) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                HomeScreen(
                    state = state,
                    onRefresh = viewModel::refreshConnection,
                    onEditEngineUrl = viewModel::editEngineUrl,
                    onSaveEngineUrl = viewModel::saveEngineUrl,
                    onSelectSniffExecutor = viewModel::selectSniffExecutor,
                    onDismissSniffNotice = viewModel::dismissSniffNotice,
                    onSelectDownloadExecutor = viewModel::selectDownloadExecutor,
                    onEditSourceUrl = viewModel::editSourceUrl,
                    onOpenBrowser = { viewModel.openBrowser(showPage = true) },
                    onSniff = viewModel::sniff,
                    onPickFormats = viewModel::pickFormats,
                    onSelectFormat = viewModel::selectFormat,
                    onEditNamingTemplate = viewModel::editNamingTemplate,
                    onToggleMerge = viewModel::toggleMerge,
                    onStartDownload = viewModel::startDownload,
                    onQueuedHintShown = viewModel::consumeQueuedHint,
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onEditEngineUrl: (String) -> Unit,
    onSaveEngineUrl: () -> Unit,
    onSelectSniffExecutor: (SniffExecutor) -> Unit,
    onDismissSniffNotice: () -> Unit,
    onSelectDownloadExecutor: (DownloadExecutor) -> Unit,
    onEditSourceUrl: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onSniff: () -> Unit,
    onPickFormats: () -> Unit,
    onSelectFormat: (String) -> Unit,
    onEditNamingTemplate: (String) -> Unit,
    onToggleMerge: (Boolean) -> Unit,
    onStartDownload: () -> Unit,
    onQueuedHintShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = BgvDimens.screenPaddingHorizontal,
                    vertical = BgvDimens.screenPaddingVertical,
                ),
        ) {
            val (sniffPart, downloadPart) = homeSubtitleRes(state.sniffExecutor, state.downloadExecutor)
            BgvTopBar(
                title = stringResource(R.string.home_title),
                subtitle = stringResource(
                    R.string.home_subtitle,
                    stringResource(sniffPart),
                    stringResource(downloadPart),
                ),
                large = true,
            )

            state.sniffNotice?.let { notice ->
                Spacer(Modifier.height(14.dp))
                NoticeBanner(notice, onDismissSniffNotice)
            }

            Spacer(Modifier.height(18.dp))
            SniffWorkCard(
                state = state,
                onRefreshConnection = onRefresh,
                onSelectSniffExecutor = onSelectSniffExecutor,
                onEditSourceUrl = onEditSourceUrl,
                onOpenBrowser = onOpenBrowser,
                onSniff = onSniff,
                onPickFormats = onPickFormats,
            )

            val download = state.download
            if (download is FormatPickable) {
                Spacer(Modifier.height(16.dp))
                FormatCard(state, onSelectFormat, onEditNamingTemplate, onToggleMerge, onStartDownload)
            }
            // 按下「开始下载」之后这里就空了：进度、成败、文件位置都搬到「下载」那一页去说。
            if (download is DownloadPhase.Failed) {
                Spacer(Modifier.height(16.dp))
                DownloadTriggerFailedCard(state, download, onPickFormats, onSniff)
            }

            Spacer(Modifier.height(16.dp))
            EngineSettingsSection(
                state = state,
                onRefresh = onRefresh,
                onEditEngineUrl = onEditEngineUrl,
                onSaveEngineUrl = onSaveEngineUrl,
                onSelectDownloadExecutor = onSelectDownloadExecutor,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }

    // 轻提示读一次就消费：下一次回到主页不再重播同一句「已加入队列」。
    LaunchedEffect(state.queuedHint) {
        val hint = state.queuedHint ?: return@LaunchedEffect
        onQueuedHintShown()
        snackbarHostState.showSnackbar(hint)
    }
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

/**
 * 输入框上方的连接状态胶囊：M6-2 把原来的「引擎状态卡」收敛到这一枚。
 *
 * 点一下就是重连；未连接时右侧露出刷新图标，离线态也有直接出口。
 */
@Composable
private fun ConnectionStatusPill(connection: Connection, onRefresh: () -> Unit) {
    val statusColors = bgvStatusColors
    val dot = when (connection) {
        Connection.Idle, is Connection.Failed -> statusColors.idle
        Connection.Checking -> statusColors.warning
        is Connection.Connected -> statusColors.success
    }
    val label = when (connection) {
        Connection.Idle -> stringResource(R.string.status_pill_idle)
        Connection.Checking -> stringResource(R.string.status_pill_checking)
        is Connection.Connected -> stringResource(R.string.status_pill_connected)
        is Connection.Failed -> stringResource(R.string.status_pill_offline)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onRefresh)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dot, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connection !is Connection.Connected) {
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.status_pill_reconnect),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SniffExecutorSegmented(
    selected: SniffExecutor,
    enabled: Boolean,
    onSelect: (SniffExecutor) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = selected == SniffExecutor.LOCAL_WEBVIEW,
            onClick = { onSelect(SniffExecutor.LOCAL_WEBVIEW) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = enabled,
            label = {
                Text(
                    text = stringResource(R.string.sniff_mode_local),
                    fontWeight = FontWeight.SemiBold,
                )
            },
        )
        SegmentedButton(
            selected = selected == SniffExecutor.ENGINE,
            onClick = { onSelect(SniffExecutor.ENGINE) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = enabled,
            label = { Text(stringResource(R.string.sniff_mode_engine)) },
        )
    }
}

@Composable
private fun SniffWorkCard(
    state: HomeUiState,
    onRefreshConnection: () -> Unit,
    onSelectSniffExecutor: (SniffExecutor) -> Unit,
    onEditSourceUrl: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onSniff: () -> Unit,
    onPickFormats: () -> Unit,
) {
    val context = LocalContext.current
    val connection = state.connection
    val url = state.sourceUrl.trim()
    val running = state.sniff is SniffPhase.Running
    val editable = !running && !state.downloadInFlight
    val urlProblem = when {
        url.isEmpty() -> null
        !isHttpUrl(url) -> stringResource(R.string.sniff_url_invalid)
        else -> null
    }
    val sniffEnabled = state.canSniff && editable && url.isNotEmpty() && urlProblem == null
    var pasteNotice by remember { mutableStateOf<String?>(null) }
    val clipboardEmpty = stringResource(R.string.sniff_clipboard_empty)

    BgvCard {
        ConnectionStatusPill(connection, onRefreshConnection)

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.sniff_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SniffExecutorSegmented(
            selected = state.sniffExecutor,
            enabled = editable,
            onSelect = onSelectSniffExecutor,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sniff_mode_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.sniffExecutor == SniffExecutor.ENGINE && connection is Connection.Failed) {
            Spacer(Modifier.height(10.dp))
            OfflineHint(onSwitchToLocal = { onSelectSniffExecutor(SniffExecutor.LOCAL_WEBVIEW) })
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.sniff_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    val text = runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.trim()
                    }.getOrNull()
                    if (text.isNullOrEmpty()) {
                        pasteNotice = clipboardEmpty
                    } else {
                        pasteNotice = null
                        onEditSourceUrl(text)
                    }
                },
                enabled = editable,
            ) {
                Text(stringResource(R.string.sniff_paste))
            }
        }
        Spacer(Modifier.height(4.dp))
        BgvInputField(
            value = state.sourceUrl,
            onValueChange = {
                pasteNotice = null
                onEditSourceUrl(it)
            },
            enabled = editable,
            isError = urlProblem != null,
            label = stringResource(R.string.sniff_field),
            placeholder = stringResource(R.string.sniff_placeholder),
            clearable = true,
            supportingText = urlProblem ?: pasteNotice ?: when {
                url.isEmpty() -> stringResource(R.string.sniff_url_hint)
                else -> stringResource(R.string.sniff_url_ready)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BgvButton(
                text = stringResource(R.string.sniff_action),
                onClick = onSniff,
                enabled = sniffEnabled,
            )
            if (state.sniffExecutor == SniffExecutor.LOCAL_WEBVIEW) {
                // 常驻入口：隐藏深嗅在跑时禁止改输入，但「显示网页」必须点得动。
                BgvButton(
                    text = stringResource(R.string.browser_show_page),
                    onClick = onOpenBrowser,
                    enabled = editable || state.deepSniff != DeepSniffPhase.Off,
                    style = BgvButtonStyle.Secondary,
                )
            }
            when {
                state.deepSniff == DeepSniffPhase.Running -> Text(
                    text = stringResource(R.string.deep_sniff_running_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.deepSniff == DeepSniffPhase.NoResult -> Unit
                else -> when (val phase = state.sniff) {
                    is SniffPhase.Running -> Text(
                        text = stringResource(R.string.sniff_running) + " " + phase.status.displayWithWire(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is SniffPhase.Failed -> Text(
                        text = phase.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Unit
                }
            }
        }
        if (running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val connected = connection as? Connection.Connected
        if (state.sniffExecutor == SniffExecutor.ENGINE &&
            connected != null && connected.selfcheck.outputDirAllowlist.isEmpty()
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sniff_no_allowlist),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            state.deepSniff == DeepSniffPhase.Running -> {
                Spacer(Modifier.height(12.dp))
                DeepSniffRunningCard(muted = state.muteWhileSniffing)
            }
            state.deepSniff == DeepSniffPhase.NoResult -> {
                Spacer(Modifier.height(12.dp))
                DeepSniffNoResultCard(onShowPage = onOpenBrowser)
            }
            else -> when (val phase = state.sniff) {
                is SniffPhase.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    SniffFailureCard(phase, onRetry = if (sniffEnabled) onSniff else null)
                }
                is SniffPhase.Success -> {
                    Spacer(Modifier.height(16.dp))
                    SniffResultsBlock(
                        results = phase.results,
                        canPickFormats = state.canPickFormats,
                        preparing = state.download as? DownloadPhase.Preparing,
                        onPickFormats = onPickFormats,
                    )
                }
                is SniffPhase.Running -> Unit
                SniffPhase.Idle -> {
                    if (state.download is DownloadPhase.Idle && !running) {
                        Spacer(Modifier.height(12.dp))
                        if (url.isEmpty()) {
                            EmptyHint(
                                title = stringResource(R.string.sniff_empty_title),
                                description = stringResource(R.string.sniff_empty_body),
                            )
                        } else if (urlProblem == null) {
                            EmptyHint(
                                title = stringResource(R.string.sniff_ready_title),
                                description = stringResource(R.string.sniff_ready_body),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineHint(onSwitchToLocal: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.sniff_offline_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(2.dp))
            TextButton(onClick = onSwitchToLocal, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.sniff_offline_switch))
            }
        }
    }
}

@Composable
private fun DeepSniffRunningCard(muted: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.deep_sniff_running_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (muted) R.string.deep_sniff_running_body else R.string.deep_sniff_running_body_unmuted,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeepSniffNoResultCard(onShowPage: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.deep_sniff_no_result_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.deep_sniff_no_result_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            BgvButton(
                text = stringResource(R.string.deep_sniff_show_page),
                onClick = onShowPage,
                style = BgvButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun EmptyHint(title: String, description: String) {
    StateView(
        kind = StateViewKind.Empty,
        title = title,
        description = description,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SniffFailureCard(phase: SniffPhase.Failed, onRetry: (() -> Unit)?) {
    val noMedia = phase.code == ErrorCode.NO_MEDIA_FOUND.wireCode()
    val drm = phase.code == ErrorCode.DRM_PROTECTED.wireCode()
    ErrorCard(
        title = when {
            noMedia -> stringResource(R.string.sniff_no_media_title)
            drm -> ErrorCode.DRM_PROTECTED.message
            else -> stringResource(R.string.sniff_failed_title)
        },
        message = if (noMedia) {
            phase.message + " " + stringResource(R.string.sniff_no_media_hint)
        } else {
            phase.message
        },
        code = phase.code,
        retryLabel = stringResource(R.string.sniff_retry),
        onRetry = onRetry,
    )
}

@Composable
private fun SniffResultsBlock(
    results: List<SniffResult>,
    canPickFormats: Boolean,
    preparing: DownloadPhase.Preparing?,
    onPickFormats: () -> Unit,
) {
    if (results.isEmpty()) {
        EmptyHint(
            title = stringResource(R.string.sniff_no_media_title),
            description = stringResource(R.string.sniff_no_media_hint),
        )
        return
    }

    val allDrm = results.all { it.drmDetected }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.sniff_result_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.sniff_count, results.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        results.forEach { SniffResultCard(it) }
    }

    if (allDrm) {
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = stringResource(R.string.sniff_drm_all_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.sniff_drm_all_body),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BgvButton(
            text = stringResource(R.string.format_action),
            onClick = onPickFormats,
            enabled = canPickFormats && !allDrm,
        )
        if (preparing != null) {
            Text(
                text = stringResource(R.string.format_preparing) + " " + preparing.status.displayWithWire(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(modifier = Modifier.width(96.dp))
        }
    }
    if (allDrm) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.format_drm_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SniffResultCard(result: SniffResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        border = if (result.drmDetected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = SniffRowCopy.primary(result),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!result.drmDetected) {
                    Text(
                        text = stringResource(R.string.sniff_no_drm),
                        style = MaterialTheme.typography.labelSmall,
                        color = bgvStatusColors.success,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = SniffRowCopy.secondary(result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result.drmDetected) {
                Spacer(Modifier.height(6.dp))
                // DRM 文案取协议 §5 原文（`ErrorCode` 自带），不与界面另写一套措辞
                Text(
                    text = ErrorCode.DRM_PROTECTED.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
    val recommendedId = if (selecting.formats.size > 1) {
        recommendedFormat(selecting.formats)?.formatId
    } else {
        null
    }
    BgvCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.format_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.format_option_count, selecting.formats.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            selecting.formats.forEach { format ->
                FormatOptionCard(
                    format = format,
                    formats = selecting.formats,
                    selected = format.formatId == state.selectedFormatId,
                    recommended = format.formatId == recommendedId,
                    onSelect = { onSelectFormat(format.formatId) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        BgvInputField(
            value = state.namingTemplate,
            onValueChange = onEditNamingTemplate,
            isError = state.namingTemplateError != null,
            label = stringResource(R.string.format_naming_field),
            placeholder = "{title}-{height}p.{ext}",
            supportingText = state.namingTemplateError ?: stringResource(R.string.format_naming_hint),
            modifier = Modifier.fillMaxWidth(),
        )

        val mergeEnabled = state.mergeAvailable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = mergeEnabled) { onToggleMerge(!state.mergeSelected) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.mergeSelected,
                onCheckedChange = onToggleMerge,
                enabled = mergeEnabled,
            )
            Text(
                text = stringResource(R.string.format_merge_label),
                style = MaterialTheme.typography.bodyMedium,
                color = if (mergeEnabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!mergeEnabled) {
            Text(
                text = stringResource(R.string.format_merge_local_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        BgvButton(
            text = stringResource(R.string.format_start),
            onClick = onStartDownload,
            enabled = state.canStartDownload,
        )
    }
}

@Composable
private fun FormatOptionCard(
    format: FormatInfo,
    formats: List<FormatInfo>,
    selected: Boolean,
    recommended: Boolean,
    onSelect: () -> Unit,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        recommended -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        recommended -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        else -> null
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onSelect),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        border = border,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = FormatRowCopy.primary(format, formats),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (recommended) {
                        Spacer(Modifier.width(8.dp))
                        RecommendedTag()
                    }
                }
                Text(
                    text = FormatRowCopy.secondary(format, formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetaChip(format.container)
            }
            Spacer(Modifier.width(8.dp))
            RadioButton(selected = selected, onClick = onSelect)
        }
    }
}

@Composable
private fun RecommendedTag() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = CircleShape,
    ) {
        Text(
            text = stringResource(R.string.format_recommended),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 「开始下载」这一下就没成功：画质表都拉不出来，任务根本没进队列。
 *
 * 已经进队列的任务不在这里说话——它的成败由「下载」那一页报。
 */
@Composable
private fun DownloadTriggerFailedCard(
    state: HomeUiState,
    phase: DownloadPhase.Failed,
    onRetryPrepare: () -> Unit,
    onRetrySniff: () -> Unit,
) {
    val drm = phase.code == ErrorCode.DRM_PROTECTED.wireCode()
    val canRetryPrepare = state.canPickFormats
    ErrorCard(
        title = if (drm) ErrorCode.DRM_PROTECTED.message else stringResource(R.string.download_failed_title),
        message = phase.message,
        code = phase.code,
        retryLabel = if (canRetryPrepare) {
            stringResource(R.string.download_retry)
        } else {
            stringResource(R.string.download_retry_sniff)
        },
        onRetry = if (canRetryPrepare) onRetryPrepare else onRetrySniff,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(if (phase.retryable) R.string.sniff_retryable else R.string.sniff_not_retryable),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EngineSettingsSection(
    state: HomeUiState,
    onRefresh: () -> Unit,
    onEditEngineUrl: (String) -> Unit,
    onSaveEngineUrl: () -> Unit,
    onSelectDownloadExecutor: (DownloadExecutor) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val connection = state.connection
    BgvCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.engine_panel_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = engineSummary(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    if (expanded) R.string.engine_panel_collapse else R.string.engine_panel_expand,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(connection.dotColor(), CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.engine_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = connection.statusText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BgvButton(
                    text = stringResource(R.string.engine_reconnect),
                    onClick = onRefresh,
                    style = BgvButtonStyle.Secondary,
                )
            }
            if (connection is Connection.Failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = connection.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.engine_url_label),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BgvInputField(
                    value = state.engineUrlDraft,
                    onValueChange = onEditEngineUrl,
                    isError = state.engineUrlInvalid,
                    label = stringResource(R.string.engine_url_field),
                    supportingText = if (state.engineUrlInvalid) {
                        stringResource(R.string.engine_url_invalid)
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
                BgvButton(
                    text = stringResource(R.string.engine_url_save),
                    onClick = onSaveEngineUrl,
                    style = BgvButtonStyle.Secondary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.engine_url_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.download_executor_label),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExecutorChoice(
                    text = stringResource(R.string.download_executor_engine),
                    selected = state.downloadExecutor == DownloadExecutor.ENGINE,
                    enabled = !state.downloadInFlight,
                    onClick = { onSelectDownloadExecutor(DownloadExecutor.ENGINE) },
                )
                ExecutorChoice(
                    text = stringResource(R.string.download_executor_local),
                    selected = state.downloadExecutor == DownloadExecutor.LOCAL_DIRECT,
                    enabled = !state.downloadInFlight,
                    onClick = { onSelectDownloadExecutor(DownloadExecutor.LOCAL_DIRECT) },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (state.downloadInFlight) {
                        R.string.download_executor_busy
                    } else {
                        R.string.download_executor_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.downloadInFlight) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            val connected = connection as? Connection.Connected
            if (connected != null) {
                Spacer(Modifier.height(18.dp))
                BgvCardTitle(stringResource(R.string.selfcheck_title))
                SelfcheckRows(connected.selfcheck)
            }
        }
    }
}

@Composable
private fun SelfcheckRows(selfcheck: SelfcheckResponse) {
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

@Composable
private fun engineSummary(state: HomeUiState): String {
    val connection = state.connection
    return when {
        runsEntirelyLocally(state.sniffExecutor, state.downloadExecutor) ->
            stringResource(R.string.engine_panel_summary_local)

        connection is Connection.Connected -> stringResource(
            R.string.engine_panel_summary_connected,
            connection.health.version,
        )

        connection is Connection.Checking -> stringResource(R.string.engine_status_checking)
        else -> stringResource(R.string.engine_panel_summary_offline)
    }
}

/** 选中的那颗有底色——两颗并排时要一眼看出当前是哪条链路。 */
@Composable
private fun ExecutorChoice(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    BgvButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        style = if (selected) BgvButtonStyle.Primary else BgvButtonStyle.Secondary,
    )
}

/** 降级/失败提示：必须看得见，也要能关掉，不能变成永久占位。 */
@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            BgvButton(
                text = stringResource(R.string.sniff_notice_dismiss),
                onClick = onDismiss,
                style = BgvButtonStyle.Secondary,
            )
        }
    }
}

/**
 * 链接校验只做一件事：确认用户给的是带主机的 http/https 地址。
 *
 * 放在界面层而不是 ViewModel：`sniff()` 的行为与协议无关，是否允许点按钮是纯展示判断。
 */
private fun isHttpUrl(value: String): Boolean {
    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true)
    ) {
        return false
    }
    return runCatching { URI(value).host?.isNotBlank() == true }.getOrDefault(false)
}

/**
 * 推荐画质：有视频的在 1080p 以内取最高，同高度优先带音轨；没有视频则取码率最高。
 *
 * 只影响徽标与描边，不改 `selectedFormatId`——选哪一条仍然由用户点。
 */
private fun recommendedFormat(formats: List<FormatInfo>): FormatInfo? {
    val withVideo = formats.filter { it.hasVideo }
    val capped = withVideo.filter { it.resolution.height in 1..1080 }
    val pool = capped.ifEmpty { withVideo }
    return pool.maxWithOrNull(
        compareBy<FormatInfo> { it.resolution.height }.thenBy { it.hasAudio },
    ) ?: formats.maxByOrNull { it.bitrateKbps }
}

@Composable
private fun Connection.dotColor(): Color = when (this) {
    is Connection.Connected -> bgvStatusColors.success
    is Connection.Failed -> MaterialTheme.colorScheme.error
    Connection.Checking -> bgvStatusColors.warning
    Connection.Idle -> bgvStatusColors.idle
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

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1 shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1 shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private val previewSelfcheck = SelfcheckResponse(
    listenAddress = "127.0.0.1:8730",
    tlsEnabled = false,
    authEnabled = false,
    activeProxyIds = emptyList(),
    cookiePolicyDefault = CookiePolicy.LAZY,
    outputDirAllowlist = listOf("E:\\Videos\\BGV"),
    cacheDir = "C:\\Users\\me\\AppData\\Local\\BGV\\cache",
    cacheTtlHours = 48,
    cacheSizeBytes = 20_971_520,
    dependencyVersions = mapOf("yt-dlp" to "2026.10.26", "ffmpeg" to "7.1"),
)

private val previewConnected = Connection.Connected(
    health = HealthResponse("ok", "0.1.0", "0.2", 128),
    selfcheck = previewSelfcheck,
)

private const val PREVIEW_URL = "https://www.example.com/watch?v=abc"

private val previewResults = listOf(
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
)

private val previewDrmResult = previewResults.first().copy(
    mediaUrl = "https://cdn.example.com/dash/manifest.mpd",
    type = MediaStreamType.DASH,
    container = "mpd",
    drmDetected = true,
    drmSystem = DrmSystem.WIDEVINE,
)

private fun previewState(
    download: DownloadPhase = DownloadPhase.Idle,
    sniff: SniffPhase = SniffPhase.Idle,
    sourceUrl: String = "",
    connection: Connection = previewConnected,
    sniffExecutor: SniffExecutor = SniffExecutor.ENGINE,
    downloadExecutor: DownloadExecutor = DownloadExecutor.ENGINE,
    selectedFormatId: String? = null,
    localSandboxRoot: String? = null,
    consentWait: ConsentWait? = null,
) = HomeUiState(
    engineUrl = "http://127.0.0.1:8730",
    engineUrlDraft = "http://127.0.0.1:8730",
    connection = connection,
    sourceUrl = sourceUrl,
    sniff = sniff,
    download = download,
    selectedFormatId = selectedFormatId,
    downloadExecutor = downloadExecutor,
    sniffExecutor = sniffExecutor,
    localSandboxRoot = localSandboxRoot,
    consentWait = consentWait,
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
        formatId = "22",
        container = "mp4",
        resolution = Resolution(1280, 720),
        bitrateKbps = 2200,
        codec = Codec(video = "avc1.4d401f", audio = "mp4a.40.2"),
        hasAudio = true,
        hasVideo = true,
        fileSizeEstimateBytes = 262_144_000,
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

private const val PREVIEW_SANDBOX = "/sdcard/Android/data/com.hpu.browseguard/files/Download"

@Composable
private fun PreviewHome(state: HomeUiState, darkTheme: Boolean = false) {
    BgvTheme(darkTheme = darkTheme) {
        HomeScreen(
            state = state,
            onRefresh = {},
            onEditEngineUrl = {},
            onSaveEngineUrl = {},
            onSelectSniffExecutor = {},
            onDismissSniffNotice = {},
            onSelectDownloadExecutor = {},
            onEditSourceUrl = {},
            onOpenBrowser = {},
            onSniff = {},
            onPickFormats = {},
            onSelectFormat = {},
            onEditNamingTemplate = {},
            onToggleMerge = {},
            onStartDownload = {},
            onQueuedHintShown = {},
        )
    }
}

@Preview(name = "1 空状态", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 1900)
@Composable
private fun HomeEmptyPreview() {
    PreviewHome(previewState())
}

@Preview(name = "2 输入非法", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 1900)
@Composable
private fun HomeInvalidUrlPreview() {
    PreviewHome(previewState(sourceUrl = "ftp://example.com/video"))
}

@Preview(name = "3 引擎离线", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 2000)
@Composable
private fun HomeEngineOfflinePreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            connection = Connection.Failed("Connection refused: 127.0.0.1:8730"),
        ),
    )
}

@Preview(name = "4 本机模式·引擎离线不降级", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 1900)
@Composable
private fun HomeLocalNoDegradePreview() {
    PreviewHome(
        previewState(
            connection = Connection.Failed("Connection refused: 127.0.0.1:8730"),
            sniffExecutor = SniffExecutor.LOCAL_WEBVIEW,
            downloadExecutor = DownloadExecutor.LOCAL_DIRECT,
            localSandboxRoot = PREVIEW_SANDBOX,
        ),
    )
}

@Preview(name = "5 嗅探中", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 1900)
@Composable
private fun HomeSniffingPreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            sniff = SniffPhase.Running(TaskStatus.SNIFFING),
        ),
    )
}

@Preview(name = "6 嗅探无结果", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 2100)
@Composable
private fun HomeNoMediaPreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            sniff = SniffPhase.Failed(
                message = ErrorCode.NO_MEDIA_FOUND.message,
                code = ErrorCode.NO_MEDIA_FOUND.wireCode(),
                retryable = true,
            ),
        ),
    )
}

@Preview(name = "7 DRM 保护", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 2300)
@Composable
private fun HomeDrmPreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            sniff = SniffPhase.Success(listOf(previewDrmResult)),
        ),
    )
}

@Preview(name = "8 画质选择", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 3000)
@Composable
private fun HomeSelectingPreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            sniff = SniffPhase.Success(previewResults),
            download = DownloadPhase.Selecting("0192f3a4", previewFormats),
            selectedFormatId = "137+140",
        ),
    )
}

/**
 * 9 触发下载失败：画质表都没拉出来，任务根本没进队列。
 * 已经进队列的任务成败由「下载」那一页报，这里不再出现进度/完成/取消卡。
 */
@Preview(name = "9 触发下载失败", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 2700)
@Composable
private fun HomeFailedPreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            sniff = SniffPhase.Success(previewResults),
            download = DownloadPhase.Failed(
                message = "下载超时 · 客户端本地计时 1800s 到点，任务仍在引擎侧运行",
                code = ErrorCode.NETWORK_TIMEOUT.wireCode(),
                retryable = true,
            ),
        ),
    )
}

/**
 * 10 等待 Cookie 授权：本机直链被 403 拒了，队列里的任务停在这里——不是失败态。
 * 对话框本身由系统另开窗，Preview 里看不全，真机取证见验收文档 §2。
 */
@Preview(name = "10 等待 Cookie 授权", showBackground = true, backgroundColor = 0xFFF6F8FA, heightDp = 3000)
@Composable
private fun HomeAwaitingCookieConsentPreview() {
    PreviewHome(
        previewState(
            sourceUrl = PREVIEW_URL,
            sniff = SniffPhase.Success(previewResults),
            sniffExecutor = SniffExecutor.LOCAL_WEBVIEW,
            downloadExecutor = DownloadExecutor.LOCAL_DIRECT,
            localSandboxRoot = PREVIEW_SANDBOX,
            selectedFormatId = "137+140",
            consentWait = ConsentWait("0192f3a4", "cdn.example.com", 403, "137+140", false),
        ),
    )
}
