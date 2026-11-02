package com.hpu.browseguard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
 * 历史页骨架：M6-3 在这里接 Room 持久化与重试/删除/打开。
 *
 * 现在只有空态，文案明确「即将上线」，不假装数据已经存在。
 */
@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            text = stringResource(R.string.history_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.history_subtitle),
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
                title = stringResource(R.string.history_empty_title),
                description = stringResource(R.string.history_empty_body),
                icon = Icons.AutoMirrored.Filled.List,
            )
        }
    }
}
