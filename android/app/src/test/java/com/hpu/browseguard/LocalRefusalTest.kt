package com.hpu.browseguard

import com.hpu.browseguard.ui.LocalRefusals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 引擎回空数组时的本地判定（§19.3 缺口 3 / P1③ 裁决）。
 *
 * 曾经这里借了 `NO_MEDIA_FOUND` / `FORMAT_UNAVAILABLE` 的「文案 + 码 + retryable」三件套：
 * 引擎在那两个响应里什么失败都没说（200 + 空列表，任务态还是 `done` / `awaiting_selection`），
 * 屏上却出现了一个只有引擎才有资格发的码——审计行查无此行，`retryable` 也是继承来的假语义。
 */
class LocalRefusalTest {

    @Test
    fun emptyArrayRefusalsCarryNoProtocolCode() {
        val sniff = LocalRefusals.sniffResults()
        val formats = LocalRefusals.formats()
        listOf(sniff.message to sniff.code, formats.message to formats.code).forEach { (message, code) ->
            assertNull("本地判定不得携带 §5 协议码：$message", code)
        }
        assertTrue(
            "两条都自证是本地判定",
            listOf(sniff.message, formats.message).all { it.contains("客户端本地判定") },
        )
        assertTrue(sniff.retryable && formats.retryable)
    }

    /** 措辞与窗口逐字钉住：改文案先在这里红，不等截图。 */
    @Test
    fun refusalTextsNameWhatTheEngineReturned() {
        assertEquals("未发现可用媒体 · 引擎回空结果表，客户端本地判定", LocalRefusals.sniffResults().message)
        assertEquals("未发现可用画质 · 引擎回空格式表，客户端本地判定", LocalRefusals.formats().message)
    }

    /**
     * 结构守卫：客户端源码里不允许出现「拿枚举当自己的码发」。
     *
     * `ErrorCode.X.name` / `.retryable` 是协议的**身份字段**，只有引擎响应配得上它们；
     * 已有的裸字面量守卫（`LocalTimeoutTest`）扫不到这种写法——两处伪造当初正是这么漏的。
     * `.message` 不在禁列：那是文案同源（§14.3），不附带任何码。
     */
    @Test
    fun noEnumIdentityFieldIsUsedAsAClientSideCode() {
        val offenders = fabricatedCodeSites()
        assertTrue("客户端用协议枚举的身份字段构造本地失败：$offenders", offenders.isEmpty())
    }

    /** 守卫别扫成空集，也别弱到抓不住被点名的那次伪造。 */
    @Test
    fun guardActuallyBites() {
        val seen = scannedFiles().map { it.name }.toSet()
        assertTrue("扫描路径没找对（工作目录应为 android/app）：$seen", seen.contains("HomeViewModel.kt"))
        val bites = OFFENDER_REGEX.containsMatchIn("ErrorCode.FORMAT_UNAVAILABLE.name,")
        assertTrue("正则抓不住 `ErrorCode.FORMAT_UNAVAILABLE.name` 这种写法，守卫是假的", bites)
    }

    private fun scannedFiles(): List<File> =
        File(SRC_ROOT).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun fabricatedCodeSites(): List<String> {
        val offenders = mutableListOf<String>()
        scannedFiles().forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (OFFENDER_REGEX.containsMatchIn(line)) {
                    offenders += "${file.name}:${index + 1} → ${line.trim()}"
                }
            }
        }
        return offenders
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
        val OFFENDER_REGEX = Regex("""ErrorCode\.[A-Z][A-Z0-9_]*\.(name|retryable)\b""")
    }
}
