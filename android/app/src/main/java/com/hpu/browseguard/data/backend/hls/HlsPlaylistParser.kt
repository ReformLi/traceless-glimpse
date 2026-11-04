package com.hpu.browseguard.data.backend.hls

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** HLS 加密信息。只支持 AES-128；其余方法交给调用方明确拒绝。 */
internal data class HlsKey(
    val method: String,
    val uri: String?,
    val ivHex: String?,
)

internal data class HlsByteRange(val length: Long, val offset: Long)

internal data class HlsMap(val url: String, val range: HlsByteRange?)

internal data class HlsSegment(
    val url: String,
    val key: HlsKey?,
    val range: HlsByteRange?,
    /** 解密默认 IV 用：`mediaSequence + 段序号`。 */
    val sequence: Long,
)

internal data class HlsVariant(
    val url: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
)

/**
 * 解析后的 HLS 清单。主清单只有 [variants]；媒体清单只有 [segments]。
 *
 * 这个对象是纯数据，JVM 单测可以逐字钉住：相对地址解析、`EXT-X-MAP`、
 * `EXT-X-BYTERANGE`、AES-128 的 IV/URI、以及「没有 ENDLIST」的直播判定。
 */
internal data class HlsPlaylist(
    val isMaster: Boolean,
    val variants: List<HlsVariant>,
    val segments: List<HlsSegment>,
    val initMap: HlsMap?,
    val encrypted: Boolean,
    val unsupportedEncryption: Boolean,
    val endList: Boolean,
    val totalDurationSeconds: Double,
    /** `#EXT-X-TARGETDURATION`；清单没写时退化为最长 `#EXTINF`，用于转封装容差。 */
    val targetDurationSeconds: Double,
) {
    /** 有 init 段（fMP4）时产物是 `.mp4`，否则是传统 TS 拼接的 `.ts`。 */
    val outputContainer: String get() = if (initMap != null) "mp4" else "ts"

    /** 画质选择：1080p 以内取最高，同高度再比码率；没有分辨率信息就比码率。 */
    fun bestVariant(): HlsVariant? {
        if (variants.isEmpty()) return null
        val capped = variants.filter { it.height in 1..1080 }
        val pool = if (capped.isEmpty()) variants else capped
        return pool.maxWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })
    }
}

internal object HlsPlaylistParser {

    fun parse(body: String, baseUrl: String): HlsPlaylist? {
        if (!body.lineSequence().any { it.trim() == "#EXTM3U" }) return null
        val base = baseUrl.toHttpUrlOrNull() ?: return null

        val variants = mutableListOf<HlsVariant>()
        val segments = mutableListOf<HlsSegment>()
        var pendingVariant: HlsVariant? = null
        var pendingKey: HlsKey? = null
        var pendingRange: Pair<Long, Long?>? = null
        var initMap: HlsMap? = null
        var endList = false
        var mediaSequence = 0L
        var totalDuration = 0.0
        var targetDuration = 0.0
        var maxSegmentDuration = 0.0
        var encrypted = false
        var unsupported = false
        var nextRangeOffset = 0L
        var segmentIndex = 0L

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val resolution = attrs["RESOLUTION"]
                    pendingVariant = HlsVariant(
                        url = "",
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = resolution?.substringBefore('x')?.toIntOrNull() ?: 0,
                        height = resolution?.substringAfter('x')?.toIntOrNull() ?: 0,
                    )
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val method = attrs["METHOD"]?.uppercase() ?: "NONE"
                    when (method) {
                        "NONE" -> pendingKey = null
                        "AES-128" -> {
                            encrypted = true
                            pendingKey = HlsKey(
                                method = method,
                                uri = attrs["URI"]?.let { base.resolve(it)?.toString() },
                                ivHex = attrs["IV"],
                            )
                        }

                        else -> {
                            // SAMPLE-AES / Widevine / FairPlay：不做解密，下载前明确拒绝。
                            unsupported = true
                            pendingKey = HlsKey(method, attrs["URI"], attrs["IV"])
                        }
                    }
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val uri = attrs["URI"]?.let { base.resolve(it)?.toString() } ?: return@forEach
                    initMap = HlsMap(uri, parseByteRange(attrs["BYTERANGE"], 0L)?.let { (len, off) ->
                        HlsByteRange(len, off ?: 0L)
                    })
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = parseByteRange(line.substringAfter(':'), null)
                }

                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }

                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0
                }

                line.startsWith("#EXTINF:") -> {
                    val seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    totalDuration += seconds
                    if (seconds > maxSegmentDuration) maxSegmentDuration = seconds
                }

                line == "#EXT-X-ENDLIST" -> endList = true

                line.startsWith("#") -> Unit

                else -> {
                    val resolved = base.resolve(line)?.toString() ?: return@forEach
                    val variant = pendingVariant
                    if (variant != null) {
                        variants += variant.copy(url = resolved)
                        pendingVariant = null
                    } else {
                        val range = pendingRange?.let { (length, offset) ->
                            val start = offset ?: nextRangeOffset
                            nextRangeOffset = start + length
                            HlsByteRange(length, start)
                        }
                        if (range == null) nextRangeOffset = 0L
                        segments += HlsSegment(
                            url = resolved,
                            key = pendingKey,
                            range = range,
                            sequence = mediaSequence + segmentIndex,
                        )
                        segmentIndex += 1
                        pendingRange = null
                    }
                }
            }
        }

        val isMaster = variants.isNotEmpty() && segments.isEmpty()
        return HlsPlaylist(
            isMaster = isMaster,
            variants = variants,
            segments = segments,
            initMap = initMap,
            encrypted = encrypted,
            unsupportedEncryption = unsupported,
            endList = endList,
            totalDurationSeconds = totalDuration,
            targetDurationSeconds = if (targetDuration > 0.0) targetDuration else maxSegmentDuration,
        )
    }

    private fun parseByteRange(raw: String?, defaultOffset: Long?): Pair<Long, Long?>? {
        if (raw.isNullOrBlank()) return null
        val length = raw.substringBefore('@').trim().toLongOrNull() ?: return null
        val offset = raw.substringAfter('@', "").trim().toLongOrNull() ?: defaultOffset
        return length to offset
    }

    /** `#EXT-X-*:KEY=value,KEY="quoted,value"` 的属性表；逗号在引号内不切。 */
    private fun attributes(raw: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var index = 0
        while (index < raw.length) {
            while (index < raw.length && (raw[index] == ',' || raw[index].isWhitespace())) index++
            val nameStart = index
            while (index < raw.length && raw[index] != '=') index++
            if (index >= raw.length) break
            val name = raw.substring(nameStart, index).trim().uppercase()
            index += 1
            val value: String
            if (index < raw.length && raw[index] == '"') {
                index += 1
                val valueStart = index
                while (index < raw.length && raw[index] != '"') index++
                value = raw.substring(valueStart, index)
                if (index < raw.length) index += 1
            } else {
                val valueStart = index
                while (index < raw.length && raw[index] != ',') index++
                value = raw.substring(valueStart, index).trim()
            }
            if (name.isNotEmpty()) result[name] = value
        }
        return result
    }
}
