package com.hpu.browseguard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hpu.browseguard.R
import com.hpu.browseguard.ui.components.StateView
import com.hpu.browseguard.ui.components.StateViewKind

/**
 * 设置页骨架：M6-4/5 在这里接引擎连通性测试、Cookie 授权与 SAF 输出目录。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            StateView(
                kind = StateViewKind.Empty,
                title = stringResource(R.string.settings_placeholder_title),
                description = stringResource(R.string.settings_placeholder_body),
                icon = Icons.Filled.Settings,
            )
        }
    }
}
