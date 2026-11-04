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
     */
    fun countFrames(buffer: ByteBuffer, size: Int): Int {
        var offset = 0
        var frames = 0
        // 一帧至少要有 6 字节的头才谈得上长度；剩下不到 6 字节就不可能是完整帧。
        while (offset + 6 <= size) {
            val headerSize = headerSizeAt(buffer, offset)
            if (headerSize == 0) break
            val frameLength = frameLengthAt(buffer, offset)
            if (frameLength < headerSize || offset + frameLength > size) break
            offset += frameLength
            frames++
        }
        return frames
    }

    /** 第一帧头的采样率索引；不是 ADTS 就返回 0，调用方再退回容器给的 `KEY_SAMPLE_RATE`。 */
    fun sampleRateHz(buffer: ByteBuffer): Int {
        if (syncWordOk(buffer, 0)) {
            val index = (buffer.get(2).toInt() and 0xFF) shr 2 and 0x0F
            if (index < SAMPLE_RATES_HZ.size) return SAMPLE_RATES_HZ[index]
        }
        return 0
    }

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

    /** 整帧长度（含头）；头不完整或长度自相矛盾就返回 0，让上层停止计数。 */
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
