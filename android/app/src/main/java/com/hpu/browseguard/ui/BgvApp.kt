package com.hpu.browseguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer

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
    val tabStates = rememberSaveableStateHolder()

    // 首页 VM 提到根容器：历史页的「重新下载」要直接往同一个状态机里填链接。
    val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            container.settings,
            container,
            container.engineInfo,
            container.audit,
            container.history,
        ),
    )

    // 冷启动对账只做一次：把上次进程遗留的进行中记录落成事实状态。
    LaunchedEffect(Unit) {
        container.reconcileHistory()
    }

    BackHandler(enabled = destination != AppDestination.HOME) {
        destination = AppDestination.HOME
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    )

                    AppDestination.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}
