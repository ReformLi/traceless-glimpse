package com.hpu.browseguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 根主题：一套「科技青蓝 + 深空灰」的 Material 3 浅/深双色板。
 *
 * 不启用动态取色：系统壁纸色会稀释品牌识别，也让同一张截图在不同设备上不可复现。
 * 语义状态色（成功/警告/空闲）走 [LocalBgvStatusColors]，与 ColorScheme 并列下发。
 */
val LocalBgvStatusColors = staticCompositionLocalOf { LightStatusColors }

@Composable
fun BgvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalBgvStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = BgvTypography,
            shapes = BgvShapes,
            content = content,
        )
    }
}

/** 页面内取语义状态色：`LocalBgvStatusColors.current.success`。 */
val bgvStatusColors: BgvStatusColors
    @Composable get() = LocalBgvStatusColors.current
