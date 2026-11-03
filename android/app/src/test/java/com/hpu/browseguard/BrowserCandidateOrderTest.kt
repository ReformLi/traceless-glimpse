package com.hpu.browseguard

import com.hpu.browseguard.data.browser.BROWSER_CANDIDATE_ORDER
import com.hpu.browseguard.data.browser.BrowserCandidate
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 面板排序（M7 真机第一轮现场问题 1）：同画质时必须先发现的排前面。
 *
 * 站点内联配置里 `url` 是本集、`url_next` 是下一集，两个 m3u8 同域名同画质，
 * 只差几毫秒。倒序排列时第一张卡是「下一集」，用户顺手点下去就下错了——
 * 这条Comparator 单独拿出来测，是因为它决定的是下载目标，不是好不好看。
 */
class BrowserCandidateOrderTest {

    @Test
    fun theEpisodeAlreadyPlayingStaysInTheFirstCard() {
        val current = hlsCandidate("https://cdn.test/43765.m3u8", height = 536, at = 1_730_000_000_000L)
        val next = hlsCandidate("https://cdn.test/43766.m3u8", height = 536, at = 1_730_000_000_012L)

        val ordered = listOf(next, current).sortedWith(BROWSER_CANDIDATE_ORDER)

        assertEquals("先发现的这一集在前", listOf(current.key, next.key), ordered.map { it.key })
    }

    @Test
    fun aBetterPictureStillOutranksArrivalOrder() {
        val hd = hlsCandidate("https://cdn.test/hd.m3u8", height = 1080, at = 2_000L)
        val sd = hlsCandidate("https://cdn.test/sd.m3u8", height = 536, at = 1_000L)

        assertEquals(
            listOf("hd.m3u8", "sd.m3u8"),
            listOf(sd, hd).sortedWith(BROWSER_CANDIDATE_ORDER).map { it.result.mediaUrl.substringAfterLast('/') },
        )
    }

    @Test
    fun hlsBeatsASingleDirectFileOfTheSameGuess() {
        val mp4 = candidate("https://cdn.test/clip.mp4", MediaStreamType.MP4, 536, 1_000L)
        val hls = hlsCandidate("https://cdn.test/list.m3u8", 536, 2_000L)

        assertEquals(
            listOf("list.m3u8", "clip.mp4"),
            listOf(mp4, hls).sortedWith(BROWSER_CANDIDATE_ORDER).map { it.result.mediaUrl.substringAfterLast('/') },
        )
    }

    private fun hlsCandidate(url: String, height: Int, at: Long): BrowserCandidate =
        candidate(url, MediaStreamType.HLS, height, at)

    private fun candidate(
        url: String,
        type: MediaStreamType,
        height: Int,
        at: Long,
    ): BrowserCandidate = BrowserCandidate(
        key = url,
        result = SniffResult(
            taskId = "local",
            mediaUrl = url,
            type = type,
            container = if (type == MediaStreamType.HLS) "m3u8" else "mp4",
            estimatedQuality = EstimatedQuality(height = height, bitrateKbps = 1_200, label = "${height}p"),
            drmDetected = false,
            discoveredAt = "2026-11-03T19:01:00Z",
        ),
        source = "js:inline",
        quality = "${height}p",
        discoveredAt = at,
    )
}
