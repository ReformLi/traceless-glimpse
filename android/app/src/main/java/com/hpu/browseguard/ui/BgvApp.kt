package com.hpu.browseguard.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.ui.components.ConfirmDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 连按两次返回才退出应用的窗口：和主流 App 的 2 秒手感一致。 */
private const val EXIT_BACK_WINDOW_MS = 2_000L

enum class AppDestination(
    val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    HISTORY(R.string.nav_history, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

/**
 * 根容器：底部三 Tab + 系统返回。
 *
 * 三个顶层页用状态驱动而不是导航库——没有深链、没有多级返回栈，
 * 但用 [rememberSaveableStateHolder] 保住各页滚动位置，切回首页不从顶部重来。
 */
@Composable
fun BgvApp(container: AppContainer) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    // 播放的是哪一行，而不是哪个界面：进程被系统回收再回来，播放器还能接上同一份进度。
    var playingId by rememberSaveable { mutableStateOf<String?>(null) }
    val tabStates = rememberSaveableStateHolder()

    // 首页 VM 提到根容器：历史页的「重新下载」要直接往同一个状态机里填链接。
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            container.settings,
            container,
            container.engineInfo,
            container.audit,
            container.history,
            container.cookieConsents,
            container.downloads,
            container.keepAlive,
        ),
    )

    // 冷启动对账只做一次：把上次进程遗留的进行中记录落成事实状态。
    LaunchedEffect(Unit) {
        container.reconcileHistory()
    }

    // 切走底部 Tab 立刻结束浏览器会话：隐藏深嗅与可见浏览都不该在后台继续跑 WebView。
    LaunchedEffect(destination) {
        if (destination != AppDestination.HOME) homeViewModel.closeBrowserSession()
    }

    // 点通知栏那一条要直接落到下载页；消费完立刻清零，否则转屏或恢复进程会把用户再踹一次。
    LaunchedEffect(container) {
        container.showDownloadsTab.collect { requested ->
            if (requested) {
                destination = AppDestination.HISTORY
                container.showDownloadsTab.value = false
            }
        }
    }

    // 浏览器自己处理状态栏 inset；只订阅这一个布尔值，别跟着下载进度重组。
    val browserActive by remember(homeViewModel) {
        homeViewModel.state.map { it.isWebViewVisible }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val browserFullBleed = browserActive && destination == AppDestination.HOME

    BackHandler(enabled = destination != AppDestination.HOME) {
        destination = AppDestination.HOME
    }

    // 首页按返回：和系统 App 一样两次返回才真退出——第一次先 Toast 提示。
    // 浏览器可见时由 BrowserScreen 自己的 BackHandler 接管，这里让路。
    val context = LocalContext.current
    var lastBackAt by remember { mutableStateOf(0L) }
    val exitHint = stringResource(R.string.app_exit_hint)
    BackHandler(enabled = destination == AppDestination.HOME && !browserActive) {
        val now = System.currentTimeMillis()
        if (now - lastBackAt <= EXIT_BACK_WINDOW_MS) {
            context.findActivity()?.finish()
        } else {
            lastBackAt = now
            Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
        }
    }

    // 授权门是队列回过头来问用户的，跟在哪个 Tab 无关：弹窗挂根容器，
    // 浏览器、下载页、设置页都能看到，点「同意」后队列按原任务原地续跑。
    val consentWait by remember(homeViewModel) {
        homeViewModel.state.map { it.consentWait }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    consentWait?.let { phase ->
        ConfirmDialog(
            title = stringResource(R.string.cookie_consent_title, phase.host),
            message = stringResource(R.string.cookie_consent_body),
            confirmLabel = stringResource(R.string.cookie_consent_agree),
            dismissLabel = stringResource(R.string.cookie_consent_reject),
            onConfirm = homeViewModel::grantCookieConsent,
            onDismiss = homeViewModel::denyCookieConsent,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = if (browserFullBleed) {
                WindowInsets(0, 0, 0, 0)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            // 底部导航在浏览器里也必须常驻：否则用户只能靠系统返回退出。
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    AppDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(imageVector = item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                tabStates.SaveableStateProvider(destination.name) {
                    when (destination) {
                        AppDestination.HOME -> HomeRoute(container, homeViewModel)
                        AppDestination.HISTORY -> HistoryRoute(
                            container = container,
                            onRetry = { url ->
                                homeViewModel.adoptSourceUrl(url)
                                destination = AppDestination.HOME
                            },
                            onPlay = { recordId -> playingId = recordId },
                        )

                        AppDestination.SETTINGS -> SettingsRoute(container, homeViewModel)
                    }
                }
            }
        }

        // 播放器盖住整屏，包括底栏：看片那一屏不该有 Tab 可点，返回键先关播放器再退 Tab。
        playingId?.let { recordId ->
            PlayerRoute(
                container = container,
                recordId = recordId,
                onClose = { playingId = null },
            )
        }
    }
}
