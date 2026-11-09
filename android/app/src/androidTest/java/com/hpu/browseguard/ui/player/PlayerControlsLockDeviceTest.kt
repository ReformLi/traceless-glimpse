package com.hpu.browseguard.ui.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hpu.browseguard.ui.theme.BgvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定态的**运行时**断言（instrumented）。
 *
 * 这一条以前是 JVM 里的源码契约，靠数「`if (state.locked)` 缩进 8 空格」判断它有没有被塞进
 * `AnimatedVisibility`——注释一改、格式化一跑就可能误导，而且它证明不了淡出结束后节点真的还在。
 * 改成真跑一遍组合：锁上 → 等 120ms 淡出走完 → 直接查节点。
 *
 * 对应需求：锁定后只留只读进度条，顶栏底栏一律不得留在画面上。
 */
@RunWith(AndroidJUnit4::class)
class PlayerControlsLockDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun newState() = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 100_000L)

    private fun showControls(state: PlayerControlsState) {
        compose.setContent {
            BgvTheme {
                PlayerControls(
                    state = state,
                    title = "锁定态测试",
                    onClose = {},
                    onTogglePlay = {},
                    onSeekTo = {},
                    onSpeedSelected = {},
                    onOpenWith = {},
                    onToggleOrientation = {},
                    onEnterPip = {},
                )
            }
        }
    }

    @Test
    fun lockingTakesBothBarsAwayAndKeepsTheReadOnlyBar() {
        val state = newState()
        showControls(state)

        compose.onNodeWithTag(PlayerTestTags.TopBar).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTestTags.BottomBar).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTestTags.LockedBar).assertDoesNotExist()

        compose.runOnIdle { state.lock() }
        compose.waitForIdle()
        // 淡出是 120ms；多等一点，确保「动画结束后」这个时刻被真正跨过去。
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        // 关键断言：只读进度条必须还在组合里。它一旦被塞进 AnimatedVisibility，
        // 淡出结束时会跟着内容一起消失，这里就会红。
        compose.onNodeWithTag(PlayerTestTags.LockedBar).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTestTags.TopBar).assertDoesNotExist()
        compose.onNodeWithTag(PlayerTestTags.BottomBar).assertDoesNotExist()
    }

    @Test
    fun unlockingBringsTheBarsBackAndDropsTheReadOnlyBar() {
        val state = newState()
        showControls(state)

        compose.runOnIdle { state.lock() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.onNodeWithTag(PlayerTestTags.TopBar).assertDoesNotExist()

        compose.runOnIdle { state.unlock() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        compose.onNodeWithTag(PlayerTestTags.TopBar).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTestTags.BottomBar).assertIsDisplayed()
        compose.onNodeWithTag(PlayerTestTags.LockedBar).assertDoesNotExist()
    }
}
