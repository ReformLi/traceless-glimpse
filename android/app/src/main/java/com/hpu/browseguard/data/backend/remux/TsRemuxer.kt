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
        isCancelled: () -> Boolean = { false },
    ): RemuxResult {
        if (!source.isFile || source.length() <= 0L) return RemuxResult.Skipped("empty_source")

        val probe = try {
            probe(source) ?: return RemuxResult.Skipped("no_tracks")
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

            mux.stop()
            mux.release()
            muxer = null

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

    /** 只读每条轨道的首样本：拿起始 PTS，并给缺 `csd-0` 的 AAC 合成配置。 */
    private fun probe(source: File): Probe? {
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
            if (video < 0 && audio < 0) return null

            val videoStart = if (video >= 0) firstSample(extractor, video)?.first else null
            var csd: ByteArray? = null
            val audioStart = if (audio >= 0) {
                val first = firstSample(extractor, audio)
                val format = extractor.getTrackFormat(audio)
                if (!format.containsKey("csd-0") && format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    csd = first?.second?.let { AdtsAudioConfig.fromAdtsHeader(it, it.size) }
                }
                first?.first
            } else {
                null
            }

            val startUs = listOfNotNull(videoStart, audioStart).minOrNull() ?: return null
            return Probe(
                videoIndex = video.takeIf { it >= 0 },
                audioIndex = audio.takeIf { it >= 0 },
                audioCsd = csd,
                startUs = startUs,
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
    }
}
