package com.hpu.browseguard.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hpu.browseguard.ui.theme.BgvDimens

/** 按钮风格：实心主操作 / 描边次操作 / 实心危险操作（红色）。 */
enum class BgvButtonStyle { Primary, Secondary, Destructive }

/**
 * 统一按钮（M12.5 阶段二）：统一 44dp 高度、labelLarge 字体、12dp 圆角（形状令牌槽位自动生效）。
 *
 * - [BgvButtonStyle.Primary]：主色实心，一屏最多一个（「嗅探」「开始下载」这类）；
 * - [BgvButtonStyle.Secondary]：描边，与主按钮并排的次级动作；
 * - [BgvButtonStyle.Destructive]：错误色实心，删除/清空等危险操作。
 *
 * 只做视觉封装，禁用态、点击涟漪由 Material 3 自带。
 */
@Composable
fun BgvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BgvButtonStyle = BgvButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    leadingIcon: ImageVector? = null,
) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Icon(imageVector = leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
    val heightModifier = modifier.heightIn(min = BgvDimens.controlHeight)
    when (style) {
        BgvButtonStyle.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = heightModifier,
            content = content,
        )

        BgvButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = heightModifier,
            content = content,
        )

        BgvButtonStyle.Destructive -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = heightModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            content = content,
        )
    }
}
