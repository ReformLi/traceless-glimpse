package com.hpu.browseguard.data.backend

import android.media.MediaCodec
import android.media.MediaDescrambler
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import com.hpu.browseguard.data.backend.remux.AdtsAudioConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * 平台 AAC 解码器探针（临时诊断）：把文件前若干个音频样本喂给系统解码器，量它到底吐出多少 PCM、
 * PCM 有没有能量。门禁全过而用户听着没声音，只有这种能量/时长实测能说是哪一种坏法。
 *
 * 三个数要一起看：
 * - `pcmPerInput`（每个输入样本解出多少 PCM 采样）：1024 = 解码器只认一个 AU，
 *   样本里并进来的后面那十几个 AU 全被丢掉；≈1024×每样本 AU 数 = 载荷被完整解出来了。
 * - `pcmPeak` / `pcmRms`：都贴着 0 = 源里编的就是数字静音，帧数再多也没声音。
 * - `pcmMs` 对比 `fedSpanMs`：解出来的声音远短于喂进去的时间跨度 = 尾部静音。
 *
 * 只读、只出数字，不参与任何放行/拦截判定；探针自己出错也只记 `probeError`，绝不让下载失败。
 */
internal object AudioDecodeProbe {

    /** 只解前 400 个音频样本（并包流约 130 秒时间跨度），够看形状，也拖不慢下载收尾。 */
    private const val MAX_SAMPLES = 400
    private const val TIMEOUT_US = 2_000L
    private const val MAX_STEPS = 40_000
    private const val BUDGET_MS = 6_000L
    private const val FIRST_SAMPLE_BYTES = 64 * 1024
    private const val BYTES_PER_SHORT = 2

    fun probe(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!file.isFile || file.length() <= 0L) {
            out["probeError"] = "missing_file"
            return out
        }
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var rate = 0
        var channels = 0
        var fed = 0
        var fedBytes = 0L
        var tooBig = 0
        var firstPtsUs = -1L
        var lastPtsUs = -1L
        var outBuffers = 0
        var pcmFrames = 0L
        var countedShorts = 0L
        var sumSquares = 0.0
        var peak = 0
        var steps = 0
        var stop = "budget"
        try {
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
            val ex = extractor
            var audioIndex = -1
            var videoIndex = -1
            for (index in 0 until ex.trackCount) {
                val trackMime = ex.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    trackMime.startsWith("audio/") && audioIndex < 0 -> audioIndex = index
                    trackMime.startsWith("video/") && videoIndex < 0 -> videoIndex = index
                }
            }
            out["tracks"] = ex.trackCount.toString()
            if (videoIndex >= 0) {
                val video = ex.getTrackFormat(videoIndex)
                out["vMime"] = video.getString(MediaFormat.KEY_MIME).orEmpty()
                out["vSize"] = "${intOf(video, MediaFormat.KEY_WIDTH)}x${intOf(video, MediaFormat.KEY_HEIGHT)}"
                out["vFps"] = intOf(video, MediaFormat.KEY_FRAME_RATE).toString()
                out["vCsdLen"] = csdLengthOf(video).toString()
            }
            if (audioIndex < 0) {
                out["aMime"] = "absent"
                return out
            }

            val format = ex.getTrackFormat(audioIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            rate = intOf(format, MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
            channels = intOf(format, MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            out["aMime"] = mime
            out["aRate"] = rate.toString()
            out["aChannels"] = channels.toString()
            out["aProfile"] = intOf(format, MediaFormat.KEY_AAC_PROFILE, -1).toString()
            out["aMaxInput"] = intOf(format, MediaFormat.KEY_MAX_INPUT_SIZE).toString()
            out["aCsdLen"] = csdLengthOf(format).toString()
            // TS 侧常不给 csd-0：跟转封装一样从 ADTS 头补一份 ASC，否则解码器配不起来，探针白跑。
            if (csdLengthOf(format) <= 0 && mime.contains("aac")) {
                firstSampleBytes(file, audioIndex)?.let { head ->
                    AdtsAudioConfig.fromAdtsHeader(head, head.size)?.let { csd ->
                        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                        out["aCsdLen"] = csd.size.toString()
                        out["aCsdFrom"] = "adts"
                    }
                }
            }

            ex.selectTrack(audioIndex)
            codec = MediaCodec.createDecoderByType(mime)
            val dec = codec
            dec.configure(format, null as Surface?, 0, null as MediaDescrambler?)
            dec.start()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + BUDGET_MS
            var eosQueued = false
            var noMoreSamples = false
            var outputDone = false

            while (!outputDone && steps < MAX_STEPS && SystemClock.elapsedRealtime() < deadline) {
                steps++
                if (!eosQueued) {
                    val inIndex = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = dec.getInputBuffer(inIndex)
                        val capacity = inBuffer?.capacity()?.toLong() ?: 0L
                        val sampleSize = ex.sampleSize
                        when {
                            sampleSize < 0L || noMoreSamples || fed >= MAX_SAMPLES -> {
                                dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            }

                            inBuffer != null && sampleSize <= capacity -> {
                                val read = ex.readSampleData(inBuffer, 0)
                                if (read <= 0) {
                                    dec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    eosQueued = true
                                } else {
                                    val ptsUs = ex.sampleTime
                                    if (firstPtsUs < 0L) firstPtsUs = ptsUs
                                    if (ptsUs > lastPtsUs) lastPtsUs = ptsUs
                                    dec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                                    fed++
                                    fedBytes += read.toLong()
                                    if (!ex.advance()) noMoreSamples = true
                                }
                            }

                            else -> {
                                // 样本比解码器声明的输入槽还大：这一路喂不进去，跳过它并把次数记下来。
                                tooBig++
                                if (!ex.advance()) noMoreSamples = true
                            }
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = dec.getOutputBuffer(outIndex)
                        if (buffer != null) {
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            val shorts = info.size / BYTES_PER_SHORT
                            var sample = 0
                            while (sample < shorts) {
                                val value = buffer.short.toInt()
                                val abs = if (value < 0) -value else value
                                if (abs > peak) peak = abs
                                sumSquares += value.toDouble() * value
                                sample++
                            }
                            countedShorts += shorts.toLong()
                            pcmFrames += (info.size / (BYTES_PER_SHORT * channels)).toLong()
                            outBuffers++
                        }
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                        stop = "eos"
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = dec.outputFormat
                    rate = intOf(decoded, MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 } ?: rate
                    channels = intOf(decoded, MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 } ?: channels
                    out["aDecRate"] = rate.toString()
                    out["aDecChannels"] = channels.toString()
                }
            }
            if (stop == "budget" && steps >= MAX_STEPS) stop = "steps"
        } catch (e: Exception) {
            out["probeError"] = e.javaClass.simpleName
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }

        out["fed"] = fed.toString()
        out["fedBytes"] = fedBytes.toString()
        out["tooBig"] = tooBig.toString()
        out["fedSpanMs"] = if (lastPtsUs > firstPtsUs) ((lastPtsUs - firstPtsUs) / 1000L).toString() else "0"
        out["decBuffers"] = outBuffers.toString()
        out["pcmFrames"] = pcmFrames.toString()
        out["pcmMs"] = if (pcmFrames > 0 && rate > 0) (pcmFrames * 1000L / rate).toString() else "0"
        out["pcmPerInput"] = if (fed > 0 && pcmFrames > 0) (pcmFrames / fed).toString() else "0"
        out["pcmPeak"] = peak.toString()
        out["pcmRms"] = if (countedShorts > 0) sqrt(sumSquares / countedShorts).toLong().toString() else "0"
        out["decSteps"] = steps.toString()
        out["decStop"] = stop
        return out
    }

    /** 取首个音频样本的头部字节：容器不给 csd-0 时，用它反推 AudioSpecificConfig。 */
    private fun firstSampleBytes(file: File, track: Int): ByteArray? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(FIRST_SAMPLE_BYTES)
            val read = extractor.readSampleData(buffer, 0)
            if (read <= 0) {
                null
            } else {
                buffer.position(0)
                ByteArray(read).also { buffer.get(it) }
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun csdLengthOf(format: MediaFormat): Int = try {
        if (format.containsKey("csd-0")) format.getByteBuffer("csd-0")?.remaining() ?: 0 else 0
    } catch (e: Exception) {
        0
    }

    /** 诊断取值不允许抛：同一个键不同实现可能存成 Int/Long/Float，取不到就当没给。 */
    private fun intOf(format: MediaFormat, key: String, fallback: Int = 0): Int = try {
        if (format.containsKey(key)) format.getInteger(key) else fallback
    } catch (e: Exception) {
        try {
            format.getFloat(key)?.toInt() ?: fallback
        } catch (ignored: Exception) {
            fallback
        }
    }
}
