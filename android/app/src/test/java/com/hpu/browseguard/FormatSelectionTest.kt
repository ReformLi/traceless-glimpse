package com.hpu.browseguard

import com.hpu.browseguard.data.FormatSelection
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.PreferCodec
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SelectionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画质选择器映射与命名模板预校验（PROTOCOL §3.4 / §3.6）。 */
class FormatSelectionTest {

    private fun format(
        height: Int,
        videoCodec: String,
        hasVideo: Boolean = true,
        hasAudio: Boolean = true,
    ) = FormatInfo(
        formatId = "137+140",
        container = "mp4",
        resolution = Resolution(1920, height),
        bitrateKbps = 4500,
        codec = Codec(video = videoCodec, audio = "mp4a.40.2"),
        hasAudio = hasAudio,
        hasVideo = hasVideo,
        fileSizeEstimateBytes = 524_288_000,
        source = FormatSource.YT_DLP,
    )

    private fun unknownFixture() = FormatInfo(
        formatId = "0",
        container = "mp4",
        resolution = Resolution(0, 0),
        bitrateKbps = 0,
        codec = Codec(video = "none", audio = "none"),
        hasAudio = false,
        hasVideo = false,
        fileSizeEstimateBytes = 0,
        source = FormatSource.YT_DLP,
    )

    @Test
    fun videoFormatBecomesMaxHeightSelectorWithoutFormatIdPassthrough() {
        val selector = FormatSelection.selectorFor(format(height = 1080, videoCodec = "avc1.640028"))

        assertEquals(SelectionStrategy.MAX_HEIGHT, selector.strategy)
        assertEquals(1080, selector.maxHeight)
        assertEquals(PreferCodec.H264, selector.preferCodec)
        assertNull("引擎拒绝 exact 透传，客户端不得带 formatId", selector.formatId)
    }

    @Test
    fun audioOnlyFormatBecomesAudioStrategy() {
        val selector = FormatSelection.selectorFor(
            format(height = 0, videoCodec = "none", hasVideo = false),
        )

        assertEquals(SelectionStrategy.AUDIO_ONLY, selector.strategy)
        assertNull(selector.maxHeight)
    }

    @Test
    fun unknownCodecMetadataFallsBackToBest() {
        // 4a 真机 fixture：HLS 变体探测前 vcodec/acodec 均为 null，引擎序列化成 none/none
        val selector = FormatSelection.selectorFor(unknownFixture())

        assertEquals("元数据未知时不猜音频也不猜画质", SelectionStrategy.BEST, selector.strategy)
        assertNull(selector.formatId)
        assertNull(selector.maxHeight)
    }

    @Test
    fun videoWithoutAudioFallsBackToBest() {
        val selector = FormatSelection.selectorFor(
            format(height = 720, videoCodec = "avc1.640028", hasAudio = false),
        )

        assertEquals(SelectionStrategy.BEST, selector.strategy)
        assertNull(selector.maxHeight)
    }

    @Test
    fun audioOnlyRequiresExplicitlyKnownAudioCodec() {
        // hasVideo=false 但 acodec 也未知 → 走未知分支，不再判成纯音频
        val selector = FormatSelection.selectorFor(
            format(height = 0, videoCodec = "none", hasVideo = false, hasAudio = false)
                .copy(codec = Codec(video = "none", audio = "none")),
        )

        assertEquals(SelectionStrategy.BEST, selector.strategy)
    }

    @Test
    fun muxedVideoAndAudioKeepHeightAndCodecPreference() {
        val selector = FormatSelection.selectorFor(format(height = 720, videoCodec = "av01.0.08M.08"))

        assertEquals(SelectionStrategy.MAX_HEIGHT, selector.strategy)
        assertEquals(720, selector.maxHeight)
        assertEquals(PreferCodec.AV1, selector.preferCodec)
    }

    /** 列表文案与点击后行为必须同源：两者都只认 kindOf，不允许各自从 hasVideo 推断。 */
    @Test
    fun kindDrivesStrategyForAllFourShapes() {
        val cases = listOf(
            format(height = 1080, videoCodec = "avc1.640028") to FormatSelection.Kind.VIDEO_AND_AUDIO,
            format(height = 0, videoCodec = "none", hasVideo = false) to FormatSelection.Kind.AUDIO_ONLY,
            unknownFixture() to FormatSelection.Kind.UNKNOWN_METADATA,
            format(height = 720, videoCodec = "avc1.640028", hasAudio = false) to FormatSelection.Kind.VIDEO_ONLY,
        )

        cases.forEach { (item, expectedKind) ->
            val kind = FormatSelection.kindOf(item)
            assertEquals(expectedKind, kind)
            val expectedStrategy = when (kind) {
                FormatSelection.Kind.VIDEO_AND_AUDIO -> SelectionStrategy.MAX_HEIGHT
                FormatSelection.Kind.AUDIO_ONLY -> SelectionStrategy.AUDIO_ONLY
                FormatSelection.Kind.UNKNOWN_METADATA, FormatSelection.Kind.VIDEO_ONLY -> SelectionStrategy.BEST
            }
            assertEquals(expectedKind.toString(), expectedStrategy, FormatSelection.selectorFor(item).strategy)
        }
    }

    @Test
    fun preferCodecLabelsMatchProtocolWireNames() {
        assertEquals("h264", FormatSelection.preferCodecLabel(PreferCodec.H264))
        assertEquals("h265", FormatSelection.preferCodecLabel(PreferCodec.H265))
        assertEquals("av1", FormatSelection.preferCodecLabel(PreferCodec.AV1))
        assertEquals("vp9", FormatSelection.preferCodecLabel(PreferCodec.VP9))
        assertNull("未识别编码不显示偏好标签", FormatSelection.preferCodecLabel(null))
    }

    @Test
    fun codecDescriptorsMapToWhitelistedPreferences() {
        val cases = mapOf(
            "avc1.640028" to PreferCodec.H264,
            "avc3.1" to PreferCodec.H264,
            "hev1.1.6" to PreferCodec.H265,
            "hvc1.1.6" to PreferCodec.H265,
            "av01.0.08M.08" to PreferCodec.AV1,
            "vp09.00.10.08" to PreferCodec.VP9,
        )
        cases.forEach { (descriptor, expected) ->
            assertEquals(descriptor, expected, FormatSelection.preferCodecOf(descriptor))
        }
        assertNull("未知编码不猜偏好，交给引擎默认排序", FormatSelection.preferCodecOf("ab12.0"))
    }

    @Test
    fun blankNamingTemplateIsAcceptedAsUnset() {
        assertNull(FormatSelection.namingTemplateError(""))
    }

    @Test
    fun whitelistedNamingTemplateIsAccepted() {
        assertNull(
            FormatSelection.namingTemplateError("{platform}-{date}-{title}-{height}p-{formatId}.{ext}"),
        )
    }

    @Test
    fun unknownVariableIsRejected() {
        val error = FormatSelection.namingTemplateError("{title}-{quality}")

        assertNotNull(error)
        assertTrue(error.orEmpty(), error!!.contains("quality"))
    }

    @Test
    fun pathTraversalAndSeparatorsAreRejected() {
        listOf("../{title}", "a/{title}", "C:\\Videos\\{title}").forEach { template ->
            assertNotNull("应拒绝 $template", FormatSelection.namingTemplateError(template))
        }
    }

    @Test
    fun unbalancedBraceIsRejected() {
        assertNotNull(FormatSelection.namingTemplateError("{title-{height}p"))
    }

    @Test
    fun overlongTemplateIsRejected() {
        val tooLong = "a".repeat(FormatSelection.NAMING_MAX_LENGTH + 1)

        assertNotNull(FormatSelection.namingTemplateError(tooLong))
        assertNull(FormatSelection.namingTemplateError("a".repeat(FormatSelection.NAMING_MAX_LENGTH)))
    }
}
