package com.hpu.browseguard

import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.ui.FormatRowCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 格式行文案（收尾③第 1 类）：文案必须由映射结果决定，而不是由单行 `hasVideo` 决定。
 *
 * 输入 `FormatInfo`（含整张表）→ 输出文案字符串，全程不碰 Compose，所以能在 JVM 单测里跑。
 */
class FormatRowCopyTest {

    private fun format(
        height: Int,
        videoCodec: String = "avc1.640028",
        audioCodec: String = "mp4a.40.2",
        hasVideo: Boolean = true,
        hasAudio: Boolean = true,
        width: Int = 1920,
        bitrateKbps: Int = 4500,
        sizeBytes: Long = 524_288_000,
    ) = FormatInfo(
        formatId = "f$height$videoCodec",
        container = "mp4",
        resolution = Resolution(width, height),
        bitrateKbps = bitrateKbps,
        codec = Codec(video = videoCodec, audio = audioCodec),
        hasAudio = hasAudio,
        hasVideo = hasVideo,
        fileSizeEstimateBytes = sizeBytes,
        source = FormatSource.YT_DLP,
    )

    /** 整表画质都拿得到 → 上限判定生效，文案说多少 p 就按多少 p 下发。 */
    @Test
    fun knownHeightsAcrossTableShowQualityAndCodec() {
        val table = listOf(format(1080), format(720))
        assertEquals("1080p · h264", FormatRowCopy.primary(table[0], table))
        assertEquals("720p · h264", FormatRowCopy.primary(table[1], table))
        assertEquals(
            SelectionStrategy.MAX_HEIGHT,
            FormatSelection.selectorFor(table[1]).strategy,
        )
    }

    /** 编码名认不出来时只剩画质，不编出「· none」这种尾巴。 */
    @Test
    fun unrecognizedCodecDropsCodecSuffix() {
        val table = listOf(format(720, videoCodec = "av01.0.08"))
        assertEquals("720p · av1", FormatRowCopy.primary(table[0], table))
        val odd = listOf(format(720, videoCodec = "unknowncodec"))
        assertEquals("720p", FormatRowCopy.primary(odd[0], odd))
    }

    /**
     * 契约不一致的根：单行有画质、但整表任一行 height<=0 时，
     * 引擎 `services/formats.py` 直接降级 best（`incomplete_metadata`）。
     * 文案必须跟着说「上限不生效」，不能继续标 720p。
     */
    @Test
    fun tableWideUnknownHeightMakesCapLabelSwitchToBest() {
        val video = format(720)
        val audioOnly = format(0, hasVideo = false)
        val table = listOf(video, audioOnly)

        assertFalse(FormatRowCopy.primary(video, listOf(video)).contains("上限"))
        assertEquals("最佳可用（画质上限不生效）", FormatRowCopy.primary(video, table))
        assertTrue(FormatRowCopy.heightCapIneffective(table))
        assertEquals(SelectionStrategy.MAX_HEIGHT, FormatSelection.selectorFor(video).strategy)
    }

    /** 空表也按「判不了」处理，与引擎 `not heights` 一支同源。 */
    @Test
    fun emptyTableIsIneffective() {
        assertTrue(FormatRowCopy.heightCapIneffective(emptyList()))
    }

    /** 元数据全缺的行不假装精确：首行说最佳可用，次行说等引擎解析。 */
    @Test
    fun unknownMetadataRowHasNoFakePrecision() {
        val row = format(0, videoCodec = "none", audioCodec = "none", bitrateKbps = 0, sizeBytes = 0)
        val table = listOf(row)
        assertEquals("最佳可用（画质未标注）", FormatRowCopy.primary(row, table))
        assertEquals("画质与编码待引擎解析", FormatRowCopy.secondary(row, table))
    }

    @Test
    fun audioOnlyRowSaysAudioOnly() {
        val row = format(0, hasVideo = false)
        val table = listOf(row)
        assertEquals("纯音频", FormatRowCopy.primary(row, table))
        assertTrue(FormatRowCopy.secondary(row, table).contains("mp4a.40.2"))
    }

    /** 有画无音：首行「视频（无音轨）」、次行提示需合并，与 selectorFor 下发的 best 对齐。 */
    @Test
    fun videoOnlyRowMarksMergeNeeded() {
        val row = format(720, audioCodec = "none", hasAudio = false)
        val table = listOf(row)
        assertEquals("视频（无音轨）", FormatRowCopy.primary(row, table))
        assertTrue(FormatRowCopy.secondary(row, table).endsWith(" · 音视频分离 · 需合并"))
        assertEquals(SelectionStrategy.BEST, FormatSelection.selectorFor(row).strategy)
    }

    /** 次行只列真实拿到的元数据：height/bitrate/size 各自 <=0 就不出现。 */
    @Test
    fun secondaryOmitsUnknownParts() {
        val row = format(0, bitrateKbps = 0, sizeBytes = 0)
        val table = listOf(row, format(1080))
        assertEquals("avc1.640028/mp4a.40.2", FormatRowCopy.secondary(row, table))
    }

    /** 同源钉子：首行文案与 kindOf 分类一一对应，界面不允许再从 hasVideo 另推一套。 */
    @Test
    fun primaryLabelIsDrivenByKindOf() {
        val audio = format(0, hasVideo = false)
        val video = format(1080)
        val unknown = format(0, videoCodec = "none", audioCodec = "none")

        assertEquals(FormatSelection.Kind.AUDIO_ONLY, FormatSelection.kindOf(audio))
        assertEquals("纯音频", FormatRowCopy.primary(audio, listOf(audio)))

        assertEquals(FormatSelection.Kind.UNKNOWN_METADATA, FormatSelection.kindOf(unknown))
        assertEquals("最佳可用（画质未标注）", FormatRowCopy.primary(unknown, listOf(unknown)))

        val cleanTable = listOf(format(1080), format(720))
        assertEquals(FormatSelection.Kind.VIDEO_AND_AUDIO, FormatSelection.kindOf(video))
        assertEquals("1080p · h264", FormatRowCopy.primary(video, cleanTable))
    }
}
