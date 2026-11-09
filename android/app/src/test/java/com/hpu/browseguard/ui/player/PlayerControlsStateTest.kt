package com.hpu.browseguard.ui.player

import android.content.pm.ActivityInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制条状态的行为钉子（阶段 1）。
 *
 * 这些数以前散在 `VideoPlayerScreen` 的十几个 `remember` 里，没有一处能被单测摸到。
 * 收进 [PlayerControlsState] 之后，文案、显隐时机、拖动落点都能在这里钉住——
 * 不需要 Robolectric，也不引任何 compose 测试依赖（`SnapshotStateProbeTest` 证明 snapshot state 可用）。
 */
class PlayerControlsStateTest {

    // ---- 倍速文案：1.0 显示「倍速」，其余显示 Nx ----

    @Test
    fun theSpeedChipSaysJustTheLabelAtNormalSpeed() {
        val state = PlayerControlsState()
        assertEquals("1.0 倍不该写成「倍速 1.0x」", "倍速", state.speedChipText("倍速"))
    }

    @Test
    fun theSpeedChipShowsTheMultiplierAtOtherSpeeds() {
        val state = PlayerControlsState()
        val expected = mapOf(0.5f to "0.5x", 0.75f to "0.75x", 1.25f to "1.25x", 1.5f to "1.5x", 2f to "2.0x")
        expected.forEach { (speed, label) ->
            state.speed = speed
            assertEquals(label, state.speedChipText("倍速"))
        }
    }

    // ---- 控制条显隐 ----

    @Test
    fun theControlsHideThemselvesAfterThreeAndAHalfSecondsWhilePlaying() {
        val state = PlayerControlsState()
        state.isPlaying = true
        assertEquals(3_500L, state.autoHideDelayMs())
    }

    @Test
    fun theControlsStayPutWhilePaused() {
        val state = PlayerControlsState()
        state.isPlaying = false
        assertNull("暂停时不收起，否则看不到暂停在哪", state.autoHideDelayMs())
    }

    @Test
    fun theControlsStayPutWhileTheSpeedPanelIsOpen() {
        val state = PlayerControlsState()
        state.isPlaying = true
        state.toggleSpeedMenu()
        assertTrue(state.speedMenuShown)
        assertNull("面板开着就收起，下次显示会突然多出一排按钮", state.autoHideDelayMs())
    }

    @Test
    fun hidingTheControlsAlsoClosesTheSpeedPanel() {
        val state = PlayerControlsState()
        state.toggleSpeedMenu()
        state.hideControls()
        assertFalse(state.controlsShown)
        assertFalse("收起要一次收干净", state.speedMenuShown)
    }

    @Test
    fun enteringPipHidesEverything() {
        val state = PlayerControlsState()
        state.toggleSpeedMenu()
        state.onPipEntered()
        assertFalse(state.controlsShown)
        assertFalse(state.speedMenuShown)
    }

    @Test
    fun lockingStartsOff() {
        assertFalse(PlayerControlsState().locked)
    }

    // ---- 锁定态：控件全收，只留只读进度条 ----

    @Test
    fun lockingHidesTheControlsAndClosesTheSpeedPanel() {
        val state = PlayerControlsState()
        state.isPlaying = true
        state.toggleSpeedMenu()
        state.lock()
        assertTrue(state.locked)
        assertFalse("锁定后控件要收起来", state.controlsShown)
        assertFalse("倍速面板不能留在锁定态里", state.speedMenuShown)
        assertFalse("锁定后控件整体不可见", state.controlsVisible)
    }

    @Test
    fun aLockedScreenNeverSchedulesAutoHide() {
        val state = PlayerControlsState()
        state.isPlaying = true
        state.lock()
        assertNull("控件本就不在，不该再排一次收起", state.autoHideDelayMs())
    }

    @Test
    fun aLockedScreenCannotSummonTheControlsBack() {
        val state = PlayerControlsState()
        state.lock()
        state.hideControls()
        state.toggleControls()
        assertFalse("锁定时 hideControls / toggleControls 都不该把控件唤回来", state.controlsShown)
        assertFalse(state.controlsVisible)
    }

    @Test
    fun theLockedProgressBarIsReadOnly() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 200_000L)
        state.lock()
        state.beginScrub(0.25f)
        state.moveScrub(0.75f)
        assertNull("锁定态下不许写拖动值", state.scrubTo)
        assertNull("锁定态下 commitScrub 必须返回 null（调用方据此不 seek）", state.commitScrub())
    }

    @Test
    fun unlockingBringsTheControlsBackAndRestartsTheTimer() {
        val state = PlayerControlsState()
        state.isPlaying = true
        state.lock()
        assertNull(state.autoHideDelayMs())
        state.unlock()
        assertFalse(state.locked)
        assertTrue("解锁后 controlsShown 必须真的回到 true，否则会留下一块空白画面", state.controlsShown)
        assertTrue("解锁后控件回到显示态", state.controlsVisible)
        assertEquals("解锁后自动隐藏重新计时", 3_500L, state.autoHideDelayMs())
    }

    @Test
    fun tappingTheLockedScreenOnlyTogglesTheHud() {
        val state = PlayerControlsState()
        state.lock()
        assertTrue("刚锁上 HUD 就要露出来：锁定按钮是最快的解锁口", state.lockedHudShown)
        state.onSurfaceTap()
        assertFalse("锁定态点一下屏幕收起 HUD", state.lockedHudShown)
        state.onSurfaceTap()
        assertTrue("再点一下再露出，来回只切显隐", state.lockedHudShown)
        assertTrue("点屏不许解锁，也不许把控件叫回来", state.locked && !state.controlsShown)
    }

    @Test
    fun anUnlockedScreenStillTogglesTheControlsOnTap() {
        val state = PlayerControlsState()
        state.onSurfaceTap()
        assertFalse(state.controlsShown)
        state.onSurfaceTap()
        assertTrue(state.controlsShown)
        assertTrue("未锁定时锁定按钮常驻，不参与显隐切换", !state.locked)
    }

    @Test
    fun togglingLockGoesBothWays() {
        val state = PlayerControlsState()
        state.toggleLock()
        assertTrue(state.locked)
        state.toggleLock()
        assertFalse(state.locked)
    }

    /** 解锁必须把控件真的交还回来：只清 `locked` 不清 `controlsShown`，解锁后会剩一块空白画面。 */
    @Test
    fun unlockingGivesTheControlsBack() {
        val state = PlayerControlsState()
        state.lock()
        assertFalse(state.controlsShown)
        state.unlock()
        assertTrue("解锁后 controlsShown 要回到 true", state.controlsShown)
        assertTrue("控件必须重新可见", state.controlsVisible)
    }

    /** 锁 → 点屏 → 解锁 → 再锁：四步下来每个字段都得对，别留上一轮的残值。 */
    @Test
    fun lockingUnlockingAndLockingAgainKeepsTheStateMachineConsistent() {
        val state = PlayerControlsState()
        state.isPlaying = true

        state.lock()
        assertTrue(state.locked)
        assertFalse(state.controlsVisible)
        assertNull(state.autoHideDelayMs())

        state.onSurfaceTap()
        assertFalse("锁定态点屏收起 HUD，不许把控件叫回来", state.lockedHudShown)
        assertFalse(state.controlsShown)

        state.unlock()
        assertFalse(state.locked)
        assertTrue(state.controlsShown)
        assertTrue(state.controlsVisible)
        assertEquals("解锁后重新开始 3.5s 计时", 3_500L, state.autoHideDelayMs())

        state.lock()
        assertTrue(state.locked)
        assertFalse(state.controlsShown)
        assertFalse(state.speedMenuShown)
        assertTrue("重新锁定后 HUD 恢复显示", state.lockedHudShown)
    }

    /**
     * 锁定态那根只读进度条不许有任何触摸入口：一旦它带上 `pointerInput` 或 `clickable`，
     * 用户就能在锁定后拖动它——「只读」这条需求就破了。
     */
    @Test
    fun theLockedBarHasNoTouchHandler() {
        val source = File("$SRC_ROOT/ui/player/PlayerControls.kt").readText()
        val body = source.substringAfter("private fun LockedProgressBar(").substringBefore("@Composable")
        assertTrue("锁定态进度条是自绘的", body.contains("Canvas("))
        listOf("pointerInput", "clickable", "seekTo", "beginScrub", "toggleLock").forEach {
            assertFalse("只读进度条里不该出现 $it", body.contains(it))
        }
    }

    // ---- 拖动进度条：up 提交，cancel 丢弃 ----

    @Test
    fun committingAScrubHandsBackTheTargetAndClearsTheDrag() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 200_000L)
        state.beginScrub(0.25f)
        state.moveScrub(0.5f)
        assertEquals(100_000L, state.commitScrub())
        assertNull("落点交出去之后拖动值要清掉", state.scrubTo)
        assertNull("没拖动时再交一次必须是 null", state.commitScrub())
    }

    @Test
    fun committingWithoutDurationProducesNoTarget() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 0L)
        state.beginScrub(0.5f)
        assertNull("时长还没读出来，不能按比例算出一个假落点", state.commitScrub())
        assertNull(state.scrubTo)
    }

    /**
     * 取消路径：手指被系统抢走（来电、切后台、事件流中断）时，进度条必须只丢掉拖动值。
     * 一旦这里回了一个落点，播放器就会被跳到没人要的位置上。
     */
    @Test
    fun cancellingAScrubLeavesNothingToSeek() {
        val state = PlayerControlsState(initialPositionMs = 0L, initialDurationMs = 200_000L)
        state.beginScrub(0.1f)
        state.moveScrub(0.8f)
        state.cancelScrub()
        assertNull("取消后不能留着拖动值", state.scrubTo)
        assertNull("取消后不许再产出落点（调用方据此不 seek）", state.commitScrub())
    }

    @Test
    fun theShownFractionFollowsTheFingerWhileDragging() {
        val state = PlayerControlsState(initialPositionMs = 50_000L, initialDurationMs = 100_000L)
        assertEquals(0.5f, state.shownFraction(), 0.001f)
        state.beginScrub(0.9f)
        assertEquals("拖动期间以手指为准，否则半秒心跳会把滑块拽回去", 0.9f, state.shownFraction(), 0.001f)
    }

    @Test
    fun theBufferedFractionNeverFallsBehindWhatIsPlaying() {
        val state = PlayerControlsState(initialPositionMs = 60_000L, initialDurationMs = 100_000L)
        state.bufferedPositionMs = 20_000L
        assertEquals("缓冲落后时按已播放画，否则已播放那段会缺一截", 0.6f, state.bufferedFraction(), 0.001f)
        state.bufferedPositionMs = 80_000L
        assertEquals(0.8f, state.bufferedFraction(), 0.001f)
    }

    // ---- 气泡时间格式 ----

    @Test
    fun theScrubBubbleIsPaddedMinutesAndSeconds() {
        assertEquals("00:00", bubbleTime(0L))
        assertEquals("00:07", bubbleTime(7_400L))
        assertEquals("01:05", bubbleTime(65_000L))
        assertEquals("过一小时补上小时位，别把 1:02:03 显示成 02:03", "1:02:03", bubbleTime(3_723_000L))
    }

    // ---- 取消路径的源码契约：pointerInput 必须同时认 up 与 cancel ----

    /**
     * 状态类自己证明不了「界面真的在取消时调了 cancelScrub」，所以这里再钉一次源码：
     * 手指抬起走 `PointerEventType.Release`，其余收尾（指针消失 / 协程被取消）落到 finally 的取消分支。
     */
    @Test
    fun theProgressBarHandlesBothReleaseAndCancellation() {
        val source = File("$SRC_ROOT/ui/player/PlayerControls.kt").readText()

        assertTrue("抬起要以 Release 事件判定", source.contains("PointerEventType.Release"))
        assertTrue("两种收尾都要在 finally 里分流", source.contains("} finally {"))
        assertTrue(
            "Release 才提交，其余一律取消",
            source.contains("if (committed) latestCommit() else latestCancel()"),
        )
        assertTrue(
            "取消只清拖动值，不碰 seek",
            source.contains("onCancel = { state.cancelScrub() }"),
        )
        assertFalse(
            "取消分支里不许出现 seekTo",
            source.substringAfter("onCancel = {").substringBefore("}").contains("seekTo"),
        )
    }

    // ---- 阶段 2 的结构契约：气泡 overlay、72dp 底栏、锁定块的位置 ----

    @Test
    fun theScrubBubbleIsAnOverlayNotALayoutRow() {
        val source = File("$SRC_ROOT/ui/player/PlayerControls.kt").readText()
        assertFalse("气泡不能再占一行 20dp 的槽，否则底栏回不到 72dp", source.contains(".height(20.dp)"))
        assertTrue("气泡要能用负偏移飘到轨道上方", source.contains("IntOffset(bubbleOffset, -bubbleLiftPx)"))
    }

    @Test
    fun theBottomBarAddsUpToSeventyTwoDp() {
        val source = File("$SRC_ROOT/ui/player/PlayerControls.kt").readText()
        fun dp(name: String): Int {
            val match = Regex("""private val """ + name + """ = (\d+)\.dp""").find(source)
            assertTrue("PlayerControls.kt 里要以 `private val $name = N.dp` 声明", match != null)
            return match!!.groupValues[1].toInt()
        }
        assertEquals(
            "底栏 = 进度条 + 播放行 + 底部留白；气泡不占布局，那 20dp 不该再算进来",
            72,
            dp("progressBarHeight") + dp("playRowHeight") + dp("barBottomInset"),
        )
    }

    // 「锁定块必须在淡入淡出之外」这条改由 instrumented 的
    // PlayerControlsLockDeviceTest 用节点存在性证明，不再靠数缩进。

    @Test
    fun aLockedScreenHasNoGestureAxis() {
        val source = File("$SRC_ROOT/ui/VideoPlayerScreen.kt").readText()
        assertTrue("锁定后三条手势轴一起掐掉", source.contains("axis = if (latestLocked) {"))
        assertTrue(
            "单击改走 onSurfaceRelease（滑动超过 slop 不算点）",
            source.contains("controls.onSurfaceRelease("),
        )
        assertTrue("阈值由 PlayerGestureMath.isTap 给", source.contains("PlayerGestureMath.isTap("))
        assertFalse(
            "手势层不许绕开 slop 直接调 onSurfaceTap",
            source.contains("controls.onSurfaceTap("),
        )
    }

    // ---- 滑动 vs 点击：超过 touchSlop 的松手不算点 ----

    @Test
    fun aSwipeOnALockedScreenDoesNotToggleTheHud() {
        val state = PlayerControlsState()
        state.lock()
        state.onSurfaceRelease(tapped = false)
        assertTrue("锁定态划一下不该动 HUD，它只跟随点按", state.lockedHudShown)
    }

    @Test
    fun aTapOnALockedScreenTogglesTheHud() {
        val state = PlayerControlsState()
        state.lock()
        state.onSurfaceRelease(tapped = true)
        assertFalse("锁定态点一下收起 HUD", state.lockedHudShown)
        state.onSurfaceRelease(tapped = true)
        assertTrue("再点一下重新露出", state.lockedHudShown)
    }

    @Test
    fun anUnlockedScreenIgnoresSwipesForTheControlsToggle() {
        val state = PlayerControlsState()
        state.onSurfaceRelease(tapped = false)
        assertTrue("未锁定时滑动不该把控制条收起来", state.controlsShown)
        state.onSurfaceRelease(tapped = true)
        assertFalse("点一下才收起", state.controlsShown)
    }

    /** 单击仍要切换显隐（左右两个方向都验）：slop 判定只该挡「划」，不该把点也挡掉。 */
    @Test
    fun aTapStillTogglesTheControlsWhenUnlocked() {
        val state = PlayerControlsState()
        assertTrue(state.controlsShown)
        state.onSurfaceRelease(tapped = true)
        assertFalse("点一下收起", state.controlsShown)
        state.onSurfaceRelease(tapped = true)
        assertTrue("再点一下换回来", state.controlsShown)
    }

    // ---- 阶段 3：方向与画中画按钮的可用性 ----

    /** 锁定 = 所有控件不可交互：两个按钮都得回死键，旋转也不许写用户选择。 */
    @Test
    fun aLockedScreenCannotRotateOrEnterPip() {
        val state = PlayerControlsState()
        state.onPlaybackReady()
        assertTrue("解锁且就绪时两个键都能按", state.rotateEnabled())
        assertTrue(state.pipEnabled())

        state.lock()
        assertFalse("锁定后旋转键是死键", state.rotateEnabled())
        assertFalse("锁定后 PiP 键是死键", state.pipEnabled())

        state.toggleOrientation()
        assertNull("锁定态点旋转不该写进用户选择", state.orientationOverride)
    }

    @Test
    fun aPortraitVideoStartsUnpinned() {
        val state = PlayerControlsState()
        state.isLandscapeVideo = false
        assertNull("没手动转过就不该有 override", state.orientationOverride)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state.effectiveOrientation())

        state.isLandscapeVideo = true
        assertEquals(
            "横版视频自动横屏",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            state.effectiveOrientation(),
        )
    }

    @Test
    fun togglingOrientationPinsTheUsersChoice() {
        val state = PlayerControlsState()
        state.isLandscapeVideo = true
        state.toggleOrientation()
        assertEquals(
            "横着的时候按一下就是切竖屏",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            state.orientationOverride,
        )
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, state.effectiveOrientation())

        // 视频尺寸再回调一次也不许把手选抢回去
        state.isLandscapeVideo = false
        state.isLandscapeVideo = true
        assertEquals(
            "手选之后自动规则失效",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            state.effectiveOrientation(),
        )

        state.toggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, state.orientationOverride)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, state.effectiveOrientation())
    }

    /**
     * 锁定冻结方向。断的是**行为**：锁定后视频尺寸再怎么变，请求的方向都不动。
     * 字面值会是 `LOCKED`——那是 Android 上唯一「禁用 sensor 且保持当前方向」的常量。
     */
    @Test
    fun aLockedScreenFreezesTheOrientation() {
        val state = PlayerControlsState()
        state.isLandscapeVideo = true
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, state.effectiveOrientation())

        state.lock()
        assertEquals(
            "锁定后冻在当前方向",
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            state.effectiveOrientation(),
        )
        state.isLandscapeVideo = false
        assertEquals(
            "锁定后视频尺寸变化不影响方向",
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            state.effectiveOrientation(),
        )

        state.unlock()
        assertEquals("解锁后回到自动规则", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, state.effectiveOrientation())
    }

    /**
     * 手选的方向跨过一次锁定还作数：它钉的是「这一屏」，不是「这一次没锁」。
     *
     * 同时钉住「锁定盖过手选」：手选横屏之后再锁，必须落到 `LOCKED` 而不是留在
     * `SENSOR_LANDSCAPE`——后者在锁定态下照样跟着 sensor 转，锁定就白锁了。
     */
    @Test
    fun theManualChoiceSurvivesALockUnlockRoundTrip() {
        val state = PlayerControlsState()
        state.isLandscapeVideo = true
        state.toggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, state.effectiveOrientation())

        state.lock()
        // 这里必须是 LOCKED，不能是 PORTRAIT：PORTRAIT 会把 sensor 重新打开，
        // 「锁定后画面不再跟随手机」这条就破了。竖屏下两者看起来一样，语义不一样。
        assertEquals(
            "锁定盖过手选：手选竖屏也要落到 LOCKED（sensor 一律停用）",
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            state.effectiveOrientation(),
        )
        state.unlock()
        assertEquals(
            "锁一下再解开，手选的方向还在（直到退出播放页）",
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            state.effectiveOrientation(),
        )

        // 手选横屏 + 锁定：留在 SENSOR_LANDSCAPE 的话画面会跟着手转，锁定等于没锁。
        state.toggleOrientation()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, state.effectiveOrientation())
        state.lock()
        assertEquals(
            "手选横屏在锁定后也必须是 LOCKED",
            ActivityInfo.SCREEN_ORIENTATION_LOCKED,
            state.effectiveOrientation(),
        )
        state.unlock()
        assertEquals(
            "解锁回到手选的那一头",
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            state.effectiveOrientation(),
        )
    }

    /** 暂停也能进小窗：门槛是「播放器就绪过」，不是「正在播」。 */
    @Test
    fun pipStaysAvailableWhilePausedOnceThePlayerIsReady() {
        val state = PlayerControlsState()
        assertFalse("还没就绪时不许进小窗", state.pipEnabled())
        state.onPlaybackReady()
        state.isPlaying = false
        assertTrue("暂停态也允许进小窗", state.pipEnabled())
    }

    /** 出错回退：状态实例跟着这一屏活到底，不落闩的话按钮会一直亮着。 */
    @Test
    fun aPlaybackErrorTakesThePipButtonAway() {
        val state = PlayerControlsState()
        state.onPlaybackReady()
        assertTrue(state.pipEnabled())
        state.onPlayerError()
        assertFalse("出错后按钮回灰，别把小窗开成一块黑", state.pipEnabled())
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
