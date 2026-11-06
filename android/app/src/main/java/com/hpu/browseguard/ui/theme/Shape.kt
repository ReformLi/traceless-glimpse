package com.hpu.browseguard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状令牌（M12.5 阶段一）：圆角按控件层级递增，禁止页面散落魔法数字。
 *
 * | 槽位 | 圆角 | 套用到的默认控件 |
 * |---|---|---|
 * | extraSmall | 12dp | Button / OutlinedButton / TextField（即「按钮 12dp、输入框 12dp」） |
 * | small      | 12dp | Chip / 小型行内元素 |
 * | medium     | 16dp | Card / ElevatedCard（即「卡片 16dp」） |
 * | large      | 20dp | FAB / 大型悬浮件 |
 * | extraLarge | 28dp | AlertDialog / BottomSheet |
 */
val BgvShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
