package com.hpu.browseguard.data.history

/**
 * 播放进度落库前的那一次归一。
 *
 * 库里的 `lastPlayedPositionMs` 只有一个含义：下次打开就该从这里接着播。
 * 所以两种位置一律写成 0——「其实已经看完了」（回列表再点是重看，不是跳回片尾）、
 * 「才刚开始」（起播缓冲、误触 seek 都落在这一带，存下来只会让用户以为片子自己动了）。
 *
 * 阈值放在写入侧而不是读取侧：界面拿到的数永远是可直接可用的续播点，
 * 不必每个入口再判一次「这个 99% 到底算不算看完」。
 */
object PlaybackResume {

    /** 距结尾不足这段：视为看完。 */
    const val FINISH_MARGIN_MS = 15_000L

    /** 进度超过这个比例同样视为看完：长片子 15 秒太短，按比例更贴近「剩下那点不值得回看」。 */
    private const val FINISH_RATIO = 0.97

    /** 起播头几秒不记：这段时间基本都在缓冲，记下的位置也不属于用户真正看到的地方。 */
    const val NOISE_FLOOR_MS = 2_000L

    /** 归一后的续播点；[durationMs] 非正数（时长还不知道）时只削掉头几秒的抖动。 */
    fun resumePositionMs(positionMs: Long, durationMs: Long): Long {
        if (positionMs <= 0L) return 0L
        if (positionMs < NOISE_FLOOR_MS) return 0L
        if (durationMs <= 0L) return positionMs
        val nearEnd = positionMs >= durationMs - FINISH_MARGIN_MS ||
            positionMs >= durationMs * FINISH_RATIO
        return if (nearEnd) 0L else positionMs
    }

    /** 要写进库的那一对数：续播点归一，时长原样带上（0 表示播放器也没给）。 */
    fun stored(positionMs: Long, durationMs: Long): Pair<Long, Long> =
        resumePositionMs(positionMs, durationMs) to if (durationMs > 0L) durationMs else 0L
}
