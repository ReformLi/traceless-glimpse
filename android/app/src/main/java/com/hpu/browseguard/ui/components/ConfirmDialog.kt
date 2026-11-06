package com.hpu.browseguard.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 旧确认弹窗保留签名，内部委托给 [BgvDialog]——阶段二零改动的既有调用点
 * 自动换上统一视觉，阶段三页面适配时再逐个改为直呼 BgvDialog。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    icon: ImageVector? = if (destructive) Icons.Filled.Warning else null,
) {
    BgvDialog(
        title = title,
        message = message,
        confirmLabel = confirmLabel,
        dismissLabel = dismissLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
        destructive = destructive,
        icon = icon,
    )
}
