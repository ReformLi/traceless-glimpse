package com.hpu.browseguard

import com.hpu.browseguard.shared.model.DrmSystem
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.ui.SniffRowCopy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 嗅探结果行文案（收尾③第 1 类的另一半）：与格式行同一条底线——
 * 引擎没给的元数据不得渲染成 `0p · 0 kbps`，线名 `unknown` 不得当画质标签上屏。
 */
class SniffRowCopyTest {

    private fun result(
        type: MediaStreamType = MediaStreamType.MP4,
        container: String = "mp4",
        height: Int = 0,
        bitrateKbps: Int = 0,
        label: String = "unknown",
        drmDetected: Boolean = false,
        drmSystem: DrmSystem? = null,
    ) = SniffResult(
        taskId = "0192f3a4",
        mediaUrl = "https://cdn.example.com/media.$container",
        type = type,
        container = container,
        estimatedQuality = EstimatedQuality(height, bitrateKbps, label),
        drmDetected = drmDetected,
        drmSystem = drmSystem,
        discoveredAt = "2026-11-01T00:00:00Z",
    )

    /** 真机现场：直链 mp4 拿不到分辨率 → 首行「画质未标注」，次行只剩容器。 */
    @Test
    fun unknownQualityHasNoFakePrecision() {
        val row = result()
        assertEquals("mp4 · 画质未标注", SniffRowCopy.primary(row))
        assertEquals("mp4", SniffRowCopy.secondary(row))
    }

    /** 引擎给了标签就照它显示（`1080p60` 这类 format_note 比 `{height}p` 更准）。 */
    @Test
    fun engineLabelWinsWhenHeightIsKnown() {
        val row = result(MediaStreamType.HLS, "m3u8", 1080, 4500, "1080p60")
        assertEquals("hls · 1080p60", SniffRowCopy.primary(row))
        assertEquals("m3u8 · 1080p · 4500 kbps", SniffRowCopy.secondary(row))
    }

    /** 有高度但标签空串 → 回落 `{height}p`，不留空尾巴。 */
    @Test
    fun blankLabelFallsBackToHeight() {
        assertEquals("hls · 720p", SniffRowCopy.primary(result(MediaStreamType.HLS, "m3u8", 720, 0, "")))
    }

    /** DRM 系统名只跟随 `drmDetected=true`：未检出时协议里的 `unknown` 不进文案。 */
    @Test
    fun drmSystemAppearsOnlyWhenDetected() {
        assertEquals(
            "m3u8 · 1080p · widevine",
            SniffRowCopy.secondary(
                result(MediaStreamType.HLS, "m3u8", 1080, 0, "1080p", drmDetected = true, drmSystem = DrmSystem.WIDEVINE),
            ),
        )
        assertEquals(
            "m3u8 · 1080p",
            SniffRowCopy.secondary(
                result(MediaStreamType.HLS, "m3u8", 1080, 0, "1080p", drmSystem = DrmSystem.UNKNOWN),
            ),
        )
    }
}
