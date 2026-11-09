package com.hpu.browseguard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.EngineUrl
import com.hpu.browseguard.data.backend.RunMode
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.download.HlsConcurrency
import com.hpu.browseguard.data.keepalive.NotificationPermission
import com.hpu.browseguard.data.output.OutputAdoptResult
import com.hpu.browseguard.data.output.OutputLocation
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse
import com.hpu.browseguard.ui.components.BgvButton
import com.hpu.browseguard.ui.components.BgvButtonStyle
import com.hpu.browseguard.ui.components.BgvCard
import com.hpu.browseguard.ui.components.BgvCardTitle
import com.hpu.browseguard.ui.components.BgvInputField
import com.hpu.browseguard.ui.components.BgvTopBar
import com.hpu.browseguard.ui.components.ConfirmDialog
import com.hpu.browseguard.ui.theme.BgvDimens
import com.hpu.browseguard.ui.theme.BgvTheme
import com.hpu.browseguard.ui.theme.bgvStatusColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(container: AppContainer, homeViewModel: HomeViewModel) {
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    // 与历史页共用同一个 Activity 级 ViewModel：计数和清空都走同一条 Room Flow。
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(
            container.history,
            container.downloads,
            container.fileMover,
            container.mediaProbe,
            container.localSandboxRoot,
        ),
    )
    val historyState by historyViewModel.state.collectAsStateWithLifecycle()
    val clearing by historyViewModel.clearing.collectAsStateWithLifecycle()
    val outputLocation by container.outputs.location.collectAsStateWithLifecycle()
    // 通知可见性由活动在 onResume 重新读数：从授权弹窗或系统设置页回来，这一行立刻改口。
    val notificationsVisible by container.notificationsVisible.collectAsStateWithLifecycle()
    var outputError by remember { mutableStateOf<String?>(null) }
    // 授权表不是 Flow：进页面时读一次，撤销后立刻重读——列的就是这一份。
    var cookieHosts by remember { mutableStateOf(container.cookieConsents.grantedHosts()) }
    var confirmingClearCookies by remember { mutableStateOf(false) }

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

    // 「去设置」要点开系统里本应用的通知页；授权弹窗的机会用掉之后，这是唯一能把进度找回通知栏的路。
    val uiContext = LocalContext.current

    SettingsScreen(
        state = homeState,
        history = historyState,
        clearingHistory = clearing,
        notificationsVisible = notificationsVisible,
        outputLocation = outputLocation,
        outputError = outputError,
        cookieHosts = cookieHosts,
        cookieLastUsed = container.cookieConsents::lastUsedAt,
        onEditEngineUrl = homeViewModel::editEngineUrl,
        onSaveEngineUrl = homeViewModel::saveEngineUrl,
        onRefreshConnection = homeViewModel::refreshConnection,
        onSelectMode = homeViewModel::selectMode,
        onShowPageByDefaultChange = homeViewModel::setShowPageByDefault,
        onMuteWhileSniffingChange = homeViewModel::setMuteWhileSniffing,
        onKeepAliveChange = homeViewModel::setKeepAliveWhileDownloading,
        onHlsConcurrencyChange = { value -> homeViewModel.saveHlsConcurrency(value.toString()) },
        onOpenNotificationSettings = {
            uiContext.startActivity(NotificationPermission.systemSettingsIntent(uiContext))
        },
        onPickOutputDirectory = {
            outputError = null
            directoryPicker.launch(null)
        },
        onResetOutputDirectory = {
            outputError = null
            container.outputs.forget()
        },
        onClearHistory = historyViewModel::clearAll,
        onRevokeCookieConsent = { host ->
            container.cookieConsents.revoke(host)
            cookieHosts = container.cookieConsents.grantedHosts()
        },
        onRequestClearCookies = { confirmingClearCookies = true },
    )

    if (confirmingClearCookies) {
        ConfirmDialog(
            title = stringResource(R.string.settings_cookie_clear_title),
            message = stringResource(R.string.settings_cookie_clear_body),
            confirmLabel = stringResource(R.string.settings_cookie_clear_confirm),
            dismissLabel = stringResource(R.string.settings_cookie_clear_cancel),
            onConfirm = {
                confirmingClearCookies = false
                container.cookieVault.clearAll()
            },
            onDismiss = { confirmingClearCookies = false },
            destructive = true,
        )
    }
}

/**
 * 设置页：按职能分组——嗅探与下载、浏览体验、引擎连接、输出位置、隐私与数据。
 *
 * 配置的唯一入口收敛在这里（首页只留工作流与状态展示）；状态全部复用 [HomeViewModel]，
 * 首页的快捷切换与这里必须读写同一份偏好，否则两个 Tab 会各自显示一套「当前执行者」。
 */
@Composable
fun SettingsScreen(
    state: HomeUiState,
    history: HistoryUiState,
    clearingHistory: Boolean,
    notificationsVisible: Boolean,
    outputLocation: OutputLocation,
    outputError: String?,
    cookieHosts: List<String>,
    cookieLastUsed: (String) -> Long?,
    onEditEngineUrl: (String) -> Unit,
    onSaveEngineUrl: () -> Unit,
    onRefreshConnection: () -> Unit,
    onSelectMode: (RunMode) -> Unit,
    onShowPageByDefaultChange: (Boolean) -> Unit,
    onMuteWhileSniffingChange: (Boolean) -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onHlsConcurrencyChange: (Int) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onPickOutputDirectory: () -> Unit,
    onResetOutputDirectory: () -> Unit,
    onClearHistory: () -> Unit,
    onRevokeCookieConsent: (String) -> Unit,
    onRequestClearCookies: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = BgvDimens.screenPaddingHorizontal,
                vertical = BgvDimens.screenPaddingVertical,
            ),
    ) {
        BgvTopBar(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
            large = true,
        )

        // 分组只按职能走：怎么下载、怎么浏览、引擎连到哪、文件放哪、数据怎么清。
        Spacer(Modifier.height(20.dp))
        SniffDownloadSection(
            state = state,
            notificationsVisible = notificationsVisible,
            onSelectMode = onSelectMode,
            onKeepAliveChange = onKeepAliveChange,
            onHlsConcurrencyChange = onHlsConcurrencyChange,
            onOpenNotificationSettings = onOpenNotificationSettings,
        )

        Spacer(Modifier.height(20.dp))
        BrowsingSection(
            state = state,
            onShowPageByDefaultChange = onShowPageByDefaultChange,
            onMuteWhileSniffingChange = onMuteWhileSniffingChange,
        )

        Spacer(Modifier.height(20.dp))
        EngineSection(
            state = state,
            onEditEngineUrl = onEditEngineUrl,
            onSaveEngineUrl = onSaveEngineUrl,
            onRefreshConnection = onRefreshConnection,
        )

        Spacer(Modifier.height(20.dp))
        OutputSection(
            location = outputLocation,
            sandboxRoot = state.localSandboxRoot,
            error = outputError,
            onPick = onPickOutputDirectory,
            onReset = onResetOutputDirectory,
        )

        Spacer(Modifier.height(20.dp))
        PrivacySection(
            cookieHosts = cookieHosts,
            cookieLastUsed = cookieLastUsed,
            onRequestClearCookies = onRequestClearCookies,
            onRevokeCookieConsent = onRevokeCookieConsent,
            history = history,
            clearingHistory = clearingHistory,
            onRequestClearHistory = { confirmingClear = true },
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
    // M19：明文连公网地址时先弹确认——把「保存并测试」按成两步。
    var confirmingPublicHost by remember { mutableStateOf(false) }

    /** 保存入口：draft 的 Host 属公网明文时先弹警告，确认后才真正落盘。 */
    fun requestSave() {
        val host = EngineUrl.httpHostOf(state.engineUrlDraft)
        if (host != null && EngineUrl.isRiskyCleartextHost(host)) {
            confirmingPublicHost = true
        } else {
            onSaveEngineUrl()
        }
    }

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
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        BgvButton(
            text = stringResource(R.string.settings_engine_save_test),
            onClick = ::requestSave,
            enabled = connection !is Connection.Checking,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.engine_url_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // M19：动态封装的隐私说明——放在引擎卡的最后一行，小字不抢眼但能查证。
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.engine_guard_privacy_hint),
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

    if (confirmingPublicHost) {
        ConfirmDialog(
            title = stringResource(R.string.engine_url_public_title),
            message = stringResource(R.string.engine_url_public_body),
            confirmLabel = stringResource(R.string.engine_url_public_confirm),
            dismissLabel = stringResource(R.string.engine_url_public_cancel),
            onConfirm = {
                confirmingPublicHost = false
                onSaveEngineUrl()
            },
            onDismiss = { confirmingPublicHost = false },
            destructive = true,
        )
    }
}

/**
 * M25 HLS 分段并发设置：1~8 共 8 个整数档位。
 *
 * 界面只留一条滑杆——左侧标题、右侧当前档位，下面一句话说明作用范围。
 * 原来滑杆下方那条「1 2 3 4 5 6 7 8」刻度尺去掉了：它按等宽文本排布，
 * 与拇指的实际行程（两端各让出半个拇指）对不齐，越到两端越歪；
 * 档位信息改由轨道上的断点 + 右侧数字给足，那一行只添乱。
 *
 * 断点直接画在轨道上（内部每档一个圆点，已填充段用白点、未填充段用中性点）：
 * 滑杆本来就是离散的（`steps` 让拇指只能停在整点），没有这些点就看不出来。
 *
 * 轨道与拇指自绘：Material3 1.4 的默认滑杆是 16dp 厚轨道 + 44dp 竖条拇指，
 * 放进设置卡片里过于笨重。这里换成 6dp 圆头轨道 + 18dp 圆点；轨道画满画布即可——
 * 滑杆内部已经把轨道子项按「总宽 - 一个拇指宽」量好，并摆在半个拇指处。
 *
 * 范围与档位数全部由 [HlsConcurrency] 常量推导，不在界面里再写一份 1~8；
 * 滑杆的离散 steps 是第一层约束，ViewModel 的拒绝/审计是第二层。
 *
 * 自绘轨道要读 `SliderState.coercedValueAsFraction`，它是 M3 的实验 API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HlsConcurrencySetting(
    value: Int,
    onValueChangeFinished: (Int) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(value.toFloat()) }
    val current = draft.roundToInt().coerceIn(HlsConcurrency.MIN, HlsConcurrency.MAX)
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    // 断点颜色要在 Canvas 之外取：DrawScope 不是 @Composable，读不到 MaterialTheme。
    val tickOnFilled = Color.White
    val tickOnEmpty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_hls_concurrency_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = current.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
    Text(
        text = stringResource(R.string.settings_hls_concurrency_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Slider(
        value = draft,
        onValueChange = { draft = it },
        onValueChangeFinished = { onValueChangeFinished(current) },
        valueRange = HlsConcurrency.MIN.toFloat()..HlsConcurrency.MAX.toFloat(),
        // steps 是「两端之间的点数」：8 个整数档位需要 6 个内部点。
        steps = HlsConcurrency.MAX - HlsConcurrency.MIN - 1,
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = trackColor,
        ),
        // 拇指是固定 24dp 的方块、18dp 圆点居中：尺寸一旦定死，它的行程也就定了
        // （两端各让出半个拇指）。轨道按同一条口径画，两者才咬得上。
        thumb = {
            Box(Modifier.size(HlsThumbReach), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(18.dp)
                        .background(accent, CircleShape),
                )
            }
        },
        // 画布本身就是「滑杆总宽 - 一个拇指宽」，并被摆在半个拇指处：
        // 直接画满画布，两端正好落在拇指中心的最左/最右，不能再自己内缩一次。
        track = { sliderState ->
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(HlsThumbReach),
            ) {
                val centerY = size.height / 2f
                val end = size.width
                if (end > 0f) {
                    val stroke = 6.dp.toPx()
                    drawLine(trackColor, Offset(0f, centerY), Offset(end, centerY), stroke, StrokeCap.Round)
                    val head = end * sliderState.coercedValueAsFraction
                    if (head > 0f) {
                        drawLine(accent, Offset(0f, centerY), Offset(head, centerY), stroke, StrokeCap.Round)
                    }
                    // 档位断点：内部每一档画一个点，和 steps 一一对应。
                    // 两端不画——拇指停在 1 / 8 时正好压在轨道圆头上，多画反而糊成一团。
                    val span = (HlsConcurrency.MAX - HlsConcurrency.MIN).toFloat()
                    val dotRadius = 1.5.dp.toPx()
                    for (tick in HlsConcurrency.MIN + 1 until HlsConcurrency.MAX) {
                        val x = end * ((tick - HlsConcurrency.MIN) / span)
                        drawCircle(
                            color = if (x <= head) tickOnFilled else tickOnEmpty,
                            radius = dotRadius,
                            center = Offset(x, centerY),
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    )
}

/**
 * 滑杆拇指的方形触摸区，也是滑杆给轨道让位的口径。
 *
 * 滑杆内部按它的一半把轨道子项内缩并平移；自绘轨道因此直接画满自己的画布即可，
 * 不要照着这个数再缩一次。
 */
private val HlsThumbReach = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SniffDownloadSection(
    state: HomeUiState,
    notificationsVisible: Boolean,
    onSelectMode: (RunMode) -> Unit,
    onKeepAliveChange: (Boolean) -> Unit,
    onHlsConcurrencyChange: (Int) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    // 隐藏深嗅是浏览器自己的会话：它跑着也允许切到电脑引擎（切完立刻销毁 WebView）。
    val sniffRunning = state.sniff is SniffPhase.Running && state.deepSniff == DeepSniffPhase.Off
    // M23：模式一换就是两条腿一起换，所以这里只有一个锁；不再有「嗅探锁了但下载还能切」。
    val modeLocked = sniffRunning || state.downloadInFlight

    SettingsCard(stringResource(R.string.settings_sniff_download_section)) {
        Text(
            text = stringResource(R.string.sniff_mode_label),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.mode == RunMode.LOCAL,
                onClick = { onSelectMode(RunMode.LOCAL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                enabled = !modeLocked,
                label = {
                    Text(
                        text = stringResource(R.string.sniff_mode_local),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
            SegmentedButton(
                selected = state.mode == RunMode.ENGINE,
                onClick = { onSelectMode(RunMode.ENGINE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                enabled = !modeLocked,
                label = { Text(stringResource(R.string.sniff_mode_engine)) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (modeLocked) R.string.settings_execution_busy else R.string.sniff_mode_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (modeLocked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        // M25 HLS 分段并发：离散滑杆只能停在 1~8 八个整数档位，中间 7 段分隔断点。
        // 拖动中只改本地显示，松手才落盘——避免扫过 8 个档位写 8 次 SharedPreferences；
        // 真正的拒绝与审计仍由 HomeViewModel.saveHlsConcurrency 统一负责。
        Spacer(Modifier.height(16.dp))
        HlsConcurrencySetting(
            value = state.hlsConcurrency,
            onValueChangeFinished = onHlsConcurrencyChange,
        )

        // 保活算「怎么下载」这一段：切后台能不能继续下，是下载的性质，不是浏览体验。
        Spacer(Modifier.height(16.dp))
        SettingSwitchRow(
            label = stringResource(R.string.settings_keep_alive_label),
            hint = stringResource(R.string.settings_keep_alive_hint),
            checked = state.keepAliveWhileDownloading,
            onCheckedChange = onKeepAliveChange,
        )
        if (state.keepAliveWhileDownloading && !notificationsVisible) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_keep_alive_notify_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenNotificationSettings) {
                    Text(stringResource(R.string.settings_keep_alive_open_settings))
                }
            }
        }
    }
}

@Composable
private fun BrowsingSection(
    state: HomeUiState,
    onShowPageByDefaultChange: (Boolean) -> Unit,
    onMuteWhileSniffingChange: (Boolean) -> Unit,
) {
    SettingsCard(stringResource(R.string.settings_browsing_section)) {
        SettingSwitchRow(
            label = stringResource(R.string.settings_show_page_label),
            hint = stringResource(R.string.settings_show_page_hint),
            checked = state.showPageByDefault,
            onCheckedChange = onShowPageByDefaultChange,
        )
        Spacer(Modifier.height(14.dp))
        SettingSwitchRow(
            label = stringResource(R.string.settings_mute_label),
            hint = stringResource(R.string.settings_mute_hint),
            checked = state.muteWhileSniffing,
            onCheckedChange = onMuteWhileSniffingChange,
        )
    }
}

/** 设置开关行：左侧标题 + 一行灰字说明，右侧带颜色过渡的开关。 */
@Composable
private fun SettingSwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        BgvAnimatedSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
            BgvButton(
                text = stringResource(
                    if (location.configured) {
                        R.string.settings_output_change
                    } else {
                        R.string.settings_output_pick
                    },
                ),
                onClick = onPick,
                modifier = Modifier.weight(1f),
            )
            if (location.configured) {
                BgvButton(
                    text = stringResource(R.string.settings_output_reset),
                    onClick = onReset,
                    style = BgvButtonStyle.Secondary,
                )
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

/**
 * 隐私与数据：Cookie 授权和下载历史都属于「我留下的痕迹」，收进同一张卡、
 * 用分隔线分块——两个红色按钮各管各的数据，互不混淆。
 */
@Composable
private fun PrivacySection(
    cookieHosts: List<String>,
    cookieLastUsed: (String) -> Long?,
    onRequestClearCookies: () -> Unit,
    onRevokeCookieConsent: (String) -> Unit,
    history: HistoryUiState,
    clearingHistory: Boolean,
    onRequestClearHistory: () -> Unit,
) {
    val records = history.groups.flatMap { it.records }
    val done = records.count { it.status == RecordStatus.DONE }

    SettingsCard(stringResource(R.string.settings_privacy_section)) {
        Text(
            text = stringResource(R.string.settings_cookie_section),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (cookieHosts.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_cookie_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.settings_cookie_count, cookieHosts.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            cookieHosts.forEach { host ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = host, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = cookieLastUsed(host)?.let {
                                stringResource(R.string.settings_cookie_last_used, cookieTimeLabel(it))
                            } ?: stringResource(R.string.settings_cookie_never_used),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRevokeCookieConsent(host) }) {
                        Text(stringResource(R.string.settings_cookie_revoke))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        BgvButton(
            text = stringResource(R.string.settings_cookie_clear),
            onClick = onRequestClearCookies,
            style = BgvButtonStyle.Destructive,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_cookie_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text(
            text = stringResource(R.string.settings_history_section),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
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
        BgvButton(
            text = if (clearingHistory) {
                stringResource(R.string.settings_history_clearing)
            } else {
                stringResource(R.string.settings_history_clear)
            },
            onClick = onRequestClearHistory,
            enabled = records.isNotEmpty() && !clearingHistory,
            style = BgvButtonStyle.Destructive,
            modifier = Modifier.fillMaxWidth(),
        )
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
    BgvCard {
        BgvCardTitle(text = title)
        content()
    }
}

/** 开关切换带颜色过渡：轨道色在主色与中性色之间渐变，其余交给 Material 3 默认。 */
@Composable
private fun BgvAnimatedSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "switchTrack",
    )
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = trackColor,
            uncheckedTrackColor = trackColor,
        ),
    )
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

@Composable
private fun Connection.dotColor(): Color = when (this) {
    is Connection.Connected -> bgvStatusColors.success
    is Connection.Failed -> MaterialTheme.colorScheme.error
    Connection.Checking -> bgvStatusColors.warning
    Connection.Idle -> bgvStatusColors.idle
}

@Composable
private fun CookiePolicy.cookieText(): String = stringResource(
    when (this) {
        CookiePolicy.NONE -> R.string.settings_cookie_none
        CookiePolicy.LAZY -> R.string.settings_cookie_lazy
        CookiePolicy.EXPLICIT -> R.string.settings_cookie_explicit
    },
)

private val COOKIE_TIME_TODAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val COOKIE_TIME_EARLIER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

private fun cookieTimeLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    return time.format(if (time.toLocalDate() == LocalDate.now(zone)) COOKIE_TIME_TODAY else COOKIE_TIME_EARLIER)
}

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
                mode = RunMode.LOCAL,
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
            notificationsVisible = true,
            outputLocation = OutputLocation(treeUri = "content://preview/tree", displayName = "Download"),
            outputError = null,
            cookieHosts = listOf("cdn.example.com", "www.example.com"),
            cookieLastUsed = { host -> if (host == "cdn.example.com") System.currentTimeMillis() else null },
            onEditEngineUrl = {},
            onSaveEngineUrl = {},
            onRefreshConnection = {},
            onSelectMode = {},
            onShowPageByDefaultChange = {},
            onMuteWhileSniffingChange = {},
            onKeepAliveChange = {},
            onHlsConcurrencyChange = {},
            onOpenNotificationSettings = {},
            onPickOutputDirectory = {},
            onResetOutputDirectory = {},
            onClearHistory = {},
            onRevokeCookieConsent = {},
            onRequestClearCookies = {},
        )
    }
}
