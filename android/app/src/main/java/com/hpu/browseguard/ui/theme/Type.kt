package com.hpu.browseguard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字阶（M12.5 阶段一）：严格分层——
 *
 * | 层级 | 槽位 | 字号/行高 | 字重 | 用途 |
 * |---|---|---|---|---|
 * | H1 | headlineLarge  | 32/40 | SemiBold | 大标题（少用） |
 * | H2 | headlineMedium | 28/36 | SemiBold | 页面主标题 |
 * | H3 | headlineSmall | 24/32 | SemiBold | 区块大标题 |
 * | H4 | titleLarge    | 22/28 | SemiBold | 卡片组标题 |
 * | H5 | titleMedium   | 16/24 | Medium   | 卡片标题 / 列表主标题（视觉加粗层） |
 * | H6 | titleSmall    | 14/20 | Medium   | 列表副标题 / 小标题 |
 * | Body-L | bodyLarge  | 16/24 | Normal | 正文 |
 * | Body-M | bodyMedium | 14/20 | Normal | 次级正文 / 说明（副标题变灰变小靠 onSurfaceVariant 配合） |
 * | Body-S | bodySmall  | 12/18 | Normal | 辅助文字 |
 * | Label-L | labelLarge  | 14/20 | Medium | 按钮文字 |
 * | Label-M | labelMedium | 12/16 | Medium | 徽标 / 状态胶囊 |
 * | Label-S | labelSmall  | 11/16 | Medium | 极小标注 |
 *
 * 不打包自定义字体——中文走系统字体，避免 APK 膨胀与缺字回退。
 */
val BgvTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5f).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.2f).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1f.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1f.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1f.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2f.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3f.sp,
    ),
)
