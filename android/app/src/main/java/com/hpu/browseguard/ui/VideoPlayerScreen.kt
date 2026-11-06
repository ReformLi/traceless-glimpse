package com.hpu.browseguard.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hpu.browseguard.MainActivity
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.playback.PlaybackSource
import com.hpu.browseguard.data.playback.RefuseReason
import com.hpu.browseguard.ui.player.PlayerGestureMath
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    val owner = remember(recordId) { PlayerViewModelOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        PlayerRouteScoped(container, recordId, onClose)
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

    val activity = remember(context) { context.findActivity() }
    val pipHost = activity as? MainActivity
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(state.resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(state.knownDurationMs) }
    var scrubTo by remember { mutableStateOf<Float?>(null) }
    var controlsShown by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var menuShown by remember { mutableStateOf(false) }
    var openProblem by remember { mutableStateOf<OpenFileProblem?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var speedMenuShown by remember { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var gestureHint by remember { mutableStateOf<GestureHint?>(null) }

    // 手势块不能把 positionMs 当 key：它每半秒变一次，会把进行中的拖动打断。
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestFailed by rememberUpdatedState(failed)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    // 暂停、播完、被系统抢走音频焦点——都在这一次落库，退出时才有的续播点。
                    positionMs = player.contentPosition
                    onFlush(player.contentPosition, player.duration)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            val total = player.duration
            onFlush(player.contentPosition, if (total > 0L) total else durationMs)
            player.removeListener(listener)
            playerView?.player = null
            player.release()
        }
    }

    // 半秒一跳只为让进度条走起来；每 20 跳（约 10 秒）落一次库，进程被划掉也只丢最后十秒。
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var ticks = 0
        while (isPlaying) {
            delay(500)
            ticks++
            positionMs = player.contentPosition
            if (player.duration > 0L) durationMs = player.duration
            if (ticks >= 20) {
                ticks = 0
                onFlush(player.contentPosition, player.duration)
            }
        }
    }

    LaunchedEffect(controlsShown, isPlaying, speedMenuShown) {
        if (controlsShown && isPlaying && !speedMenuShown) {
            delay(3_500)
            controlsShown = false
        }
    }

    // 控制条收起时倍速面板也收起，免得下次显示时突然多出一排按钮。
    LaunchedEffect(controlsShown) {
        if (!controlsShown) speedMenuShown = false
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
        if (inPip) {
            controlsShown = false
            speedMenuShown = false
        }
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

    // 播放中按 Home/最近任务才进小窗；暂停状态退到后台没有可看的画面，交给系统正常最小化。
    DisposableEffect(player, pipHost) {
        if (pipHost == null) {
            onDispose { }
        } else {
            pipHost.onUserLeavePlayer = {
                if (player.isPlaying) {
                    val video = player.videoSize
                    val width = video.width.takeIf { it > 0 } ?: 16
                    val height = video.height.takeIf { it > 0 } ?: 9
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(width, height))
                        .build()
                    runCatching { pipHost.enterPictureInPictureMode(params) }.getOrDefault(false)
                } else {
                    false
                }
            }
            pipHost.onPipModeChanged = { inPip = it }
            onDispose {
                pipHost.onUserLeavePlayer = null
                pipHost.onPipModeChanged = null
            }
        }
    }

    // 切到后台：暂停就是回写进度的扳机（见 onIsPlayingChanged），不该让声音在背后继续放。
    // 小窗里 Activity 也处于后台态，此时暂停会把小窗播成静帧。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isInPictureInPictureMode != true) player.pause()
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
                                    axis = when {
                                        abs(dx) > abs(dy) -> GestureKind.Seek
                                        onLeftSide -> GestureKind.Brightness
                                        else -> GestureKind.Volume
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
                                        positionMs = target
                                    }
                                }

                                null -> if (!latestFailed) controlsShown = !controlsShown
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

        if (controlsShown && !failed && !inPip) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.player_back), tint = Color.White)
                }
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                )
                Box {
                    IconButton(onClick = { menuShown = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.player_open_with),
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(expanded = menuShown, onDismissRequest = { menuShown = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_open_with)) },
                            onClick = {
                                menuShown = false
                                openProblem = openLocalFile(context, state.playable.readTarget)
                            },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                if (speedMenuShown) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        PlayerGestureMath.SPEED_OPTIONS.forEach { option ->
                            val selected = option == speed
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .background(
                                        if (selected) Color.White.copy(alpha = 0.24f) else Color.Transparent,
                                        RoundedCornerShape(9.dp),
                                    )
                                    .clickable {
                                        speed = option
                                        player.setPlaybackSpeed(option)
                                        speedMenuShown = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = PlayerGestureMath.speedLabel(option),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = Color.White,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                Slider(
                    value = scrubTo ?: shownFraction(positionMs, durationMs),
                    onValueChange = { scrubTo = it },
                    onValueChangeFinished = {
                        scrubTo?.let { fraction ->
                            val target = (fraction * durationMs).toLong()
                            player.seekTo(target)
                            positionMs = target
                        }
                        scrubTo = null
                    },
                    enabled = durationMs > 0L,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.30f),
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(44.dp),
                    ) {
                        if (isPlaying) {
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
                        text = playbackLine(positionMs, durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .background(
                                if (speed != 1f) Color.White.copy(alpha = 0.18f) else Color.Transparent,
                                RoundedCornerShape(9.dp),
                            )
                            .clickable { speedMenuShown = !speedMenuShown }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.player_speed) + " " + PlayerGestureMath.speedLabel(speed),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        gestureHint?.let { hint ->
            GestureFeedback(
                hint = hint,
                durationMs = durationMs,
                modifier = Modifier.align(Alignment.Center),
            )
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
            .background(Color(0xCC000000), RoundedCornerShape(14.dp))
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

/**
 * file:// 要用 [Uri.fromFile]：产物名里有空格或中文时，`Uri.parse` 不会补上这层转义。
 * SAF 的 content:// 原样交给 ContentDataSource。
 */
private fun playableUriOf(source: PlaybackSource.Playable): Uri = when (source) {
    is PlaybackSource.PhoneFile -> Uri.fromFile(File(source.readTarget))
    is PlaybackSource.PhoneUri -> Uri.parse(source.readTarget)
}

/** 拖拽期间以手指的位置为准，否则每半秒的心跳会把滑块从指尖拽回去。 */
private fun shownFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

/** `1:02:03 / 1:20:00`；时长读不出来时只写已经播到哪儿，不给一个假的总长。 */
private fun playbackLine(positionMs: Long, durationMs: Long): String {
    val current = durationLabel(positionMs) ?: "0:00"
    val total = durationLabel(durationMs) ?: return current
    return "$current / $total"
}
