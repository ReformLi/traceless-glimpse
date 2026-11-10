package com.hpu.browseguard.ui.player

import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.hpu.browseguard.R
import com.hpu.browseguard.ui.durationLabel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** 播放中控制条自动收起的等待时间。暂停时不收起，见 [PlayerControlsState.autoHideDelayMs]。 */
private const val CONTROLS_AUTO_HIDE_MS = 3_500L

/** 视频上的控件一律白字压半透明黑：不取主题令牌，浅色主题下才不会糊成一片。 */
private val ScrimTop = Color(0x99000000)
private val ScrimBottom = Color(0xB3000000)
private val ChipSelected = Color.White.copy(alpha = 0.24f)
private val ChipActive = Color.White.copy(alpha = 0.18f)
private val DisabledTint = Color.White.copy(alpha = 0.38f)

/**
 * 控制层竖向几何。底栏总高 = [progressBarHeight] + [playRowHeight] + [barBottomInset] = 72dp；
 * 拖动气泡浮在轨道上方 [bubbleLift]，**不进布局**（负偏移画到框外），所以那 20dp 不计入底栏高度。
 */
private val progressBarHeight = 24.dp
private val playRowHeight = 44.dp
private val barBottomInset = 4.dp
private val bubbleLift = 20.dp

/** 进度条轨道与滑块的几何：3dp 轨道、圆角半径取一半。 */
private val barTrackHeight = 3.dp

/** 锁定时那条只读进度条比常规轨道更细：它只是一根参考线，不该抢画面。 */
private val lockedTrackHeight = 2.dp
private val thumbIdleRadius = 6.dp
private val thumbDragRadius = 8.dp

/** 控件淡入淡出时长：够短，不至于拖慢「点一下马上消失」的手感。 */
private const val CONTROLS_FADE_MS = 120

/**
 * 锁定按钮的额外右移量（物理像素）。
 *
 * 刘海屏横屏时左侧挖孔会啃掉 `CenterStart` 那一列，光靠 [displayCutoutPadding] 在部分机型上
 * 只留出挖孔本身的宽度、贴着边缘不好按；再右移这一点点，按钮才落在安全区内。
 */
private const val LOCK_BUTTON_SHIFT_PX = 20

/**
 * instrumented UI 测试用的节点标记。
 *
 * 顶栏/底栏本身有文字和图标可以找，但 [LockedProgressBar] 是自绘 `Canvas`，
 * 没有任何语义节点——不给它一个 tag，`onNodeWithTag` 就摸不到它。
 */
internal object PlayerTestTags {
    const val TopBar = "player_top_bar"
    const val BottomBar = "player_bottom_bar"
    const val LockedBar = "player_locked_bar"
    const val RotateButton = "player_rotate_button"
    const val PipButton = "player_pip_button"
}

/**
 * 控制条自己的瞬时界面状态。
 *
 * ⚠️ 别和 [com.hpu.browseguard.ui.PlayerUiState] 混：
 * - `PlayerUiState` 是「这一屏能不能播」——sealed interface，由 `PlayerViewModel` 产出（Loading / Ready / Refused）；
 * - 本类是控制条的显隐、倍速、拖动中位置这类**界面态**，一个 `remember` 出来的普通 class，
 *   不进 ViewModel、不落库，退出播放页就该整份丢掉。
 *
 * 用 `mutableStateOf` 而不是普通字段，是为了让 Compose 能在写了之后重组；
 * 纯 JVM 单测里同样可读可写（`SnapshotStateProbeTest` 钉着这一点）。
 */
internal class PlayerControlsState(
    initialPositionMs: Long = 0L,
    initialDurationMs: Long = 0L,
) {
    var controlsShown by mutableStateOf(true)
    /**
     * 锁定：锁上之后按钮与三条手势轴都不再响应。
     * 解锁入口只有一个——锁定 HUD 里的锁定按钮（见 [LockButton]），点它直接解锁。
     */
    var locked by mutableStateOf(false)

    /**
     * 锁定态的 HUD（锁定按钮 + 底部只读进度条）是否可见。
     *
     * 锁定画面上点一下屏幕就切换它：点一下露出、再点一下收起，再点再露出。
     * 与 [controlsShown] 完全是两份状态——锁定 HUD **没有**自动收起，只有点屏幕才动。
     */
    var lockedHudShown by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)
    var speedMenuShown by mutableStateOf(false)

    /** 拖动中的比例（0..1）；null 表示没在拖。 */
    var scrubTo by mutableStateOf<Float?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableLongStateOf(initialPositionMs)
    var durationMs by mutableLongStateOf(initialDurationMs)

    /** 已缓冲到的位置：进度条上那条 50% 白的浅轨道。本阶段唯一新增的数据源。 */
    var bufferedPositionMs by mutableLongStateOf(0L)

    /**
     * 视频本身是不是横的（宽 > 高）。由 `onVideoSizeChanged` 写入；宽高还是 0
     * （音频流 / 元数据没到）时不写，免得一次 0×0 把已经转好的画面顶回去。
     *
     * 只是**自动**方向规则的输入：用户手转过之后，[effectiveOrientation] 只看
     * [orientationOverride]，这个值再怎么变都不影响结果。
     */
    var isLandscapeVideo by mutableStateOf(false)

    /**
     * 用户手动锁定的方向，存 `ActivityInfo.SCREEN_ORIENTATION_*` 常量；null = 跟随自动规则。
     *
     * 「直到退出播放页」这条靠状态实例的生命周期保证：本类由播放页 `remember(uri)` 持有，
     * 换视频或离场就整份丢掉，不存在跨视频残留。
     */
    var orientationOverride by mutableStateOf<Int?>(null)

    /**
     * 播放器进过 `STATE_READY`（之后再没出错）。PiP 按钮的门槛：
     * 暂停也允许进小窗，但一个还没备好或已经坏掉的播放器不该被塞进去。
     */
    var playerReady by mutableStateOf(false)

    /** 控件是否该出现在画面上：既要「显示中」，又不能是锁定态。 */
    val controlsVisible: Boolean get() = controlsShown && !locked

    /**
     * 锁定按钮此刻该不该在画面上。
     *
     * 未锁定：跟控制条一起显隐——自动收起 3.5s 后一起消失，点屏幕一起回来，
     * 不许它永远飘在画面左侧。锁定后：跟锁定 HUD 走，点屏幕切换显隐。
     */
    fun lockButtonVisible(): Boolean = if (locked) lockedHudShown else controlsShown

    /**
     * 该等多久收控制条；null = 这次不收。
     *
     * 四条规则：显示中 + 未锁定 + 正在播 + 倍速面板没开。暂停不收起（要让人看清暂停在哪），
     * 面板开着也不收（否则下次显示会突然多一排按钮）；锁定后控制条本就不在，更不该再排一次收起。
     */
    fun autoHideDelayMs(): Long? =
        if (controlsShown && !locked && isPlaying && !speedMenuShown) CONTROLS_AUTO_HIDE_MS else null

    /**
     * 收起控制条，连带把倍速面板一起关掉——一次收干净，不留半截。
     * 锁定态下这条路走不通：锁上了就该一直收着，不能被定时器或别处再唤出来。
     */
    fun hideControls() {
        if (locked) return
        controlsShown = false
        speedMenuShown = false
    }

    fun toggleControls() {
        if (locked) return
        if (controlsShown) hideControls() else controlsShown = true
    }

    /** 点画面空白处：锁定态只切换锁定 HUD 的显隐，其余照旧切控制条。 */
    fun onSurfaceTap() {
        if (locked) lockedHudShown = !lockedHudShown else toggleControls()
    }

    /**
     * 手势收尾：只有「点一下」才走 [onSurfaceTap]。
     *
     * 滑动超过 touchSlop 的松手要在这里被挡掉——锁定态没有手势轴，滑动同样落到这条分支，
     * 不挡就会变成「随手一划就切换锁定 HUD」。判定由 [PlayerGestureMath.isTap] 在屏内算好传进来。
     */
    fun onSurfaceRelease(tapped: Boolean) {
        if (tapped) onSurfaceTap()
    }

    /** 锁上：控制条立刻收干净、倍速面板一并关掉；锁定 HUD 默认露出来（按钮在最前面，别让人找不到解锁口）。 */
    fun lock() {
        locked = true
        controlsShown = false
        speedMenuShown = false
        lockedHudShown = true
    }

    /** 解锁：控制条回到显示态，自动隐藏从这一刻重新计时（[autoHideDelayMs] 重新给出 3500）。 */
    fun unlock() {
        locked = false
        controlsShown = true
    }

    fun toggleLock() {
        if (locked) unlock() else lock()
    }

    // ---- 方向：自动规则 + 用户手选 + 锁定冻结 ----

    /** 旋转按钮能不能点：锁定后所有控件不可交互。 */
    fun rotateEnabled(): Boolean = !locked

    /** 画中画按钮能不能点：暂停态也允许，但要求播放器就绪过，且不在锁定态。 */
    fun pipEnabled(): Boolean = !locked && playerReady

    /** 播放器备好：解掉 PiP 按钮的门闩。 */
    fun onPlaybackReady() {
        playerReady = true
    }

    /**
     * 播放出错：把门闩重新落下。
     *
     * 状态实例是 `remember` 出来的、跟着这一屏活到底，不留一条回退路径的话，
     * 出错后按钮会一直亮着——用户点它只会把小窗开成一块黑。
     */
    fun onPlayerError() {
        playerReady = false
    }

    /** 这一屏眼下是不是横的：手选优先，其次看视频本身。 */
    fun isLandscapeNow(): Boolean = when (orientationOverride) {
        null -> isLandscapeVideo
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> false
        else -> true
    }

    /**
     * 右上角那个按钮：在当前方向和另一头之间切，并把结果钉进 [orientationOverride]。
     *
     * 钉住之后自动规则就失效了——视频尺寸再怎么回调、sensor 怎么转，都只认这一个值，
     * 直到退出播放页。锁定态下一律早退（按钮本来就是灰的，这里再兜一层）。
     */
    fun toggleOrientation() {
        if (locked) return
        orientationOverride = if (isLandscapeNow()) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /**
     * 这一屏该向系统请求的方向。
     *
     * 锁定态返回 [ActivityInfo.SCREEN_ORIENTATION_LOCKED]——这是「禁用 sensor 且保持当前方向」
     * 的唯一常量。这里不能写 `SCREEN_ORIENTATION_UNSPECIFIED`：那恰恰是「交给 sensor 自由判断」，
     * 锁定时用它，画面会跟着手一起转。
     *
     * 代价是锁定前后 `requestedOrientation` 的字面值会变（`SENSOR_LANDSCAPE` → `LOCKED`），
     * 但屏幕方向不变、sensor 不再响应，这正是「锁定」要的效果；测试钉的是行为（改
     * [isLandscapeVideo] 不影响结果），不是字段值。
     *
     * 手选竖屏（`PORTRAIT`）+ 锁定时返回 `LOCKED` 而非 `PORTRAIT`：`PORTRAIT` 会重新打开
     * sensor，`LOCKED` 才是正确的冻结语义。测试严格断言 `LOCKED`。
     *
     * 竖版视频 / 尺寸未知时返回 `PORTRAIT` 而不是 `UNSPECIFIED`：后者是「交给 sensor 自由判断」，
     * 在默认横放的模拟器（MuMu 这类）或用户手里横着拿的时候，竖版视频会被顶成横屏。
     * 全应用既然钉了竖屏，播放页的默认档也必须是同一个值。
     */
    fun effectiveOrientation(): Int = when {
        locked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
        orientationOverride != null -> orientationOverride!!
        isLandscapeVideo -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun toggleSpeedMenu() {
        speedMenuShown = !speedMenuShown
    }

    /** 进小窗：小窗里只有画面，控制条全收。 */
    fun onPipEntered() = hideControls()

    /**
     * 倍速胶囊上的文案：1.0 倍时只写 [neutral]（「倍速」），其余显示 `1.5x`。
     * 只改这一处，[PlayerGestureMath.speedLabel] 的 `1.0x` 语义给倍速面板用。
     */
    fun speedChipText(neutral: String): String =
        if (speed == 1f) neutral else PlayerGestureMath.speedLabel(speed)

    /** 已播放比例。 */
    fun playedFraction(): Float = fractionOf(positionMs, durationMs)

    /** 已缓冲比例，至少不低于已播放（缓冲落后于播放时画出来会缺一截）。 */
    fun bufferedFraction(): Float =
        fractionOf(bufferedPositionMs, durationMs).coerceAtLeast(playedFraction())

    /** 拖动期间以手指位置为准，否则每半秒的心跳会把滑块从指尖拽回去。 */
    fun shownFraction(): Float = scrubTo ?: playedFraction()

    /** 锁定时进度条只读：[beginScrub] / [moveScrub] 一律不写入拖动值。 */
    fun beginScrub(fraction: Float) {
        if (locked) return
        scrubTo = fraction.coerceIn(0f, 1f)
    }

    fun moveScrub(fraction: Float) {
        if (locked) return
        scrubTo = fraction.coerceIn(0f, 1f)
    }

    /**
     * 松手：交回落点并清掉拖动值；没拖过就返回 null（调用方据此不 seek）。
     * 拖动中被取消走 [cancelScrub]，那条路**不会**产出落点。
     */
    fun commitScrub(): Long? {
        if (locked) {
            // 锁定前进来的拖动值也要清掉，别留到解锁后变成一次迟到的跳转。
            scrubTo = null
            return null
        }
        val fraction = scrubTo ?: return null
        scrubTo = null
        if (durationMs <= 0L) return null
        return (fraction * durationMs).toLong().coerceIn(0L, durationMs)
    }

    /**
     * 拖动被取消（指针没以 up 收尾，或 pointerInput 协程被取消）：只清拖动值，绝不 seek。
     * 否则「手指被系统抢走」会变成一次没人要的跳转。
     */
    fun cancelScrub() {
        scrubTo = null
    }
}

/**
 * 播放控制层：顶栏、底栏、自绘进度条、倍速面板、锁定态。
 *
 * 只在「没失败、不在小窗」时被调用；显隐由 [PlayerControlsState.controlsVisible] 自己管，
 * 所以这个 composable 本身要一直在组合里——控制条自动收起的计时器挂在这里。
 * 锁定 HUD 没有计时器：它只跟随 [PlayerControlsState.lockedHudShown]，点屏幕才切换。
 *
 * 图层顺序很重要：锁定后要留的只读进度条与锁定按钮必须在两个 `AnimatedVisibility`
 * **外面**渲染，否则控件淡出一结束，它们会跟着内容一起离开组合。
 */
@Composable
internal fun PlayerControls(
    state: PlayerControlsState,
    title: String,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onOpenWith: () -> Unit,
    onToggleOrientation: () -> Unit,
    onEnterPip: () -> Unit,
) {
    // 一个 key 就够：延时值从 null 变成 3500 时重启计时，变回 null 时自然不再排程。
    val hideDelayMs = state.autoHideDelayMs()
    LaunchedEffect(hideDelayMs) {
        if (hideDelayMs != null) {
            delay(hideDelayMs)
            state.hideControls()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(tween(CONTROLS_FADE_MS)),
            exit = fadeOut(tween(CONTROLS_FADE_MS)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            PlayerTopBar(
                state = state,
                title = title,
                onClose = onClose,
                onOpenWith = onOpenWith,
                onToggleOrientation = onToggleOrientation,
                onEnterPip = onEnterPip,
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(tween(CONTROLS_FADE_MS)),
            exit = fadeOut(tween(CONTROLS_FADE_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerBottomBar(
                state = state,
                onTogglePlay = onTogglePlay,
                onSeekTo = onSeekTo,
                onSpeedSelected = onSpeedSelected,
            )
        }

        // 锁定后要留的东西必须在这两个 AnimatedVisibility **外面**：里面的内容在淡出结束时
        // 会离开组合，跟着一起放进去就等于「一锁定全没了」。锁定画面上点一下屏幕切换
        // [PlayerControlsState.lockedHudShown]：露出→收起→再露出，只动 HUD，不碰解锁逻辑。
        if (state.locked && state.lockedHudShown) {
            LockedProgressBar(
                playedFraction = state.playedFraction(),
                bufferedFraction = state.bufferedFraction(),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // 锁定开关在 CenterStart 原位：未锁定是开口锁、点了锁上；锁定后原地换成闭口锁、
        // 点了直接解锁。未锁定时跟控制条一起淡入淡出，锁定后跟随 HUD——两种状态都不常驻。
        AnimatedVisibility(
            visible = state.lockButtonVisible(),
            enter = fadeIn(tween(CONTROLS_FADE_MS)),
            exit = fadeOut(tween(CONTROLS_FADE_MS)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            LockButton(
                locked = state.locked,
                onToggle = { state.toggleLock() },
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    state: PlayerControlsState,
    title: String,
    onClose: () -> Unit,
    onOpenWith: () -> Unit,
    onToggleOrientation: () -> Unit,
    onEnterPip: () -> Unit,
) {
    var menuShown by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(ScrimTop, Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 2.dp, end = 2.dp, bottom = 10.dp)
            .testTag(PlayerTestTags.TopBar),
    ) {
        IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.player_back),
                tint = Color.White,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        // 横竖屏切换与画中画：enabled 由状态层给，锁定态两个都是死键。
        TopBarAction(
            icon = RotateScreenIcon,
            description = stringResource(R.string.player_rotate),
            enabled = state.rotateEnabled(),
            testTag = PlayerTestTags.RotateButton,
            onClick = onToggleOrientation,
        )
        TopBarAction(
            icon = PictureInPictureIcon,
            description = stringResource(R.string.player_pip),
            enabled = state.pipEnabled(),
            testTag = PlayerTestTags.PipButton,
            onClick = onEnterPip,
        )
        Box {
            IconButton(onClick = { menuShown = true }, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.player_open_with),
                    tint = Color.White,
                )
            }
            DropdownMenu(expanded = menuShown, onDismissRequest = { menuShown = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_open_with)) },
                    onClick = {
                        menuShown = false
                        onOpenWith()
                    },
                )
            }
        }
    }
}

/**
 * 顶栏右侧的功能键（旋转 / 画中画）。
 *
 * `enabled` 一律从状态层取（[PlayerControlsState.rotateEnabled] / [PlayerControlsState.pipEnabled]），
 * 不在这里现推：锁定态下整条顶栏已经离开组合，这两个 `enabled = false` 属于纵深防御——
 * 万一将来顶栏在锁定态被保留，也不会冒出两个按得动的键。
 */
@Composable
private fun TopBarAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .testTag(testTag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) Color.White else DisabledTint,
        )
    }
}

@Composable
private fun PlayerBottomBar(
    state: PlayerControlsState,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, ScrimBottom)))
            .navigationBarsPadding()
            .padding(horizontal = 8.dp)
            .testTag(PlayerTestTags.BottomBar),
    ) {
        if (state.speedMenuShown) {
            SpeedPanel(
                current = state.speed,
                onSelected = {
                    state.speed = it
                    state.speedMenuShown = false
                    onSpeedSelected(it)
                },
            )
        }

        PlayerProgressBar(
            shown = state.shownFraction(),
            bufferedFraction = state.bufferedFraction(),
            scrubTargetMs = state.scrubTo?.let { (it * state.durationMs).toLong() },
            enabled = state.durationMs > 0L && !state.locked,
            onStart = state::beginScrub,
            onMove = state::moveScrub,
            onCommit = { state.commitScrub()?.let(onSeekTo) },
            onCancel = { state.cancelScrub() },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(playRowHeight),
        ) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(44.dp)) {
                if (state.isPlaying) {
                    PauseBars(modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = playbackLine(state.positionMs, state.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .background(
                        if (state.speed != 1f) ChipActive else Color.Transparent,
                        RoundedCornerShape(18.dp),
                    )
                    .clickable { state.toggleSpeedMenu() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.speedChipText(stringResource(R.string.player_speed)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(barBottomInset))
    }
}

/** 倍速面板：一排等高胶囊，选中的那格有底色，其余透明。 */
@Composable
private fun SpeedPanel(current: Float, onSelected: (Float) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = bubbleLift)
            .background(Color(0x99000000), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PlayerGestureMath.SPEED_OPTIONS.forEach { option ->
            val selected = option == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        if (selected) ChipSelected else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelected(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = PlayerGestureMath.speedLabel(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 自绘进度条。
 *
 * 为什么不直接用 material3 的 `Slider`：那条滑块的轨道厚度、拇指大小、缓冲段都改不动，
 * 而需求要的正是这三样。这里只画三根圆角横条 + 一个圆点，指针位置直接映射成 0..1。
 *
 * 整块高度就是 [progressBarHeight]（24dp），拖动气泡用**负 offset 浮在轨道上方**，不进布局：
 * 否则手指一按，底栏会因为多出一行文字整体跳一下。负偏移能画到框外，是因为 Compose 的
 * Box 默认不裁剪子节点（这里面没有 `clip`，加了就会把气泡切掉）。
 */
@Composable
private fun PlayerProgressBar(
    shown: Float,
    bufferedFraction: Float,
    scrubTargetMs: Long?,
    enabled: Boolean,
    onStart: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val bubbleWidth = 58.dp
    // 回调每次重组都是新实例；pointerInput 的 key 不能带上它们，否则半秒一跳的心跳
    // 就会把进行中的拖动连根重启，所以走 rememberUpdatedState 读最新那一份。
    val latestStart by rememberUpdatedState(onStart)
    val latestMove by rememberUpdatedState(onMove)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestCancel by rememberUpdatedState(onCancel)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val bubbleWidthPx = with(density) { bubbleWidth.toPx() }
        val bubbleLiftPx = with(density) { bubbleLift.toPx() }.roundToInt()
        val bubbleOffset = with(density) {
            (shown * widthPx - bubbleWidthPx / 2f)
                .coerceIn(0f, (widthPx - bubbleWidthPx).coerceAtLeast(0f))
                .roundToInt()
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(progressBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            latestStart(fractionAt(down.position.x, size.width))
                            var committed = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (event.type == PointerEventType.Release) {
                                        committed = true
                                        break
                                    }
                                    // 指针没了但不是 up（被上层手势抢走 / 事件流中断）：按取消走。
                                    if (!change.pressed) break
                                    latestMove(fractionAt(change.position.x, size.width))
                                    change.consume()
                                }
                            } finally {
                                // Compose 没有 PointerEventType.Cancel：取消是「协程被取消」或
                                // 「指针在非 Release 情况下消失」，两种都会走到这里。
                                if (committed) latestCommit() else latestCancel()
                            }
                        }
                    },
            ) {
                val cy = size.height / 2f
                val trackPx = barTrackHeight.toPx()
                val inset = thumbDragRadius.toPx()
                val span = (size.width - inset * 2f).coerceAtLeast(1f)
                val radius = CornerRadius(trackPx / 2f)
                val playing = shown.coerceIn(0f, 1f)
                val buffered = bufferedFraction.coerceIn(0f, 1f)

                drawRoundRect(
                    color = Color.White.copy(alpha = 0.30f),
                    topLeft = Offset(0f, cy - trackPx / 2f),
                    size = Size(size.width, trackPx),
                    cornerRadius = radius,
                )
                if (buffered > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.50f),
                        topLeft = Offset(0f, cy - trackPx / 2f),
                        size = Size(size.width * buffered, trackPx),
                        cornerRadius = radius,
                    )
                }
                if (playing > 0f) {
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(0f, cy - trackPx / 2f),
                        size = Size(size.width * playing, trackPx),
                        cornerRadius = radius,
                    )
                }
                // 拖动时拇指变大一圈，给手指一点「抓住了」的反馈。
                val thumbRadius =
                    (if (scrubTargetMs != null) thumbDragRadius else thumbIdleRadius).toPx()
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = Offset(inset + span * playing, cy),
                )
            }

            // 叠在轨道上方：y 走负值，不占任何布局高度。
            if (scrubTargetMs != null) {
                Text(
                    text = bubbleTime(scrubTargetMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .offset { IntOffset(bubbleOffset, -bubbleLiftPx) }
                        .width(bubbleWidth)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 锁定开关：中间靠左的圆点，锁定前后都不挪窝。
 *
 * 图标随状态换形状：未锁定显示开口锁（[UnlockIcon]，示意「点这里锁上」）；
 * 锁定后显示闭口锁（[LockIcon]）并加深底色示意「现在是锁着的」，点击直接解锁。
 * 它在 [PlayerControls] 里排在两个 `AnimatedVisibility` 之外（锁定 HUD 收起后仍要在组合里），
 * 自身的显隐由 [PlayerControlsState.lockButtonVisible] 单独控制。
 */
@Composable
private fun LockButton(locked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            // 先让开刘海：横屏时挖孔在左右两侧，inset 会自动把按钮推进安全区。
            .displayCutoutPadding()
            .offset { IntOffset(LOCK_BUTTON_SHIFT_PX, 0) }
            .padding(start = 12.dp)
            .size(36.dp)
            .background(
                if (locked) Color(0xCC000000) else Color(0x66000000),
                RoundedCornerShape(18.dp),
            )
            .clickable { onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (locked) LockIcon else UnlockIcon,
            contentDescription = stringResource(
                if (locked) R.string.player_unlock else R.string.player_lock,
            ),
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 锁定态唯一留在画面上的控件：底部一根只读进度条，没有拇指，也没有 pointerInput。 */
@Composable
private fun LockedProgressBar(
    playedFraction: Float,
    bufferedFraction: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .height(lockedTrackHeight)
            .testTag(PlayerTestTags.LockedBar),
    ) {
        val trackPx = size.height
        val radius = CornerRadius(trackPx / 2f)
        val playing = playedFraction.coerceIn(0f, 1f)
        val buffered = bufferedFraction.coerceIn(0f, 1f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.30f),
            size = Size(size.width, trackPx),
            cornerRadius = radius,
        )
        if (buffered > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.50f),
                size = Size(size.width * buffered, trackPx),
                cornerRadius = radius,
            )
        }
        if (playing > 0f) {
            drawRoundRect(
                color = Color.White,
                size = Size(size.width * playing, trackPx),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * 暂停符号自己画：仓库只依赖 material-icons-core，里面没有 Pause，
 * 而为了一个图标引入整套扩展图标不划算。
 */
@Composable
private fun PauseBars(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val bar = size.width * 0.26f
        val gap = size.width * 0.16f
        val left = (size.width - bar * 2f - gap) / 2f
        val top = size.height * 0.20f
        val height = size.height * 0.60f
        val corner = CornerRadius(bar / 2.5f)
        drawRoundRect(tint, Offset(left, top), Size(bar, height), cornerRadius = corner)
        drawRoundRect(tint, Offset(left + bar + gap, top), Size(bar, height), cornerRadius = corner)
    }
}

/** 指针位置 → 0..1。两头各让出一个拇指半径，免得按到最边上一格时拇指被切掉一半。 */
private fun fractionAt(x: Float, widthPx: Int): Float {
    val inset = 8f
    val span = (widthPx - inset * 2f).coerceAtLeast(1f)
    return ((x - inset) / span).coerceIn(0f, 1f)
}

/** 比例；时长未知时一律 0。 */
internal fun fractionOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

/** 拖动气泡里的时间：`mm:ss`，过一小时才补成 `h:mm:ss`，免得 1:02:03 被截成 02:03。 */
internal fun bubbleTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
    } else {
        "%02d:%02d".format(java.util.Locale.US, minutes, seconds)
    }
}

/** `1:02:03 / 1:20:00`；时长读不出来时只写已经播到哪儿，不给一个假的总长。 */
internal fun playbackLine(positionMs: Long, durationMs: Long): String {
    val current = durationLabel(positionMs) ?: "0:00"
    val total = durationLabel(durationMs) ?: return current
    return "$current / $total"
}

/**
 * 圆角矩形的四段折线近似。
 *
 * 只用 moveTo/lineTo/close：`PathBuilder` 在这个 compose 版本上既没有 `addRoundRect`，
 * 二次曲线入口也调不通（试过 `quadraticTo` 与 `quadraticBezierTo`，都是 unresolved）。
 * 24 视口 + 1.6dp 描边下，两段折线的拐角看不出棱。
 * 若后续 Compose 支持 quadraticBezierTo，可替换为真曲线。
 */
private fun PathBuilder.roundRectPath(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
    val r = radius.coerceAtMost(minOf((right - left) / 2f, (bottom - top) / 2f))
    // 拐角切成两段斜线，比一刀 45° 更接近圆弧
    val b = r * 0.45f
    moveTo(left + r, top)
    lineTo(right - r, top)
    lineTo(right - b, top + b)
    lineTo(right, top + r)
    lineTo(right, bottom - r)
    lineTo(right - b, bottom - b)
    lineTo(right - r, bottom)
    lineTo(left + r, bottom)
    lineTo(left + b, bottom - b)
    lineTo(left, bottom - r)
    lineTo(left, top + r)
    lineTo(left + b, top + b)
    close()
}

/**
 * 横竖屏切换。仓库没有 material-icons-extended，这三个图标用 path 自绘。
 * 竖屏机身整体斜一点，看上去就是「转过来」；颜色由 `Icon` 的 tint 统一覆盖。
 */
private val RotateScreenIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlayerRotateScreen",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        group(rotate = -24f, pivotX = 12f, pivotY = 12f) {
            // 机身外框
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
            ) {
                roundRectPath(8.4f, 2.4f, 15.6f, 21.6f, 1.9f)
            }
            // 顶部听筒，一眼能认成「手机」而不是一块砖
            path(fill = SolidColor(Color.Black)) {
                roundRectPath(10.9f, 4.9f, 13.1f, 5.5f, 0.3f)
            }
        }
    }.build()
}

/** 画中画：一块屏幕里嵌一块小的。 */
private val PictureInPictureIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlayerPictureInPicture",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
        ) {
            roundRectPath(2.2f, 3.6f, 21.8f, 20.4f, 2.2f)
        }
        path(fill = SolidColor(Color.Black)) {
            roundRectPath(12.4f, 11.8f, 20.0f, 18.2f, 1.2f)
        }
    }.build()
}

/** 解锁：锁梁整体右移并抬起，和锁体之间留出缺口——同一家族里一眼能和 [LockIcon] 区分。 */
private val UnlockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlayerUnlock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
        ) {
            roundRectPath(9.6f, 3.0f, 18.2f, 10.6f, 3.8f)
        }
        path(fill = SolidColor(Color.Black)) {
            roundRectPath(4.6f, 9.8f, 17.2f, 20.6f, 2.4f)
        }
    }.build()
}

/** 锁：上方锁梁（描边圆角矩形，下半截被锁体盖住）+ 下方锁体。 */
private val LockIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "PlayerLock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
        ) {
            roundRectPath(8.0f, 3.4f, 16.0f, 15.0f, 4.0f)
        }
        path(fill = SolidColor(Color.Black)) {
            roundRectPath(5.0f, 10.4f, 19.0f, 21.2f, 2.4f)
        }
    }.build()
}
