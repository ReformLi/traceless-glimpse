package com.hpu.browseguard.data.backend.hls

import com.hpu.browseguard.data.backend.CookieProvider
import com.hpu.browseguard.data.backend.NoCookieProvider
import com.hpu.browseguard.data.backend.SniffHeaderPolicy
import com.hpu.browseguard.shared.model.SniffResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** 清单探测结果：只用于界面展示，下载器会自己重新读一遍清单。 */
internal data class HlsProbeResult(
    val height: Int,
    val bitrateKbps: Int,
    val segmentCount: Int,
    val durationSeconds: Int,
    val encrypted: Boolean,
    val unsupportedEncryption: Boolean,
    val live: Boolean,
    val container: String,
)

/**
 * 用 GET 读清单并给出画质/段数。HEAD 在很多源站会回 502，
 * 所以这里固定用 GET——清单本身只有几 KB。
 */
internal class HlsProbeClient(
    private val http: OkHttpClient = DEFAULT_HTTP,
    private val cookies: CookieProvider = NoCookieProvider,
) {

    fun probe(link: SniffResult): HlsProbeResult? {
        val masterBody = fetchText(link.mediaUrl, link) ?: return null
        val master = HlsPlaylistParser.parse(masterBody, link.mediaUrl) ?: return null
        if (!master.isMaster) {
            return master.toProbe(height = link.estimatedQuality.height, bitrateKbps = link.estimatedQuality.bitrateKbps)
        }

        val variant = master.bestVariant() ?: return master.toProbe(0, 0)
        val kbps = (variant.bandwidth / 1000).toInt()
        val variantBody = fetchText(variant.url, link) ?: return master.toProbe(variant.height, kbps)
        val media = HlsPlaylistParser.parse(variantBody, variant.url)
            ?: return master.toProbe(variant.height, kbps)
        return media.toProbe(variant.height, kbps)
    }

    private fun HlsPlaylist.toProbe(height: Int, bitrateKbps: Int) = HlsProbeResult(
        height = height,
        bitrateKbps = bitrateKbps,
        segmentCount = segments.size,
        durationSeconds = totalDurationSeconds.toInt(),
        encrypted = encrypted,
        unsupportedEncryption = unsupportedEncryption,
        live = !endList,
        container = outputContainer,
    )

    private fun fetchText(url: String, link: SniffResult): String? = try {
        val builder = Request.Builder().url(url)
        SniffHeaderPolicy.sanitize(link.headersHint).forEach { (name, value) -> builder.header(name, value) }
        cookies.cookieFor(url)?.let { builder.header("Cookie", it) }
        http.newCall(builder.build()).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
