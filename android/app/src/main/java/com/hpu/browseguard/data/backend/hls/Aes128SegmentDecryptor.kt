package com.hpu.browseguard.data.backend.hls

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS AES-128-CBC 分片解密。纯 Kotlin + JCA，没有 Android 依赖，JVM 单测可以逐字节钉住。
 *
 * 三个协议细节决定了这里的写法：
 * ① 清单没给 `IV` 时，IV 是「媒体序号」的 **16 字节大端序**，不是小端、也不是 ASCII；
 * ② 规范要求 CBC **不填充**（TS 分片本身是 16 的倍数），所以用 `NoPadding`——用
 *    `PKCS5Padding` 会在服务端真不填充时抛 `BadPaddingException`，整段下载直接失败；
 * ③ 现实里仍有编码器给分片补 PKCS7，于是解密后再做一次**防御性裁尾**：只有尾部
 *    确实构成合法 PKCS7 且裁完仍是完整 TS 包时才裁，避免误伤正常数据。
 */
internal object Aes128SegmentDecryptor {

    const val KEY_BYTES = 16
    private const val BLOCK_BYTES = 16

    /** TS 包长：非 16 的整数倍，刚好能用来区分「已对齐的明文」和「带填充的明文」。 */
    private const val TS_PACKET_BYTES = 188

    /**
     * 未显式给出 `IV` 时的默认 IV：媒体序号（`EXT-X-MEDIA-SEQUENCE + 分片索引`）
     * 写成 16 字节大端序，序号落在**低 8 字节**，高 8 字节补零。
     *
     * 注意不能写成 `ByteBuffer.allocate(16).putLong(sequence)`：那会把序号写进高 8 字节，
     * 等价于把 128 位整数左移 64 位，解出来就是乱码。
     */
    fun ivForSequence(sequence: Long): ByteArray {
        val iv = ByteArray(BLOCK_BYTES)
        for (index in 0 until 8) {
            iv[BLOCK_BYTES - 1 - index] = ((sequence ushr (8 * index)) and 0xFF).toByte()
        }
        return iv
    }

    /**
     * 解析 `IV=0x...`。允许奇偶长度（奇数按单字符补齐）、允许短于 16 字节（左侧补零），
     * 超过 16 字节或含非十六进制字符则整条拒绝——宁可报「解密失败」，也不拿错 IV 解出乱码。
     */
    fun parseIv(hex: String): ByteArray? {
        val cleaned = hex.removePrefix("0x").removePrefix("0X").trim()
        if (cleaned.isEmpty() || cleaned.length > BLOCK_BYTES * 2) return null
        val nibbles = if (cleaned.length % 2 == 0) cleaned else "0$cleaned"
        val value = ByteArray(nibbles.length / 2) { index ->
            val high = nibbles[index * 2].digitToIntOrNull(16) ?: return null
            val low = nibbles[index * 2 + 1].digitToIntOrNull(16) ?: return null
            ((high shl 4) or low).toByte()
        }
        if (value.size > BLOCK_BYTES) return null
        return if (value.size == BLOCK_BYTES) value else ByteArray(BLOCK_BYTES - value.size) + value
    }

    /** 切片带的密钥规格 → 实际 IV：显式 `IV` 优先，否则按序号推。非法显式 IV 返回 null。 */
    fun ivFor(key: HlsKey, sequence: Long): ByteArray? =
        key.ivHex?.let(::parseIv) ?: ivForSequence(sequence)

    /**
     * 解密一段。密文长度不是 16 的整数倍、密钥不是 16 字节、IV 长度不对都返回 null。
     *
     * 不抛异常是刻意的：调用方只需要「这一段能不能解开」这一个判断。
     */
    fun decrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (key.size != KEY_BYTES || iv.size != BLOCK_BYTES) return null
        if (ciphertext.isEmpty() || ciphertext.size % BLOCK_BYTES != 0) return null
        val plain = try {
            Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }.doFinal(ciphertext)
        } catch (e: Exception) {
            return null
        }
        return withoutTrailingPadding(plain)
    }

    /**
     * 只裁「确定是 PKCS7」的尾字节。
     *
     * `plain.size % 188 == 0` 说明已经是一串完整 TS 包，直接原样返回——这一条同时挡住了
     * 误判：真实 TS 明文的长度不可能在裁掉 1..16 字节后还保持 188 对齐，所以不会被误伤。
     * fMP4 分片没有 188 对齐可依，只能依赖 PKCS7 自身的自校验（尾部 n 个字节都等于 n）。
     */
    fun withoutTrailingPadding(plain: ByteArray): ByteArray {
        if (plain.size % TS_PACKET_BYTES == 0) return plain
        val pad = plain.lastOrNull()?.toInt()?.and(0xFF) ?: return plain
        if (pad !in 1..BLOCK_BYTES || pad > plain.size) return plain
        for (index in plain.size - pad until plain.size) {
            if (plain[index] != pad.toByte()) return plain
        }
        return plain.copyOf(plain.size - pad)
    }
}
