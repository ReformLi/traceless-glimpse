package com.hpu.browseguard

import com.hpu.browseguard.data.backend.hls.Aes128SegmentDecryptor
import com.hpu.browseguard.data.backend.hls.HlsKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HLS AES-128 解密器的协议钉子。
 *
 * 这里的密文全部由本机 `openssl enc -aes-128-cbc` 生成，明文写死在断言里：
 * ① 未给 `IV` 时序号必须是 16 字节**大端序**；② 规范里的「不填充」不能用
 * `PKCS5Padding` 解；③ 有些服务端仍会补 PKCS7，要能裁掉且不误伤已对齐的 TS。
 */
class Aes128SegmentDecryptorTest {

    private val key = hex("00112233445566778899aabbccddeeff")

    /** 752 = 188 × 4，是同时满足 TS 包长与 AES 块长的最小常见分片尺寸。 */
    private val tsPlainHex = hex(
        "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c939aa1a8afb6bdc4cbd2d9e0e7eef5fc030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d141b222930373e454c535a61686f767d848b9299a0a7aeb5bcc3cad1d8dfe6edf4fb020910171e252c333a41484f565d646b727980878e959ca3aab1b8bfc6cdd4dbe2e9f0f7fe050c131a21282f363d444b525960676e757c838a91989fa6adb4bbc2c9d0d7dee5ecf3fa01080f161d242b323940474e555c636a71787f868d949ba2a9b0b7bec5ccd3dae1e8eff6fd040b121920272e353c434a51585f666d747b828990979ea5acb3bac1c8cfd6dde4ebf2f900070e151c232a31383f464d545b626970777e858c",
    )

    /** openssl `-nopad`，IV 取序号 5 的大端序；解出来必须与明文逐字节相等。 */
    private val tsCipherHex = hex(
        "8161666f77cd1752d5b706b8d850ec55b2e4b76c8be58edac9ab5be779f734bb858b72562cc2b6bc98840ec3bbf301bcb5f411793f9f79b03676c0ff3032227a916f571da261cfcfaa55344664c1c28c9ecc91523cca8eed4c9147f1db4827af3529e7fdab9d11c67bbf99bca1a30a7cfc11d168e6c5e2f3ecae2377b1c383d62e755ec8779525032447fde4e089cb55644137398d7f15c13ca542d16bc66960f19dafdaa8b5103fa860d6a35097d2fb62dfbad8160baf48620f57ffba39fc8b272ec6d103c7c81b755a7f53ccc7e4fa1305cae0e898e8fa27a6c6f4ddcfd420c8230f2e52209a070f750b7f4c7fc60289566547a258b39d7fed457fa228284513197e73a7a9082fa421814a53f26a2c6e8af31e637bef819bad075320dff655f3ac908f39704648c482e17f3887f0da496bc478068c6f4c79a4b1f4c0c67ec8306b2894cc23a195c36177f3b8c5fd24e0e153b3e00cebe759e128847c05606bb2afedb3e8b77bf7a51a0c3ac3e94a0f1f2089859953dcb7efe75813ce76ea3114f8288913701fe1951a1bf1d313be86ebd7157ac6b98b04a2ee3ae25ad426200e39fce00a0405dcd64b12f0a475492f4f825a798914a0ebfcbcda16aa6f3af3dbe93bfcb198d2e9b52652cba3377b80bd7e2045b79b37b4f77328c14a162243c2079e0f0edd73933737fc80adb6d9a7aaadf9df19269d937beeb3a12aca27d1d0cf1d87b73a02909cc6540b9ed420ab37b337ac56778daab28ebbe297c66758f4ba519116e68110bd70ea6c924299998fa0a18f54906e24891cdc936636215e453fbb448fcf006204cae236bb1bbd4e70dcee38c152b060e308a9a722cae3c2f80a7034d6ed9d09d6ce4858acdcc9ee809694f5ea31b90593b0a1af3ff8a9ac8b75022147390d6aff4e5fdd30b05056ec349e2ce50ecdf71284828c6ddd6bb0034096b9aa4506f5785d986eb778ce995aa6fa2dd07b0e6814be2588c91b3fdf010329df1a631421aba3bca5f7355a8552b07279e1c07f9391de9a5141bd47357a914b51583ef6d0c2f3f4570f1696db",
    )

    /** openssl 默认 PKCS7：200 字节明文补到 208，解密后必须裁回 200。 */
    private val paddedCipherHex = hex(
        "dd747e0933755cd2f67965d36225a8060cc8bf17cf830192ba2fd4bdeeb51ba53ea777c4a3b25db27127d5f459038201770b22cb6256b59a5d220c38eea462f1ea8f03c0ab15ba001de8fec67a7ad5fa93d0ad6f69387e189e732f0abe509e84e27e8a67792ae3c9d63424aa74e3dcc74d78732cff940aca089ad6cf25531585ab9f74284d87ec522ff067d2b0bf691da9854f21d4bc2bc30f85311835602e1847c909742aa258191a2bfd1a1713ef7a2a476d214680c83de4fad759ed2f046673acbf472d5523cdbc29d54a8f71f43a",
    )

    private val paddedPlainHex = hex(
        "05101b26313c47525d68737e89949faab5c0cbd6e1ecf7020d18232e39444f5a65707b86919ca7b2bdc8d3dee9f4ff0a15202b36414c57626d78838e99a4afbac5d0dbe6f1fc07121d28333e49545f6a75808b96a1acb7c2cdd8e3eef9040f1a25303b46515c67727d88939ea9b4bfcad5e0ebf6010c17222d38434e59646f7a85909ba6b1bcc7d2dde8f3fe09141f2a35404b56616c77828d98a3aeb9c4cfdae5f0fb06111c27323d48535e69747f8a95a0abb6c1ccd7e2edf8030e19242f3a45505b66717c8792",
    )

    @Test
    fun missingIvFallsBackToBigEndianSequence() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8),
            Aes128SegmentDecryptor.ivForSequence(0x0102030405060708),
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5),
            Aes128SegmentDecryptor.ivForSequence(5),
        )
    }

    @Test
    fun explicitIvWinsAndShortHexIsLeftPadded() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5),
            Aes128SegmentDecryptor.parseIv("0x5"),
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7),
            Aes128SegmentDecryptor.parseIv("0X01020304050607"),
        )
        assertNull("超过 16 字节不该猜", Aes128SegmentDecryptor.parseIv("00".repeat(17)))
        assertNull("非十六进制不该猜", Aes128SegmentDecryptor.parseIv("zz"))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 9),
            Aes128SegmentDecryptor.ivFor(HlsKey("AES-128", "k", ivHex = "0x09"), sequence = 5),
        )
        assertArrayEquals(
            Aes128SegmentDecryptor.ivForSequence(5),
            Aes128SegmentDecryptor.ivFor(HlsKey("AES-128", "k", ivHex = null), sequence = 5),
        )
    }

    @Test
    fun decryptsOpenSslNoPaddingVector() {
        val plain = Aes128SegmentDecryptor.decrypt(
            tsCipherHex,
            key,
            Aes128SegmentDecryptor.ivForSequence(5),
        )
        assertNotNull("规范里的不填充必须能解", plain)
        assertEquals(752, plain!!.size)
        assertArrayEquals(tsPlainHex, plain)
    }

    @Test
    fun stripsPkcs7ThatSomeServersStillAdd() {
        val plain = Aes128SegmentDecryptor.decrypt(
            paddedCipherHex,
            key,
            Aes128SegmentDecryptor.ivForSequence(7),
        )
        assertNotNull(plain)
        assertEquals("填充字节要裁干净，否则拼出来的 TS 尾部是脏数据", 200, plain!!.size)
        assertArrayEquals(paddedPlainHex, plain)
    }

    @Test
    fun tsAlignedDataIsNeverMistakenForPadding() {
        val aligned = ByteArray(752).also { it[751] = 0x01; it[750] = 0x01 }
        assertArrayEquals("188 对齐的明文原样返回，不猜填充", aligned, Aes128SegmentDecryptor.withoutTrailingPadding(aligned))
    }

    @Test
    fun malformedInputReturnsNullInsteadOfThrowing() {
        val iv = Aes128SegmentDecryptor.ivForSequence(1)
        assertNull("不是 16 的整数倍", Aes128SegmentDecryptor.decrypt(ByteArray(20), key, iv))
        assertNull("空密文", Aes128SegmentDecryptor.decrypt(ByteArray(0), key, iv))
        assertNull("密钥长度不对", Aes128SegmentDecryptor.decrypt(tsCipherHex, ByteArray(8), iv))
        assertNull("IV 长度不对", Aes128SegmentDecryptor.decrypt(tsCipherHex, key, ByteArray(8)))
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
