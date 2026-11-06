package com.hpu.browseguard

import com.hpu.browseguard.data.playback.PlaybackSource
import com.hpu.browseguard.data.playback.RefuseReason
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeHistoryRepository
import com.hpu.browseguard.ui.PlayerUiState
import com.hpu.browseguard.ui.PlayerViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 一次播放会话的读写：打开时带上续播点，退出时把新的位置写回去。
 *
 * 钉住两件容易做错的事：
 * 1. 一帧都没播出去就写库，等于把上一次真正的续播点抹成「从头播」；
 * 2. 归一化必须发生在落库之前，界面拿到的数才能直接用来 seekTo。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelBehaviorTest {

    private companion object {
        val TMP = File(requireNotNull(System.getProperty("java.io.tmpdir")))
        val SANDBOX = File(TMP, "bgv-player-sandbox").path
        val OUTSIDE = File(TMP, "bgv-player-engine").path
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun opensAPhoneSideArtifactWithTheResumePointAlreadyInIt() = runTest {
        val history = completedRow(path = File(SANDBOX, "clip.mp4").path, watched = 45_000, total = 120_000)
        val audit = FakeAuditLogger()

        val state = PlayerViewModel(history, audit, SANDBOX, "a").state.value

        val ready = state as PlayerUiState.Ready
        assertEquals(PlaybackSource.PhoneFile(File(SANDBOX, "clip.mp4").path), ready.playable)
        assertEquals("库里那个续播点要原样交给播放器去 seek", 45_000L, ready.resumePositionMs)
        assertEquals(120_000L, ready.knownDurationMs)
        assertEquals(1, audit.of("playback_opened").size)
    }

    @Test
    fun anArtifactLivingOnTheComputerNeverReachesThePlayer() = runTest {
        val history = completedRow(path = File(OUTSIDE, "clip.mp4").path, watched = 0, total = 0)
        val audit = FakeAuditLogger()

        val state = PlayerViewModel(history, audit, SANDBOX, "a").state.value

        assertEquals(PlayerUiState.Refused(RefuseReason.ON_ENGINE), state)
        assertEquals("被拒的这次要留下为什么", 1, audit.of("playback_refused").size)
    }

    @Test
    fun aRowThatDisappearedIsRefusedToo() = runTest {
        val history = FakeHistoryRepository()

        val state = PlayerViewModel(history, FakeAuditLogger(), SANDBOX, "gone").state.value

        assertEquals(PlayerUiState.Refused(RefuseReason.NO_ARTIFACT), state)
    }

    /** 起播失败、文件读不出来那一类会话：位置与时长都是 0，写下去就是毁掉上次的进度。 */
    @Test
    fun aSessionThatNeverStartedPlayingKeepsThePreviousResumePoint() = runTest {
        val history = completedRow(path = File(SANDBOX, "clip.mp4").path, watched = 45_000, total = 120_000)
        val audit = FakeAuditLogger()
        val viewModel = PlayerViewModel(history, audit, SANDBOX, "a")
        // 摆数据时那句 markPlayed 也在调用记录里，要比的是「这一次之后有没有多写」。
        val seededWrites = history.calls.count { it == "markPlayed" }

        viewModel.flush(0L, 0L)
        advanceUntilIdle()

        assertEquals("一行都没播出去就不该动库", seededWrites, history.calls.count { it == "markPlayed" })
        assertEquals(45_000L, history.rows.first { it.id == "a" }.lastPlayedPositionMs)
        assertEquals(1, audit.of("playback_flush_skipped").size)
    }

    @Test
    fun everyFlushStoresWhatTheNextSessionShouldSeekTo() = runTest {
        val history = completedRow(path = File(SANDBOX, "clip.mp4").path, watched = 45_000, total = 120_000)
        val viewModel = PlayerViewModel(history, FakeAuditLogger(), SANDBOX, "a")

        viewModel.flush(70_000, 120_000)
        advanceUntilIdle()
        assertEquals("看到哪儿就续到哪儿", 70_000L, row(history).lastPlayedPositionMs)

        viewModel.flush(115_000, 120_000)
        advanceUntilIdle()
        assertEquals("只剩五秒算看完，下次从头播", 0L, row(history).lastPlayedPositionMs)

        // C.TIME_UNSET（读不出总长的那种容器）：位置照写，总长留 0，别把那个哨兵值写进库。
        viewModel.flush(30_000, Long.MIN_VALUE)
        advanceUntilIdle()
        assertEquals(30_000L, row(history).lastPlayedPositionMs)
        assertEquals(0L, row(history).mediaDurationMs)
    }

    private fun row(history: FakeHistoryRepository) = history.rows.first { it.id == "a" }

    /** 摆一行「已完成、产物在 [path]、看过 watched 毫秒」的记录。 */
    private suspend fun completedRow(path: String, watched: Long, total: Long): FakeHistoryRepository {
        val history = FakeHistoryRepository()
        history.beginSniff("a", "https://example.com/watch")
        history.markDone("a", path, "mp4", 1_024L, null)
        history.markPlayed("a", watched, total)
        return history
    }
}
