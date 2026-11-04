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
    ) : RemuxResult

    /** 不转：调用方保留 `.ts`，并如实告诉用户系统播放器可能不认。 */
    data class Skipped(val reason: String) : RemuxResult

    data object Cancelled : RemuxResult
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
                probe.audioCsd?.let { csd -> format.setByteBuffer("csd-0", ByteBuffer.wrap(csd)) }
                val out = try {
                    mux.addTrack(format)
                } catch (e: IllegalArgumentException) {
                    return RemuxResult.Skipped("audio_track_rejected")
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
                    return RemuxResult.Skipped(if (isVideo) "video_pts_negative" else "audio_pts_negative")
                }
                val verdict = if (isVideo) gate.checkVideo(ptsUs) else gate.checkAudio(ptsUs)
                if (verdict is PtsVerdict.Reject) return RemuxResult.Skipped(verdict.reason)

                val flags = if (isVideo && ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, read, ptsUs, flags)
                mux.writeSampleData(outIndex, buffer, info)
                if (isVideo) videoSamples++ else audioSamples++
                if (!ex.advance()) break
            }

            if (videoSamples == 0 && audioSamples == 0) return RemuxResult.Skipped("no_samples")
            // 视频正常但音频样本为 0，就是「只有画面」的哑巴文件——宁可保留 .ts。
            RemuxAudioGuard.sampleGate(audioSamples)?.let { return RemuxResult.Skipped(it) }

            mux.stop()
            mux.release()
            muxer = null

            verifyOutput(target, segmentDurationMs)?.let { return RemuxResult.Skipped(it) }

            val durationMs = outputDurationMs(target) ?: return RemuxResult.Skipped("output_unreadable")
            if (expectedDurationMs > 0L && durationMs < expectedDurationMs * 3 / 4) {
                return RemuxResult.Skipped("duration_short")
            }
            if (expectedDurationMs > 0L && durationMs > expectedDurationMs * 3 / 2 + 30_000L) {
                return RemuxResult.Skipped("duration_too_long")
            }

            finished = true
            RemuxResult.Done(
                bytes = target.length(),
                durationMs = durationMs,
                videoSamples = videoSamples,
                audioSamples = audioSamples,
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
            val audioStart = if (audio >= 0) {
                val first = firstSample(extractor, audio)
                val format = extractor.getTrackFormat(audio)
                val mime = format.getString(MediaFormat.KEY_MIME)
                val csdSize = if (format.containsKey("csd-0")) {
                    format.getByteBuffer("csd-0")?.remaining() ?: 0
                } else {
                    0
                }
                val hasUsableCsd = csdSize > 0
                if (!hasUsableCsd && mime == RemuxAudioGuard.AAC_MIME) {
                    csd = first?.second?.let { AdtsAudioConfig.fromAdtsHeader(it, it.size) }
                }
                RemuxAudioGuard.csdGate(mime, hasUsableCsd, csd)?.let {
                    return ProbeOutcome.Skipped(it)
                }
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
     * `MediaMuxer` 在 PTS 大跳变时会静默丢样本——文件照常写完，音轨却只剩开头一小段。
     * 所以这里从头读到尾，同时卡三件事：
     * 1. 音轨存在且首个样本读得出来；
     * 2. 音轨最后一个样本的 PTS 与视频最后一个样本相差不超过容差；
     * 3. 相邻音频样本的 PTS 间隔不超过容差（中段整段丢失时，首尾可能都对得上）。
     */
    private fun verifyOutput(file: File, segmentDurationMs: Long): String? {
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
            if (videoIndex >= 0) extractor.selectTrack(videoIndex)

            var buffer = ByteBuffer.allocate(VERIFY_SAMPLE_BUFFER_BYTES)
            var audioFirstRead = -1
            var audioPrevPtsUs = Long.MIN_VALUE
            var audioLastPtsUs = Long.MIN_VALUE
            var audioMaxGapUs = 0L
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
                        if (audioFirstRead < 0) audioFirstRead = read
                        if (audioPrevPtsUs != Long.MIN_VALUE) {
                            val gap = ptsUs - audioPrevPtsUs
                            if (gap > audioMaxGapUs) audioMaxGapUs = gap
                        }
                        audioPrevPtsUs = ptsUs
                        if (ptsUs > audioLastPtsUs) audioLastPtsUs = ptsUs
                    }

                    videoIndex -> if (ptsUs > videoLastPtsUs) videoLastPtsUs = ptsUs
                }
                if (!extractor.advance()) break
            }

            RemuxAudioGuard.outputGate(audioIndex, audioFirstRead)?.let { return it }
            if (videoIndex < 0 || videoLastPtsUs <= 0L) return null
            val toleranceUs = RemuxAudioGuard.avToleranceUs(segmentDurationMs)
            RemuxAudioGuard.avDurationGate(
                videoLastPtsUs = videoLastPtsUs,
                audioLastPtsUs = audioLastPtsUs,
                toleranceUs = toleranceUs,
            )?.let { return it }
            RemuxAudioGuard.audioGapGate(audioMaxGapUs, toleranceUs)
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
