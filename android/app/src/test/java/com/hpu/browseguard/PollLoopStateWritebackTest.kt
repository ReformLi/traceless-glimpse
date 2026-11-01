package com.hpu.browseguard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 轮询循环必须把读到的状态写回 UI 镜像（收尾③ 之后的 MuMu 物理验收轮，P1）。
 *
 * `HomeViewModel` 里 `await*` 一族是「读引擎、等状态」的轮询，界面看的是 `_state` 里的
 * sealed 镜像。轮询只 return 不回写，镜像就永远停在点击那一刻的初始 `QUEUED`，
 * 于是 `sniffing` / `format_preparing` 两条屏显文案变成永远不会命中的死分支。
 * 这条守卫把「回写」钉成结构约束，而不是靠人记得。
 *
 * 为什么是静态守卫而不是行为单测：`HomeViewModel` 自己 `EngineClient()` 直接实例化，
 * 现阶段没法把假引擎注进去。M4 第 3 步落 FakeBackend 后，这里换成行为断言
 * （推一轮假任务 → 断言 `_state` 里出现 `sniffing` / `format_preparing`）。
 */
class PollLoopStateWritebackTest {

    private val viewModelSource: List<String> by lazy {
        val root = File("src/main/java/com/hpu/browseguard/ui/HomeViewModel.kt")
        assertTrue("扫描路径没找对（工作目录应为 android/app）：$root", root.isFile)
        root.readLines()
    }

    /**
     * 每个轮询循环 ↔ 它必须回写的 UI 镜像阶段。
     *
     * 左侧是轮询函数名，右侧是「回写目标」——写进 `while` 体里的 `_state.update` 之后
     * 必须出现的构造调用。缺一个即判失败，措辞直接指出界面会看到什么假数。
     */
    @Test
    fun everyAwaitLoopWritesItsStatusBackToItsPhase() {
        val required = linkedMapOf(
            "awaitTerminal" to "SniffPhase.Running(",
            "awaitAwaitingSelection" to "DownloadPhase.Preparing(",
            "awaitDownloadTerminal" to "DownloadPhase.Running(",
        )
        val missing = mutableListOf<String>()
        required.forEach { (functionName, phaseCtor) ->
            val body = functionBody(functionName)
            if (body == null) {
                missing += "$functionName 没找到（函数改名了？）"
                return@forEach
            }
            if ("client.task(" !in body) {
                missing += "$functionName 不是轮询循环，请同步本表"
            }
            if (phaseCtor !in body) {
                missing += "$functionName 只 return 不回写：界面将停在点击时的初始 QUEUED，" +
                    "$phaseCtor 成死分支"
            }
        }
        assertTrue("轮询循环漏写 UI 镜像回写：$missing", missing.isEmpty())
    }

    /** 守卫别扫成空集：`await*` 必须恰好找到这三条，多一条就得先把它纳管。 */
    @Test
    fun scanSeesExactlyTheThreeAwaitLoops() {
        val found = viewModelSource
            .mapNotNull { line -> Regex("^\\s*(?:private )?suspend fun (await\\w*)\\(").find(line)?.groupValues?.get(1) }
            .toSet()
        assertTrue("预期三条轮询循环，实际 $found", found == setOf("awaitTerminal", "awaitAwaitingSelection", "awaitDownloadTerminal"))
    }

    /** 回写在循环体里、取到任务之后——写在 `client.task` 之前等于回写上一轮的旧值。 */
    @Test
    fun writebackHappensAfterThePollNotBefore() {
        listOf("awaitTerminal", "awaitAwaitingSelection", "awaitDownloadTerminal").forEach { functionName ->
            val body = requireNotNull(functionBody(functionName)) { "$functionName 没找到" }
            val poll = body.indexOf("client.task(")
            val writeback = body.indexOf("_state.update")
            assertTrue("$functionName 的回写不在取任务之后", poll in 0 until writeback)
        }
    }

    /** 取 `fun {name}` 到首个与 `fun` 同级缩进闭合括号为止的源码文本。 */
    private fun functionBody(name: String): String? {
        val header = Regex("^\\s*(?:private )?suspend fun $name\\(")
        val start = viewModelSource.indexOfFirst { header.containsMatchIn(it) }
        if (start < 0) return null
        val indent = viewModelSource[start].takeWhile { it == ' ' }
        val end = ((start + 1) until viewModelSource.size).firstOrNull { index ->
            viewModelSource[index] == "$indent}"
        } ?: return null
        return viewModelSource.subList(start, end + 1).joinToString("\n")
    }
}
