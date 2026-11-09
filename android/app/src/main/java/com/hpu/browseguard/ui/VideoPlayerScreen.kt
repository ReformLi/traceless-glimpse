package com.hpu.browseguard.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hpu.browseguard.MainActivity
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.playback.PlaybackSource
import com.hpu.browseguard.data.playback.RefuseReason
import com.hpu.browseguard.ui.player.PlayerControls
import com.hpu.browseguard.ui.player.PlayerControlsState
import com.hpu.browseguard.ui.player.PlayerGestureMath
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏播放器路由。
 *
 * 挂在 [BgvApp] 的 Scaffold 之外：底栏与状态栏都不该出现在看片的那一屏，
 * 返回键先关播放器而不是退出 Tab。
 */
@Composable
fun PlayerRoute(container: AppContainer, recordId: String, onClose: () -> Unit) {
    // 每次打开播放器都配一套自己的 ViewModelStore，关掉就清空。
    // 用 Activity 那个 store 的话，同一条记录第二次打开会复用上次的 ViewModel，
    // 续播点还是上次打开时读到的旧值——用户看到的后果是「没从断点续播」。
    //
    // 外面再套一层 key(recordId)：换记录（甚至同一条记录重新进场）时整棵子树作废重建，
    // 不给 Compose 按槽位复用上一个播放器 Composition 的机会。
    key(recordId) {
        val owner = remember(recordId) { PlayerViewModelOwner() }
        DisposableEffect(owner) {
            onDispose { owner.viewModelStore.clear() }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            PlayerRouteScoped(container, recordId, onClose)
        }
    }
}

private class PlayerViewModelOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

@Composable
private fun PlayerRouteScoped(container: AppContainer, recordId: String, onClose: () -> Unit) {
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$recordId",
        factory = PlayerViewModel.factory(
            container.history,
            container.audit,
            container.localSandboxRoot,
            recordId,
            container.applicationScope,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    when (val current = state) {
        // 库里的行还没读出来：先给一屏黑，别闪一句「打不开」。
        PlayerUiState.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        )

        is PlayerUiState.Refused -> PlayerUnavailable(current.reason, onClose)
        is PlayerUiState.Ready -> VideoPlayer(current, onFlush = viewModel::flush, onClose = onClose)
    }
}

@Composable
private fun PlayerUnavailable(reason: RefuseReason, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = stringResource(
                    if (reason == RefuseReason.ON_ENGINE) R.string.history_pc_hint else R.string.player_unavailable,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onClose) {
                Text(stringResource(R.string.player_back))
            }
        }
    }
}

@Composable
private fun VideoPlayer(
    state: PlayerUiState.Ready,
    onFlush: (positionMs: Long, durationMs: Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(state) { playableUriOf(state.playable) }
    val player = remember(uri) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            // 上次看到哪儿就从哪儿起播；归一化已经保证这个数不会是「其实看完了」或刚起播的抖动。
            if (state.resumePositionMs > 0L) seekTo(state.resumePositionMs)
            playWhenReady = true
            prepare()
        }
    }

    // Activity 的 Home 回调和 ON_STOP 是从组合外面摸播放器的两条路，它们可能比这一屏晚一步被调到。
    // 组合里直接用 [player]，组合外只准通过这只把手摸；离场置空后，摸到的是 null 而不是
    // 一个已经 release、看起来还活着的实例。
    var livePlayer by remember(uri) { mutableStateOf<ExoPlayer?>(player) }
    val latestPlayer by rememberUpdatedState(livePlayer)

    val activity = remember(context) { context.findActivity() }
    val pipHost = activity as? MainActivity
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    // 控制条的界面态（显隐 / 倍速 / 拖动中位置 / 缓冲位置 / 方向锁定）收进这一个持有类。
    // 播放器实例不进它——那是 layout 之外的生命周期东西，仍归本函数管。
    //
    // key 必须是 uri 而不是空：本类里有 playerReady、orientationOverride 这种「跟着某一个播放器
    // 才有意义」的字段。空 key 的 remember 只跟调用点绑定，同一个 Composition 槽位换成另一个
    // 视频时会原样留下上一份——表现就是「新视频还没就绪，PiP 按钮已经是亮的」。
    // 外层还有 key(recordId) 兜一层，两道一起保证换视频必换状态。
    val controls = remember(uri) { PlayerControlsState(state.resumePositionMs, state.knownDurationMs) }
    var failed by remember { mutableStateOf(false) }
    var openProblem by remember { mutableStateOf<OpenFileProblem?>(null) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var gestureHint by remember { mutableStateOf<GestureHint?>(null) }

    // 手势块不能把 positionMs 当 key：它每半秒变一次，会把进行中的拖动打断。
    val latestPositionMs by rememberUpdatedState(controls.positionMs)
    val latestDurationMs by rememberUpdatedState(controls.durationMs)
    val latestFailed by rememberUpdatedState(failed)
    // 锁定不写进手势块的 key（那会把进行中的手势打断），只让它在事件里读到最新值。
    val latestLocked by rememberUpdatedState(controls.locked)

    // 单击要等双击窗口过去才生效：点下先排程，窗口内来了第二击就把排程取消。
    // 只有 300ms 的等待能换来「单击必定是显隐、双击必定是播放/暂停」这一条硬保证——
    // 立即执行再回滚的做法会让控制条先闪一下，而且第二击会落在刚弹出来的按钮上。
    val tapScope = rememberCoroutineScope()
    var pendingTapJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                controls.isPlaying = playing
                if (!playing) {
                    // 暂停、播完、被系统抢走音频焦点——都在这一次落库，退出时才有的续播点。
                    controls.positionMs = player.contentPosition
                    onFlush(player.contentPosition, player.duration)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                // 播放器坏了就不能再往小窗里塞：PiP 按钮跟着回灰。
                controls.onPlayerError()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) controls.onPlaybackReady()
            }

            /**
             * 自动方向规则的唯一输入。宽高还是 0（纯音频、元数据没到）时**不写**：
             * 一次 0×0 的回调会把已经转成横屏的画面顶回竖屏。
             *
             * media3 给的宽高已经算进了旋转元数据，竖拍的 mp4 在这里就是高 > 宽。
             */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    controls.isLandscapeVideo = videoSize.width > videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            val total = player.duration
            onFlush(player.contentPosition, if (total > 0L) total else controls.durationMs)
            player.removeListener(listener)
            playerView?.player = null
            player.release()
            // 松手之后谁都摸不到它了：释放过的实例留在把手里，只会让晚一步的回调
            // 对着一个不再播东西的播放器做决定。
            livePlayer = null
        }
    }

    // 半秒一跳只为让进度条走起来；每 20 跳（约 10 秒）落一次库，进程被划掉也只丢最后十秒。
    LaunchedEffect(controls.isPlaying) {
        if (!controls.isPlaying) return@LaunchedEffect
        var ticks = 0
        while (controls.isPlaying) {
            delay(500)
            ticks++
            controls.positionMs = player.contentPosition
            controls.bufferedPositionMs = player.bufferedPosition
            if (player.duration > 0L) controls.durationMs = player.duration
            if (ticks >= 20) {
                ticks = 0
                onFlush(player.contentPosition, player.duration)
            }
        }
    }

    // 手势提示在松手后自己消失；拖动期间每次变化都会重启这个计时。
    LaunchedEffect(gestureHint) {
        if (gestureHint != null) {
            delay(900)
            gestureHint = null
        }
    }

    // 进小窗后不再画控制层：小窗里只有视频，点开 App 再继续操作。
    LaunchedEffect(inPip) {
        if (inPip) controls.onPipEntered()
    }

    // 亮度是窗口属性，只改窗口值会在退出播放器后一直留着；进来时记下原值，走时还原。
    val initialWindowBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: -1f
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = initialWindowBrightness }
            }
        }
    }

    /**
     * 把当前播放器塞进小窗。两条入口共用这一段：
     * - 按 Home / 最近任务（[MainActivity.onUserLeaveHint]）：只在那时**正在播**才进，
     *   暂停着退到后台没有值得看的画面，交给系统正常最小化；
     * - 右上角的 PiP 按钮：暂停也允许，用户是主动要求的。
     *
     * 返回 false 表示这次没进去（设备不支持 / 系统不批 / 播放器已经没了），由调用方决定要不要出声：
     * 按 Home 那条路保持静默，按按钮那条要提示。
     *
     * 宽高兜底交给 16:9：视频尺寸读不出来时 `Rational(0, 0)` 会直接抛。
     */
    fun enterPlayerPip(): Boolean {
        val host = pipHost ?: return false
        val current = latestPlayer ?: return false
        val video = current.videoSize
        val width = video.width.takeIf { it > 0 } ?: 16
        val height = video.height.takeIf { it > 0 } ?: 9
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
        return runCatching { host.enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    // 播放中按 Home/最近任务才进小窗；暂停状态退到后台没有可看的画面，交给系统正常最小化。
    DisposableEffect(pipHost) {
        if (pipHost == null) {
            onDispose { }
        } else {
            pipHost.onUserLeavePlayer = {
                val current = latestPlayer
                if (current != null && current.isPlaying) enterPlayerPip() else false
            }
            pipHost.onPipModeChanged = { inPip = it }
            onDispose {
                pipHost.onUserLeavePlayer = null
                pipHost.onPipModeChanged = null
            }
        }
    }

    // 方向：自动按视频宽高，用户手转之后只认手选，锁定后冻在当前方向。
    PlayerOrientationEffect(activity, controls)

    // 切到后台：暂停就是回写进度的扳机（见 onIsPlayingChanged），不该让声音在背后继续放。
    // 小窗里 Activity 也处于后台态，此时暂停会把小窗播成静帧。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isInPictureInPictureMode != true) livePlayer?.pause()
    }

    openProblem?.let { problem ->
        val message = openProblemText(problem)
        LaunchedEffect(problem) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            openProblem = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // 主题不是 AppCompat，PlayerView 内建控制器解不了它那些 ?attr/ 引用；控制条自己画。
                    useController = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }.also { playerView = it }
            },
            update = { view -> view.player = player },
        )

        if (!inPip && !failed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(player, activity, audioManager, failed) {
                        // 双击检测的跨手势记忆：上一击的时间和落点。手势块本身按 (player, failed)
                        // 重启，换视频或出错时这份记忆跟着归零，不会把旧视频的双击带过来；
                        // 上一支视频留下的待定单击也要一并作废，否则它会在新画面上切一次显隐。
                        var lastTapUptimeMs = Long.MIN_VALUE
                        var lastTapX = Float.NaN
                        var lastTapY = Float.NaN
                        pendingTapJob?.cancel()
                        pendingTapJob = null
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val height = size.height.toFloat().coerceAtLeast(1f)
                            val startX = down.position.x
                            val onLeftSide = startX < width / 2f
                            val startPosition = latestPositionMs
                            val startDuration = latestDurationMs
                            val startBrightness = activity?.window?.attributes?.screenBrightness
                                ?.takeIf { it >= 0f } ?: 0.5f
                            val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
                            val startVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                            var axis: GestureKind? = null
                            var seekTarget: Long? = null

                            var change = down
                            while (change.pressed) {
                                val event = awaitPointerEvent()
                                change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = change.position.x - startX
                                val dy = change.position.y - down.position.y
                                if (axis == null &&
                                    (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)
                                ) {
                                    // 锁定后三条手势轴全关：调亮度/音量也算「操作播放」，锁上就不该再动。
                                    // 置 null 之后下面的 when 一路走空分支，循环照旧跑到手指抬起为止。
                                    axis = if (latestLocked) {
                                        null
                                    } else {
                                        when {
                                            abs(dx) > abs(dy) -> GestureKind.Seek
                                            onLeftSide -> GestureKind.Brightness
                                            else -> GestureKind.Volume
                                        }
                                    }
                                }
                                when (axis) {
                                    GestureKind.Seek -> {
                                        val target =
                                            PlayerGestureMath.seekTargetMs(startPosition, startDuration, dx / width)
                                        seekTarget = target
                                        gestureHint = GestureHint(
                                            kind = GestureKind.Seek,
                                            targetMs = target,
                                            forward = target >= startPosition,
                                        )
                                        change.consume()
                                    }

                                    GestureKind.Brightness -> {
                                        val value = PlayerGestureMath.brightnessFor(startBrightness, dy / height)
                                        activity?.window?.let { window ->
                                            window.attributes = window.attributes.apply { screenBrightness = value }
                                        }
                                        gestureHint = GestureHint(GestureKind.Brightness, fraction = value)
                                        change.consume()
                                    }

                                    GestureKind.Volume -> {
                                        val volume = PlayerGestureMath.volumeFor(startVolume, dy / height, maxVolume)
                                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                                        gestureHint = GestureHint(
                                            kind = GestureKind.Volume,
                                            fraction = if (maxVolume > 0) volume.toFloat() / maxVolume else 0f,
                                        )
                                        change.consume()
                                    }

                                    null -> Unit
                                }
                            }

                            when (axis) {
                                GestureKind.Seek -> {
                                    val target = seekTarget
                                    if (target != null) {
                                        player.seekTo(target)
                                        controls.positionMs = target
                                    }
                                }

                                null -> if (!latestFailed) {
                                    // 锁定态三条轴都关，滑动也落到这里：位移不超过 touchSlop 才算「点一下」，
                                    // 否则在锁定画面上随手一划就会切换锁定 HUD 的显隐。
                                    val tapped = PlayerGestureMath.isTap(
                                        dx = change.position.x - startX,
                                        dy = change.position.y - down.position.y,
                                        touchSlop = viewConfiguration.touchSlop,
                                    )
                                    val now = change.uptimeMillis
                                    val previousTapMs = lastTapUptimeMs
                                    val doubleTap = tapped &&
                                        !latestLocked &&
                                        previousTapMs != Long.MIN_VALUE &&
                                        PlayerGestureMath.isDoubleTap(
                                            elapsedMs = now - previousTapMs,
                                            dx = change.position.x - lastTapX,
                                            dy = change.position.y - lastTapY,
                                            touchSlop = viewConfiguration.touchSlop *
                                                PlayerGestureMath.DOUBLE_TAP_SLOP_FACTOR,
                                        )
                                    when {
                                        // 拖动过，不是点击：清掉记忆，别让下一次点击和这次凑成双击。
                                        !tapped -> lastTapUptimeMs = Long.MIN_VALUE

                                        // 锁定态没有双击语义：点屏立刻切 HUD，不必等双击窗口。
                                        latestLocked -> {
                                            lastTapUptimeMs = Long.MIN_VALUE
                                            controls.onSurfaceRelease(true)
                                        }

                                        // 第二击落在窗口内：先取消待定的单击（显隐一次都不会被触发），
                                        // 再只切播放/暂停。这就是「双击永远是播放/暂停」的那一半保证。
                                        doubleTap -> {
                                            lastTapUptimeMs = Long.MIN_VALUE
                                            pendingTapJob?.cancel()
                                            pendingTapJob = null
                                            if (player.isPlaying) player.pause() else player.play()
                                        }

                                        // 第一击：只记下来并排程一次单击。窗口内没有第二击，它才真正
                                        // 去切显隐——用户看到的就是「单击只切显隐」，绝不会先闪一下。
                                        else -> {
                                            lastTapUptimeMs = now
                                            lastTapX = change.position.x
                                            lastTapY = change.position.y
                                            pendingTapJob?.cancel()
                                            pendingTapJob = tapScope.launch {
                                                delay(PlayerGestureMath.DOUBLE_TAP_MS)
                                                pendingTapJob = null
                                                // 这 300ms 里用户可能已经点了锁定按钮：那一击不该再
                                                // 动画面，否则刚锁上 HUD 又被这一下切成隐藏。
                                                if (!controls.locked) controls.onSurfaceRelease(true)
                                            }
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                    },
            )
        }

        if (failed && !inPip) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = { openProblem = openLocalFile(context, state.playable.readTarget) }) {
                    Text(stringResource(R.string.player_open_with))
                }
            }
        }

        if (!failed && !inPip) {
            PlayerControls(
                state = controls,
                title = state.title,
                onClose = onClose,
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onSeekTo = { target ->
                    player.seekTo(target)
                    controls.positionMs = target
                },
                onSpeedSelected = { option -> player.setPlaybackSpeed(option) },
                onOpenWith = { openProblem = openLocalFile(context, state.playable.readTarget) },
                onToggleOrientation = { controls.toggleOrientation() },
                onEnterPip = {
                    // 只在这次真的没进去时才出声。返回 false 才等于失败，
                    // 「设备不支持」只是失败的一种，别提前弹一句把话说死。
                    if (!enterPlayerPip()) {
                        Toast.makeText(context, R.string.player_pip_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        gestureHint?.let { hint ->
            GestureFeedback(
                hint = hint,
                durationMs = controls.durationMs,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * 让 Activity 的方向跟着控制状态走。
 *
 * 每次重组都重新算一遍 [PlayerControlsState.effectiveOrientation]，再把这个结果当作 `LaunchedEffect`
 * 的 key：视频尺寸到了、用户手转、锁定、解锁，都会让 key 变，效果重跑一次并写入**当下**的值。
 * 这里刻意不把结果 `remember` 起来——记住一份旧值再在回调里读，正是「拿着上一帧的方向去写窗口」
 * 那类 bug 的源头。
 *
 * 离场还原 `UNSPECIFIED`：manifest 没给 MainActivity 声明 `screenOrientation`，出厂值就是它。
 * 不还原的话，退出播放页之后整个 App 会卡在播放器强制的那一个方向上。
 */
@Composable
internal fun PlayerOrientationEffect(activity: Activity?, state: PlayerControlsState) {
    val orientation = state.effectiveOrientation()
    LaunchedEffect(activity, orientation) {
        if (activity != null) activity.requestedOrientation = orientation
    }
    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

private enum class GestureKind { Seek, Brightness, Volume }

private data class GestureHint(
    val kind: GestureKind,
    val targetMs: Long = 0L,
    val fraction: Float = 0f,
    val forward: Boolean = true,
)

/** 拖动时居中的一块提示：快进/快退到哪、亮度或音量到了几成。 */
@Composable
private fun GestureFeedback(hint: GestureHint, durationMs: Long, modifier: Modifier = Modifier) {
    val text = when (hint.kind) {
        GestureKind.Seek -> stringResource(
            if (hint.forward) R.string.player_seek_forward else R.string.player_seek_backward,
            durationLabel(hint.targetMs) ?: "0:00",
        )

        GestureKind.Brightness -> stringResource(R.string.player_brightness, (hint.fraction * 100f).roundToInt())
        GestureKind.Volume -> stringResource(R.string.player_volume, (hint.fraction * 100f).roundToInt())
    }
    val progress = when (hint.kind) {
        GestureKind.Seek -> if (durationMs > 0L) (hint.targetMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        else -> hint.fraction.coerceIn(0f, 1f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // 半透明黑底白字：视频上最稳的对比度，圆角跟全局卡片令牌走。
            .background(Color(0xCC000000), MaterialTheme.shapes.medium)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .width(150.dp)
                .height(3.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.28f),
        )
    }
}

/** Compose 里拿到的 Context 可能包了一层，逐层拆到 Activity。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * file:// 要用 [Uri.fromFile]：产物名里有空格或中文时，`Uri.parse` 不会补上这层转义。
 * SAF 的 content:// 原样交给 ContentDataSource。
 */
private fun playableUriOf(source: PlaybackSource.Playable): Uri = when (source) {
    is PlaybackSource.PhoneFile -> Uri.fromFile(File(source.readTarget))
    is PlaybackSource.PhoneUri -> Uri.parse(source.readTarget)
}

