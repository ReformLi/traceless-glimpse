package com.hpu.browseguard.ui.player

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpu.browseguard.ui.theme.BgvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 顶栏两个功能键的运行时断言（instrumented）。
 *
 * 三条判据都来自状态层：`rotateEnabled()` / `pipEnabled()`。
 * 锁定态那一问这里断的是 `assertDoesNotExist` 而不是 `assertIsNotEnabled`——
 * 阶段 2 定了「锁定后整条顶栏离开组合」，节点根本不在树里，`isNotEnabled` 只会报 no node found。
 * 「锁定 = 死键」这条由 PlayerControlsStateTest 在状态层钉住，两端各管一半。
 */
@RunWith(AndroidJUnit4::class)
class PlayerTopBarActionsDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showControls(
        state: PlayerControlsState,
        onRotate: () -> Unit = {},
        onEnterPip: () -> Unit = {},
    ) {
        compose.setContent {
            BgvTheme {
                PlayerControls(
                    state = state,
                    title = "顶栏按钮测试",
                    onClose = {},
                    onTogglePlay = {},
                    onSeekTo = {},
                    onSpeedSelected = {},
                    onOpenWith = {},
                    onToggleOrientation = onRotate,
                    onEnterPip = onEnterPip,
                )
            }
        }
    }

    @Test
    fun bothActionsAreClickableOnceThePlayerIsReady() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 100_000L)
        state.onPlaybackReady()
        state.isLandscapeVideo = true
        var rotations = 0
        var pips = 0
        showControls(
            state = state,
            onRotate = {
                rotations++
                state.toggleOrientation()
            },
            onEnterPip = { pips++ },
        )

        compose.onNodeWithTag(PlayerTestTags.RotateButton).assertIsEnabled()
        compose.onNodeWithTag(PlayerTestTags.PipButton).assertIsEnabled()

        compose.onNodeWithTag(PlayerTestTags.RotateButton).performClick()
        compose.onNodeWithTag(PlayerTestTags.PipButton).performClick()
        compose.runOnIdle {
            assertEquals("旋转键要真的回调出去", 1, rotations)
            assertEquals("PiP 键要真的回调出去", 1, pips)
            assertEquals(
                "点一下就从横切到竖",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                state.orientationOverride,
            )
        }
    }

    @Test
    fun thePipButtonIsDeadBeforeThePlayerIsReady() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 100_000L)
        showControls(state)

        compose.onNodeWithTag(PlayerTestTags.PipButton).assertIsNotEnabled()
        // 旋转不依赖播放器：还没就绪也该能转。
        compose.onNodeWithTag(PlayerTestTags.RotateButton).assertIsEnabled()
    }

    @Test
    fun lockingTakesBothActionsAway() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 100_000L)
        state.onPlaybackReady()
        showControls(state)

        compose.onNodeWithTag(PlayerTestTags.RotateButton).assertIsEnabled()

        compose.runOnIdle { state.lock() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.onNodeWithTag(PlayerTestTags.RotateButton).assertDoesNotExist()
        compose.onNodeWithTag(PlayerTestTags.PipButton).assertDoesNotExist()
    }
}
