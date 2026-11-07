package com.hpu.browseguard.data.backend.remux

/**
 * MPEG-TS 里的 AAC 是 ADTS 封装；MP4 的 `esds` 要的是 AudioSpecificConfig。
 * `ffmpeg -bsf:a aac_adtstoasc` 干的就是这一步，平台链路里由 `csd-0` 承担。
 *
 * 只在 `MediaExtractor` 没给 `csd-0` 时兜底用；显式采样率（index 15）在 2 字节里写不下，
 * 直接放弃，让调用方降级保留 `.ts`。
 */
internal object AdtsAudioConfig {

    fun fromAdtsHeader(sample: ByteArray, size: Int): ByteArray? {
        // M17：size 由调用方给，谎报一个大于数组的长度会让 sample[3] 越界读。
        if (size < 7 || size > sample.size) return null
        val b0 = sample[0].toInt() and 0xFF
        val b1 = sample[1].toInt() and 0xFF
        // syncword 12 bit + layer 必须是 00
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
        val b2 = sample[2].toInt() and 0xFF
        val b3 = sample[3].toInt() and 0xFF
        val objectType = ((b2 shr 6) and 0x03) + 1
        val frequencyIndex = (b2 shr 2) and 0x0F
        if (frequencyIndex == 0x0F) return null
        val channels = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
        // 5 bit object type | 4 bit sampling index | 4 bit channel config | 3 bit zero
        val config = (objectType shl 11) or (frequencyIndex shl 7) or (channels shl 3)
        return byteArrayOf((config shr 8).toByte(), config.toByte())
    }
}
