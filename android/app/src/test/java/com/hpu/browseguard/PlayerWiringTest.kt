package com.hpu.browseguard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放器手势与实例生命周期的接线钉子（JVM 跑不动窗口属性与 Media3，只能钉源码）。
 *
 * 两件事各自对应一条 M12 遗留：
 * - 亮度手势在模拟器上看不出画面变化，能证的是「它真的落到 `window.attributes.screenBrightness`，
 *   离场也真的还原」——MuMu 上取不到背光，这条只能这样钉（实体机复核亮度仍挂在待办里）。
 * - 「BACK 偶发回到旧播放器实例」要求实例绝对被销毁：release 之后组合外面再摸播放器只能摸到 null。
 */
class PlayerWiringTest {

    @Test
    fun brightnessAndVolumeGesturesActuallyReachTheSystem() {
        val screen = File("$SRC_ROOT/ui/VideoPlayerScreen.kt").readText()

        assertTrue("亮度落到窗口属性", screen.contains("screenBrightness = value"))
        assertTrue("窗口属性要整体写回，只改 attributes 对象不生效", screen.contains("window.attributes = window.attributes"))
        assertTrue("离场要把窗口亮度还原，否则退出播放器后全站跟着变暗", screen.contains("screenBrightness = initialWindowBrightness"))
        assertTrue("亮度算法只有一个出处", screen.contains("PlayerGestureMath.brightnessFor(startBrightness, dy / height)"))
        assertTrue("音量手势要落到媒体流", screen.contains("setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)"))
        assertTrue("快进快退只在松手时落位", screen.contains("player.seekTo(target)"))
    }

    @Test
    fun thePlayerInstanceIsReleasedAndDroppedOnLeave() {
        val screen = File("$SRC_ROOT/ui/VideoPlayerScreen.kt").readText()

        assertTrue("离场必须 release", screen.contains("player.release()"))
        assertTrue("release 后把手置空：组合外晚一步的回调只准摸到 null", screen.contains("livePlayer = null"))
        assertTrue("PlayerView 也要先摘掉播放器", screen.contains("playerView?.player = null"))
        assertTrue("换记录整棵子树重建，不给 Composition 复用旧播放器", screen.contains("key(recordId) {"))
        assertTrue("离场先回写续播点", screen.contains("onFlush(player.contentPosition"))
        assertTrue("小窗回调经把手取当前实例，不写死进场那一只", screen.contains("val current = latestPlayer"))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
