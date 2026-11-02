package com.hpu.browseguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 品牌青绿主色 + 中性石板灰辅助色。
 *
 * 不启用动态取色：系统壁纸色会稀释品牌识别，也让同一张截图在不同设备上不可复现。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF475569),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF78350F),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    surfaceDim = Color(0xFFE2E8F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFFCBD5E1),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF5EEAD4),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF04201C),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF94A3B8),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF1F2937),
    surfaceDim = Color(0xFF0B1220),
    surfaceBright = Color(0xFF1E293B),
    surfaceContainerLowest = Color(0xFF0B1220),
    surfaceContainerLow = Color(0xFF111827),
    surfaceContainer = Color(0xFF172033),
    surfaceContainerHigh = Color(0xFF1E293B),
    surfaceContainerHighest = Color(0xFF273449),
    inverseSurface = Color(0xFFE5E7EB),
    inverseOnSurface = Color(0xFF1F2937),
    inversePrimary = Color(0xFF0F766E),
    scrim = Color(0xFF000000),
)

@Composable
fun BgvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = BgvTypography,
        shapes = BgvShapes,
        content = content,
    )
}
