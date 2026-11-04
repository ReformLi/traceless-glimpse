package com.hpu.browseguard.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.browser.BrowserCandidate
import com.hpu.browseguard.data.browser.BrowserSniffController
import com.hpu.browseguard.data.browser.BrowserSniffState
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.ui.components.ConfirmDialog
import java.net.URI
import java.util.Locale

/**
 * 纯手机模式的主界面：地址栏 + WebView + 底部嗅探抽屉。
 *
 * 嗅探中枢由 [AppContainer.newBrowserSniffer] 提供，WebView 本身仍归界面创建/销毁；
 * 页面标题与 URL 只用来给产物起名和补 Referer，不写进任何协议字段。
 */
@Composable
fun BrowserRoute(
    container: AppContainer,
    viewModel: HomeViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = remember { container.newBrowserSniffer() }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    val sniff by controller.state.collectAsStateWithLifecycle()

    BrowserScreen(
        state = state,
        sniff = sniff,
        controller = controller,
        onDownload = { result ->
            viewModel.downloadFromBrowser(result, sniff.pageUrl, sniff.title)
        },
        onCancelDownload = viewModel::cancelDownload,
        onSwitchToEngine = { viewModel.selectSniffExecutor(SniffExecutor.ENGINE) },
        onGrantCookieConsent = viewModel::grantCookieConsent,
        onDenyCookieConsent = viewModel::denyCookieConsent,
    )
}

@Composable
internal fun BrowserScreen(
    state: HomeUiState,
    sniff: BrowserSniffState,
    controller: BrowserSniffController,
    onDownload: (SniffResult) -> Unit,
    onCancelDownload: () -> Unit,
    onSwitchToEngine: () -> Unit,
    onGrantCookieConsent: () -> Unit,
    onDenyCookieConsent: () -> Unit,
) {
    val holder = remember { WebViewHolder() }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var address by rememberSaveable { mutableStateOf(state.sourceUrl.takeIf(::isBrowserUrl).orEmpty()) }
    var addressInvalid by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 抽屉是 Column 里的一条：拉高它等于把 WebView 压矮，所以自动展开只露一张卡，
    // 想看更多由把手拖——不然站点里刚加载完就把播放器顶出视口。
    val drawerMin = remember(density) { with(density) { DRAWER_MIN_HEIGHT.toPx() } }
    val drawerAuto = remember(density) { with(density) { DRAWER_AUTO_HEIGHT.toPx() } }
    val drawerMax = remember(density) { with(density) { DRAWER_MAX_HEIGHT.toPx() } }
    var drawerHeight by rememberSaveable { mutableStateOf(drawerAuto) }
    val drawerExpanded = drawerHeight > drawerMin + 1f
    val initialUrl = remember { addressToUrl(address) }
    val busy = state.downloadInFlight
    val copyToClipboard: (String) -> Unit = { url ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("media", url))
            Toast.makeText(context, R.string.browser_candidate_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun openAddress() {
        val url = addressToUrl(address)
        if (url == null) {
            addressInvalid = true
            return
        }
        addressInvalid = false
        address = url
        focusManager.clearFocus()
        holder.view?.loadUrl(url)
    }

    // 页面自己跳转/重定向时地址栏跟着走；用户正在输入时 pageUrl 不变，不会被覆盖。
    LaunchedEffect(sniff.pageUrl) {
        if (isBrowserUrl(sniff.pageUrl)) {
            address = sniff.pageUrl
            addressInvalid = false
        }
    }
    // 第一次抓到媒体就把抽屉弹出来；之后每次新命中同样自动展开。
    LaunchedEffect(sniff.hitAt) {
        if (sniff.hitAt > 0L) drawerHeight = drawerAuto
    }
    DisposableEffect(Unit) {
        onDispose {
            holder.view?.release()
            holder.view = null
            runCatching { CookieManager.getInstance().flush() }
        }
    }
    BackHandler(enabled = sniff.canGoBack) { holder.view?.goBack() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserTopBar(
                address = address,
                addressInvalid = addressInvalid,
                insecure = address.startsWith("http://", ignoreCase = true),
                loading = sniff.loading,
                canGoBack = sniff.canGoBack,
                canGoForward = sniff.canGoForward,
                engineSwitchEnabled = !busy,
                onAddressChange = {
                    address = it
                    addressInvalid = false
                },
                onOpen = ::openAddress,
                onBack = { holder.view?.goBack() },
                onForward = { holder.view?.goForward() },
                onRefresh = {
                    val view = holder.view
                    if (sniff.loading) view?.stopLoading() else view?.reload()
                },
                onSwitchToEngine = onSwitchToEngine,
            )

            if (sniff.loading) {
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        createBrowserWebView(
                            context = ctx,
                            controller = controller,
                            onProgress = { progress = it },
                        ).also { view ->
                            holder.view = view
                            view.loadUrl(initialUrl ?: BLANK_PAGE)
                        }
                    },
                )
                if (sniff.pageUrl.isBlank()) {
                    BrowserStartHint(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 32.dp),
                    )
                }
                sniff.error?.let { message ->
                    BrowserErrorBanner(
                        message = stringResource(R.string.browser_page_error, message),
                        onDismiss = { controller.clearError() },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp),
                    )
                }
            }

            BrowserDownloadCard(phase = state.download, onCancel = onCancelDownload)

            BrowserSniffDrawer(
                sniff = sniff,
                height = drawerHeight,
                expanded = drawerExpanded,
                busy = busy,
                onResize = { delta -> drawerHeight = (drawerHeight - delta).coerceIn(drawerMin, drawerMax) },
                onToggle = { drawerHeight = if (drawerExpanded) drawerMin else drawerAuto },
                onDownload = onDownload,
                onCopy = copyToClipboard,
                onSwitchToEngine = onSwitchToEngine,
            )
        }
    }

    val awaiting = state.download as? DownloadPhase.AwaitingCookieConsent
    awaiting?.let { phase ->
        ConfirmDialog(
            title = stringResource(R.string.cookie_consent_title, phase.host),
            message = stringResource(R.string.cookie_consent_body),
            confirmLabel = stringResource(R.string.cookie_consent_agree),
            dismissLabel = stringResource(R.string.cookie_consent_reject),
            onConfirm = onGrantCookieConsent,
            onDismiss = onDenyCookieConsent,
        )
    }
}

@Composable
private fun BrowserTopBar(
    address: String,
    addressInvalid: Boolean,
    insecure: Boolean,
    loading: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    engineSwitchEnabled: Boolean,
    onAddressChange: (String) -> Unit,
    onOpen: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchToEngine: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.browser_forward),
                        modifier = Modifier.size(20.dp),
                    )
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = onAddressChange,
                    singleLine = true,
                    isError = addressInvalid,
                    placeholder = { Text(stringResource(R.string.browser_address_placeholder)) },
                    supportingText = if (addressInvalid) {
                        { Text(stringResource(R.string.browser_address_invalid)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onOpen() }),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )
                FilledTonalButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.browser_go))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (insecure) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFFD97706), CircleShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.browser_insecure),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onRefresh) {
                    Icon(
                        imageVector = if (loading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = stringResource(
                            if (loading) R.string.browser_stop else R.string.browser_reload,
                        ),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(if (loading) R.string.browser_stop else R.string.browser_reload),
                    )
                }
                TextButton(onClick = onSwitchToEngine, enabled = engineSwitchEnabled) {
                    Text(stringResource(R.string.browser_engine_switch))
                }
            }
        }
    }
}

@Composable
private fun BrowserStartHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.browser_start_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.browser_start_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrowserErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun BrowserSniffDrawer(
    sniff: BrowserSniffState,
    height: Float,
    expanded: Boolean,
    busy: Boolean,
    onResize: (Float) -> Unit,
    onToggle: () -> Unit,
    onDownload: (SniffResult) -> Unit,
    onCopy: (String) -> Unit,
    onSwitchToEngine: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { height.toDp() }),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .draggable(
                        state = rememberDraggableState(onDelta = onResize),
                        orientation = Orientation.Vertical,
                    )
                    .clickable(onClick = onToggle)
                    .padding(vertical = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.browser_panel_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (sniff.candidates.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        CountChip(stringResource(R.string.browser_panel_count, sniff.candidates.size))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            if (expanded) R.string.browser_panel_collapse else R.string.browser_panel_expand,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (!expanded) return@Column

            if (sniff.blobLinks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // blob 只是播放器拿到的假地址；排序保证 HLS 清单在第一张，
                // 所以「第一条可下载」就是我们要强行下载的那条底层清单。
                BlobHintCard(
                    hlsTarget = sniff.candidates.firstOrNull { it.downloadable },
                    enabled = !busy,
                    onSwitchToEngine = onSwitchToEngine,
                    onDownload = onDownload,
                )
            }

            when {
                sniff.candidates.isNotEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sniff.candidates, key = { it.key }) { candidate ->
                            BrowserCandidateRow(
                                candidate = candidate,
                                enabled = !busy,
                                onDownload = { onDownload(candidate.result) },
                                onCopy = { onCopy(candidate.result.mediaUrl) },
                            )
                        }
                    }
                }

                sniff.loading || sniff.blobLinks.isNotEmpty() -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.browser_panel_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.browser_panel_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BlobHintCard(
    hlsTarget: BrowserCandidate?,
    enabled: Boolean,
    onSwitchToEngine: () -> Unit,
    onDownload: (SniffResult) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = stringResource(R.string.browser_blob_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hlsTarget != null) {
                    stringResource(R.string.browser_blob_hls_caught)
                } else {
                    stringResource(R.string.browser_blob_body)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            if (hlsTarget != null) {
                // 黄卡自己就带下载入口：抽屉只有 220dp 时下面那张卡的按钮会被裁掉，
                // 只靠卡片可点等于没放行。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { onDownload(hlsTarget.result) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (hlsTarget.segments > 0) {
                                stringResource(R.string.browser_blob_force_hls, hlsTarget.segments)
                            } else {
                                stringResource(R.string.browser_blob_force_hls_plain)
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSwitchToEngine, enabled = enabled) {
                        Text(stringResource(R.string.browser_blob_switch))
                    }
                }
            } else {
                TextButton(onClick = onSwitchToEngine, enabled = enabled) {
                    Text(stringResource(R.string.browser_blob_switch))
                }
            }
        }
    }
}

@Composable
private fun BrowserCandidateRow(
    candidate: BrowserCandidate,
    enabled: Boolean,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatTag(candidate.formatLabel)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = candidate.quality,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (candidate.segments > 0) {
                    Text(
                        text = stringResource(R.string.browser_segments, candidate.segments),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = candidate.host,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = candidate.result.mediaUrl,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            candidate.note?.let { note ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (candidate.downloadable) note else "$note · ${stringResource(R.string.browser_not_downloadable)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (candidate.downloadable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onCopy) {
                    Text(stringResource(R.string.browser_candidate_copy))
                }
                FilledTonalButton(onClick = onDownload, enabled = enabled && candidate.downloadable) {
                    Text(stringResource(R.string.browser_candidate_download))
                }
            }
        }
    }
}

@Composable
private fun FormatTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BrowserDownloadCard(phase: DownloadPhase, onCancel: () -> Unit) {
    if (phase is DownloadPhase.Idle) return
    var confirmCancel by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (phase) {
                is DownloadPhase.Preparing, is DownloadPhase.Selecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.browser_download_preparing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is DownloadPhase.AwaitingCookieConsent -> {
                    Text(
                        text = stringResource(R.string.browser_consent_waiting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is DownloadPhase.Running -> {
                    val task = phase.task
                    val progress = task.progress
                    val percent = (progress?.percent ?: 0.0).coerceIn(0.0, 100.0)
                    val determinate = progress != null && (progress.bytesTotal > 0 || progress.percent > 0)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.browser_download_running),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        if (determinate) {
                            Text(
                                text = String.format(Locale.ROOT, "%.0f%%", percent),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (determinate) {
                        LinearProgressIndicator(
                            progress = { (percent / 100.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = runningDetail(task),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { confirmCancel = true }, enabled = !phase.cancelRequested) {
                            Text(
                                stringResource(
                                    if (phase.cancelRequested) R.string.download_cancelling else R.string.download_cancel,
                                ),
                            )
                        }
                    }
                }

                is DownloadPhase.Success -> BrowserSuccessContent(phase)

                is DownloadPhase.Cancelled -> {
                    Text(
                        text = stringResource(R.string.browser_download_cancelled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is DownloadPhase.Failed -> {
                    Text(
                        text = stringResource(R.string.browser_download_failed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = phase.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.browser_download_retry_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DownloadPhase.Idle -> Unit
            }
        }
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.download_cancel_title),
            message = stringResource(R.string.download_cancel_message),
            confirmLabel = stringResource(R.string.download_cancel),
            dismissLabel = stringResource(R.string.download_cancel_keep),
            destructive = true,
            onConfirm = {
                confirmCancel = false
                onCancel()
            },
            onDismiss = { confirmCancel = false },
        )
    }
}

@Composable
private fun BrowserSuccessContent(phase: DownloadPhase.Success) {
    val context = LocalContext.current
    val path = phase.publishedPath ?: localOutputPath(phase.task)
    var openProblem by remember(phase.task.id) { mutableStateOf<OpenFileProblem?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF16A34A),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.browser_download_done),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (!phase.publishing) {
            TextButton(onClick = { openProblem = path?.let { openLocalFile(context, it) } }, enabled = path != null) {
                Text(stringResource(R.string.browser_download_open))
            }
        }
    }
    path?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (phase.publishing) {
        Text(
            text = stringResource(R.string.download_publishing),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    phase.publishWarning?.let { warning ->
        Text(
            text = stringResource(R.string.download_publish_failed, warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    openProblem?.let { problem ->
        Text(
            text = openProblemText(problem),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun runningDetail(task: com.hpu.browseguard.shared.model.Task): String {
    val progress = task.progress ?: return ""
    val parts = ArrayList<String>(3)
    if (progress.bytesTotal > 0) {
        parts += "${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.bytesTotal)}"
    } else {
        progress.phase.takeIf { it.isNotBlank() }?.let(parts::add)
        parts += formatBytes(progress.bytesDownloaded)
    }
    if (progress.speedBps > 0) parts += "${formatBytes(progress.speedBps)}/s"
    return parts.joinToString(" · ")
}

private fun isBrowserUrl(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

/** 用户没写 scheme 时补 https；只有带主机名的地址才算数。 */
private fun addressToUrl(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val withScheme = when {
        value.startsWith("http://", ignoreCase = true) -> value
        value.startsWith("https://", ignoreCase = true) -> value
        else -> "https://$value"
    }
    return runCatching { URI(withScheme).host?.isNotBlank() == true }
        .getOrDefault(false)
        .let { if (it) withScheme else null }
}

private class WebViewHolder {
    var view: WebView? = null
}

private fun WebView.release() {
    stopLoading()
    removeJavascriptInterface(BRIDGE_NAME)
    loadUrl(BLANK_PAGE)
    clearHistory()
    removeAllViews()
    destroy()
}

@SuppressLint("SetJavaScriptEnabled")
private fun createBrowserWebView(
    context: Context,
    controller: BrowserSniffController,
    onProgress: (Int) -> Unit,
): WebView = WebView(context).apply {
    val webSettings = settings
    webSettings.javaScriptEnabled = true
    webSettings.domStorageEnabled = true
    webSettings.mediaPlaybackRequiresUserGesture = false
    @Suppress("DEPRECATION")
    webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    webSettings.allowFileAccess = false
    webSettings.allowContentAccess = false
    webSettings.javaScriptCanOpenWindowsAutomatically = false
    webSettings.setSupportMultipleWindows(false)
    webSettings.useWideViewPort = true
    webSettings.loadWithOverviewMode = true
    webSettings.cacheMode = WebSettings.LOAD_DEFAULT
    webSettings.userAgentString?.let { controller.userAgent = it }
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

    // 浏览器模式：站点可以正常写 Cookie；「读」仍然只走下载前的显式授权。
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    addJavascriptInterface(BrowserBridge(controller), BRIDGE_NAME)
    webViewClient = browserClient(controller)
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            controller.onTitle(title.orEmpty())
        }
    }

    // 文档开始时注入到所有 frame；不支持的老 WebView 仍由 onPageStarted/Finished 兜底。
    if (controller.hookScript.isNotBlank() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        runCatching { WebViewCompat.addDocumentStartJavaScript(this, controller.hookScript, setOf("*")) }
    }
}

private fun browserClient(controller: BrowserSniffController): WebViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url.isNullOrBlank()) return
        controller.onPageStarted(url)
        controller.userAgent = view?.settings?.userAgentString.orEmpty()
        view?.evaluateJavascript(controller.hookScript, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (url.isNullOrBlank()) return
        controller.onPageFinished(url)
        view?.evaluateJavascript(controller.hookScript, null)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        if (url.isNullOrBlank() || view == null) return
        controller.onUrlChanged(url, view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) {
            controller.onError(error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "未知错误")
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
            controller.onError("HTTP ${errorResponse?.statusCode}")
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        if (request != null) {
            controller.onRequest(
                rawUrl = request.url?.toString() ?: return null,
                method = request.method,
                requestHeaders = request.requestHeaders ?: emptyMap(),
                mainFrameUrl = controller.state.value.pageUrl,
            )
        }
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return true
        val scheme = request?.url?.scheme?.lowercase(Locale.ROOT) ?: return true
        if (scheme != "http" && scheme != "https") return true
        // 站外广告落地页最常借一次点击/滑动把整页带走；这里按注册域 + 手势收口。
        return controller.onMainFrameNavigation(
            target = url,
            currentPage = view?.url ?: controller.state.value.pageUrl,
            hasGesture = request?.hasGesture() == true,
            isRedirect = request?.isRedirect() == true,
        )
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        controller.onError("网页渲染进程已退出，请返回上一页或重新打开")
        return true
    }
}

private class BrowserBridge(private val controller: BrowserSniffController) {
    @JavascriptInterface
    fun report(json: String) {
        controller.onBridgeJson(json)
    }
}

private const val BRIDGE_NAME = "__BGV_BRIDGE__"
private const val BLANK_PAGE = "about:blank"

/** 收起只留把手那一条；自动展开刚好露出第一张卡；把手最多拖到半屏多一点。 */
private val DRAWER_MIN_HEIGHT = 60.dp
private val DRAWER_AUTO_HEIGHT = 220.dp
private val DRAWER_MAX_HEIGHT = 480.dp
