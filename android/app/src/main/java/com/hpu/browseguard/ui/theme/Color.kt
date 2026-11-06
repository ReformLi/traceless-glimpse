package com.hpu.browseguard.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 全局色彩令牌（M12.5 阶段一）。
 *
 * 主色「科技青蓝」：浅色模式取沉稳的深青蓝，深色模式取清亮的浅青蓝；
 * 背景浅色为纯白/浅灰、深色为「深空灰」——中性冷灰，不带旧版的重蓝调。
 * 警示（tertiary 琥珀）与错误（红）沿用既有语义，只微调对比度。
 */

// ---------- 浅色主题：白/浅灰底 + 深青蓝主色 ----------

val LightColors = lightColorScheme(
    primary = Color(0xFF00668F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E7FF),
    onPrimaryContainer = Color(0xFF001E2F),
    secondary = Color(0xFF4E616E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E5F4),
    onSecondaryContainer = Color(0xFF0A1E2B),
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
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = Color(0xFF5F6B78),
    outline = Color(0xFFC6D0DB),
    outlineVariant = Color(0xFFE2E8F0),
    surfaceDim = Color(0xFFDDE3EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE6ECF2),
    surfaceContainerHighest = Color(0xFFD9E0E8),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF8CC8F8),
    scrim = Color(0xFF000000),
)

// ---------- 深色主题：深空灰底 + 浅青蓝主色 ----------

val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC8F8),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B6F),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFFB4C5D9),
    onSecondary = Color(0xFF1F2E3C),
    secondaryContainer = Color(0xFF34485A),
    onSecondaryContainer = Color(0xFFD1E5F4),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF111418),
    onBackground = Color(0xFFE2E3E9),
    surface = Color(0xFF15181D),
    onSurface = Color(0xFFE2E3E9),
    surfaceVariant = Color(0xFF22262B),
    onSurfaceVariant = Color(0xFFC4CAD1),
    outline = Color(0xFF8E959D),
    outlineVariant = Color(0xFF44484E),
    surfaceDim = Color(0xFF0D0F12),
    surfaceBright = Color(0xFF32363B),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF191C21),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    inverseSurface = Color(0xFFE2E3E9),
    inverseOnSurface = Color(0xFF2F3136),
    inversePrimary = Color(0xFF00668F),
    scrim = Color(0xFF000000),
)

/**
 * 语义状态色：连接状态点、下载成功徽标等「_scheme 里没有的第四种颜色」。
 *
 * 之前各页面硬编码 0xFF16A34A / 0xFFD97706 / 0xFF9CA3AF，深色模式下对比度没人管；
 * 收敛成令牌后由主题统一给浅色/深色两套值。
 */
@Immutable
data class BgvStatusColors(
    val success: Color,
    val warning: Color,
    val idle: Color,
)

val LightStatusColors = BgvStatusColors(
    success = Color(0xFF16A34A),
    warning = Color(0xFFD97706),
    idle = Color(0xFF9CA3AF),
)

val DarkStatusColors = BgvStatusColors(
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    idle = Color(0xFF8E959D),
)
