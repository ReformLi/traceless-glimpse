package com.hpu.browseguard.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距与尺寸令牌（M12.5 阶段一）：页面留白、卡片内边距、卡片间距统一从这里取。
 *
 * 之前首页用 20/24dp、下载页用 16dp、设置页另有一套——阶段三逐页适配时全部改读这里的值。
 */
object BgvDimens {
    /** 页面水平内边距 */
    val screenPaddingHorizontal = 16.dp

    /** 页面垂直内边距 */
    val screenPaddingVertical = 12.dp

    /** 同页相邻卡片之间的间距 */
    val cardSpacing = 12.dp

    /** 卡片内水平内边距 */
    val cardPaddingHorizontal = 16.dp

    /** 卡片内垂直内边距 */
    val cardPaddingVertical = 14.dp

    /** 列表项（卡片内部）之间的间距 */
    val listItemSpacing = 12.dp

    /** 统一按钮高度（BgvButton） */
    val controlHeight = 44.dp

    /** 卡片圆角（与 BgvShapes.medium 一致，单独暴露给需要 Shape 的调用点） */
    val cardCorner = 16.dp

    /** 按钮/输入框圆角 */
    val controlCorner = 12.dp
}
