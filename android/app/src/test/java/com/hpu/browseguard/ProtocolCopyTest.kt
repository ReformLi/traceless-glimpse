package com.hpu.browseguard

import com.hpu.browseguard.data.backend.RunMode
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.displayLabel
import com.hpu.browseguard.ui.displayWithWire
import com.hpu.browseguard.ui.homeSubtitleRes
import com.hpu.browseguard.ui.runsEntirelyLocally
import com.hpu.browseguard.ui.wireName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态展示文案（收尾③第 3 类）：中文措辞对齐 PROTOCOL §4 状态机语义，线名同时保留。 */
class ProtocolCopyTest {

    @Test
    fun everyStateHasASemanticLabel() {
        val expected = mapOf(
            TaskStatus.QUEUED to "排队中，等引擎领取",
            TaskStatus.SNIFFING to "嗅探中",
            TaskStatus.AWAITING_SELECTION to "等待选择画质",
            TaskStatus.DOWNLOADING to "下载中",
            TaskStatus.MERGING to "合并音视频中",
            TaskStatus.POST_PROCESSING to "后处理中",
            TaskStatus.DONE to "已完成",
            TaskStatus.CANCELLED to "已取消",
            TaskStatus.FAILED to "已失败",
        )
        assertEquals(TaskStatus.values().toSet(), expected.keys)
        expected.forEach { (status, label) -> assertEquals(label, status.displayLabel()) }
    }

    /** 线名必须继续等于协议 `@SerialName`，取证脚本按它 grep 才不受中文化影响。 */
    @Test
    fun wireNamesMatchProtocolTokens() {
        val expected = mapOf(
            TaskStatus.QUEUED to "queued",
            TaskStatus.SNIFFING to "sniffing",
            TaskStatus.AWAITING_SELECTION to "awaiting_selection",
            TaskStatus.DOWNLOADING to "downloading",
            TaskStatus.MERGING to "merging",
            TaskStatus.POST_PROCESSING to "post_processing",
            TaskStatus.DONE to "done",
            TaskStatus.CANCELLED to "cancelled",
            TaskStatus.FAILED to "failed",
        )
        expected.forEach { (status, wire) -> assertEquals(wire, status.wireName()) }
    }

    @Test
    fun displayCombinesLabelAndWire() {
        assertEquals("等待选择画质（awaiting_selection）", TaskStatus.AWAITING_SELECTION.displayWithWire())
    }

    /** 终态三条不能和进行中的措辞混在一起，界面靠它区分卡片标题。 */
    @Test
    fun terminalStatesReadAsTerminal() {
        assertEquals("已完成", TaskStatus.DONE.displayLabel())
        assertEquals("已取消", TaskStatus.CANCELLED.displayLabel())
        assertEquals("已失败", TaskStatus.FAILED.displayLabel())
    }

    /**
     * 首页副标题：一个模式一句话（M23）。
     *
     * 回归点（M23 之前）：这里返回「嗅探：X · 下载：Y」两段，四格组合里有两格是
     * 「手机嗅探 + 电脑下载」——那一格正是 bug 的来源：用户在手机上操作，
     * 任务却发给电脑。合并后副标题只说一件事：这次活儿整条在哪台机器上跑。
     */
    @Test
    fun homeSubtitleFollowsTheSingleMode() {
        val expected = mapOf(
            RunMode.LOCAL to R.string.home_mode_local,
            RunMode.ENGINE to R.string.home_mode_engine,
        )
        assertEquals(RunMode.values().toSet(), expected.keys)
        expected.forEach { (mode, res) -> assertEquals("模式=$mode", res, homeSubtitleRes(mode)) }
    }

    /**
     * 「不需要电脑」直接等于「本机模式」（M23）。
     *
     * 回归点（M6-5 / M23）：改造前这要问两个开关的组合，于是「手机嗅探 + 电脑下载」
     * 既不是「不需要电脑」也不该说成需要——用户据此判断断网还能不能干活。
     */
    @Test
    fun onlyLocalModeClaimsNoComputer() {
        assertTrue(runsEntirelyLocally(RunMode.LOCAL))
        assertFalse("引擎模式两步都要电脑在线", runsEntirelyLocally(RunMode.ENGINE))
    }
}
