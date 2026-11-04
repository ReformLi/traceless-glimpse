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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 清单的目标分片时长；缺失时为 0，转封装容差退化成 5 秒。 */
    val segmentDurationSeconds: Double = 0.0,
)

internal sealed interface HlsPrepare {
    data class Ready(val plan: HlsPlan) : HlsPrepare
    data class Failed(val failure: BackendFailure.Local) : HlsPrepare
}

internal sealed interface HlsOutcome {
    data class Done(val bytes: Long, val segments: Int, val retries: Int = 0) : HlsOutcome
    data class Cancelled(val bytes: Long, val segments: Int) : HlsOutcome
    data class Failed(val failure: BackendFailure.Local, val bytes: Long) : HlsOutcome
}

/**
 * HLS 本地下载：主清单 → 媒体清单 → 并发取段 + 顺序落盘 → 拼接成 `.ts` 或 fMP4 `.mp4`。
 *
 * 三条明确拒绝，绝不假装成功：直播（没有 `ENDLIST`）、SAMPLE-AES/DRM、空清单。
 * AES-128 按段解密，密钥按 URI 缓存（取密钥串行化，避免 N 个段同时打同一个密钥 URL）；
 * 默认 IV 用 `EXT-X-MEDIA-SEQUENCE + 段序号`。
 *
 * 每一段都和清单请求带同一套 Referer / User-Agent / 已授权 Cookie——防盗链站的死穴
 * 就在「段请求没带头」，这一段不能因为并发改造被稀释。
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
                segmentDurationSeconds = media.targetDurationSeconds,
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
        val keys = ConcurrentHashMap<String, ByteArray>()
        val keyMutex = Mutex()
        val retries = AtomicInteger()
        var written = 0L
        return try {
            partFile.parentFile?.mkdirs()
            RandomAccessFile(partFile, "rw").use { out ->
                out.setLength(0)

                plan.initMap?.let { map ->
                    if (isCancelled()) return HlsOutcome.Cancelled(out.length(), 0)
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

                runWindowed(out, plan, link, isCancelled, keys, keyMutex, retries, onProgress)
            }
        } catch (e: IOException) {
            HlsOutcome.Failed(
                LocalFailures.transport("HLS 传输中断 · ${e.message ?: e.javaClass.simpleName}"),
                written,
            )
        } catch (e: SecurityException) {
            HlsOutcome.Failed(LocalFailures.disk(e.message ?: "存储权限被拒"), written)
        }
    }

    /**
     * 并发取、顺序写：固定 4 个槽位轮流补位，写盘只发生在这一个协程里。
     *
     * 顺序性靠「按清单 index 依次 await」保证，不是靠完成顺序——段 5 比段 4 先回来
     * 也要等段 4 落盘。槽位数就是并发上限，所以既不需要额外信号量，内存峰值也被钉在
     * 4 个段以内（`body.bytes()` 是整段进堆的，不设窗口就会在慢盘 + 快 CDN 下失控）。
     */
    private suspend fun runWindowed(
        out: RandomAccessFile,
        plan: HlsPlan,
        link: SniffResult,
        isCancelled: () -> Boolean,
        keys: ConcurrentHashMap<String, ByteArray>,
        keyMutex: Mutex,
        retries: AtomicInteger,
        onProgress: suspend (bytes: Long, segmentsDone: Int, segmentsTotal: Int) -> Unit,
    ): HlsOutcome = coroutineScope {
        val total = plan.segments.size
        var written = out.length()
        var done = 0
        val window = ArrayDeque<Deferred<SegmentRead>>()
        var next = 0
        while (next < total || window.isNotEmpty()) {
            if (isCancelled()) {
                window.forEach { it.cancel() }
                return@coroutineScope HlsOutcome.Cancelled(written, done)
            }
            while (window.size < SEGMENT_CONCURRENCY && next < total) {
                val segment = plan.segments[next]
                val index = next
                window += async(Dispatchers.IO) { readSegment(segment, index, link, keys, keyMutex, retries) }
                next++
            }
            when (val head = window.removeFirst().await()) {
                is SegmentRead.Ok -> {
                    out.write(head.bytes)
                    written += head.bytes.size
                    done++
                    onProgress(written, done, total)
                }

                is SegmentRead.Bad -> {
                    window.forEach { it.cancel() }
                    return@coroutineScope HlsOutcome.Failed(head.failure, written)
                }
            }
        }
        HlsOutcome.Done(written, done, retries.get())
    }

    private sealed interface SegmentRead {
        data class Ok(val bytes: ByteArray) : SegmentRead
        data class Bad(val failure: BackendFailure.Local) : SegmentRead
    }

    /**
     * 取一段并解密。瞬时失败按退避重试，防盗链失效则立刻认输。
     *
     * 401/403/404/410 不重试：那几种状态意味着 Referer/Cookie 已经过期，
     * 再打四遍只是继续刷 CDN 的日志，正确出路是回页面重新播放再嗅一次。
     */
    private suspend fun readSegment(
        segment: HlsSegment,
        index: Int,
        link: SniffResult,
        keys: ConcurrentHashMap<String, ByteArray>,
        keyMutex: Mutex,
        retries: AtomicInteger,
    ): SegmentRead {
        var attempt = 0
        while (true) {
            attempt++
            val raw = when (val fetched = fetchBytes(segment.url, link, segment.range)) {
                is BytesFetch.Ok -> fetched.bytes

                is BytesFetch.Http -> {
                    if (fetched.status in NO_RETRY_STATUS) {
                        return SegmentRead.Bad(LocalFailures.linkUnloadable(fetched.status))
                    }
                    if (attempt >= SEGMENT_ATTEMPTS) {
                        return SegmentRead.Bad(
                            LocalFailures.transport("HLS 切片 ${index + 1} 取不到（HTTP ${fetched.status}）"),
                        )
                    }
                    retries.incrementAndGet()
                    delay(BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.lastIndex)])
                    continue
                }

                BytesFetch.Transport -> {
                    if (attempt >= SEGMENT_ATTEMPTS) {
                        return SegmentRead.Bad(
                            LocalFailures.transport("HLS 切片 ${index + 1} 连续 $SEGMENT_ATTEMPTS 次读取失败"),
                        )
                    }
                    retries.incrementAndGet()
                    delay(BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.lastIndex)])
                    continue
                }
            }
            val key = segment.key?.takeIf { it.method == "AES-128" } ?: return SegmentRead.Ok(raw)
            val keyBytes = fetchKey(key.uri.orEmpty(), link, keys, keyMutex)
                ?: return SegmentRead.Bad(LocalFailures.unsupportedLink("HLS 解密密钥取不到"))
            val plain = decrypt(raw, keyBytes, key, segment.sequence)
                ?: return SegmentRead.Bad(LocalFailures.unsupportedLink("HLS AES-128 解密失败"))
            return SegmentRead.Ok(plain)
        }
    }

    /** 密钥按 URI 缓存；取密钥串行化——8 个段同时缺同一个密钥时只该打一次。 */
    private suspend fun fetchKey(
        uri: String,
        link: SniffResult,
        keys: ConcurrentHashMap<String, ByteArray>,
        keyMutex: Mutex,
    ): ByteArray? {
        if (uri.isEmpty()) return null
        keys[uri]?.let { return it }
        return keyMutex.withLock {
            keys[uri] ?: (fetchBytes(uri, link, null) as? BytesFetch.Ok)?.bytes?.also { keys[uri] = it }
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
        /**
         * 同时在飞的切片数。4 是「能吃满按连接限速的 CDN」和「不被当成爬虫」之间的折中：
         * 再高就接近 okhttp 单主机默认上限 5，排队行为开始变得不可预期。
         * CDN 若按 IP 总带宽限速，这里加并发也不会有收益——真机实测再调。
         */
        const val SEGMENT_CONCURRENCY = 4

        /** 含首次在内的尝试次数：1 次正常 + 2 次退避重试。 */
        const val SEGMENT_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(500L, 2000L)

        /** 这些状态是「身份/链接不对」，不是「网络抖了」：重试只会继续刷 CDN。 */
        val NO_RETRY_STATUS = setOf(400, 401, 403, 404, 405, 410)

        val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
