package com.hpu.browseguard

import com.hpu.browseguard.data.EngineFailure
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.toBackendFailure
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.ui.toDownloadPhase
import com.hpu.browseguard.ui.toSniffPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地失败的错误码表示（收尾③ 裁决②，P1；M4 第 2 步① 搬到后端层）。
 *
 * 下载侧曾在本地超时装进 `NETWORK_TIMEOUT`——那是 §5 表里**引擎才会发**的码，
 * 客户端计时器没有资格替引擎发言。三条本地超时统一 `code = null`，
 * 超时窗口写进文案，界面就能自证「这是本地计时，不是引擎报的错」。
 *
 * 改造后超时发生在后端（`withTimeout` 跟着轮询环一起搬过去了），产出的是
 * `BackendFailure.Local`；「没有码」从「写 null」升级成「类型里就没有码可写」——
 * 但界面阶段仍然带可空的 `code`，所以这里同时钉住映射后的结果。
 */
class LocalTimeoutTest {

    @Test
    fun allThreeTimeoutsAreLocalFailuresWithoutAProtocolCode() {
        val failures = listOf(
            LocalFailures.sniffTimeout(),
            LocalFailures.prepareTimeout(),
            LocalFailures.downloadTimeout(),
            LocalFailures.webViewTimeout(),
        )
        assertTrue(
            "四条都必须归本地超时，不许混进协议失败",
            failures.all { it.reason == LocalReason.TIMEOUT },
        )
        val phases = listOf(
            failures[0].toSniffPhase().code,
            failures[1].toDownloadPhase().code,
            failures[2].toDownloadPhase().code,
            failures[3].toSniffPhase().code,
        )
        phases.forEach { assertNull("本地失败映射后仍不得携带 §5 协议码", it) }
        assertTrue("四条都标了本地计时", failures.all { it.message.contains("本地计时") })
        assertTrue("四条都可重试", failures.all { it.retryable })
    }

    /** 三条文案与窗口逐字钉住：改了常量或措辞，这里先红，而不是等截图发现。 */
    @Test
    fun timeoutTextsNameTheirOwnWindow() {
        assertEquals("嗅探超时 · 客户端本地计时 90s 到点", LocalFailures.sniffTimeout().message)
        assertEquals("画质解析超时 · 客户端本地计时 120s 到点", LocalFailures.prepareTimeout().message)
        assertEquals(
            "下载超时 · 客户端本地计时 1800s 到点，任务仍在引擎侧运行",
            LocalFailures.downloadTimeout().message,
        )
        assertEquals("手机 WebView 嗅探超时 · 客户端本地计时 30s 到点", LocalFailures.webViewTimeout().message)
        assertEquals(90_000L, LocalFailures.SNIFF_MS)
        assertEquals(120_000L, LocalFailures.PREPARE_MS)
        assertEquals(1_800_000L, LocalFailures.DOWNLOAD_MS)
        assertEquals(30_000L, LocalFailures.WEBVIEW_MS)
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
        scannedFiles().forEach { file ->
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

    /** 守卫别扫成空集：搬迁后的后端文件也必须在扫描范围内。 */
    @Test
    fun scanListIncludesTheMovedFiles() {
        val seen = scannedFiles().map { it.name }.toSet()
        assertTrue(
            "扫描集缺文件：$seen",
            seen.containsAll(
                setOf(
                    "HomeViewModel.kt",
                    "HomeScreen.kt",
                    "EngineClient.kt",
                    "HttpSniffer.kt",
                    "HttpDownloader.kt",
                    "LocalFailures.kt",
                ),
            ),
        )
    }

    /** 引擎响应 → `BackendFailure.Protocol` 是码上屏的唯一通道，这里钉住它确实带码。 */
    @Test
    fun protocolFailuresKeepTheEngineCode() {
        val failure = EngineFailure.Protocol(504, ErrorPayload.of(ErrorCode.NETWORK_TIMEOUT)).toBackendFailure()
        assertTrue("引擎报的超时应保留协议码：$failure", failure is BackendFailure.Protocol)
        failure as BackendFailure.Protocol
        assertEquals("NETWORK_TIMEOUT", failure.code.name)
        assertEquals("NETWORK_TIMEOUT", failure.toSniffPhase().code)
        assertEquals("NETWORK_TIMEOUT", failure.toDownloadPhase().code)
    }

    private fun scannedFiles(): List<File> = File("src/main/java/com/hpu/browseguard")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}
