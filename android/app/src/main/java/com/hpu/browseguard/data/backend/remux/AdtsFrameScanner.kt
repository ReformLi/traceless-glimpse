package com.hpu.browseguard.data.backend.remux

import java.nio.ByteBuffer

/**
 * ADTS 帧计数：数一段音频缓冲里到底装了几个 AAC 帧。
 *
 * 为什么要有这个：`MediaExtractor` 读 TS 时会把多个 ADTS 帧并成一个样本
 * （实测 488 个样本铺满 79 秒），所以「样本数 × 1024 / 采样率」算不出真实音频长度，
 * 「样本数 × 每样本帧数」又不能假定——只有逐帧走 ADTS 头才知道这儿的音频到底有多少秒。
 * 这是目前唯一不受 moov 头部时长、也不受样本重铺影响的绝对量。
 */
internal object AdtsFrameScanner {

    /** 一个 AAC 帧固定的 PCM 样本数（HE-AAC 的 SBR 不在本项目的处理范围内）。 */
    const val SAMPLES_PER_FRAME = 1024

    /** 帧长小于这个字节数就算「薄帧」：真内容一帧通常上百字节，静音或填充帧才会薄到 32 以下。 */
    const val TINY_FRAME_BYTES = 32

    private val SAMPLE_RATES_HZ = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    /**
     * 从 `buffer` 开头连续走 ADTS 头，返回完整帧的个数。
     *
     * 走到不像 ADTS 的地方就停：TS 的 PES 载荷是干净的 ADTS 串，中途断了说明后半截不可信，
     * 已数出来的帧仍然是真实存在的音频内容，宁可少数也不要臆造。
     * 返回 0 表示「这一路根本没法按 ADTS 解释」，调用方必须当未知，不能拿它当判据。
     */
    fun countFrames(buffer: ByteBuffer, size: Int): Int = scanShape(buffer, size).frames

    /** 第一帧头的采样率索引；不是 ADTS 就返回 0，调用方再退回容器给的 `KEY_SAMPLE_RATE`。 */
    fun sampleRateHz(buffer: ByteBuffer): Int {
        if (syncWordOk(buffer, 0)) {
            val index = (buffer.get(2).toInt() and 0xFF) shr 2 and 0x0F
            if (index < SAMPLE_RATES_HZ.size) return SAMPLE_RATES_HZ[index]
        }
        return 0
    }

    /**
     * 同一条走帧的路，顺手把「形状」记下来：帧长分布、首帧头声明的 objectType/声道/编码版本。
     *
     * 门禁不需要这些，但取证需要——无声文件到底是「源里就编了静音」（帧会小得反常、
     * 或者满屏 tinyFrames）、「一个样本塞了十几个 AU」（[AudioScan] 的每样本帧数），
     * 还是「其实是 HE-AAC 却被当 AAC-LC 封进 MP4」（objectType 不是 2），全靠这几个数字分。
     *
     * 取位和走帧保持一致：都按「无 CRC 的 7 字节头」解释（带 CRC 的流走帧本来就会在中途停下，
     * 计数偏少是安全侧，见 [frameLengthAt]）。`number_of_raw_data_blocks_in_frame` 跨字节、
     * 各家写法不一，取错一位就会把人误引到「这流不是标准 AAC」，所以不报——是不是 HE-AAC
     * 由 `CsdHex` 里 AudioSpecificConfig 的前 5 位说话，那个没有歧义。
     */
    fun scanShape(buffer: ByteBuffer, size: Int): Shape {
        var offset = 0
        var frames = 0
        var minLen = 0
        var maxLen = 0
        var tiny = 0
        var objectType = 0
        var channelConfig = 0
        var mpegVersion = -1
        while (true) {
            val frame = frameAt(buffer, offset, size) ?: break
            val frameLength = frame.nextOffset - offset
            if (frames == 0) {
                val b1 = buffer.get(offset + 1).toInt() and 0xFF
                val b2 = buffer.get(offset + 2).toInt() and 0xFF
                val b3 = buffer.get(offset + 3).toInt() and 0xFF
                mpegVersion = (b1 shr 3) and 0x01
                objectType = ((b2 shr 6) and 0x03) + 1
                channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            }
            minLen = if (minLen == 0) frameLength else minOf(minLen, frameLength)
            if (frameLength > maxLen) maxLen = frameLength
            if (frameLength < TINY_FRAME_BYTES) tiny++
            offset = frame.nextOffset
            frames++
        }
        // 起始就是 ADTS 头 = 载荷带 ADTS（TS 里正常，MP4 里就是「每样本塞多帧」的旁证）。
        return Shape(frames, minLen, maxLen, tiny, headerSizeAt(buffer, 0) > 0, objectType,
            channelConfig, mpegVersion)
    }

    /**
     * `offset` 处那一帧的边界；不是 ADTS、头里的长度自相矛盾、或整帧越过 [size] 都返回 null。
     *
     * 拆分重封靠它：TS 的音频载荷是干净的 ADTS 串，按这里给出的区间剥掉头，
     * 就是 mp4a 要求的「一个样本一个 AU」。返回的 [AuFrame.nextOffset] 直接当下一个 `offset` 用，
     * 走帧与计数共用同一套边界，不会出现「数出来 15 帧、只拆出 14 帧」这种自相矛盾。
     */
    fun frameAt(buffer: ByteBuffer, offset: Int, size: Int): AuFrame? {
        // 一帧至少要有 6 字节的头才谈得上长度；剩下不到 6 字节就不可能是完整帧。
        if (offset < 0 || offset + 6 > size) return null
        val headerSize = headerSizeAt(buffer, offset)
        if (headerSize == 0) return null
        val frameLength = frameLengthAt(buffer, offset)
        if (frameLength < headerSize || offset + frameLength > size) return null
        return AuFrame(offset + headerSize, frameLength - headerSize, offset + frameLength)
    }

    /**
     * 拆分后第 [index] 个 AU 的时间戳：一帧 AAC 固定 [SAMPLES_PER_FRAME] 个采样点，步进就是它除以采样率。
     *
     * 从 [baseUs] 一次换算而不是逐帧累加：48kHz 下步进是 21333.33… 微秒，
     * 逐帧取整会让 4 万帧的尾部漂出几百毫秒，音画末尾对齐那条门禁就这么被自己造出来。
     */
    fun auPtsUs(baseUs: Long, index: Int, sampleRateHz: Int): Long =
        if (sampleRateHz <= 0 || index <= 0) {
            baseUs
        } else {
            baseUs + index.toLong() * SAMPLES_PER_FRAME * 1_000_000L / sampleRateHz
        }

    /** [frameAt] 的返回值：一帧里「真正的 AAC 载荷」在哪、下一帧从哪开始。 */
    internal class AuFrame(val payloadStart: Int, val payloadSize: Int, val nextOffset: Int)

    /** [scanShape] 的返回值；纯数据，构造它不需要 Android 环境。 */
    internal class Shape(
        val frames: Int,
        val minFrameLen: Int,
        val maxFrameLen: Int,
        val tinyFrames: Int,
        val startsWithAdts: Boolean,
        val objectType: Int,
        val channelConfig: Int,
        val mpegVersion: Int,
    )

    private fun syncWordOk(buffer: ByteBuffer, offset: Int): Boolean {
        if (offset + 2 > buffer.limit()) return false
        val b0 = buffer.get(offset).toInt() and 0xFF
        val b1 = buffer.get(offset + 1).toInt() and 0xFF
        return b0 == 0xFF && (b1 and 0xF6) == 0xF0
    }

    /** 一帧的头部长度：ADTS 头合法时 7 或 9 字节，不合法返回 0，让上层停止计数。 */
    private fun headerSizeAt(buffer: ByteBuffer, offset: Int): Int =
        if (!syncWordOk(buffer, offset)) 0
        else if (buffer.get(offset + 1).toInt() and 0x01 == 0) 9 else 7

    /**
     * 整帧长度（含头）；头不完整或长度自相矛盾就返回 0，让上层停止计数。
     *
     * 已知缺口：带 CRC 的 9 字节头里这三个字节位在 +5/+6/+7，本实现固定读 +3/+4/+5，
     * 所以那类流会走一两帧就停、计数偏少。偏少只会让门禁更严（宁可降级保留原格式），
     * 不会放行坏文件，因此不在这里扩；实测广播类 HLS 都不带 CRC，真遇到再按头长平移。
     */
    private fun frameLengthAt(buffer: ByteBuffer, offset: Int): Int {
        val headerSize = headerSizeAt(buffer, offset)
        if (headerSize == 0) return 0
        val b3 = buffer.get(offset + 3).toInt() and 0xFF
        val b4 = buffer.get(offset + 4).toInt() and 0xFF
        val b5 = buffer.get(offset + 5).toInt() and 0xFF
        val length = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)
        return if (length < headerSize) 0 else length
    }
}
