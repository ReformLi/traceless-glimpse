package com.hpu.browseguard

import com.hpu.browseguard.data.backend.hls.HlsPlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 清单解析的纯逻辑：相对地址、画质选择、加密、MAP/BYTERANGE、直播判定。 */
class HlsPlaylistParserTest {

    @Test
    fun masterPlaylistResolvesVariantsAndPicksTheBestCappedOne() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x536
            3000k/hls/mixed.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080
            https://cdn.example.com/hd/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=3840x2160
            4k/index.m3u8
            """.trimIndent(),
            "https://super.example.com/20261021/a/index.m3u8",
        )!!

        assertTrue(playlist.isMaster)
        assertEquals(3, playlist.variants.size)
        assertEquals(
            "https://super.example.com/20261021/a/3000k/hls/mixed.m3u8",
            playlist.variants.first().url,
        )
        assertEquals("1080p 是上限：不选 4K", 1080, playlist.bestVariant()!!.height)
    }

    @Test
    fun mediaPlaylistKeepsRelativeSegmentsAndEndList() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:5.5,
            seg0.ts
            #EXTINF:5.5,
            https://cdn2.example.com/seg1.ts?token=abc
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.example.com/a/index.m3u8",
        )!!

        assertFalse(playlist.isMaster)
        assertEquals(2, playlist.segments.size)
        assertEquals("https://cdn.example.com/a/seg0.ts", playlist.segments[0].url)
        assertEquals("https://cdn2.example.com/seg1.ts?token=abc", playlist.segments[1].url)
        assertEquals(10L, playlist.segments[0].sequence)
        assertEquals(11L, playlist.segments[1].sequence)
        assertTrue(playlist.endList)
        assertEquals(11.0, playlist.totalDurationSeconds, 0.01)
        assertEquals("ts", playlist.outputContainer)
    }

    @Test
    fun aes128MapAndByteRangeAreResolved() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x0123456789abcdef0123456789abcdef
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:4,
            seg.mp4
            #EXT-X-BYTERANGE:2000
            #EXTINF:4,
            seg.mp4
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.example.com/a/index.m3u8",
        )!!

        assertEquals("mp4", playlist.outputContainer)
        val initMap = requireNotNull(playlist.initMap)
        assertEquals("https://cdn.example.com/a/init.mp4", initMap.url)
        assertEquals(720L, requireNotNull(initMap.range).length)
        assertTrue(playlist.encrypted)
        assertEquals("https://cdn.example.com/a/key.bin", playlist.segments[0].key!!.uri)
        assertEquals(0L, playlist.segments[0].range!!.offset)
        assertEquals("省略 offset 时接着上一段结尾", 1000L, playlist.segments[1].range!!.offset)
    }

    @Test
    fun sampleAesIsFlaggedAsUnsupportedAndLiveHasNoEndList() {
        val sampleAes = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key"
            #EXTINF:4,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.example.com/a/index.m3u8",
        )!!
        assertTrue(sampleAes.unsupportedEncryption)

        val live = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:4,
            seg.ts
            """.trimIndent(),
            "https://cdn.example.com/live/index.m3u8",
        )!!
        assertFalse("直播没有 ENDLIST", live.endList)
    }

    @Test
    fun keyRotationOnlyAppliesToFollowingSegments() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:42
            #EXT-X-KEY:METHOD=AES-128,URI="key1.bin",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4,
            seg0.ts
            #EXTINF:4,
            seg1.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            plain.ts
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/key2.bin"
            #EXTINF:4,
            seg2.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.example.com/a/b/index.m3u8",
        )!!

        assertEquals("第一段带密钥", "https://cdn.example.com/a/b/key1.bin", playlist.segments[0].key!!.uri)
        assertEquals("密钥沿用，直到下一条 EXT-X-KEY", "AES-128", playlist.segments[1].key!!.method)
        assertNull("METHOD=NONE 必须把密钥复位", playlist.segments[2].key)
        assertEquals(
            "新密钥按清单位置解析相对地址",
            "https://cdn.example.com/a/keys/key2.bin",
            playlist.segments[3].key!!.uri,
        )
        assertNull("未写 IV 时留给序号推导", playlist.segments[3].key!!.ivHex)
        assertEquals("序号 = MEDIA-SEQUENCE + 分片下标", 44L, playlist.segments[2].sequence)
        assertEquals(45L, playlist.segments[3].sequence)
        assertEquals(
            "IV 原样保留，由解密器解析",
            "0x000102030405060708090a0b0c0d0e0f",
            playlist.segments[0].key!!.ivHex,
        )
    }

    @Test
    fun aes128WithoutUriIsKeptButRejectedLaterInsteadOfGuessing() {
        val playlist = HlsPlaylistParser.parse(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128
            #EXTINF:4,
            seg.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
            "https://cdn.example.com/a/index.m3u8",
        )!!

        assertTrue(playlist.encrypted)
        assertNotNull(playlist.segments[0].key)
        assertNull("没有 URI 就不能编一个出来", playlist.segments[0].key!!.uri)
    }

    @Test
    fun nonHlsBodyIsRejected() {
        assertNull(HlsPlaylistParser.parse("<html>not a playlist</html>", "https://example.com"))
    }
}
