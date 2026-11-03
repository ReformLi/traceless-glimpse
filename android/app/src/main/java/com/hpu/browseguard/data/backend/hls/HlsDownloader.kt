package com.hpu.browseguard.data.backend.hls

import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.CookieProvider
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.NoCookieProvider
import com.hpu.browseguard.data.backend.SniffHeaderPolicy
import com.hpu.browseguard.shared.model.SniffResult
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.Request

/** 解析完成、可以直接逐段落盘的 HLS 计划。 */
internal data class HlsPlan(
    val mediaUrl: String,
    val segments: List<HlsSegment>,
    val initMap: HlsMap?,
    val encrypted: Boolean,
    val container: String,
    val durationSeconds: Int,
)

internal sealed interface HlsPrepare {
    data class Ready(val plan: HlsPlan) : HlsPrepare
    data class Failed(val failure: BackendFailure.Local) : HlsPrepare
}

internal sealed interface HlsOutcome {
    data class Done(val bytes: Long, val segments: Int) : HlsOutcome
    data class Cancelled(val bytes: Long, val segments: Int) : HlsOutcome
    data class Failed(val failure: BackendFailure.Local, val bytes: Long) : HlsOutcome
}

/**
 * HLS 本地下载：主清单 → 媒体清单 → 逐段 GET → 拼接成 `.ts` 或 fMP4 `.mp4`。
 *
 * 三条明确拒绝，绝不假装成功：直播（没有 `ENDLIST`）、SAMPLE-AES/DRM、空清单。
 * AES-128 按段解密，密钥按 URI 缓存；默认 IV 用 `EXT-X-MEDIA-SEQUENCE + 段序号`。
 */
internal class HlsDownloader(
    private val http: OkHttpClient = DEFAULT_HTTP,
    private val cookies: CookieProvider = NoCookieProvider,
) {

    fun prepare(link: SniffResult, isCancelled: () -> Boolean): HlsPrepare {
        if (isCancelled()) return HlsPrepare.Failed(LocalFailures.transport("下载已取消"))
        val master = when (val fetched = fetchPlaylist(link.mediaUrl, link)) {
            is TextFetch.Ok -> fetched.playlist
            is TextFetch.Http -> return HlsPrepare.Failed(LocalFailures.linkUnloadable(fetched.status))
            TextFetch.Transport -> return HlsPrepare.Failed(LocalFailures.transport("HLS 清单读取失败"))
        }
        if (master.unsupportedEncryption) {
            return HlsPrepare.Failed(LocalFailures.unsupportedLink("HLS 使用 SAMPLE-AES/DRM 加密，本机不做解密"))
        }

        val (media, mediaUrl) = if (master.isMaster) {
            val variant = master.bestVariant()
                ?: return HlsPrepare.Failed(LocalFailures.unsupportedLink("HLS 主清单没有可用画质"))
            val playlist = when (val fetched = fetchPlaylist(variant.url, link)) {
                is TextFetch.Ok -> fetched.playlist
                is TextFetch.Http -> return HlsPrepare.Failed(LocalFailures.linkUnloadable(fetched.status))
                TextFetch.Transport -> return HlsPrepare.Failed(LocalFailures.transport("HLS 媒体清单读取失败"))
            }
            playlist to variant.url
        } else {
            master to link.mediaUrl
        }

        if (media.unsupportedEncryption) {
            return HlsPrepare.Failed(LocalFailures.unsupportedLink("HLS 使用 SAMPLE-AES/DRM 加密，本机不做解密"))
        }
        if (media.segments.isEmpty()) {
            return HlsPrepare.Failed(LocalFailures.unsupportedLink("HLS 清单里没有切片"))
        }
        // 直播流没有尽头：按清单里当下的段下出来只是几秒钟的残片。
        if (!media.endList) {
            return HlsPrepare.Failed(LocalFailures.unsupportedLink("这是直播流（清单没有 ENDLIST），本机只下点播"))
        }
        return HlsPrepare.Ready(
            HlsPlan(
                mediaUrl = mediaUrl,
                segments = media.segments,
                initMap = media.initMap,
                encrypted = media.encrypted,
                container = media.outputContainer,
                durationSeconds = media.totalDurationSeconds.toInt(),
            ),
        )
    }

    suspend fun download(
        plan: HlsPlan,
        link: SniffResult,
        partFile: File,
        isCancelled: () -> Boolean,
        onProgress: suspend (bytes: Long, segmentsDone: Int, segmentsTotal: Int) -> Unit,
    ): HlsOutcome {
        val keys = HashMap<String, ByteArray>()
        var written = 0L
        var done = 0
        return try {
            partFile.parentFile?.mkdirs()
            RandomAccessFile(partFile, "rw").use { out ->
                out.setLength(0)

                plan.initMap?.let { map ->
                    if (isCancelled()) return HlsOutcome.Cancelled(out.length(), done)
                    val bytes = when (val fetched = fetchBytes(map.url, link, map.range)) {
                        is BytesFetch.Ok -> fetched.bytes
                        is BytesFetch.Http -> return HlsOutcome.Failed(
                            LocalFailures.linkUnloadable(fetched.status),
                            out.length(),
                        )
                        BytesFetch.Transport -> return HlsOutcome.Failed(
                            LocalFailures.transport("HLS 初始化段读取失败"),
                            out.length(),
                        )
                    }
                    out.write(bytes)
                    written += bytes.size
                }

                plan.segments.forEachIndexed { index, segment ->
                    if (isCancelled()) return HlsOutcome.Cancelled(out.length(), done)
                    val raw = when (val fetched = fetchBytes(segment.url, link, segment.range)) {
                        is BytesFetch.Ok -> fetched.bytes
                        is BytesFetch.Http -> return HlsOutcome.Failed(
                            LocalFailures.linkUnloadable(fetched.status),
                            out.length(),
                        )
                        BytesFetch.Transport -> return HlsOutcome.Failed(
                            LocalFailures.transport("HLS 切片 ${index + 1} 读取失败"),
                            out.length(),
                        )
                    }
                    val bytes = segment.key?.takeIf { it.method == "AES-128" }?.let { key ->
                        val keyBytes = keys.getOrPut(key.uri.orEmpty()) {
                            when (val fetched = fetchBytes(key.uri.orEmpty(), link, null)) {
                                is BytesFetch.Ok -> fetched.bytes
                                is BytesFetch.Http -> return HlsOutcome.Failed(
                                    LocalFailures.linkUnloadable(fetched.status),
                                    out.length(),
                                )
                                BytesFetch.Transport -> return HlsOutcome.Failed(
                                    LocalFailures.unsupportedLink("HLS 解密密钥取不到"),
                                    out.length(),
                                )
                            }
                        }
                        decrypt(raw, keyBytes, key, segment.sequence)
                            ?: return HlsOutcome.Failed(
                                LocalFailures.unsupportedLink("HLS AES-128 解密失败"),
                                out.length(),
                            )
                    } ?: raw

                    out.write(bytes)
                    written += bytes.size
                    done = index + 1
                    onProgress(out.length(), done, plan.segments.size)
                }
            }
            HlsOutcome.Done(written, done)
        } catch (e: IOException) {
            HlsOutcome.Failed(
                LocalFailures.transport("HLS 传输中断 · ${e.message ?: e.javaClass.simpleName}"),
                written,
            )
        } catch (e: SecurityException) {
            HlsOutcome.Failed(LocalFailures.disk(e.message ?: "存储权限被拒"), written)
        }
    }

    private sealed interface TextFetch {
        data class Ok(val playlist: HlsPlaylist) : TextFetch
        data class Http(val status: Int) : TextFetch
        data object Transport : TextFetch
    }

    private sealed interface BytesFetch {
        data class Ok(val bytes: ByteArray) : BytesFetch
        data class Http(val status: Int) : BytesFetch
        data object Transport : BytesFetch
    }

    private fun fetchPlaylist(url: String, link: SniffResult): TextFetch = try {
        execute(url, link, null).use { response ->
            if (!response.isSuccessful) {
                TextFetch.Http(response.code)
            } else {
                val text = response.body.string()
                val playlist = HlsPlaylistParser.parse(text, url)
                if (playlist == null) TextFetch.Transport else TextFetch.Ok(playlist)
            }
        }
    } catch (e: IOException) {
        TextFetch.Transport
    } catch (e: IllegalArgumentException) {
        TextFetch.Transport
    }

    private fun fetchBytes(url: String, link: SniffResult, range: HlsByteRange?): BytesFetch = try {
        execute(url, link, range).use { response ->
            if (response.isSuccessful) BytesFetch.Ok(response.body.bytes()) else BytesFetch.Http(response.code)
        }
    } catch (e: IOException) {
        BytesFetch.Transport
    } catch (e: IllegalArgumentException) {
        BytesFetch.Transport
    }

    private fun execute(url: String, link: SniffResult, range: HlsByteRange?): okhttp3.Response {
        val builder = Request.Builder().url(url)
        SniffHeaderPolicy.sanitize(link.headersHint).forEach { (name, value) -> builder.header(name, value) }
        cookies.cookieFor(url)?.let { builder.header("Cookie", it) }
        range?.let { builder.header("Range", "bytes=${it.offset}-${it.offset + it.length - 1}") }
        return http.newCall(builder.build()).execute()
    }

    private fun decrypt(raw: ByteArray, key: ByteArray, spec: HlsKey, sequence: Long): ByteArray? = try {
        val iv = spec.ivHex?.let(::parseIv) ?: ByteBuffer.allocate(16).putLong(sequence).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        cipher.doFinal(raw)
    } catch (e: Exception) {
        null
    }

    private fun parseIv(hex: String): ByteArray? {
        val cleaned = hex.removePrefix("0x").removePrefix("0X")
        if (cleaned.length > 32 || cleaned.length % 2 != 0) return null
        val value = cleaned.chunked(2).map { it.toIntOrNull(16)?.toByte() ?: return null }.toByteArray()
        if (value.size == 16) return value
        return ByteArray(16 - value.size) + value
    }

    private companion object {
        val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
