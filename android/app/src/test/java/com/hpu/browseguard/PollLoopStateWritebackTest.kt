package com.hpu.browseguard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 轮询环必须把读到的状态发出去（收尾③ 之后的 MuMu 物理验收轮，P1；M4 第 2 步① 搬迁）。
 *
 * 界面看的是 `_state` 里的 sealed 镜像。轮询只 return 不回写，镜像就永远停在点击那一刻的
 * 初始 `QUEUED`，于是 `sniffing` / `format_preparing` 两条屏显文案变成永远不会命中的死分支。
 *
 * 搬迁记录（§19.5 迁移影响 1）：嗅探那条环原来叫 `HomeViewModel.awaitTerminal()`，
 * 现在住在 `data/backend/HttpSniffer.kt` 里，「回写」的形式从 `_state.update` 换成发 `SniffEvent.Status`，
 * 语义不变——守卫跟着换了锚点，并用「删掉发 Status 那行」的变异体复跑过（见 §20 取证）。
 * 另外两条环（`awaitAwaitingSelection` / `awaitDownloadTerminal`）第 2 步② 才搬，
 * 锚点仍指向 ViewModel。
 *
 * 为什么还是静态守卫而不是行为单测：`HomeViewModel` 现在仍然自己 `new` 后端，
 * 假引擎进不来。M4 第 3 步落 FakeBackend 后整族换成行为断言。
 */
class PollLoopStateWritebackTest {

    /**
     * 每条轮询环 ↔ 它必须发出的状态。
     *
     * [emitAnchor] 必须出现在同一函数体内、`client.task(` 之后——写在取任务之前
     * 等于回写上一轮的旧值。
     */
    private data class Loop(
        val file: String,
        val function: String,
        val emitAnchor: String,
        val whatGoesStale: String,
    )

    private val loops = listOf(
        Loop(
            "$SRC_ROOT/data/backend/HttpSniffer.kt",
            "fun sniff(",
            "emit(SniffEvent.Status(",
            "SniffPhase.Running 停在初始 QUEUED，界面拿不到 sniffing",
        ),
        Loop(
            "$SRC_ROOT/ui/HomeViewModel.kt",
            "fun awaitAwaitingSelection(",
            "DownloadPhase.Preparing(",
            "Preparing.status 停在初始 QUEUED，「画质解析中」是过时报数",
        ),
        Loop(
            "$SRC_ROOT/ui/HomeViewModel.kt",
            "fun awaitDownloadTerminal(",
            "DownloadPhase.Running(",
            "进度条不刷新，取消后的冻结进度也无从写回",
        ),
    )

    @Test
    fun everyPollLoopEmitsItsStatusAfterThePoll() {
        val problems = mutableListOf<String>()
        loops.forEach { loop ->
            val body = functionBody(loop)
                ?: run {
                    problems += "${loop.function} 没找到（改名了？）"
                    return@forEach
                }
            if ("client.task(" !in body) {
                problems += "${loop.function} 不是轮询循环，请同步本表"
            }
            if (loop.emitAnchor !in body) {
                problems += "${loop.function} 只 return 不发状态：${loop.whatGoesStale}"
            }
        }
        assertTrue("轮询环漏发状态：$problems", problems.isEmpty())
    }

    /** 回写在取任务之后，不在之前。 */
    @Test
    fun writebackHappensAfterThePollNotBefore() {
        loops.forEach { loop ->
            val body = requireNotNull(functionBody(loop)) { "${loop.function} 没找到" }
            val poll = body.indexOf("client.task(")
            val writeback = body.indexOf(loop.emitAnchor)
            assertTrue("${loop.function} 的发状态不在取任务之后", poll in 0 until writeback)
        }
    }

    /**
     * 守卫别扫成空集，也别漏掉新写的环：全 `src/main` 里 `client.task(` 的调用点
     * 必须恰好三条，且都在这两个文件里、都被上表纳管。
     */
    @Test
    fun pollLoopsAreAllRegistered() {
        val sites = File(SRC_ROOT).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().mapIndexedNotNull { index, line ->
                if ("client.task(" in line) "${file.name}:${index + 1}" else null
            } }
            .toList()
        assertTrue("预期三条轮询取任务，实际 $sites", sites.size == 3)
        val managed = loops.map { it.file.substringAfterLast('/') }.toSet()
        val covered = sites.map { it.substringBefore(':') }.toSet()
        assertTrue("有轮询环没被本表纳管：$covered vs $managed", covered == managed)
    }

    private fun functionBody(loop: Loop): String? {
        val lines = File(loop.file).let { f ->
            assertTrue("扫描路径没找对（工作目录应为 android/app）：$f", f.isFile)
            f.readLines()
        }
        val start = lines.indexOfFirst { loop.function in it }
        if (start < 0) return null
        val indent = lines[start].takeWhile { it == ' ' }
        val end = ((start + 1) until lines.size).firstOrNull { index ->
            lines[index] == "$indent}"
        } ?: return null
        return lines.subList(start, end + 1).joinToString("\n")
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
