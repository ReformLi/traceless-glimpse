package com.hpu.browseguard.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 统一确认弹窗（M12.5 阶段二）：
 *
 * - 普通操作：主色实心确认 + 文本取消；
 * - 危险操作（[destructive]）：错误色实心确认，与普通操作一眼区分；
 * - 可选 [icon]：语义图标放在标题上方（如删除用 Warning）。
 *
 * 确认动作只负责「做」，关闭由调用方在 [onConfirm] 后自行收起，避免两次关闭竞态。
 */
@Composable
fun BgvDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Column {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(text = title, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            if (dismissLabel != null) {
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel)
                }
            }
        },
    )
}
