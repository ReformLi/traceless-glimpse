package com.hpu.browseguard.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载管理页的卡片等高（M26 后补）。
 *
 * 症状：同一条列表里，正在下载的行带着进度条 + 取消按钮，失败的行带着两行原因 + 重试按钮，
 * 已看完的行带着观看进度条，剩下的行只有一行小字——每条的卡片各高一截，滚起来参差不齐。
 *
 * 修法是把卡片拆成四段固定槽位（标题 / 元信息 / 进度槽 / 信息带），
 * 每一段无论状态都占同样的位置，条件内容只换内容不换行数。这个测试锁的就是这四段的位置：
 * 谁再往卡片里塞一个 `if (状态) { Spacer + Text }`，卡片就会重新变成参差不齐，测试先红。
 */
class HistoryCardLayoutTest {

    @Test
    fun theCardHasFourSlotsAndNoStatusDependentVerticalSpace() {
        val card = slice(source, "private fun HistoryCard(", "private val progressSlotHeight")

        // 卡片内的垂直间距只有三处，且顺序固定：标题后 4、进度槽前 6、进度槽后 4。
        val gaps = Regex("""Spacer\(Modifier\.height\((\d+)\.dp\)\)""")
            .findAll(card)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            "卡片的垂直间距必须固定成这三处；新增一处就意味着某一行会比别的行高",
            listOf("4", "6", "4"),
            gaps,
        )

        // 状态只影响信息带里的动作按钮，不能再影响行长出来的行。
        assertEquals(
            "卡片里只允许一处按状态分支，且只该是行内动作",
            1,
            Regex("""if \(running\)""").findAll(card).count(),
        )
    }

    @Test
    fun theProgressBarAlwaysOccupiesItsSlotEvenWhenThereIsNothingToDraw() {
        val slot = slice(source, "private fun RecordProgressSlot(", "private enum class FooterTone")

        assertTrue(
            "进度槽的高度是独立常量，三种内容共用",
            source.contains("private val progressSlotHeight = 6.dp"),
        )
        assertTrue(
            "下载进度、观看进度、留白都落在同一个固定高度里",
            slot.contains("height(progressSlotHeight)"),
        )
        assertTrue("下载进度仍在槽里", slot.contains("RecordStatus.DOWNLOADING"))
        assertFalse(
            "「已看」不再画轨道：一个定格的数字画成进度条会让人以为还在走",
            slot.contains("watchedFraction("),
        )
        // 轨道本身不再是条件语句的一部分：没有进度就不画，但槽的高度照占。
        val card = slice(source, "private fun HistoryCard(", "private val progressSlotHeight")
        assertTrue(
            "进度条必须收进进度槽，卡片本体不许自己画一条",
            !card.contains("LinearProgressIndicator"),
        )
    }

    @Test
    fun theWatchedProgressIsAPercentageNotABar() {
        val card = slice(source, "private fun HistoryCard(", "private val progressSlotHeight")

        assertTrue(
            "已看进度只报百分比",
            card.contains("watchedPercent(record)") &&
                card.contains("R.string.history_watched"),
        )
        assertTrue(
            "百分比不进可省略的信息带正文，否则窄屏上会被详情挤掉",
            card.contains("text = stringResource(R.string.history_watched, percent)"),
        )
    }

    @Test
    fun theFooterBandKeepsOneLineAndAFixedHeight() {
        val card = slice(source, "private fun HistoryCard(", "private val progressSlotHeight")

        assertTrue(
            "信息带的高度是独立常量（32dp），有动作和没动作都一样高",
            card.contains("height(footerBandHeight)"),
        )
        assertTrue(
            source.contains("private val footerBandHeight = 32.dp"),
        )
        assertTrue(
            "信息带里那句话只能占一行，超长省略",
            card.contains("maxLines = 1") &&
                card.contains("overflow = TextOverflow.Ellipsis"),
        )
    }

    @Test
    fun theSelectionCheckboxDoesNotStretchTheTitleRow() {
        val card = slice(source, "private fun HistoryCard(", "private val progressSlotHeight")

        assertTrue(
            "多选勾选框要关在固定 24dp 的槽里，否则 48dp 的触摸区会把标题行撑高",
            card.contains("Modifier.size(24.dp)") && card.contains("Modifier.size(20.dp)"),
        )
    }

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("找不到锚点：$from", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("找不到锚点：$to", end > start)
        return text.substring(start, end)
    }

    private val source: String
        get() = File("$SRC_ROOT/ui/HistoryScreen.kt").readText()

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
