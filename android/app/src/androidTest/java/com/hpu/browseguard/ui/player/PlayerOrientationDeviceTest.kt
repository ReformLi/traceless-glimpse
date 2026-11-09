package com.hpu.browseguard.ui.player

import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpu.browseguard.ui.PlayerOrientationEffect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 方向**真的落到 Activity** 的运行时断言（instrumented）。
 *
 * 状态层的 `effectiveOrientation()` 只能证明「算得对」，证明不了「有人把它写进窗口」。
 * 这里挂生产代码里那个 [PlayerOrientationEffect]，直接读宿主 Activity 的 `requestedOrientation`：
 *
 * - 自动：横版视频 → `SENSOR_LANDSCAPE`；
 * - 手选：切过之后自动回调不许把它顶掉；
 * - 锁定：冻在 `LOCKED`（禁用 sensor 的唯一常量），解锁回到自动；
 * - 离场：还原 `PORTRAIT`（manifest 里钉的竖屏），不能写 `UNSPECIFIED`——那等于交还 sensor，
 *   默认横放的模拟器上退出播放页后整个 App 会一直停在横屏。
 */
@RunWith(AndroidJUnit4::class)
class PlayerOrientationDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<PlayerOrientationTestActivity>()

    private fun mount(state: PlayerControlsState) {
        compose.setContent { PlayerOrientationEffect(compose.activity, state) }
    }

    /** 只在测试线程上读一个字段，不碰窗口，不必绕回主线程。 */
    private fun requested(): Int = compose.activity.requestedOrientation

    @Test
    fun aLandscapeVideoTurnsTheActivitySideways() {
        val state = PlayerControlsState()
        mount(state)
        assertEquals(
            "还没读到视频尺寸时就是竖屏（全应用钉竖屏）",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requested(),
        )

        compose.runOnIdle { state.isLandscapeVideo = true }
        compose.waitForIdle()
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            requested(),
        )
    }

    @Test
    fun aManualChoiceOutlivesTheVideoSizeCallback() {
        val state = PlayerControlsState()
        mount(state)

        compose.runOnIdle { state.isLandscapeVideo = true }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())

        compose.runOnIdle { state.toggleOrientation() }
        compose.waitForIdle()
        assertEquals("手动切竖屏要真的写进窗口", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, requested())

        compose.runOnIdle { state.isLandscapeVideo = false }
        compose.waitForIdle()
        assertEquals("自动规则不许把手选顶掉", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, requested())
    }

    @Test
    fun lockingFreezesTheOrientationUntilUnlocked() {
        val state = PlayerControlsState()
        mount(state)

        compose.runOnIdle { state.isLandscapeVideo = true }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())

        compose.runOnIdle { state.lock() }
        compose.waitForIdle()
        assertEquals("锁定后冻在当前方向", ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested())

        compose.runOnIdle { state.isLandscapeVideo = false }
        compose.waitForIdle()
        assertEquals("锁定后不再响应视频尺寸", ActivityInfo.SCREEN_ORIENTATION_LOCKED, requested())

        compose.runOnIdle { state.unlock() }
        compose.waitForIdle()
        assertEquals("解锁后回到竖屏基线", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, requested())
    }

    @Test
    fun leavingThePlayerRestoresTheDefaultOrientation() {
        val state = PlayerControlsState()
        var mounted by mutableStateOf(true)
        compose.setContent {
            if (mounted) PlayerOrientationEffect(compose.activity, state)
        }

        compose.runOnIdle { state.isLandscapeVideo = true }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, requested())

        // 整棵内容拆掉 = 离开播放页：DisposableEffect 的 onDispose 必须把方向还原成竖屏。
        compose.runOnIdle { mounted = false }
        compose.waitForIdle()
        assertEquals(
            "退出播放页要还原竖屏，否则整个 App 会停在横屏",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            requested(),
        )
    }
}
