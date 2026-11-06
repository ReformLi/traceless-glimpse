package com.hpu.browseguard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hpu.browseguard.ui.theme.BgvDimens

/**
 * 通用卡片容器（M12.5 阶段二）：圆角 16dp（形状令牌 medium 槽）、
 * 纯白/深空灰面板色 + 1dp 微阴影，全 App 的卡片只有这一副面孔。
 *
 * 分层靠背景色完成：浅色下 surface(#FFF) 浮在 background(#F6F8FA) 上，
 * 深色下 surface(#15181D) 浮在 background(#111418) 上，阴影只做浅色模式的轻强调。
 *
 * @param border 选中态描边等场景由调用方传入（如下载多选的高亮边框）
 */
@Composable
fun BgvCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = BgvDimens.cardPaddingHorizontal,
        vertical = BgvDimens.cardPaddingVertical,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = border,
        // 微阴影：浅色模式给 1dp 贴地投影，深色模式靠面板色差分层
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 卡片内小节标题：统一「标题行 + 底部 12dp 呼吸」的节奏。
 *
 * @param trailing 标题右侧的补充区（计数徽标、开关等）
 */
@Composable
fun BgvCardTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
    Spacer(Modifier.height(12.dp))
}
