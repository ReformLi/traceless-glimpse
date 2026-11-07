package com.hpu.browseguard

import com.hpu.browseguard.data.EngineUrl
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.LocalSniffMapping
import com.hpu.browseguard.data.backend.hls.Aes128SegmentDecryptor
import com.hpu.browseguard.data.backend.hls.HlsPlaylistParser
import com.hpu.browseguard.data.backend.remux.AdtsAudioConfig
import com.hpu.browseguard.data.backend.remux.AdtsFrameScanner
import com.hpu.browseguard.data.backend.remux.AudioUnitWriter
import com.hpu.browseguard.data.backend.remux.PtsGate
import com.hpu.browseguard.data.backend.remux.PtsVerdict
import com.hpu.browseguard.data.backend.remux.RemuxAudioGuard
import com.hpu.browseguard.data.backend.remux.RemuxResult
import com.hpu.browseguard.data.backend.remux.TsRemuxer
import com.hpu.browseguard.data.browser.NavigationPolicy
import com.hpu.browseguard.shared.model.ErrorCategory
import com.hpu.browseguard.shared.model.ErrorCode
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.random.Random
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M17 · 解析器模糊测试与极值防御。
 *
 * 目标不是「再验一遍正常清单」，而是把极端输入喂给**底层纯逻辑解析器**，钉死一条底线：
 * 任何输入都只能得到「返回 null / 空列表 / 明确的降级理由 / 明确的错误码」，
 * 绝不允许把 [IndexOutOfBoundsException]、[NullPointerException]、数值解析异常或
 * 整数溢出后的脏数据漏到上层（上层一旦拿到 Infinity / 负数，会直接进 UI 或 MediaMuxer）。
 *
 * 两处与需求措辞不同的**代码事实**，按实际契约断言：
 * 1. Android 端没有 `HlsParseException`（全仓库只有 `EngineException`）。
 *    [HlsPlaylistParser.parse] 的契约是返回可空 `HlsPlaylist?`，null 就是「拒绝 / 空清单」，
 *    由下游 `LocalFailures.unsupportedLink` 转成明确的本地失败。因此这里要求**零异常**，
 *    而不是要求抛某个异常类型。
 * 2. SSRF 校验器在 Python 引擎侧（`engine/security.py`），App 侧没有入站 SSRF 组件。
 *    App 侧真正能拦恶意 URL 的是格式校验器（[EngineUrl.normalize] / `toHttpUrlOrNull` /
 *    [LocalSniffMapping.toResult]）与导航闸门 [NavigationPolicy.shouldBlock]；
 *    协议侧的 `SSRF_BLOCKED` 在这里只作为「最终会拿到的明确错误码」被钉住。
 *
 * 生成器固定 seed，任何一次失败都能用同一串输入复现，不用靠「多跑几次碰运气」。
 * [TsRemuxer] 依赖 Android `MediaExtractor` / `MediaMuxer`，JVM 单测只能覆盖它的
 * 前置守卫与所有纯逻辑门禁；原生崩溃的防线正是「守卫先于任何 native 调用把坏输入挡掉」。
 */
class FuzzDefenseTest {

    private companion object {
        const val BASE_URL = "https://cdn.example.com/a/index.m3u8"
        const val PAGE_URL = "https://example.com/watch?v=1"
        const val M17_SEED = 0x4D3137L
        const val RANDOM_ROUNDS = 240
    }

    // ---------------------------------------------------------------- 生成器

    /** 确定性极值数据源：可复现的乱码，不是随机碰运气的 flaky test。 */
    private class FuzzSource(seed: Long) {
        private val rng = Random(seed)

        private val tags = arrayOf(
            "#EXTINF:", "#EXT-X-STREAM-INF:", "#EXT-X-KEY:", "#EXT-X-MAP:",
            "#EXT-X-BYTERANGE:", "#EXT-X-MEDIA-SEQUENCE:", "#EXT-X-TARGETDURATION:",
            "#EXT-X-ENDLIST", "#EXT-X-DISCONTINUITY", "#", "seg", "http://",
        )
        private val values = arrayOf(
            "-1", "0", "9".repeat(400), "2147483647", "9223372036854775807",
            "-9223372036854775808", "NaN", "Infinity", "BANDWIDTH=-1,RESOLUTION=-9x-9",
            "URI=\"", "METHOD=AES-128", "99999999999999999999999999", "@", "%00%0d%0a",
            "0xFFFFFFFFFFFFFFFF", "RESOLUTION=99999999999999999999x99999999999999999999",
        )

        fun int(bound: Int): Int = rng.nextInt(bound)

        fun pick(list: List<String>): String = list[rng.nextInt(list.size)]

        fun bytes(count: Int): ByteArray = ByteArray(count) { rng.nextInt(256).toByte() }

        /** 极端 Unicode：高位非法字节、替换符、孤立代理项、C0 控制符（含 CRLF/NUL）、DEL。 */
        fun garbage(count: Int): String = buildString(count) {
            repeat(count) {
                append(
                    when (rng.nextInt(6)) {
                        0 -> rng.nextInt(0x80, 0x100).toChar()
                        1 -> '\uFFFD'
                        2 -> rng.nextInt(0xD800, 0xE000).toChar()
                        3 -> rng.nextInt(0x00, 0x20).toChar()
                        4 -> '\u007F'
                        else -> ('a'.code + rng.nextInt(26)).toChar()
                    },
                )
            }
        }

        /** 一行「像 HLS 但完全是垃圾」的输入；末尾的乱码可能自带换行，故意打乱行切分。 */
        fun hlsLine(): String = tags[rng.nextInt(tags.size)] +
            values[rng.nextInt(values.size)] + garbage(rng.nextInt(14))

        /** 恶意 URL：畸形 scheme / 私有地址 / 百分号风暴 / CRLF 头注入 / 控制字符 / 代理项。 */
        fun evilUrl(): String {
            val scheme = pick(listOf("https://", "http://", "file://", "javascript:", "", "HTTPS://"))
            val host = pick(listOf("example.com", "127.0.0.1", "169.254.169.254", "[::1]", "", "%00%0d%0a"))
            val tail = pick(
                listOf(
                    "/v.mp4", "/a\r\nX-Inject: 1", "/%", "/" + "%".repeat(rng.nextInt(1, 200)),
                    "\u0000", "/\u0007", "?q=" + "%2".repeat(100), "#" + "%0d%0a".repeat(20),
                    "/\uD800", "/\uFFFD", "/\r\n\r\nHTTP/1.1 200 OK",
                ),
            )
            return scheme + host + tail
        }
    }

    /** 任何异常都算破防：解析器的契约是「返回值表达拒绝」，不是「抛异常让调用方猜」。 */
    private fun <T> mustNotThrow(label: String, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        val kind = when (t) {
            is IndexOutOfBoundsException -> "IndexOutOfBounds（越界读）"
            is NullPointerException -> "NullPointerException（空引用）"
            is NumberFormatException -> "NumberFormatException（数值解析泄漏）"
            is IllegalArgumentException -> "IllegalArgumentException（非法参数泄漏）"
            else -> "未声明异常"
        }
        throw AssertionError("[$label] 破防，抛出了 $kind：${t::class.java.name}: ${t.message}", t)
    }

    private fun finite(value: Double, label: String) {
        assertFalse("$label 不得是 NaN：实际 $value", value.isNaN())
        assertFalse("$label 不得是 Infinity：实际 $value", value.isInfinite())
    }

    private fun withTempFile(bytes: ByteArray, block: (File, File) -> Unit) {
        val source = Files.createTempFile("bgv-fuzz-", ".ts").toFile()
        val target = File(source.parentFile, source.name + ".mp4.part")
        try {
            source.writeBytes(bytes)
            block(source, target)
        } finally {
            source.delete()
            target.delete()
        }
    }

    // ---------------------------------------------------- 1. 生成器自证（防假通过）

    @Test
    fun generatorIsDeterministicAndActuallyProducesExtremeData() {
        val a = FuzzSource(M17_SEED)
        val b = FuzzSource(M17_SEED)
        val first = List(60) { a.hlsLine() + "|" + a.evilUrl() + "|" + a.garbage(24) }
        val second = List(60) { b.hlsLine() + "|" + b.evilUrl() + "|" + b.garbage(24) }
        assertEquals("固定 seed 必须逐字节可复现", first, second)

        val blob = a.garbage(4096)
        assertTrue("生成器必须真的产出控制字符", blob.any { it < ' ' })
        assertTrue("生成器必须真的产出高位乱码/替换符", blob.any { it.code >= 0x80 })
        assertTrue("生成器必须真的产出孤立代理项", blob.any { it.code in 0xD800..0xDFFF })
        val bytes = a.bytes(4096)
        assertTrue("字节生成器要覆盖整个 0x00-0xFF 域", bytes.map { it.toInt() and 0xFF }.toSet().size > 200)
    }

    // ---------------------------------------------------- 2. HlsPlaylistParser 防御

    @Test
    fun emptyAndBlankBodiesAreRejectedWithoutCrashing() {
        val bodies = listOf(
            "", " ", "\n", "\t\r\n  ", "\uFEFF", "\u0000".repeat(64), "<html>not hls</html>",
        )
        bodies.forEach { body ->
            assertNull("空/非 HLS 文本必须返回 null", mustNotThrow("空清单") {
                HlsPlaylistParser.parse(body, BASE_URL)
            })
        }
        // 只有头、没有任何内容行：合法的「空清单」，不能崩也不能凭空造出切片。
        val headerOnly = mustNotThrow("只有 EXTM3U 头") {
            HlsPlaylistParser.parse("#EXTM3U\n", BASE_URL)
        }
        assertNotNull(headerOnly)
        assertEquals(0, headerOnly!!.segments.size)
        assertEquals(0, headerOnly.variants.size)
        finite(headerOnly.totalDurationSeconds, "空清单总时长")
    }

    @Test
    fun nonUtf8GarbageNeverEscapesThePlaylistParser() {
        val gen = FuzzSource(M17_SEED + 1)
        repeat(RANDOM_ROUNDS / 4) {
            val body = "#EXTM3U\n" + gen.garbage(256) + "\n" + gen.hlsLine() +
                "\nseg.ts\n#EXT-X-ENDLIST\n"
            val playlist = mustNotThrow("非 UTF-8 清单") {
                HlsPlaylistParser.parse(body, BASE_URL)
            }
            playlist?.let {
                finite(it.totalDurationSeconds, "乱码清单总时长")
                finite(it.targetDurationSeconds, "乱码清单目标时长")
                assertTrue("总时长不得为负", it.totalDurationSeconds >= 0.0)
                it.variants.forEach { v ->
                    assertTrue("带宽不得为负：${v.bandwidth}", v.bandwidth >= 0L)
                    assertTrue("宽不得为负：${v.width}", v.width >= 0)
                    assertTrue("高不得为负：${v.height}", v.height >= 0)
                }
            }
        }
    }

    @Test
    fun endlessExtinfNeverLeaksInfinityIntoTheUi() {
        val extremes = listOf(
            "9".repeat(200_000),       // 超长整数：Double.parseDouble 会给 Infinity
            "1" + "0".repeat(400),     // 1e400
            "NaN", "Infinity", "-Infinity", "-1e308", "-9".repeat(1_000),
        )
        extremes.forEach { raw ->
            val body = "#EXTM3U\n#EXTINF:$raw,\nseg.ts\n#EXT-X-ENDLIST\n"
            val playlist = mustNotThrow("无限长 EXTINF(${raw.take(12)}…)") {
                HlsPlaylistParser.parse(body, BASE_URL)
            }
            playlist?.let {
                finite(it.totalDurationSeconds, "EXTINF 总时长")
                finite(it.targetDurationSeconds, "EXTINF 目标时长")
                assertTrue("时长不得为负：${it.totalDurationSeconds}", it.totalDurationSeconds >= 0.0)
            }
        }
    }

    @Test
    fun negativeAndExtremeBandwidthNeverLeaksIntoVariantSelection() {
        val values = listOf(
            "-1", "-9223372036854775808", "9223372036854775807",
            "99999999999999999999999999", "NaN", "1e309",
        )
        values.forEach { raw ->
            val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=$raw,RESOLUTION=1280x720\nv.m3u8\n"
            val playlist = mustNotThrow("极值 BANDWIDTH=$raw") {
                HlsPlaylistParser.parse(body, BASE_URL)
            }
            playlist?.let {
                it.variants.forEach { v -> assertTrue("带宽不得为负：${v.bandwidth}", v.bandwidth >= 0L) }
                mustNotThrow("极值带宽下的画质选择") { it.bestVariant() }
            }
        }
    }

    @Test
    fun oversizedResolutionNeverLeaksIntoVariantSelection() {
        val values = listOf(
            "2147483647x2147483647", "-1x-2", "99999999999999999999x99999999999999999999",
            "1920x1080x999", "x", "0x0", "1x1x1x1",
        )
        values.forEach { raw ->
            val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=$raw\nv.m3u8\n"
            val playlist = mustNotThrow("极值 RESOLUTION=$raw") {
                HlsPlaylistParser.parse(body, BASE_URL)
            }
            playlist?.let {
                it.variants.forEach { v ->
                    assertTrue("宽不得为负：${v.width}", v.width >= 0)
                    assertTrue("高不得为负：${v.height}", v.height >= 0)
                }
                mustNotThrow("极值分辨率下的画质选择") { it.bestVariant() }
            }
        }
    }

    @Test
    fun missingExtm3uHeaderIsRejectedWhileCrLfHeaderStillParses() {
        val gen = FuzzSource(M17_SEED + 2)
        val bodies = listOf(
            "<html/>", gen.garbage(200), "#EXTINF:4,\nseg.ts\n",
            "\uFEFF#EXTM3U\n#EXTINF:4,\nseg.ts\n", "#extm3u\n#EXTINF:4,\nseg.ts\n",
        )
        bodies.forEach { body ->
            assertNull("缺头/大小写不符必须返回 null", mustNotThrow("缺头清单") {
                HlsPlaylistParser.parse(body, BASE_URL)
            })
        }
        // CRLF 是真实 CDN 常见写法，极值防御不能把它误杀。
        val crlf = mustNotThrow("CRLF 清单") {
            HlsPlaylistParser.parse("#EXTM3U\r\n#EXTINF:4,\r\nseg.ts\r\n#EXT-X-ENDLIST\r\n", BASE_URL)
        }
        assertNotNull(crlf)
        assertEquals(1, crlf!!.segments.size)
    }

    @Test
    fun randomizedPlaylistFuzzNeverCrashesAndNeverEmitsDirtyNumbers() {
        val gen = FuzzSource(M17_SEED + 3)
        repeat(RANDOM_ROUNDS) {
            val body = buildString {
                append("#EXTM3U\n")
                repeat(gen.int(30) + 1) { append(gen.hlsLine()).append('\n') }
                append("seg.ts\n")
            }
            val playlist = mustNotThrow("随机清单 fuzz") {
                HlsPlaylistParser.parse(body, BASE_URL)
            }
            playlist?.let {
                finite(it.totalDurationSeconds, "随机清单总时长")
                finite(it.targetDurationSeconds, "随机清单目标时长")
                assertTrue(it.totalDurationSeconds >= 0.0)
                it.variants.forEach { v ->
                    assertTrue(v.bandwidth >= 0L)
                    assertTrue(v.width >= 0 && v.height >= 0)
                }
                it.segments.forEach { s -> assertFalse("切片序号不得为负", s.sequence < 0L) }
                it.segments.forEach { s -> s.range?.let { r -> assertTrue("BYTERANGE 长度不得为负", r.length >= 0L) } }
                mustNotThrow("随机清单画质选择") { it.bestVariant() }
            }
        }
    }

    @Test
    fun malformedIvAndKeyMaterialAreRejectedWithoutThrowing() {
        val hexes = listOf(
            "", "0x", "0X", "%".repeat(1_000), "zz", "0x" + "f".repeat(100),
            "0x1", "\u0000", "\uFFFD", "0x\r\n", "0x123456789abcdef0123456789abcdef0",
        )
        hexes.forEach { hex ->
            mustNotThrow("parseIv") { Aes128SegmentDecryptor.parseIv(hex) }
        }
        val gen = FuzzSource(M17_SEED + 4)
        repeat(32) {
            val cipher = gen.bytes(gen.int(256))
            val key = gen.bytes(gen.int(32))
            val iv = gen.bytes(gen.int(32))
            mustNotThrow("乱码密钥/IV 解密") { Aes128SegmentDecryptor.decrypt(cipher, key, iv) }
        }
        // 短 IV 必须在左侧补零到 16 字节，这是解密前的最后一道长度契约。
        assertEquals(16, mustNotThrow("短 IV 补零") {
            Aes128SegmentDecryptor.parseIv("0x00")
        }!!.size)
    }

    // ------------------------------------------- 3. TsRemuxer / ADTS 防御

    @Test
    fun oneByteFakeTsHeaderIsSafelyDowngradedBeforeAnyMuxerCall() {
        val oneByte = byteArrayOf(0x47)
        val buffer = ByteBuffer.wrap(oneByte)
        assertNull(mustNotThrow("1 字节伪 TS 头的帧边界") {
            AdtsFrameScanner.frameAt(buffer, 0, oneByte.size)
        })
        assertEquals(0, mustNotThrow("1 字节伪 TS 头数帧") {
            AdtsFrameScanner.countFrames(ByteBuffer.wrap(oneByte), oneByte.size)
        })
        assertEquals(0, mustNotThrow("1 字节伪 TS 头采样率") {
            AdtsFrameScanner.sampleRateHz(ByteBuffer.wrap(oneByte))
        })
        assertNull(mustNotThrow("1 字节伪 TS 头合成 csd") {
            AdtsAudioConfig.fromAdtsHeader(oneByte, oneByte.size)
        })
        assertEquals(0, mustNotThrow("1 字节伪 TS 头拆 AU") {
            AudioUnitWriter(48_000) { _, _, _ -> }.write(ByteBuffer.wrap(oneByte), oneByte.size, 0L)
        })
        assertEquals("找不到音轨必须给明确降级理由", RemuxAudioGuard.NO_AUDIO_TRACK, RemuxAudioGuard.trackGate(null))
    }

    @Test
    fun zeroLengthAndFakeTsSegmentsAreSkippedBeforeMediaMuxerIsTouched() {
        withTempFile(ByteArray(0)) { source, target ->
            val result = mustNotThrow("0 长度分片") { TsRemuxer().remux(source, target, 0L) }
            assertTrue("0 长度分片必须降级：$result", result is RemuxResult.Skipped)
            assertEquals("empty_source", (result as RemuxResult.Skipped).reason)
            assertFalse("降级后不许留下半成品", target.exists())
        }
        withTempFile(byteArrayOf(0x47)) { source, target ->
            // 只有 1 个同步字节：extractor 探不到任何轨道，必须在 native 调用之前被守卫挡掉。
            val result = mustNotThrow("1 字节伪 TS 文件") { TsRemuxer().remux(source, target, 0L) }
            assertTrue("伪造 TS 头必须降级保留 .ts：$result", result is RemuxResult.Skipped)
            assertFalse("降级后不许留下半成品", target.exists())
        }
    }

    @Test
    fun garbageAudioTracksProduceAnExplicitRegisteredFallbackReason() {
        val gen = FuzzSource(M17_SEED + 5)
        repeat(96) {
            val garbage = gen.bytes(gen.int(512) + 1)
            val buffer = ByteBuffer.wrap(garbage)
            assertEquals(0, mustNotThrow("乱码音轨数帧") {
                AdtsFrameScanner.countFrames(buffer, garbage.size)
            })
            assertNull(mustNotThrow("乱码音轨合成 csd") {
                AdtsAudioConfig.fromAdtsHeader(garbage, garbage.size)
            })
            assertEquals(0, mustNotThrow("乱码音轨拆 AU") {
                AudioUnitWriter(44_100) { _, _, _ -> }.write(ByteBuffer.wrap(garbage), garbage.size, 0L)
            })
        }
        val reasons = listOfNotNull(
            RemuxAudioGuard.trackGate(null),
            RemuxAudioGuard.sampleGate(0),
            RemuxAudioGuard.csdGate(RemuxAudioGuard.AAC_MIME, false, null),
            RemuxAudioGuard.outputGate(-1, 0),
            RemuxAudioGuard.auCountGate(3, 2, 3),
            RemuxAudioGuard.audioSampleGapGate(Long.MAX_VALUE),
        )
        assertEquals("每条触发器都必须给出理由", 6, reasons.size)
        reasons.forEach { reason ->
            assertTrue("$reason 必须登记在降级理由表里", reason in RemuxAudioGuard.AUDIO_FALLBACK_REASONS)
        }
    }

    @Test
    fun adtsScannerRefusesOutOfRangeOffsetsAndLyingSizes() {
        val frame = adts(64)
        val buffer = ByteBuffer.wrap(frame)
        // offset + 6 / offset + frameLength 在 Int.MAX_VALUE 处会回绕成负数，绕过边界判断后越界读。
        assertNull(mustNotThrow("Int.MAX_VALUE offset") {
            AdtsFrameScanner.frameAt(buffer, Int.MAX_VALUE, Int.MAX_VALUE)
        })
        assertNull(mustNotThrow("负 offset") { AdtsFrameScanner.frameAt(buffer, -1, frame.size) })
        assertNull(mustNotThrow("负 size") { AdtsFrameScanner.frameAt(buffer, 0, -1) })
        val lied = mustNotThrow("谎报超大 size") {
            AdtsFrameScanner.frameAt(buffer, 0, Int.MAX_VALUE)
        }
        lied?.let {
            assertTrue("size 撒谎时帧边界不得超出缓冲真实容量", it.nextOffset <= frame.size)
            assertTrue(it.payloadStart <= frame.size)
        }
        // 只有 2 字节同步字：syncWordOk 通过但 get(2) 越界。
        assertEquals(0, mustNotThrow("2 字节同步头查采样率") {
            AdtsFrameScanner.sampleRateHz(ByteBuffer.wrap(byteArrayOf(0xFF.toByte(), 0xF1.toByte())))
        })
        // 谎报 size 大于数组真实长度，同样不许越界读。
        assertNull(mustNotThrow("谎报 ADTS 头长度") {
            AdtsAudioConfig.fromAdtsHeader(ByteArray(3), 7)
        })
        repeat(64) {
            val gen = FuzzSource(M17_SEED + 6 + it)
            val noise = gen.bytes(gen.int(64) + 1)
            mustNotThrow("随机 offset/size 组合") {
                AdtsFrameScanner.frameAt(
                    ByteBuffer.wrap(noise),
                    gen.int(noise.size + 8) - 4,
                    gen.int(noise.size + 8) - 4,
                )
            }
        }
    }

    @Test
    fun extremePtsJumpsAreRejectedInsteadOfBeingWrittenIntoMuxer() {
        val extremes = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        extremes.forEach { first ->
            val videoGate = PtsGate()
            val audioGate = PtsGate()
            mustNotThrow("极值 PTS 首样本 $first") { videoGate.checkVideo(first) }
            mustNotThrow("极值 PTS 首样本 $first") { audioGate.checkAudio(first) }
            val jump = if (first > 0L) Long.MIN_VALUE else Long.MAX_VALUE
            val v = mustNotThrow("极值 PTS 跳变 $first→$jump") { videoGate.checkVideo(jump) }
            val a = mustNotThrow("极值 PTS 跳变 $first→$jump") { audioGate.checkAudio(jump) }
            assertTrue("视频时间轴极度跳跃必须拒绝：$first→$jump / $v", v is PtsVerdict.Reject)
            assertTrue("音频时间轴极度跳跃必须拒绝：$first→$jump / $a", a is PtsVerdict.Reject)
        }
        // 单调链里塞一个极值也不许崩，且必须给结论。
        val gate = PtsGate()
        repeat(64) { mustNotThrow("单调 PTS 链") { gate.checkVideo(it * 40_000L) } }
        assertTrue(mustNotThrow("单调链后插入 Long.MAX_VALUE") {
            gate.checkVideo(Long.MAX_VALUE)
        } is PtsVerdict.Reject)
    }

    @Test
    fun remuxGuardNumericExtremesNeverCrashOrSilentlyPass() {
        mustNotThrow("sliceWindowCount") { RemuxAudioGuard.sliceWindowCount(Long.MAX_VALUE) }
        mustNotThrow("sliceWindowIndex") { RemuxAudioGuard.sliceWindowIndex(Long.MIN_VALUE, Long.MAX_VALUE) }
        mustNotThrow("timeSliceGate") {
            RemuxAudioGuard.timeSliceGate(Long.MIN_VALUE, Long.MAX_VALUE, mapOf(Int.MAX_VALUE to 1))
        }
        mustNotThrow("contentRatioGate") { RemuxAudioGuard.contentRatioGate(Long.MAX_VALUE, Long.MAX_VALUE) }
        mustNotThrow("avToleranceUs") { RemuxAudioGuard.avToleranceUs(Long.MAX_VALUE) }
        mustNotThrow("auPtsUs") { AdtsFrameScanner.auPtsUs(Long.MAX_VALUE, Int.MAX_VALUE, 48_000) }
        // Long 加减法溢出后，「明显对不齐」的音视频末尾不能被算成通过。
        val verdict = mustNotThrow("avDurationGate 溢出") {
            RemuxAudioGuard.avDurationGate(Long.MAX_VALUE, Long.MIN_VALUE, 0L)
        }
        assertEquals(
            "极值差值必须判成对不齐，不能静默放行坏文件",
            RemuxAudioGuard.OUTPUT_AV_DURATION_MISMATCH,
            verdict,
        )
    }

    // ---------------------------------------------------- 4. URL 模糊测试

    @Test
    fun percentEncodedStormsAreRejectedByEveryAppSideValidator() {
        val urls = listOf(
            "https://example.com/" + "%".repeat(10_000),
            "%00%0d%0a%00",
            "https://example.com/%".repeat(200),
            "https://example.com/" + "%2".repeat(5_000),
            "https://example.com/%GG%ZZ%",
        )
        urls.forEach { raw ->
            assertNull("带路径的畸形 URL 不能当引擎地址", mustNotThrow("百分号风暴 normalize") {
                EngineUrl.normalize(raw)
            })
            mustNotThrow("百分号风暴 toHttpUrlOrNull") { raw.toHttpUrlOrNull() }
            mustNotThrow("百分号风暴 清单 baseUrl") {
                HlsPlaylistParser.parse("#EXTM3U\n#EXTINF:4,\nseg.ts\n", raw)
            }
            assertTrue("畸形 URL 的导航必须被拦", mustNotThrow("百分号风暴 导航闸门") {
                NavigationPolicy.shouldBlock(raw, PAGE_URL, false, false)
            })
        }
    }

    @Test
    fun crlfInjectionIsBlockedAndNeverLeaksIntoRequestHeaders() {
        val injected = listOf(
            "https://example.com/a\r\nSet-Cookie: sid=1",
            "https://example.com/\nHost: evil.test",
            "https://example.com/a\u0000b",
            "https://example.com/\r\n\r\nHTTP/1.1 200 OK",
            "https://example.com/\u0007\u001b",
        )
        injected.forEach { raw ->
            mustNotThrow("CRLF 注入 normalize") { EngineUrl.normalize(raw) }
            mustNotThrow("CRLF 注入 toHttpUrlOrNull") { raw.toHttpUrlOrNull() }
            mustNotThrow("CRLF 注入 映射") {
                LocalSniffMapping.toResult(raw, PAGE_URL, "UA", emptyMap(), "now")
            }
            // 把恶意串当 pageUrl：referer 是真正会被塞进 HTTP 头的地方。
            val result = mustNotThrow("CRLF 注入 referer") {
                LocalSniffMapping.toResult("https://cdn.example.com/v.mp4", raw, "UA", emptyMap(), "now")
            }
            result?.headersHint?.forEach { (name, value) ->
                assertTrue("头名不得含控制字符：$name", name.none { it < ' ' || it == '\u007F' })
                assertTrue("头值不得含 CRLF/控制字符：$value", value.none { it < ' ' || it == '\u007F' })
            }
        }
    }

    @Test
    fun controlCharactersAndSurrogatesAreRejectedByFormatValidators() {
        val chars = listOf(
            '\u0000', '\u0001', '\u0007', '\u0008', '\u000B', '\u000C',
            '\u001B', '\u007F', '\uFFFD', '\u200B', '\uD800', '\uDFFF', '\uFEFF',
        )
        chars.forEach { c ->
            val raw = "http://127.0.0.1:8730$c"
            val normalized = mustNotThrow("控制字符 normalize") { EngineUrl.normalize(raw) }
            normalized?.let {
                assertTrue("归一化结果不得把控制字符带进地址", it.none { x -> x < ' ' || x == '\u007F' })
            }
            mustNotThrow("控制字符 toHttpUrlOrNull") { raw.toHttpUrlOrNull() }
            mustNotThrow("控制字符 导航闸门") { NavigationPolicy.shouldBlock(raw, PAGE_URL, false, false) }
            mustNotThrow("控制字符 清单 baseUrl") {
                HlsPlaylistParser.parse("#EXTM3U\n#EXTINF:4,\nseg.ts\n", raw)
            }
        }
    }

    @Test
    fun rejectedUrlsHaveAnExplicitCodeAndNeverForgeAProtocolFailure() {
        assertEquals("链接格式不正确", ErrorCode.INVALID_URL.message)
        assertEquals(ErrorCategory.INPUT, ErrorCode.INVALID_URL.category)
        assertFalse("格式错误不可重试", ErrorCode.INVALID_URL.retryable)
        assertEquals("仅支持 http/https 链接", ErrorCode.UNSUPPORTED_SCHEME.message)
        assertEquals(ErrorCategory.SECURITY, ErrorCode.SSRF_BLOCKED.category)
        assertFalse("SSRF 拒绝不可重试", ErrorCode.SSRF_BLOCKED.retryable)

        val failure = LocalFailures.unsupportedLink("恶意 URL")
        assertEquals(LocalReason.UNSUPPORTED_LINK, failure.reason)
        // 静态类型抬到 sealed 接口再断言：直接用 BackendFailure.Local 会被编译器判成恒 false。
        val asBase: BackendFailure = failure
        assertTrue(asBase is BackendFailure.Local)
        assertFalse("客户端本地判定不能伪造协议码", asBase is BackendFailure.Protocol)
        assertTrue(failure.message.contains("客户端本地判定"))
    }

    @Test
    fun randomizedUrlFuzzNeverCrashesAnyValidator() {
        val gen = FuzzSource(M17_SEED + 7)
        repeat(RANDOM_ROUNDS) {
            val raw = gen.evilUrl()
            mustNotThrow("随机 URL normalize") { EngineUrl.normalize(raw) }
            mustNotThrow("随机 URL toHttpUrlOrNull") { raw.toHttpUrlOrNull() }
            val result = mustNotThrow("随机 URL 映射") {
                LocalSniffMapping.toResult(raw, PAGE_URL, "UA", emptyMap(), "now")
            }
            result?.headersHint?.forEach { (_, value) ->
                assertTrue("随机 URL 不得把 CRLF 带进头值：$value", value.none { it < ' ' })
            }
            mustNotThrow("随机 URL 导航闸门") { NavigationPolicy.shouldBlock(raw, PAGE_URL, false, false) }
            mustNotThrow("随机 URL 当清单 baseUrl") {
                HlsPlaylistParser.parse("#EXTM3U\n#EXTINF:4,\nseg.ts\n", raw)
            }
            mustNotThrow("随机 URL 当清单里的切片行") {
                HlsPlaylistParser.parse("#EXTM3U\n#EXTINF:4,\n$raw\n", BASE_URL)
            }
        }
    }

    /** 与 AdtsFrameScannerTest 同源的造头方式：7 字节头、AAC-LC、44.1kHz。 */
    private fun adts(frameLength: Int): ByteArray {
        val bytes = ByteArray(frameLength) { 0x21 }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xF1.toByte()
        bytes[2] = ((1 shl 6) or (4 shl 2)).toByte()
        bytes[3] = ((frameLength shr 11) and 0x03).toByte()
        bytes[4] = ((frameLength shr 3) and 0xFF).toByte()
        bytes[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        bytes[6] = 0x00
        return bytes
    }
}
