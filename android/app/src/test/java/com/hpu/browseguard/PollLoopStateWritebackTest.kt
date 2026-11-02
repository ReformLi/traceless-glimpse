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
 * 搬迁记录（§19.5 迁移影响 1）：三条环原来都叫 `HomeViewModel.await*()`，
 * 现在分别住在 `data/backend/HttpSniffer.kt` 和 `data/backend/HttpDownloader.kt` 里，
 * 「回写」的形式从 `_state.update` 换成发 `*Event.Status` / `*Event.Preparing` /
 * `*Event.Progress`，语义不变——守卫跟着换了锚点，并各用「删掉发状态那行」的变异体复跑过
 * （见 §20 取证）。
 *
 * 第 2 步③ 加了第四条环：`LocalDirectDownloader.start()`。它不取任务，「读到新事实」
 * 那一行是 `stream.read(buffer)`——本表用 [Loop.pollAnchor] 表达这个差别，
 * 所以「全部登记」那条用例改成按 `pollAnchor` 分组计数，引擎环和本地环都受约束。
 *
 * 为什么还是静态守卫而不是行为单测：`HomeViewModel` 现在仍然自己 `new` 后端，
 * 假引擎进不来。M4 第 3 步落 FakeBackend 后整族换成行为断言。
 */
class PollLoopStateWritebackTest {

    /**
     * 每条轮询环 ↔ 它必须发出的状态。
     *
     * [emitAnchor] 必须出现在同一函数体内、[pollAnchor] 之后——写在读新事实之前
     * 等于回写上一轮的旧值。两条锚点都在「去掉全部空白」后的函数体里找：
     * `emit(` 和事件名为了行宽经常拆成两行，按原始行匹配会把真实存在的回写判成没有。
     */
    private data class Loop(
        val file: String,
        val function: String,
        /** 「读到新事实」那一行：引擎轮询是取任务，本地下载是读 socket。 */
        val pollAnchor: String,
        val emitAnchor: String,
        val whatGoesStale: String,
    )

    private val loops = listOf(
        Loop(
            "$SRC_ROOT/data/backend/HttpSniffer.kt",
            "fun sniff(",
            "client.task(",
            "emit(SniffEvent.Status(",
            "SniffPhase.Running 停在初始 QUEUED，界面拿不到 sniffing",
        ),
        Loop(
            "$SRC_ROOT/data/backend/HttpDownloader.kt",
            "fun prepare(",
            "client.task(",
            "emit(DownloadEvent.Preparing(",
            "Preparing.status 停在初始 QUEUED，「画质解析中」是过时报数",
        ),
        Loop(
            "$SRC_ROOT/data/backend/HttpDownloader.kt",
            "fun start(",
            "client.task(",
            "emit(DownloadEvent.Progress(",
            "进度条不刷新，取消后的冻结进度也无从写回",
        ),
        Loop(
            "$SRC_ROOT/data/backend/LocalDirectDownloader.kt",
            "fun start(",
            "stream.read(buffer)",
            "emit(DownloadEvent.Progress(",
            "本地直链下载进度条不刷新，用户看不出还在写盘",
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
            if (loop.pollAnchor !in body) {
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
            val poll = body.indexOf(loop.pollAnchor)
            val writeback = body.indexOf(loop.emitAnchor)
            assertTrue("${loop.function} 的发状态不在取任务之后", poll in 0 until writeback)
        }
    }

    /**
     * 守卫别扫成空集，也别漏掉新写的环：按「读到新事实」那一行分组，
     * 每种锚点在 `src/main` 的出现次数必须和本表登记的环数一一对应，文件也要对得上。
     *
     * 这条把引擎轮询（`client.task(`）和本地轮询（`stream.read(buffer)`）都纳进枚举——
     * 上一版只数 `client.task(`，第四条本地环一进来就会把「纳管文件集合」比歪。
     *
     * 允许一行任务查询显式标注 `one-shot` 退出枚举：冷启动对账只查一次状态，
     * 没有「每轮回写」的语义。标注必须写在同一行，避免整文件豁免把新轮询也漏掉。
     */
    @Test
    fun pollLoopsAreAllRegistered() {
        val sources = File(SRC_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val problems = mutableListOf<String>()
        loops.groupBy(Loop::pollAnchor).forEach { (anchor, registered) ->
            val sites = sources.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (anchor in line && ONE_SHOT_MARK !in line) "${file.name}:${index + 1}" else null
                }
            }
            if (sites.size != registered.size) {
                problems += "$anchor 在源码里出现 ${sites.size} 次，本表只登记 ${registered.size} 条：$sites"
            }
            val managed = registered.map { it.file.substringAfterLast('/') }.toSet()
            val covered = sites.map { it.substringBefore(':') }.toSet()
            if (covered != managed) {
                problems += "$anchor 的调用点 $covered 与本表纳管的 $managed 不一致"
            }
        }
        assertTrue("轮询环登记与源码不一致：$problems", problems.isEmpty())
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
            lines[index].startsWith("$indent}")
        } ?: return null
        // 去空白：让 `emit(\n    DownloadEvent.Progress(` 这种拆行也能按一个锚点命中。
        return lines.subList(start, end + 1).joinToString("\n").filterNot { it.isWhitespace() }
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
        const val ONE_SHOT_MARK = "one-shot"
    }
}
