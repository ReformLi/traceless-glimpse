package com.hpu.browseguard.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 旧状态视图保留签名，内部委托给 [BgvStateView]——既有调用点零改动换新视觉。
 */
@Composable
fun StateView(
    kind: StateViewKind,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    BgvStateView(
        kind = kind,
        title = title,
        modifier = modifier,
        description = description,
        icon = icon,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}
