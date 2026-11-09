package com.hpu.browseguard.ui.player

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 播放器手势与倍速的纯计算。界面只负责采集手指位移和应用副作用（seek/亮度/音量），
 * 这些数都放在这里，JVM 单测能直接钉住边界。
 */
internal object PlayerGestureMath {

    /** 横向滑满一屏对应的快进/快退时长；90 秒接近主流播放器的手感。 */
    const val SEEK_MS_PER_SCREEN = 90_000L

    /** 屏幕亮度按窗口值 0..1 调；0 会让画面全黑，留一点下限。 */
    const val MIN_BRIGHTNESS = 0.01f
    const val MAX_BRIGHTNESS = 1f

    val SPEED_OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** [dragFraction] 是横向位移占屏幕宽度的比例，右正左负。 */
    fun seekTargetMs(positionMs: Long, durationMs: Long, dragFraction: Float): Long {
        val delta = (dragFraction * SEEK_MS_PER_SCREEN).toLong()
        val raw = (positionMs + delta).coerceAtLeast(0L)
        return if (durationMs > 0L) raw.coerceAtMost(durationMs) else raw
    }

    /** [dyFraction] 是纵向位移占屏幕高度的比例，下正上负；向上是增大。 */
    fun brightnessFor(start: Float, dyFraction: Float): Float =
        (start - dyFraction).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

    fun volumeFor(startVolume: Int, dyFraction: Float, maxVolume: Int): Int {
        if (maxVolume <= 0) return 0
        return (startVolume - dyFraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
    }

    /** `0.5x` / `0.75x` / `1.0x`：最多两位小数，整档保一位。 */
    fun speedLabel(speed: Float): String {
        val rounded = (speed * 100f).roundToInt() / 100f
        val text = if ((rounded * 10f).roundToInt() / 10f == rounded) {
            "%.1f".format(Locale.US, rounded)
        } else {
            "%.2f".format(Locale.US, rounded)
        }
        return "${text}x"
    }

    /**
     * 松手时算不算「点一下」：位移的欧氏距离不超过 [touchSlop] 才算。
     *
     * 锁定态三条手势轴全关，滑动也会落到同一条收尾分支——不判这个阈值，
     * 用户在锁定画面上划一下就会把解锁按钮叫出来。
     */
    fun isTap(dx: Float, dy: Float, touchSlop: Float): Boolean =
        dx * dx + dy * dy <= touchSlop * touchSlop

    /** 双击窗口；两击间隔超过它就是两次独立的单击。 */
    const val DOUBLE_TAP_MS = 300L

    /**
     * 这次抬起能不能和上一次点击凑成双击：间隔不超窗口、且两次落点几乎重合。
     * 距离用平方判，与 [isTap] 同一套手感阈值。
     */
    fun isDoubleTap(elapsedMs: Long, dx: Float, dy: Float, touchSlop: Float): Boolean =
        elapsedMs <= DOUBLE_TAP_MS && dx * dx + dy * dy <= touchSlop * touchSlop
}
