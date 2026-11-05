package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.AdtsFrameScanner
import com.hpu.browseguard.data.backend.remux.AudioScan
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADTS 帧计数与音频事实扫描：这是「声音到底有几秒」的唯一绝对量。
 *
 * 样本数说不了话（`MediaExtractor` 会把多帧并成一个样本），moov 头部时长会说谎
 * （底层能把 2611 帧的音频标成 862 秒），只有按 ADTS 头一帧一帧走出来的数不受这两件事影响。
 */
class AdtsFrameScannerTest {

    @Test
    fun oneAdtsFrameCountsAsOneFrame() {
        assertEquals(1, AdtsFrameScanner.countFrames(bufferOf(adts(frameLength = 40)), 40))
    }

    @Test
    fun concatenatedFramesAreCountedOneByOne() {
        val run = adts(30) + adts(30) + adts(30) + adts(48)
        assertEquals("并包样本要说清里面装了几帧，488 个样本铺满 79 秒就是这么来的", 4, AdtsFrameScanner.countFrames(bufferOf(run), run.size))
        val withCrc = adts(30, headerBytes = 9) + adts(30, headerBytes = 9)
        assertEquals("带 CRC 的 9 字节头也要按各自的长度走", 2, AdtsFrameScanner.countFrames(bufferOf(withCrc), withCrc.size))
    }

    @Test
    fun aTruncatedTailIsNotCountedAsContent() {
        val run = adts(30) + adts(30) + adts(30).copyOf(12)
        assertEquals("最后一帧不完整就不算声音，宁可少数", 2, AdtsFrameScanner.countFrames(bufferOf(run), run.size))
    }

    @Test
    fun aFrameLengthBeyondTheBufferStopsTheWalk() {
        val lying = adts(30).also { it[3] = 0x03; it[4] = 0.toByte(); it[5] = 0.toByte() }
        assertEquals("头里宣称的帧长超出缓冲，整段当不可信", 0, AdtsFrameScanner.countFrames(bufferOf(lying), lying.size))
    }

    @Test
    fun nonAdtsPayloadCountsZeroFrames() {
        val noise = ByteArray(600) { (it * 7 % 251).toByte() }
        assertEquals("认不出来就是未知，不能臆造帧数", 0, AdtsFrameScanner.countFrames(bufferOf(noise), noise.size))
        assertEquals(0, AdtsFrameScanner.sampleRateHz(bufferOf(noise)))
    }

    @Test
    fun theSampleRateComesFromTheAdtsIndexTable() {
        assertEquals(44100, AdtsFrameScanner.sampleRateHz(bufferOf(adts(30, sampleRateIndex = 4))))
        assertEquals(48000, AdtsFrameScanner.sampleRateHz(bufferOf(adts(30, sampleRateIndex = 3))))
        assertEquals(11025, AdtsFrameScanner.sampleRateHz(bufferOf(adts(30, sampleRateIndex = 10))))
        assertEquals("保留索引不当成采样率", 0, AdtsFrameScanner.sampleRateHz(bufferOf(adts(30, sampleRateIndex = 13))))
        assertEquals("空缓冲没有头可查", 0, AdtsFrameScanner.sampleRateHz(bufferOf(ByteArray(1))))
    }

    /** 帧长 1 帧的 44.1kHz AAC = 1024/44100 秒 ≈ 23 毫秒。 */
    @Test
    fun framesBecomeTheDurationTheyCanActuallyPlay() {
        val run = adts(30) + adts(30) + adts(30)
        val scan = AudioScan().apply { add(0L, run.size, bufferOf(run)) }
        assertEquals(3, scan.frames)
        assertEquals(44100, scan.sampleRateHz)
        assertEquals(69L, scan.contentMs)
        assertEquals(run.size.toLong(), scan.bytes)
        assertEquals("一个样本并了三帧：字节数说不了时长，帧数才能", 90, scan.firstSampleBytes)
    }

    @Test
    fun theContainerRateIsUsedWhenTheAdtsHeaderIsNotReadable() {
        val scan = AudioScan().apply {
            seedSampleRateHz(16000)
            add(0L, 20, bufferOf(adts(20, sampleRateIndex = 4)))
        }
        assertEquals("已经由容器垫过底就不再查 ADTS，取值以轨道声明为准", 16000, scan.sampleRateHz)
        assertEquals(64L, scan.contentMs)
    }

    @Test
    fun noFramesMeansUnknownContentNotSilence() {
        val scan = AudioScan().apply {
            seedSampleRateHz(44100)
            add(0L, 4, bufferOf(ByteArray(4)))
        }
        assertEquals("帧数为 0 是「认不出」，不能当「没有声音」的判据", 0, scan.frames)
        assertEquals(0L, scan.contentMs)
        assertEquals(1, scan.samples)
    }

    @Test
    fun theScanRemembersTheLargestHoleBetweenNeighbouringSamples() {
        val frame = adts(30)
        val scan = AudioScan()
        scan.add(0L, frame.size, bufferOf(frame))
        scan.add(1_000_000L, frame.size, bufferOf(frame))
        scan.add(12_000_000L, frame.size, bufferOf(frame))
        scan.add(13_000_000L, frame.size, bufferOf(frame))
        assertEquals("1 秒跳到 12 秒：中间空了 11 秒，这就是注水的时间戳", 11_000_000L, scan.maxGapUs)
        assertEquals(11_000L, scan.maxGapMs)
        assertEquals(0L, scan.firstPtsUs)
        assertEquals(13_000_000L, scan.lastPtsUs)
        assertEquals(13_000L, scan.spanMs)
        assertEquals(4, scan.samples)
    }

    @Test
    fun theFirstSampleOfAnEmptyScanIsReportedAsAbsent() {
        val scan = AudioScan()
        assertEquals(-1, scan.firstSampleBytes)
        assertEquals(0L, scan.maxGapUs)
        assertEquals(0L, scan.spanMs)
    }

    @Test
    fun theAuditLineSeparatesSourceFromOutputWithPrefixes() {
        val frame = adts(30)
        val scan = AudioScan().apply { add(500_000L, frame.size, bufferOf(frame)) }
        val map = scan.auditMap("src")
        assertEquals(
            setOf("srcSamples", "srcBytes", "srcFrames", "srcRateHz", "srcContentMs", "srcSpanMs", "srcMaxGapMs"),
            map.keys,
        )
        assertEquals("30", map["srcBytes"])
        assertEquals("1", map["srcFrames"])
        assertEquals("0", map["srcSpanMs"])
        assertEquals("0", map["srcMaxGapMs"])
        assertAuditValuesAreNumbers(map)
    }

    @Test
    fun theShapeWalkMeasuresHowThickEachFrameIs() {
        val run = adts(40) + adts(40, headerBytes = 9) + adts(64)
        val shape = AdtsFrameScanner.scanShape(bufferOf(run), run.size)
        assertEquals("形状走的必须是同一条帧边界", 3, shape.frames)
        assertEquals(40, shape.minFrameLen)
        assertEquals(64, shape.maxFrameLen)
        assertEquals("真内容的帧不该薄于 32 字节", 0, shape.tinyFrames)
        assertTrue("载荷开头就是 ADTS 同步字（TS 里正常，MP4 里就是并包旁证）", shape.startsWithAdts)
        assertEquals("ADTS 的 profile 字段是 objectType 减一，AAC-LC 该读到 2", 2, shape.objectType)
        assertEquals(0, shape.channelConfig)
    }

    @Test
    fun thinFramesAreCountedSeparatelyFromThickOnes() {
        val run = adts(AdtsFrameScanner.TINY_FRAME_BYTES - 1) + adts(120)
        val shape = AdtsFrameScanner.scanShape(bufferOf(run), run.size)
        assertEquals(2, shape.frames)
        assertEquals("静音/填充帧才会薄到 32 字节以下", 1, shape.tinyFrames)
        assertEquals(31, shape.minFrameLen)
        assertEquals(120, shape.maxFrameLen)
    }

    @Test
    fun aPayloadThatDoesNotStartWithAdtsIsNotClaimedTo() {
        val noise = ByteArray(600) { (it * 7 % 251).toByte() }
        val shape = AdtsFrameScanner.scanShape(bufferOf(noise), noise.size)
        assertEquals(0, shape.frames)
        assertEquals("没有帧就没有帧长可报，别写成 0 字节帧", 0, shape.minFrameLen)
        assertFalse("裸 AAC 或视频数据混进来时这条要能自证", shape.startsWithAdts)
    }

    @Test
    fun theChannelConfigComesOutOfTheSameHeaderAsTheObjectTypeId() {
        val shape = AdtsFrameScanner.scanShape(bufferOf(adts(40, channelConfig = 2)), 40)
        assertEquals("单声道/双声道是从 ADTS 头读的，不是猜的", 2, shape.channelConfig)
        assertEquals(2, shape.objectType)
        assertEquals("编码版本位 0 是 MPEG-4：AAC-LC 就该读到它", 0, shape.mpegVersion)
    }

    @Test
    fun aScanWithNoFramesReportsTheVersionBitAsUnknown() {
        val noise = ByteArray(60) { (it * 7 % 251).toByte() }
        assertEquals("没数出帧就是不知道，不能默认写成 MPEG-4 或 MPEG-2", -1, AdtsFrameScanner.scanShape(bufferOf(noise), noise.size).mpegVersion)
    }

    @Test
    fun theShapeLineSaysHowManyFramesSatInsideOneSample() {
        val run = adts(30) + adts(30) + adts(30)
        val scan = AudioScan().apply {
            add(0L, run.size, bufferOf(run))
            seedConfig(byteArrayOf(0x12, 0x10), 2, 2)
        }
        val map = scan.shapeMap("src")
        assertEquals("3 帧挤在 1 个样本里 = 每样本 3.00 帧（放大 100 倍存成整数）", "300", map["srcFramesPerSampleX100"])
        assertEquals("3", map["srcAuPerSampleMax"])
        assertEquals("1", map["srcAdtsStartSamples"])
        assertEquals("2", map["srcObjType"])
        assertEquals("1210", map["srcCsdHex"])
        assertEquals("2", map["srcChannels"])
        assertEquals(
            "形状行只报能确定解读的位：跨字节、各家写法不一的字段（如裸数据块数）不进审计行",
            setOf(
                "srcFramesPerSampleX100", "srcAuPerSampleMax", "srcFrameLenMin", "srcFrameLenMax",
                "srcTinyFrames", "srcAdtsStartSamples", "srcObjType", "srcChanCfg", "srcMpegVer",
                "srcAacProfile", "srcChannels", "srcCsdHex",
            ),
            map.keys,
        )
    }

    @Test
    fun aTrackWithoutCsdIsPrintedAsNoneRatherThanBlank() {
        val map = AudioScan().apply { seedConfig(null, -1, 0) }.shapeMap("out")
        assertEquals("none", map["outCsdHex"])
        assertEquals("-1", map["outAacProfile"])
        assertEquals("空扫描不该编出帧数", "0", map["outFramesPerSampleX100"])
    }

    /** 帧边界要能单独问出来：拆分重封靠它，剥的就是这里给的那几个字节。 */
    @Test
    fun frameAtHandsBackThePayloadBoundsOfOneAdtsFrame() {
        val frame = adts(40)
        val at = AdtsFrameScanner.frameAt(bufferOf(frame), 0, frame.size)!!
        assertEquals("无 CRC 的 7 字节头：载荷从第 7 字节开始", 7, at.payloadStart)
        assertEquals("载荷长度 = 整帧减去头", 33, at.payloadSize)
        assertEquals("下一帧正好从整帧末尾开始", 40, at.nextOffset)

        val withCrc = adts(40, headerBytes = 9)
        val crcAt = AdtsFrameScanner.frameAt(bufferOf(withCrc), 0, withCrc.size)!!
        assertEquals("带 CRC 的 9 字节头要多剥 2 字节", 9, crcAt.payloadStart)
        assertEquals(31, crcAt.payloadSize)
    }

    @Test
    fun frameAtRefusesAnythingThatIsNotAWholeFrame() {
        val truncated = adts(40).copyOf(39)
        assertNull("最后一帧不完整就不给边界", AdtsFrameScanner.frameAt(bufferOf(truncated), 0, truncated.size))
        assertNull("不足 6 字节连头都读不出来", AdtsFrameScanner.frameAt(bufferOf(adts(40)), 0, 5))

        val lying = adts(40).also { it[3] = 0x03; it[4] = 0.toByte(); it[5] = 0.toByte() }
        assertNull("头里宣称的帧长越界就整段作废", AdtsFrameScanner.frameAt(bufferOf(lying), 0, lying.size))
        assertNull("不是 ADTS 就不给边界", AdtsFrameScanner.frameAt(bufferOf(ByteArray(60) { 0x7F }), 0, 60))
    }

    /** 数帧和拆帧必须是同一条走法，否则「数出来 15 帧、只拆出 14 帧」这种自相矛盾会直接打脸 AU 数门禁。 */
    @Test
    fun theWalkUsedForCountingIsTheSameOneUsedForSplitting() {
        val run = adts(40) + adts(64) + adts(30, headerBytes = 9) + adts(120)
        val buffer = bufferOf(run)
        var offset = 0
        var walked = 0
        while (true) {
            val frame = AdtsFrameScanner.frameAt(buffer, offset, run.size) ?: break
            offset = frame.nextOffset
            walked++
        }
        assertEquals(run.size, offset)
        assertEquals(AdtsFrameScanner.scanShape(buffer, run.size).frames, walked)
    }

    /**
     * AU 的时间戳按 1024/采样率 步进。这里必须是「从基准一次换算」而不是逐帧累加取整：
     * 48kHz 下步进 21333.33… 微秒，逐帧取整攒到 4 万帧就短 13.5 毫秒，末尾对不齐是自家造出来的。
     */
    @Test
    fun auTimestampsStepWithoutAccumulatingRoundingError() {
        val rate = 48_000
        val lastAu = 40_410

        assertEquals(
            "第 40410 帧必须正好落在 40410×1024/48000 秒上",
            862_080_000L,
            AdtsFrameScanner.auPtsUs(0L, lastAu, rate),
        )
        assertEquals("基准时间也要跟着走", 1_000_000L + 21_333L, AdtsFrameScanner.auPtsUs(1_000_000L, 1, rate))
        assertEquals("第 0 帧就是样本自己的时间戳", 5_000L, AdtsFrameScanner.auPtsUs(5_000L, 0, rate))
        assertEquals("认不出采样率就别编时间戳", 5_000L, AdtsFrameScanner.auPtsUs(5_000L, 7, 0))
        assertTrue(
            "步进只能越来越大，绝不能出现相等或倒退",
            (1..lastAu).all { AdtsFrameScanner.auPtsUs(0L, it, rate) > AdtsFrameScanner.auPtsUs(0L, it - 1, rate) },
        )
    }

    @Test
    fun aDeclaredOneAuPerSampleTrackCountsFramesEvenWithoutAdtsHeaders() {
        val bareAac = ByteArray(180) { (it * 13 % 251).toByte() }
        val scan = AudioScan().apply {
            seedSampleRateHz(48_000)
            countOneAuPerSample()
            add(0L, bareAac.size, bufferOf(bareAac))
            add(21_333L, bareAac.size, bufferOf(bareAac))
        }
        assertEquals("裸 AAC 载荷一帧 ADTS 头都没有", 0, scan.adtsStartSamples)
        assertEquals("但按声明的「一样本一 AU」要能换成内容时长", 2, scan.frames)
        assertEquals("两帧就是 2048/48000 秒", 42_666L, scan.contentUs)
        assertEquals("产物审计行要如实写着每样本一帧", 1, scan.maxAuPerSample)
        assertEquals("产物审计行要如实写着每样本一帧", "100", scan.shapeMap("out")["outFramesPerSampleX100"])
    }

    private fun assertAuditValuesAreNumbers(map: Map<String, String>) {
        for ((key, value) in map) {
            assertTrue("$key 要能在审计行里被解析成数字：$value", value.toLongOrNull() != null)
        }
    }

    private fun bufferOf(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes)

    /** 拼一个合法 ADTS 头：帧长按 13 位摊进第 3/4/5 字节，profile 字段写 AAC-LC（objectType 2）。 */
    private fun adts(
        frameLength: Int,
        sampleRateIndex: Int = 4,
        headerBytes: Int = 7,
        channelConfig: Int = 0,
    ): ByteArray {
        require(frameLength >= headerBytes)
        val bytes = ByteArray(frameLength) { 0x7F }
        bytes[0] = 0xFF.toByte()
        bytes[1] = (0xF0 or if (headerBytes == 7) 0x01 else 0x00).toByte()
        bytes[2] = ((1 shl 6) or (sampleRateIndex shl 2) or ((channelConfig shr 2) and 0x01)).toByte()
        bytes[3] = (((frameLength shr 11) and 0x03) or ((channelConfig and 0x03) shl 6)).toByte()
        bytes[4] = ((frameLength shr 3) and 0xFF).toByte()
        bytes[5] = ((((frameLength and 0x07) shl 5) or 0x1F)).toByte()
        bytes[6] = 0x00
        return bytes
    }
}
