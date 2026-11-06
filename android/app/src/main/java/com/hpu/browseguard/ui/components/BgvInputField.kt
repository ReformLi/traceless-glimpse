package com.hpu.browseguard.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 统一圆角输入框（M12.5 阶段二）：12dp 圆角（形状令牌 extraSmall 槽自动生效），
 * 支持前缀图标与后缀「清空」按钮，错误文案自动变红。
 *
 * 支持文字（supportingText）只在错误时才用 error 色——普通提示走 onSurfaceVariant，
 * 让「红 = 出问题了」的条件反射保持成立。
 *
 * @param clearable 为 true 且内容非空时,右侧出现清空按钮（替代旧页面五花八门的清空入口）
 */
@Composable
fun BgvInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    singleLine: Boolean = true,
    leadingIcon: ImageVector? = null,
    clearable: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        readOnly = readOnly,
        isError = isError,
        singleLine = singleLine,
        leadingIcon = leadingIcon?.let {
            { Icon(imageVector = it, contentDescription = null) }
        },
        trailingIcon = {
            when {
                clearable && value.isNotEmpty() && enabled && !readOnly -> {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                trailing != null -> trailing()
            }
        },
        supportingText = supportingText?.let {
            {
                Text(
                    text = it,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.extraSmall,
    )
}
