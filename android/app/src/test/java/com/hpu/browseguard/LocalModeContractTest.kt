package com.hpu.browseguard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地模式的两条界面/状态契约（细节 1、3 与缺口裁决 ① 的第二道门）。
 *
 * 这些路径没有可直接驱动的界面入口，因此用源码契约钉住：
 * 函数体提取后做包含/排斥判定，并先断言哨兵，确保扫描本身不是空的——
 * 否则「没找到就等于没违规」会让守卫变成一句空话。
 */
class LocalModeContractTest {

    private val viewModel = File("$SRC_ROOT/ui/HomeViewModel.kt")
    private val screen = File("$SRC_ROOT/ui/HomeScreen.kt")

    /** 细节 1：有活跃任务时开关不生效，且不再走「取消任务 + 打回 Idle」的旧路。 */
    @Test
    fun executorSwitchIsRefusedWhileATaskIsInFlight() {
        val body = functionBody(viewModel, "selectDownloadExecutor")
        assertTrue("扫描没落到位：$body", body.contains("settings.downloadExecutor = executor"))
        assertTrue("缺了活跃任务门", body.contains("if (snapshot.downloadInFlight) return"))
        assertFalse("切换不得再取消正在跑的任务", body.contains("downloadJob?.cancel()"))
    }

    /** 缺口裁决 ①：本地模式既不显示可勾的合并，也不接受编程调用把状态勾回去。 */
    @Test
    fun choosingLocalModeClearsAndLocksMerge() {
        val switch = functionBody(viewModel, "selectDownloadExecutor")
        assertTrue("切到本地必须把 mergeSelected 落回 false", switch.contains("executor == DownloadExecutor.LOCAL_DIRECT) false"))
        assertTrue("toggleMerge 少了第二道门", functionBody(viewModel, "toggleMerge").contains("mergeAvailable"))
        val card = functionBody(screen, "FormatCard")
        assertTrue("扫描没落到位：$card", card.contains("format_merge_local_hint"))
        assertTrue("复选框本身必须是置灰的", card.contains("enabled = mergeEnabled"))
        assertTrue("整行也要一起禁用", card.contains("clickable(enabled = mergeEnabled)"))
    }

    /** 细节 3：单行画质表自动选中，按钮随 selectedFormat 立刻变成高亮可点。 */
    @Test
    fun aSingleFormatRowIsSelectedAutomatically() {
        val body = functionBody(viewModel, "applyDownloadEvent")
        assertTrue("扫描没落到位：$body", body.contains("toFinalPhase()"))
        assertTrue(
            "Selecting 分支必须拿 singleOrNull 自动选中",
            body.contains("event.formats.singleOrNull()?.formatId"),
        )
    }

    /** 缺口裁决 ②：本地分支只认手机侧沙箱根，绝不回落到引擎侧的 outputDir。 */
    @Test
    fun localDownloadUsesOnlyThePhoneSandboxRoot() {
        val body = functionBody(viewModel, "startDownload")
        assertTrue("扫描没落到位：$body", body.contains("StartRequest.ViaLocal("))
        assertFalse("本地分支不得读引擎侧输出目录", body.contains("snapshot.outputDir"))
        assertTrue(body.contains("snapshot.localSandboxRoot"))
        assertTrue(body.contains("allowedRoots = listOf(sandboxRoot)"))
    }

    private fun functionBody(file: File, name: String): String {
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText()
        val start = text.indexOf("fun $name(")
        assertTrue("找不到函数 $name", start >= 0)
        val open = text.indexOf('{', start)
        assertTrue("$name 不是块体函数", open > start)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        throw AssertionError("$name 的函数体没有闭合")
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
