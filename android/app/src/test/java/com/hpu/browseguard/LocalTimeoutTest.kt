package com.hpu.browseguard

import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.ui.LocalTimeouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地失败的错误码表示（收尾③ 裁决②，P1）。
 *
 * 下载侧曾在本地超时装进 `NETWORK_TIMEOUT`——那是 §5 表里**引擎才会发**的码，
 * 客户端计时器没有资格替引擎发言。三条本地超时统一 `code = null`，
 * 超时窗口写进文案，界面自证「这是本地计时，不是引擎报的错」。
 */
class LocalTimeoutTest {

    @Test
    fun allThreeTimeoutsCarryNoProtocolCode() {
        val sniff = LocalTimeouts.sniff()
        val prepare = LocalTimeouts.prepare()
        val download = LocalTimeouts.download()
        listOf(
            sniff.message to sniff.code,
            prepare.message to prepare.code,
            download.message to download.code,
        ).forEach { (message, code) ->
            assertNull("本地失败不得携带 §5 协议码：$message", code)
        }
        val messages = listOf(sniff.message, prepare.message, download.message)
        assertTrue("三条都标了本地计时", messages.all { it.contains("本地计时") })
        assertTrue("三条都可重试", sniff.retryable && prepare.retryable && download.retryable)
    }

    /** 三条文案与窗口逐字钉住：改了常量或措辞，这里先红，而不是等截图发现。 */
    @Test
    fun timeoutTextsNameTheirOwnWindow() {
        assertEquals("嗅探超时 · 客户端本地计时 90s 到点", LocalTimeouts.sniff().message)
        assertEquals("画质解析超时 · 客户端本地计时 120s 到点", LocalTimeouts.prepare().message)
        assertEquals(
            "下载超时 · 客户端本地计时 1800s 到点，任务仍在引擎侧运行",
            LocalTimeouts.download().message,
        )
    }

    /**
     * 结构守卫：客户端源码里不允许出现「等于 §5 码名」的字符串字面量。
     *
     * 协议码只能来自 `ErrorCode` 枚举（引擎响应的解码结果），一旦有裸字面量，
     * 就是客户端在伪造引擎没说过的话——`NETWORK_TIMEOUT` 那次就是这么漏的。
     */
    @Test
    fun noProtocolCodeNameAppearsAsBareLiteral() {
        val root = File("src/main/java/com/hpu/browseguard")
        assertTrue("扫描路径没找对（工作目录应为 android/app）：$root", root.isDirectory)
        val codeNames = ErrorCode.values().map { it.name }.toSet()
        val literal = Regex("\"([A-Z][A-Z0-9_]*)\"")
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    literal.findAll(line).forEach { match ->
                        if (match.groupValues[1] in codeNames) {
                            offenders += "${file.name}:${index + 1} → ${match.value}"
                        }
                    }
                }
            }
        assertTrue("客户端伪造了 §5 协议码字面量：$offenders", offenders.isEmpty())
    }

    /** 守卫别扫成空集：被点名的问题文件必须在扫描范围内。 */
    @Test
    fun scanListIncludesViewModel() {
        val seen = File("src/main/java/com/hpu/browseguard").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toSet()
        assertTrue(seen.containsAll(setOf("HomeViewModel.kt", "HomeScreen.kt", "EngineClient.kt")))
    }
}
