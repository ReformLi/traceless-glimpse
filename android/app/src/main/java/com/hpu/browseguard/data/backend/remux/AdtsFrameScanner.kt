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

    private val SAMPLE_RATES_HZ = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    )

    /**
     * 从 `buffer` 开头连续走 ADTS 头，返回完整帧的个数。
     *
     * 走到不像 ADTS 的地方就停：TS 的 PES 载荷是干净的 ADTS 串，中途断了说明后半截不可信，
     * 已数出来的帧仍然是真实存在的音频内容，宁可少数也不要臆造。
     * 返回 0 表示「这一路根本没法按 ADTS 解释」，调用方必须当未知，不能拿它当判据。
     *
     * 走法与 [frameAt] 完全一致——拆分重封用的就是它，两边若走出不同的数，
     * [RemuxAudioGuard.auCountGate] 会当场把这种自相矛盾判成降级。
     */
    fun countFrames(buffer: ByteBuffer, size: Int): Int {
        var offset = 0
        var frames = 0
        while (true) {
            val frame = frameAt(buffer, offset, size) ?: break
            offset = frame.nextOffset
            frames++
        }
        return frames
    }

    /** 第一帧头的采样率索引；不是 ADTS 就返回 0，调用方再退回容器给的 `KEY_SAMPLE_RATE`。 */
    fun sampleRateHz(buffer: ByteBuffer): Int {
        // M17：syncWordOk 只保证前 2 字节可读，采样率索引却在第 3 字节。
        // 只有「FF F1」两字节的缓冲若不先挡住，get(2) 会直接越界。
        if (buffer.limit() < 3) return 0
        if (syncWordOk(buffer, 0)) {
            val index = (buffer.get(2).toInt() and 0xFF) shr 2 and 0x0F
            if (index < SAMPLE_RATES_HZ.size) return SAMPLE_RATES_HZ[index]
        }
        return 0
    }

    /**
     * `offset` 处那一帧的边界；不是 ADTS、头里的长度自相矛盾、或整帧越过 [size] 都返回 null。
     *
     * 拆分重封靠它：TS 的音频载荷是干净的 ADTS 串，按这里给出的区间剥掉头，
     * 就是 mp4a 要求的「一个样本一个 AU」。返回的 [AuFrame.nextOffset] 直接当下一个 `offset` 用，
     * 走帧与计数共用同一套边界，不会出现「数出来 15 帧、只拆出 14 帧」这种自相矛盾。
     */
    fun frameAt(buffer: ByteBuffer, offset: Int, size: Int): AuFrame? {
        // M17 极值防御：size 是调用方声明的可读长度，缓冲本身可能更小，取交集；
        // 且一律用减法比较——`offset + 6` 在 Int.MAX_VALUE 处会回绕成负数，
        // 绕过边界判断后 buffer.get(offset) 直接抛 IndexOutOfBoundsException。
        val limit = minOf(size, buffer.limit())
        // 一帧至少要有 6 字节的头才谈得上长度；剩下不到 6 字节就不可能是完整帧。
        if (offset < 0 || limit < 6 || offset > limit - 6) return null
        val headerSize = headerSizeAt(buffer, offset)
        if (headerSize == 0) return null
        val frameLength = frameLengthAt(buffer, offset)
        if (frameLength < headerSize || offset > limit - frameLength) return null
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

    private fun syncWordOk(buffer: ByteBuffer, offset: Int): Boolean {
        // 减法比较：offset 靠近 Int.MAX_VALUE 时 `offset + 2` 会溢出成负数。
        if (offset < 0 || offset > buffer.limit() - 2) return false
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
