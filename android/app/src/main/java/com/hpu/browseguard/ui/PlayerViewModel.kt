package com.hpu.browseguard.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.defaultHistoryTitle
import com.hpu.browseguard.data.playback.PlaybackSource
import com.hpu.browseguard.data.playback.RefuseReason
import com.hpu.browseguard.data.playback.playbackSourceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 播放器界面只看这一份状态。 */
sealed interface PlayerUiState {
    /** 记录还没从库里读出来：黑屏等着，别先闪一个「打不开」。 */
    data object Loading : PlayerUiState

    data class Ready(
        val title: String,
        val playable: PlaybackSource.Playable,
        /** 上一次看到的位次；0 就是从头播。 */
        val resumePositionMs: Long,
        /** 记录里那个可能已经滞后的总时长，探测到之前先用它占位。 */
        val knownDurationMs: Long,
    ) : PlayerUiState

    /** 这一行压根没法播：产物在电脑上，或者就没有产物。 */
    data class Refused(val reason: RefuseReason) : PlayerUiState
}

/**
 * 一次播放会话：读那一行、把进度写回那一行。
 *
 * 回写挂在 [writeScope] 上而不是界面协程：播放器 composable 销毁的那一刻正是**唯一**
 * 必须落库的时刻。用容器级作用域（而不是 viewModelScope）还有一个原因——播放器关掉时
 * 会清空自己的 ViewModelStore，如果回写还挂在那个 scope 上，会被一并取消。
 */
class PlayerViewModel(
    private val history: HistoryRepository,
    private val audit: AuditLogger,
    private val sandboxRoot: String?,
    private val recordId: String,
    writeScope: CoroutineScope? = null,
) : ViewModel() {

    private val writeScope: CoroutineScope = writeScope ?: viewModelScope

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Room 的清单流第一次发射就是全表，不用为播放器再加一个单行查询。
            val record = history.records.first().firstOrNull { it.id == recordId }
            if (record == null) {
                audit.log("playback_row_gone", mapOf("id" to recordId))
                _state.value = PlayerUiState.Refused(RefuseReason.NO_ARTIFACT)
                return@launch
            }
            val source = playbackSourceOf(record, sandboxRoot)
            if (source !is PlaybackSource.Playable) {
                val reason = (source as PlaybackSource.Refused).reason
                audit.log("playback_refused", mapOf("id" to recordId, "reason" to reason.name.lowercase()))
                _state.value = PlayerUiState.Refused(reason)
                return@launch
            }
            audit.log(
                "playback_opened",
                mapOf(
                    "id" to recordId,
                    "resume" to record.lastPlayedPositionMs.toString(),
                    "kind" to if (source is PlaybackSource.PhoneUri) "saf" else "file",
                ),
            )
            _state.value = PlayerUiState.Ready(
                title = record.title ?: defaultHistoryTitle(record.url),
                playable = source,
                resumePositionMs = record.lastPlayedPositionMs,
                knownDurationMs = record.mediaDurationMs,
            )
        }
    }

    /**
     * 落一次观看进度。
     *
     * 一帧都没播出去（位置与时长都是 0）时**什么都不写**：那种时候写下去就是把上一次
     * 真正的续播点抹成「从头播」，用户看到的后果是片子回到开头。
     * [durationMs] 可能是 `C.TIME_UNSET`（-1，直播流或没读到 moov），当成「时长未知」而不是时长。
     */
    fun flush(positionMs: Long, durationMs: Long) {
        val total = durationMs.takeIf { it > 0L } ?: 0L
        if (positionMs <= 0L && total <= 0L) {
            audit.log("playback_flush_skipped", mapOf("id" to recordId))
            return
        }
        writeScope.launch { history.markPlayed(recordId, positionMs, total) }
    }

    companion object {
        fun factory(
            history: HistoryRepository,
            audit: AuditLogger,
            sandboxRoot: String?,
            recordId: String,
            writeScope: CoroutineScope,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
                        "PlayerViewModel.Factory 不认识的类型：$modelClass"
                    }
                    return PlayerViewModel(history, audit, sandboxRoot, recordId, writeScope) as T
                }
            }
    }
}
