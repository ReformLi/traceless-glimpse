package com.hpu.browseguard.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hpu.browseguard.R
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.playback.PlaybackSource
import com.hpu.browseguard.data.playback.RefuseReason
import java.io.File
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

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(state.resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(state.knownDurationMs) }
    var scrubTo by remember { mutableStateOf<Float?>(null) }
    var controlsShown by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var menuShown by remember { mutableStateOf(false) }
    var openProblem by remember { mutableStateOf<OpenFileProblem?>(null) }

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

    LaunchedEffect(controlsShown, isPlaying) {
        if (controlsShown && isPlaying) {
            delay(3_500)
            controlsShown = false
        }
    }

    // 切到后台：暂停就是回写进度的扳机（见 onIsPlayingChanged），不该让声音在背后继续放。
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

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
            .background(Color.Black)
            .pointerInput(state) { detectTapGestures { controlsShown = !controlsShown } },
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

        if (failed) {
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

        if (controlsShown && !failed) {
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
                }
            }
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
