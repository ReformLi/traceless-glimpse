package com.hpu.browseguard.data.backend.remux

internal sealed interface PtsVerdict {
    data object Ok : PtsVerdict
    data class Reject(val reason: String) : PtsVerdict
}

/**
 * 时间戳闸门：只判定「这段 PTS 还能不能安全地塞进 MP4」，不改写样本时间。
 *
 * 视频不做单调夹紧——B 帧在解码顺序里 PTS 本来就会倒退，夹紧会把显示顺序改坏，
 * 正是「后半段卡死」的来源。这里只拒绝两类真正危险的信号：
 * - 倒退幅度超过 B 帧重排窗口（切片拼接处的时间戳跳变）；
 * - 前跳超过一个切片的量级（另一段时间轴接进来了）。
 *
 * 宁可拒绝、退回 `.ts`，也不产出一个「能生成但播不了」的 MP4。
 */
internal class PtsGate(
    private val videoBackwardToleranceUs: Long = 2_000_000L,
    private val audioBackwardToleranceUs: Long = 100_000L,
    private val forwardGapToleranceUs: Long = 5_000_000L,
) {
    private var videoMaxUs: Long? = null
    private var audioMaxUs: Long? = null

    fun checkVideo(ptsUs: Long): PtsVerdict {
        val max = videoMaxUs
        if (max != null) {
            if (ptsUs < max - videoBackwardToleranceUs) return PtsVerdict.Reject("video_pts_backward")
            if (ptsUs > max + forwardGapToleranceUs) return PtsVerdict.Reject("video_pts_gap")
        }
        videoMaxUs = if (max == null) ptsUs else maxOf(max, ptsUs)
        return PtsVerdict.Ok
    }

    fun checkAudio(ptsUs: Long): PtsVerdict {
        val max = audioMaxUs
        if (max != null) {
            if (ptsUs < max - audioBackwardToleranceUs) return PtsVerdict.Reject("audio_pts_backward")
            if (ptsUs > max + forwardGapToleranceUs) return PtsVerdict.Reject("audio_pts_gap")
        }
        audioMaxUs = if (max == null) ptsUs else maxOf(max, ptsUs)
        return PtsVerdict.Ok
    }
}
