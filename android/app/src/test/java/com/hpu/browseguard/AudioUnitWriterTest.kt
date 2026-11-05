package com.hpu.browseguard

import com.hpu.browseguard.data.backend.remux.AudioUnitWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一个样本并了 N 个 ADTS 帧」重封成合规 mp4a 的那段逻辑。
 *
 * 这次真机查出来的病灶不是丢字节，而是**封装形状**：`MediaExtractor` 读 TS 里的 AAC 时每个样本
 * 并了 15.47 帧、载荷还带着 ADTS 头，直接抄进 MP4 后 Android 自己的解码器认，严格播放器只解得出
 * 每样本第一帧——862 秒听感变 55 秒，而时间戳、密度、首尾对齐全绿。所以这里验的是形状：
 * 剥头、一样本一 AU、时间戳按 1024/采样率 铺，一条都不许靠真机才验得出来。
 */
class AudioUnitWriterTest {

    private val emitted = ArrayList<Triple<ByteArray, Long, Int>>()

    private fun writer(rateHz: Int): AudioUnitWriter = AudioUnitWriter(rateHz) { payload, size, ptsUs ->
        // 复用缓冲稍后会覆盖掉内容，取证必须当场克隆。
        val copy = ByteArray(size)
        val at = payload.duplicate()
        at.get(copy)
        assertEquals("交给 muxer 的缓冲必须从位置 0 开始：offset 非 0 时两种缓冲的解释不一致", 0, payload.position())
        assertEquals("剩余可读长度必须正好是声明的 size", size, payload.remaining())
        emitted += Triple(copy, ptsUs, size)
    }

    @Test
    fun oneMergedSampleBecomesOneOutputPerAccessUnit() {
        val run = adts(40) + adts(40) + adts(64)
        val source = ByteBuffer.wrap(run)

        assertEquals("三个 AU 就该写出三个样本", 3, writer(48000).write(source, run.size, 1_000_000L))
        assertEquals("每个样本只装一帧的载荷：33/33/57", listOf(33, 33, 57), emitted.map { it.third })
        emitted.forEach { (payload, _, _) ->
            val head = payload.take(2).joinToString(" ") { String.format("%02X", it) }
            assertTrue("剥完头不许还剩 ADTS 同步字：$head", (payload[0].toInt() and 0xFF) != 0xFF && head != "FF F1")
            assertEquals("载荷就是原帧头之后的那串比特", 0x21.toByte(), payload[0])
        }
    }

    @Test
    fun auTimestampsArePacedByTheFrameDuration() {
        val run = adts(40) + adts(40) + adts(40)
        val source = ByteBuffer.wrap(run)

        assertEquals(3, writer(48000).write(source, run.size, 10_000L))
        // 48kHz 下一帧 1024 个采样点 = 21333µs；由 10ms 起铺。
        assertEquals(listOf(10_000L, 31_333L, 52_666L), emitted.map { it.second })
    }

    /**
     * 时间轴由 AU 序号推导，不跟源样本的 PTS 走。
     *
     * 源里每样本并几帧是打包决定：48kHz 下 15 帧是 320000µs、16 帧是 341333µs，而 extractor 给的
     * 样本间隔按它自己的账本走。跟着源走，并到第 16 帧的那一秒就会越过下一个样本的起点——时间戳倒退，
     * 一条完全正常的流被自己的拆分器判成坏流、整条降级。这里只认第一个样本的起点，之后一律匀铺。
     */
    @Test
    fun theTimelineIsPacedByTheUnitIndexNotByTheSourceSamples() {
        val run = adts(40) + adts(40) + adts(40)
        val source = ByteBuffer.wrap(run)
        val unit = writer(48000)

        assertEquals(3, unit.write(source, run.size, 0L))
        // 下一个源样本的 PTS 比上一条铺到的位置还早（真实流里并包数量抖动就会这样）：不许倒退，也不许留洞。
        assertEquals("基准只在第一个样本被采用，之后由 AU 序号接着铺", 3, unit.write(source, run.size, 31_333L))
        assertEquals(
            listOf(0L, 21_333L, 42_666L, 64_000L, 85_333L, 106_666L),
            emitted.map { it.second },
        )
        assertEquals("对账用的总数就是写出的 AU 数", 6, unit.writtenAus)
    }

    @Test
    fun aSampleThatIsNotAdtsYieldsNothing() {
        val bare = ByteArray(400) { (it * 7 % 251).toByte() }
        val source = ByteBuffer.wrap(bare)

        assertEquals("认不出 ADTS 就一个都别写，交给调用方判拆分失败", 0, writer(48000).write(source, bare.size, 0L))
        assertTrue("没写出样本就不该有载荷", emitted.isEmpty())
    }

    @Test
    fun aTruncatedTailStopsAtTheLastWholeFrame() {
        val run = adts(40) + adts(40) + adts(40).copyOf(20)
        val source = ByteBuffer.wrap(run)

        assertEquals("尾部残帧不算 AU；少写的那一帧由 AU 数门禁抓住", 2, writer(48000).write(source, run.size, 0L))
    }

    @Test
    fun splittingNeverDrainsTheBufferItReads() {
        val run = adts(40) + adts(64)
        val source = ByteBuffer.wrap(run)

        assertEquals(2, writer(48000).write(source, run.size, 0L))
        // 和 csd-0 那次同类的坑：就地读源缓冲会让后面拿到 0 字节。
        assertEquals("拆分不许推进源缓冲的 position", 0, source.position())
        assertEquals("也不许动它的 limit", run.size, source.limit())
    }

    @Test
    fun aFrameThickerThanTheReuseBufferStillGetsOutWhole() {
        // ADTS 的帧长只有 13 位，一帧最多 8191 字节；取一个一定超过起步 4KB 缓冲的值。
        val huge = adts(6000)
        val source = ByteBuffer.wrap(huge)

        assertEquals(1, writer(48000).write(source, huge.size, 0L))
        assertEquals("超出起步容量要按需长大，不能截断", 5993, emitted.single().third)
    }

    /** 与 [AdtsFrameScannerTest] 同一套造头方式：载荷填 0x21，剥完头一眼就能看出还剩没剩头。 */
    private fun adts(frameLength: Int, sampleRateIndex: Int = 3): ByteArray {
        val bytes = ByteArray(frameLength) { 0x21 }
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xF1.toByte()
        bytes[2] = ((1 shl 6) or (sampleRateIndex shl 2)).toByte()
        bytes[3] = ((frameLength shr 11) and 0x03).toByte()
        bytes[4] = ((frameLength shr 3) and 0xFF).toByte()
        bytes[5] = ((((frameLength and 0x07) shl 5) or 0x1F)).toByte()
        bytes[6] = 0x00
        return bytes
    }
}
