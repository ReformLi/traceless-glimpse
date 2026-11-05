package com.hpu.browseguard.data.backend.remux

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

internal sealed interface RemuxResult {
    data class Done(
        val bytes: Long,
        val durationMs: Long,
        val videoSamples: Int,
        val audioSamples: Int,
        val sourceAudio: AudioScan,
        val outputAudio: AudioScan,
    ) : RemuxResult

    /** 不转：调用方保留 `.ts`，并如实告诉用户系统播放器可能不认。 */
    data class Skipped(
        val reason: String,
        val sourceAudio: AudioScan? = null,
        val outputAudio: AudioScan? = null,
    ) : RemuxResult

    data object Cancelled : RemuxResult
}

/**
 * 一路音频样本扫出来的事实，源 `.ts` 和产物各一份，摆在一起就能看出声音是在哪一跳丢的。
 *
 * 只用 Android 之外的类型（`java.nio.ByteBuffer`），所以这条能在 JVM 上直接单测。
 * [frames] 是按 ADTS 头一帧一帧走出来的真实 AAC 帧数：`MediaExtractor` 会把多帧并成一个样本，
 * 所以样本数说不了音频有多长，帧数可以——这是目前唯一不受 moov 时长和样本重铺影响的绝对量。
 * [contentMs] 为 0 表示「认不出 ADTS，未知」，调用方不能拿 0 当「没有声音」的判据。
 */
internal class AudioScan {

    var samples: Int = 0
        private set
    var bytes: Long = 0L
        private set
    var frames: Int = 0
        private set
    var sampleRateHz: Int = 0
        private set
    var firstPtsUs: Long = -1L
        private set
    var lastPtsUs: Long = -1L
        private set
    var maxGapUs: Long = 0L
        private set
    var firstSampleBytes: Int = -1
        private set

    private var oneAuPerSample = false

    /**
     * 声明「这个文件的音频就是一个样本一个 AU」——拆分重封后的产物复检必须走这条路。
     *
     * 合规 mp4a 的载荷是**裸 AAC，没有 ADTS 头**，逐帧走自然数出 0 帧；不声明的话
     * 内容比例与密度两条门禁会把我们自己刚写好的合规文件当成哑巴拦下来。
     */
    fun countOneAuPerSample() {
        oneAuPerSample = true
    }

    fun add(ptsUs: Long, size: Int, buffer: ByteBuffer) {
        if (sampleRateHz == 0) sampleRateHz = AdtsFrameScanner.sampleRateHz(buffer)
        val counted = AdtsFrameScanner.countFrames(buffer, size)
        frames += counted
        if (counted == 0 && oneAuPerSample) {
            // 裸 AAC 载荷：一帧 ADTS 头都认不出来正是合规的表现，帧数按样本数一一对上。
            frames++
        }
        if (samples == 0) firstSampleBytes = size
        samples++
        bytes += size.toLong()
        if (firstPtsUs < 0L) firstPtsUs = ptsUs
        val gap = if (lastPtsUs < 0L) 0L else ptsUs - lastPtsUs
        if (gap > maxGapUs) maxGapUs = gap
        if (ptsUs > lastPtsUs) lastPtsUs = ptsUs
    }

    /**
     * 用容器给的采样率垫底：首个样本万一是裸 AAC 或不从 ADTS 头开始，帧数照样得能换成时长，
     * 否则这条测量白做。已给过率时 [add] 不再查 ADTS，取值以轨道声明为准。
     */
    fun seedSampleRateHz(hz: Int) {
        if (sampleRateHz == 0 && hz > 0) sampleRateHz = hz
    }

    /** 这些帧实际能播多久；帧数或采样率认不出来就是 0（未知），不是「没有声音」。 */
    val contentUs: Long
        get() = if (frames <= 0 || sampleRateHz <= 0) {
            0L
        } else {
            frames.toLong() * AdtsFrameScanner.SAMPLES_PER_FRAME * 1_000_000L / sampleRateHz
        }

    val contentMs: Long get() = contentUs / 1000L
    val maxGapMs: Long get() = maxGapUs / 1000L
    val spanMs: Long get() = if (lastPtsUs > firstPtsUs) (lastPtsUs - firstPtsUs) / 1000L else 0L

    /**
     * 审计行字段：源和产物各一份，用 [prefix] 区分。
     *
     * 全是数字，没有 URL、没有字节内容。「帧数量出来的时长」和「时间轴跨度」成对摆出来，
     * 就能直接看出底层是不是只把时间轴铺满、声音其实没进来。
     */
    fun auditMap(prefix: String): Map<String, String> = mapOf(
        "${prefix}Samples" to samples.toString(),
        "${prefix}Bytes" to bytes.toString(),
        "${prefix}Frames" to frames.toString(),
        "${prefix}RateHz" to sampleRateHz.toString(),
        "${prefix}ContentMs" to contentMs.toString(),
        "${prefix}SpanMs" to spanMs.toString(),
        "${prefix}MaxGapMs" to maxGapMs.toString(),
    )
}

/**
 * TS → MP4 的 copy remux：只换容器，不解码、不重编码。
 *
 * 安全策略是「宁可不转，也不产出坏文件」：PTS 只做全局归零，逐样本的单调性交给
 * [PtsGate] 判定、不夹紧（夹紧会破坏 B 帧显示顺序）；轨道被拒、时间戳跳变、
 * 输出时长与清单明显不符，一律 [RemuxResult.Skipped]。
 *
 * 输出先写调用方给的 `.mp4.part`；本类不负责改名，也不删源文件——只有全部校验
 * 通过后，调用方才把 `.part` 落地为 `.mp4` 并删掉 `.ts`。
 */
internal class TsRemuxer {

    fun remux(
        source: File,
        target: File,
        expectedDurationMs: Long,
        segmentDurationMs: Long = 0L,
        isCancelled: () -> Boolean = { false },
    ): RemuxResult {
        if (!source.isFile || source.length() <= 0L) return RemuxResult.Skipped("empty_source")

        val probe = try {
            when (val outcome = probe(source)) {
                is ProbeOutcome.Skipped -> return RemuxResult.Skipped(outcome.reason)
                is ProbeOutcome.Ready -> outcome.probe
            }
        } catch (e: Exception) {
            return RemuxResult.Skipped("extract_failed")
        }

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var finished = false
        return try {
            val ex = MediaExtractor().apply { setDataSource(source.absolutePath) }
            extractor = ex

            target.delete()
            val mux = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val outputTracks = HashMap<Int, Int>()
            val sourceAudio = AudioScan()
            probe.videoIndex?.let { index ->
                ex.selectTrack(index)
                val format = ex.getTrackFormat(index)
                val out = try {
                    mux.addTrack(format)
                } catch (e: IllegalArgumentException) {
                    return RemuxResult.Skipped("video_track_rejected")
                }
                outputTracks[index] = out
            }
            probe.audioIndex?.let { index ->
                ex.selectTrack(index)
                val format = ex.getTrackFormat(index)
                sourceAudio.seedSampleRateHz(sampleRateHzOf(format))
                probe.audioCsd?.let { csd -> format.setByteBuffer("csd-0", ByteBuffer.wrap(csd)) }
                val out = try {
                    mux.addTrack(format)
                } catch (e: IllegalArgumentException) {
                    return RemuxResult.Skipped(RemuxAudioGuard.AUDIO_TRACK_REJECTED)
                }
                outputTracks[index] = out
            }
            if (outputTracks.isEmpty()) return RemuxResult.Skipped("no_tracks")

            mux.start()
            val gate = PtsGate()
            val info = MediaCodec.BufferInfo()
            var buffer = ByteBuffer.allocate(INITIAL_BUFFER_BYTES)
            var videoSamples = 0
            var audioSamples = 0
            // AAC-in-TS 常把十几个 AU 并成一个样本还带着 ADTS 头，直接抄进 MP4 就是不合规的 mp4a：
            // Android 解得开，严格播放器解不开。这里按 AU 拆开重封，见 [AudioUnitWriter]。
            val audioOutIndex = probe.audioIndex?.let { outputTracks[it] }
            val auInfo = MediaCodec.BufferInfo()
            val splitter = if (probe.splitAudioUnits && audioOutIndex != null) {
                AudioUnitWriter(probe.audioRateHz) { payload, size, ptsUs ->
                    auInfo.set(0, size, ptsUs, 0)
                    mux.writeSampleData(audioOutIndex, payload, auInfo)
                }
            } else {
                null
            }

            while (true) {
                if (isCancelled()) return RemuxResult.Cancelled
                val sampleSize = ex.sampleSize
                if (sampleSize < 0L) break
                if (sampleSize > buffer.capacity().toLong()) buffer = ByteBuffer.allocate(sampleSize.toInt())
                val read = ex.readSampleData(buffer, 0)
                if (read < 0) break
                if (read == 0) {
                    if (!ex.advance()) break
                    continue
                }

                val sourceIndex = ex.sampleTrackIndex
                val outIndex = outputTracks[sourceIndex]
                if (outIndex == null) {
                    ex.advance()
                    continue
                }
                val isVideo = sourceIndex == probe.videoIndex
                val ptsUs = ex.sampleTime - probe.startUs
                if (ptsUs < 0L) {
                    return RemuxResult.Skipped(
                        if (isVideo) "video_pts_negative" else RemuxAudioGuard.AUDIO_PTS_NEGATIVE,
                    )
                }
                val verdict = if (isVideo) gate.checkVideo(ptsUs) else gate.checkAudio(ptsUs)
                if (verdict is PtsVerdict.Reject) return RemuxResult.Skipped(verdict.reason)

                val flags = if (isVideo && ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, read, ptsUs, flags)
                if (isVideo) {
                    videoSamples++
                    mux.writeSampleData(outIndex, buffer, info)
                } else {
                    // `writeSampleData` 会推进 position，ADTS 帧计数必须在写入之前做。
                    sourceAudio.add(ptsUs, read, buffer)
                    audioSamples++
                    if (splitter == null) {
                        mux.writeSampleData(outIndex, buffer, info)
                    } else if (splitter.write(buffer, read, ptsUs) == 0) {
                        return RemuxResult.Skipped(RemuxAudioGuard.AUDIO_AU_SPLIT_FAILED, sourceAudio)
                    }
                }
                if (!ex.advance()) break
            }

            if (videoSamples == 0 && audioSamples == 0) return RemuxResult.Skipped("no_samples")
            // 视频正常但音频样本为 0，就是「只有画面」的哑巴文件——宁可保留 .ts。
            RemuxAudioGuard.sampleGate(audioSamples)?.let {
                return RemuxResult.Skipped(it, sourceAudio)
            }

            mux.stop()
            mux.release()
            muxer = null

            val outputAudio = AudioScan()
            verifyOutput(target, segmentDurationMs, outputAudio, splitAudioUnits = splitter != null)?.let {
                return RemuxResult.Skipped(it, sourceAudio, outputAudio)
            }
            // 拆完之后 AU 数必须严丝合缝：源帧数 = 写入数 = 产物复开的样本数。
            // 少一个就是「有声音被吃掉」，而时间戳/密度/时长在样本被重铺时全都照常好看。
            if (splitter != null) {
                RemuxAudioGuard.auCountGate(sourceAudio.frames, splitter.writtenAus, outputAudio.samples)?.let {
                    return RemuxResult.Skipped(it, sourceAudio, outputAudio)
                }
            }

            val durationMs = outputDurationMs(target) ?: return RemuxResult.Skipped("output_unreadable")
            if (expectedDurationMs > 0L && durationMs < expectedDurationMs * 3 / 4) {
                return RemuxResult.Skipped("duration_short", sourceAudio, outputAudio)
            }
            if (expectedDurationMs > 0L && durationMs > expectedDurationMs * 3 / 2 + 30_000L) {
                return RemuxResult.Skipped("duration_too_long", sourceAudio, outputAudio)
            }

            finished = true
            RemuxResult.Done(
                bytes = target.length(),
                durationMs = durationMs,
                videoSamples = videoSamples,
                audioSamples = audioSamples,
                sourceAudio = sourceAudio,
                outputAudio = outputAudio,
            )
        } catch (e: Exception) {
            RemuxResult.Skipped("muxer_failed")
        } finally {
            try {
                extractor?.release()
            } catch (_: Exception) {
                // 释放失败不影响结论，产物已在下面按成败处理。
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
                // 未 stop 的 muxer 释放时会报错，这里只保证 fd 不泄漏。
            }
            if (!finished) target.delete()
        }
    }

    private class Probe(
        val videoIndex: Int?,
        val audioIndex: Int?,
        val audioCsd: ByteArray?,
        val startUs: Long,
        val audioRateHz: Int,
        val splitAudioUnits: Boolean,
    )

    private sealed class ProbeOutcome {
        class Ready(val probe: Probe) : ProbeOutcome()
        class Skipped(val reason: String) : ProbeOutcome()
    }

    /**
     * 只读每条轨道的首样本：拿起始 PTS，并给缺 `csd-0` 的 AAC 合成配置。
     *
     * 音轨的两道源头门禁也在这里：找不到音轨、AAC 既没有可用 `csd-0` 也合成失败，
     * 都直接拒绝转封装——后面的 mux 再正常也只会得到哑巴文件。
     */
    private fun probe(source: File): ProbeOutcome {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            var video = -1
            var audio = -1
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                if (video < 0 && mime.startsWith("video/")) {
                    video = index
                } else if (audio < 0 && mime.startsWith("audio/")) {
                    audio = index
                }
            }
            if (video < 0 && audio < 0) return ProbeOutcome.Skipped("no_tracks")
            RemuxAudioGuard.trackGate(audio.takeIf { it >= 0 })?.let {
                return ProbeOutcome.Skipped(it)
            }

            val videoStart = if (video >= 0) firstSample(extractor, video)?.first else null
            var csd: ByteArray? = null
            var audioRateHz = 0
            var splitAudioUnits = false
            val audioStart = if (audio >= 0) {
                val first = firstSample(extractor, audio)
                val head = first?.second
                val format = extractor.getTrackFormat(audio)
                val mime = format.getString(MediaFormat.KEY_MIME)
                val rawCsd = csdBytesOf(format)
                val hasUsableCsd = rawCsd != null && rawCsd.isNotEmpty()
                // 有些 TS extractor 把整串 ADTS 头当 csd-0 交回来；它对 MP4 不是可用的解码配置。
                val csdIsAdtsHeader = rawCsd != null &&
                    AdtsFrameScanner.frameAt(ByteBuffer.wrap(rawCsd), 0, rawCsd.size) != null
                if (RemuxAudioGuard.replacesDecoderConfig(mime, hasUsableCsd, csdIsAdtsHeader)) {
                    csd = head?.let { AdtsAudioConfig.fromAdtsHeader(it, it.size) }
                }
                RemuxAudioGuard.csdGate(mime, hasUsableCsd && !csdIsAdtsHeader, csd)?.let {
                    return ProbeOutcome.Skipped(it)
                }
                // 拆分重封的前提：轨道声明的采样率优先，容器没给就信 ADTS 头里的索引。
                audioRateHz = intOf(format, MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                    ?: head?.let { AdtsFrameScanner.sampleRateHz(ByteBuffer.wrap(it)) }
                    ?: 0
                splitAudioUnits = RemuxAudioGuard.splitsAudioUnits(
                    mime = mime,
                    sampleRateHz = audioRateHz,
                    firstSampleIsAdts = head != null &&
                        AdtsFrameScanner.frameAt(ByteBuffer.wrap(head), 0, head.size) != null,
                )
                first?.first
            } else {
                null
            }

            val startUs = listOfNotNull(videoStart, audioStart).minOrNull()
                ?: return ProbeOutcome.Skipped("no_tracks")
            return ProbeOutcome.Ready(
                Probe(
                    videoIndex = video.takeIf { it >= 0 },
                    audioIndex = audio.takeIf { it >= 0 },
                    audioCsd = csd,
                    startUs = startUs,
                    audioRateHz = audioRateHz,
                    splitAudioUnits = splitAudioUnits,
                ),
            )
        } finally {
            extractor.release()
        }
    }

    private fun firstSample(extractor: MediaExtractor, track: Int): Pair<Long, ByteArray>? {
        extractor.selectTrack(track)
        return try {
            val buffer = ByteBuffer.allocate(FIRST_SAMPLE_BUFFER_BYTES)
            val size = extractor.readSampleData(buffer, 0)
            if (size <= 0) {
                null
            } else {
                val time = extractor.sampleTime
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes)
                time to bytes
            }
        } finally {
            extractor.unselectTrack(track)
        }
    }

    /**
     * 产物复检：`addTrack` 成功、`writeSampleData` 不报错，都不代表播放器能听到完整声音。
     *
     * `MediaMuxer` 在 PTS 大跳变时会静默丢样本，而且会**把 moov 头部的时长写成完整值**，
     * 还会把剩下的样本重新铺满整条时间轴，所以只看时长、只看首尾 PTS、甚至只看分布密度
     * 都可能被伪装骗过去。这里从头读到尾，按优先级卡六件事：
     * 1. 音轨存在且首个样本读得出来；
     * 2. 相邻音频样本间隔审计（PTS Gap Audit）：任何一次间隔超过 5 秒直接拒绝——
     *    底层能把 moov 时长写成完整值，但变不出那 5 秒的声音；
     * 3. 内容时长比例审计：ADTS 帧数换算的声音长度不足视频跨度 70% 就拒绝——
     *    均匀重铺能让每条判据都看起来正常，只有帧数这种绝对量骗不了；
     * 4. 时间窗口切片审计：视频时长按 10 秒切格，连续 3 格没有音频样本，或空格超过 30%，拒绝；
     * 5. 密度审计：开头几格的样本数作基准，后面的格子薄到基准 20% 以下就拒绝（防均匀重铺）；
     * 6. 音轨最后一个样本的 PTS 与视频最后一个样本相差不超过容差。
     *
     * 间隔与内容两条排在分布审计之前：它们量的分别是「中间空了多久」和「总共有多久声音」，
     * 比分布统计更贴近现场，而这几条判据命中的往往是同一个坏文件。
     *
     * 还有第七道在调用方：拆开重封的产物必须「源 ADTS 帧数 == 写入 AU 数 == 复开样本数」
     * （[RemuxAudioGuard.auCountGate]）。这一条只有 remux 自己知道前两个数，复检拿不到。
     *
     * 无论放行还是拒绝，产物的音频事实都写进 [out]：调用方把源和产物两份数字并排打进审计行，
     * 「声音是在下载侧丢的还是 muxer 侧丢的」才能一眼看出来。
     */
    private fun verifyOutput(
        file: File,
        segmentDurationMs: Long,
        out: AudioScan,
        splitAudioUnits: Boolean,
    ): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioIndex = -1
            var videoIndex = -1
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("audio/") == true -> audioIndex = index
                    mime?.startsWith("video/") == true -> videoIndex = index
                }
            }
            if (audioIndex < 0) return RemuxAudioGuard.OUTPUT_AUDIO_MISSING

            extractor.selectTrack(audioIndex)
            val outFormat = extractor.getTrackFormat(audioIndex)
            out.seedSampleRateHz(sampleRateHzOf(outFormat))
            // 拆开重封的产物载荷是裸 AAC，逐帧走必然数出 0 帧；不声明「一样本一 AU」，
            // 内容比例和密度两条门禁会把我们自己刚写好的合规文件当哑巴拦下来。
            if (splitAudioUnits) out.countOneAuPerSample()
            if (videoIndex >= 0) extractor.selectTrack(videoIndex)

            var buffer = ByteBuffer.allocate(VERIFY_SAMPLE_BUFFER_BYTES)
            val audioWindowCounts = HashMap<Int, Int>()
            var videoFirstPtsUs = Long.MAX_VALUE
            var videoLastPtsUs = Long.MIN_VALUE
            while (true) {
                val index = extractor.sampleTrackIndex
                if (index < 0) break
                val size = extractor.sampleSize
                if (size > buffer.capacity().toLong()) buffer = ByteBuffer.allocate(size.toInt())
                val read = extractor.readSampleData(buffer, 0)
                if (read < 0) break
                val ptsUs = extractor.sampleTime
                when (index) {
                    audioIndex -> {
                        out.add(ptsUs, read, buffer)
                        // 先按绝对 PTS 归格并计数，视频起点偏移在门禁内部再平移。
                        val window = RemuxAudioGuard.sliceWindowIndex(0L, ptsUs)
                        audioWindowCounts[window] = (audioWindowCounts[window] ?: 0) + 1
                    }

                    videoIndex -> {
                        if (ptsUs > videoLastPtsUs) videoLastPtsUs = ptsUs
                        if (ptsUs < videoFirstPtsUs) videoFirstPtsUs = ptsUs
                    }
                }
                if (!extractor.advance()) break
            }

            RemuxAudioGuard.outputGate(audioIndex, out.firstSampleBytes)?.let { return it }
            if (videoIndex < 0 || videoLastPtsUs <= 0L) return null
            RemuxAudioGuard.audioSampleGapGate(out.maxGapUs)?.let { return it }
            val videoStartUs = if (videoFirstPtsUs == Long.MAX_VALUE) 0L else videoFirstPtsUs
            RemuxAudioGuard.contentRatioGate(videoLastPtsUs - videoStartUs, out.contentUs)?.let { return it }
            RemuxAudioGuard.timeSliceGate(videoStartUs, videoLastPtsUs, audioWindowCounts)?.let { return it }
            val toleranceUs = RemuxAudioGuard.avToleranceUs(segmentDurationMs)
            RemuxAudioGuard.avDurationGate(
                videoLastPtsUs = videoLastPtsUs,
                audioLastPtsUs = out.lastPtsUs,
                toleranceUs = toleranceUs,
            )
        } catch (e: Exception) {
            RemuxAudioGuard.OUTPUT_AUDIO_MISSING
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
                // 复检失败本身就是降级理由，释放异常不再覆盖它。
            }
        }
    }

    /** 轨道声明的采样率：缺键、或某些实现存成 Long 而取不到时，都当没给，别把复检带崩。 */
    private fun sampleRateHzOf(format: MediaFormat): Int = intOf(format, MediaFormat.KEY_SAMPLE_RATE)

    /** 整型轨道参数：不同实现同一个键可能存成 Int/Long/Float，取不到就回 [fallback]，诊断取值不允许抛。 */
    private fun intOf(format: MediaFormat, key: String, fallback: Int = 0): Int = try {
        if (format.containsKey(key)) format.getInteger(key) else fallback
    } catch (e: Exception) {
        try {
            format.getFloat(key).toInt()
        } catch (ignored: Exception) {
            fallback
        }
    }

    /** `csd-0` 的原始字节（AAC 就是 2 字节 AudioSpecificConfig）；只进审计行，不参与判定。 */
    private fun csdBytesOf(format: MediaFormat): ByteArray? = try {
        val buffer = if (format.containsKey("csd-0")) format.getByteBuffer("csd-0") else null
        buffer?.let { source ->
            // 必须 duplicate：MediaFormat 给回来的就是它自己那只缓冲，就地 get 会把 position 顶到末尾。
            // 之后 mux.addTrack(同一个 format) 再读就是 remaining=0，MPEG4Writer 报
            // 「Missing codec specific data / 0 frames to dump timeStamps in Audio track」，
            // 整条音轨一帧不写——上一轮真机把带声的 .ts 判成降级，就是这么被探没的。
            val read = source.duplicate()
            read.position(0)
            ByteArray(read.remaining()).also { bytes -> read.get(bytes) }
        }
    } catch (e: Exception) {
        null
    }

    private fun outputDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // 同上：释放异常不改变「文件能不能用」的结论。
            }
        }
    }

    private companion object {
        const val INITIAL_BUFFER_BYTES = 1024 * 1024
        const val FIRST_SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024
        const val VERIFY_SAMPLE_BUFFER_BYTES = 1024 * 1024
    }
}

/**
 * 把「一个样本并了若干 ADTS 帧」的音频重封成合规 mp4a：剥掉头、一帧一个样本、
 * 时间戳按 1024/采样率 从音频起点匀铺。
 *
 * 为什么必须拆：`MediaExtractor` 读 TS 里的 AAC 时实测每样本并了 15.47 帧，而且载荷仍带 ADTS 头
 * （862 秒的片子只有 2611 个样本、里面装着 40411 个 AU）。直接抄进 MP4，Android 自己的解码器
 * 认这种写法，相册、网页、电视盒子、剪辑软件这类严格按「一样本一 AU、不带 ADTS 头」走的解码器
 * 就只解得出每样本第一个 AU——862 秒只剩 55 秒，听感就是「没声音」。而这一切发生时时间戳一条没丢、
 * 密度均匀、首尾对齐：原有的六条门禁全绿。
 *
 * 为什么时间戳不跟源样本的 PTS 走：合规 AAC 每帧时长恒等于 `1024/采样率`，源里每样本并几帧是
 * **打包**的决定，不是**时间**的决定。跟源样本走会两头出错——某样本并到第 16 帧就越过下一个样本的
 * 起点（时间戳倒退，把好流误判成坏流而整条放弃）；源里真有几十秒空洞时，又恰恰把空洞原样复刻进产物。
 * 这里从第一个音频样本的 PTS 起按 AU 序号匀铺：单调性由构造保证，音频总时长等于
 * 「AU 数 × 1024/采样率」这个绝对量。真有空洞的流会因此「提前收尾」，由音视频末尾对齐门禁拦下；
 * 注水的流由内容时长比例门禁拦下——两条都只看 AU 数，不依赖源时间轴的形状，反而更准。
 *
 * [sink] 只在同一时刻拿到那只复用缓冲，必须**立刻**把它写走（`MediaMuxer.writeSampleData`
 * 就是同步拷贝），不许存下来稍后再用。缓冲的位置恒为 0、长度恒等于 [MediaCodec.BufferInfo.size]，
 * 因为 `writeSampleData` 对堆缓冲和直连缓冲走两条路径，靠 offset 推断起点最容易出错。
 */
internal class AudioUnitWriter(
    private val rateHz: Int,
    private val sink: (payload: ByteBuffer, size: Int, ptsUs: Long) -> Unit,
) {

    private var payload = ByteBuffer.allocate(AU_BUFFER_BYTES)
    private var startUs = -1L
    private var totalAus = 0

    /**
     * 写出 [source] 前 [size] 字节里的每一个 AU，返回写了几个；返回 0 表示这一路拆不下去，
     * 调用方据此放弃整条流——半拆半不拆的产物是只有严格播放器才会踩的混合封装，不能赌。
     *
     * [basePtsUs] 只在第一个样本被读取时采用，作为整条音频时间轴的起点；之后一律由 AU 序号推导。
     */
    fun write(source: ByteBuffer, size: Int, basePtsUs: Long): Int {
        if (startUs < 0L) startUs = basePtsUs
        var offset = 0
        var index = 0
        while (true) {
            val frame = AdtsFrameScanner.frameAt(source, offset, size) ?: break
            if (frame.payloadSize <= 0) return 0
            if (frame.payloadSize > payload.capacity()) payload = ByteBuffer.allocate(frame.payloadSize)
            payload.clear()
            // 每帧另开一只视图：position/limit 只能各用一次，复用同一只会在第二帧
            // 「newPosition(47) > limit(40)」当场炸掉——真机每个样本并十几帧，第一帧之后就是这条路径。
            val slice = source.duplicate()
            slice.position(frame.payloadStart)
            slice.limit(frame.payloadStart + frame.payloadSize)
            payload.put(slice)
            payload.flip()
            sink(payload, frame.payloadSize, AdtsFrameScanner.auPtsUs(startUs, totalAus, rateHz))
            offset = frame.nextOffset
            totalAus++
            index++
        }
        return index
    }

    /** 已经写出的 AU 总数，就是产物的音频样本数；[RemuxAudioGuard.auCountGate] 拿它对账。 */
    val writtenAus: Int get() = totalAus

    private companion object {
        /** 一帧 AAC 常见上百字节，先按 4KB 起步，超出时按需长大。 */
        const val AU_BUFFER_BYTES = 4 * 1024
    }
}
