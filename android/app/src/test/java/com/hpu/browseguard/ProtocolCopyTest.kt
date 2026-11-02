package com.hpu.browseguard

import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.SniffExecutor
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
     * 首页副标题四格取值（M6-4 收尾②）。
     *
     * 回归点：改造前这里写死「本地执行 · 浏览器隔离嗅探」，而默认执行者其实是电脑引擎，
     * 用户照副标题判断「谁在跑这次活儿」就会判反。两段各自跟随各自的配置。
     */
    @Test
    fun homeSubtitleFollowsBothExecutors() {
        val engine = R.string.home_subtitle_engine
        val expected = mapOf(
            (SniffExecutor.ENGINE to DownloadExecutor.ENGINE) to (engine to engine),
            (SniffExecutor.ENGINE to DownloadExecutor.LOCAL_DIRECT) to
                (engine to R.string.home_subtitle_download_local),
            (SniffExecutor.LOCAL_WEBVIEW to DownloadExecutor.ENGINE) to
                (R.string.home_subtitle_sniff_local to engine),
            (SniffExecutor.LOCAL_WEBVIEW to DownloadExecutor.LOCAL_DIRECT) to
                (R.string.home_subtitle_sniff_local to R.string.home_subtitle_download_local),
        )
        assertEquals(4, expected.size)
        expected.forEach { (executors, slots) ->
            assertEquals(
                "嗅探=${executors.first} 下载=${executors.second}",
                slots,
                homeSubtitleRes(executors.first, executors.second),
            )
        }
    }

    /**
     * 「本机嗅探 · 不需要电脑」只在两条链路都不碰引擎时成立（M6-5 现场取证发现）。
     *
     * 回归点：MuMu 上把下载切到电脑引擎、嗅探留在本机，连接卡片仍写着「不需要电脑」，
     * 而这一次下载确实要电脑在线——用户据此判断的是断网还能不能干活。
     */
    @Test
    fun onlyFullyLocalCombinationClaimsNoComputer() {
        assertTrue(runsEntirelyLocally(SniffExecutor.LOCAL_WEBVIEW, DownloadExecutor.LOCAL_DIRECT))
        listOf(
            SniffExecutor.ENGINE to DownloadExecutor.ENGINE,
            SniffExecutor.ENGINE to DownloadExecutor.LOCAL_DIRECT,
            SniffExecutor.LOCAL_WEBVIEW to DownloadExecutor.ENGINE,
        ).forEach { (sniff, download) ->
            assertFalse(
                "嗅探=$sniff 下载=$download 仍然要碰电脑引擎",
                runsEntirelyLocally(sniff, download),
            )
        }
    }
}
