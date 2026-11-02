package com.hpu.browseguard

import com.hpu.browseguard.data.LogcatAuditLogger
import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.LocalWebViewSniffer
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.ui.toSniffPhase
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第 3 步①：后端构造收进组装根。
 *
 * 静态部分钉「UI 不再 new 实现」；行为部分钉空壳 WebView 嗅探的第一条事实——
 * 被选中时给的是 `Local(WEBVIEW_UNAVAILABLE)` 且不带 §5 码，而不是空表。
 */
class BackendInjectionTest {

    private val src = File(SRC_ROOT)
    private val ui = File("$SRC_ROOT/ui")

    @Test
    fun uiNeverConstructsBackendImplementations() {
        val files = ui.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertTrue("扫描没落到位：${ui.absolutePath}", files.size >= 2)

        val banned = listOf("HttpSniffer(", "HttpDownloader(", "LocalDirectDownloader(", "LocalWebViewSniffer(")
        val hits = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> banned.any { line.contains(it) } }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertTrue("界面层不得 new 具体后端：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun viewModelDependsOnTheInjectedInterfacesOnly() {
        val text = File("$SRC_ROOT/ui/HomeViewModel.kt").readText()
        assertTrue("设置必须是注入的", text.contains("private val settings: EngineSettings"))
        assertTrue("后端必须是注入的", text.contains("private val backends: BackendProvider"))
        assertTrue("引擎事实必须是注入的", text.contains("private val engineInfo: EngineInfo"))
        assertTrue("审计必须是注入的", text.contains("private val audit: AuditLogger"))
        assertFalse("不得再继承 AndroidViewModel", text.contains("AndroidViewModel"))
        assertFalse("不得自己 new 引擎客户端", text.contains("EngineClient("))
        assertFalse("审计不得直接写 Log.w", text.contains("Log.w("))
        assertTrue("嗅探必须经组装点", text.contains("backends.sniff("))
        assertTrue("下载必须经组装点", text.contains("backends.download("))
    }

    @Test
    fun assemblyRootIsTheOnlyConstructionSite() {
        val files = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("扫描没落到位：${src.absolutePath}", files.size >= 10)

        val construction = Regex("""(HttpSniffer|HttpDownloader|LocalDirectDownloader|LocalWebViewSniffer)\(""")
        val hits = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) ->
                    val trimmed = line.trim()
                    !trimmed.startsWith("class ") &&
                        !trimmed.startsWith("internal class ") &&
                        !trimmed.startsWith("*") &&
                        !trimmed.startsWith("//") &&
                        construction.containsMatchIn(line)
                }
                .map { (_, line) -> "${file.name}: ${line.trim()}" }
        }
        val owners = hits.map { it.substringBefore(':') }.distinct().sorted()
        assertEquals("具体后端只允许在组装根里构造：\n${hits.joinToString("\n")}", listOf("AppContainer.kt"), owners)
    }

    /** 审计与引擎事实也必须从组装根流进去；真机取证依赖固定 TAG。 */
    @Test
    fun assemblyRootWiresAuditAndEngineInfo() {
        val container = File("$SRC_ROOT/data/AppContainer.kt").readText()
        assertTrue("连接态事实必须由组装根提供", container.contains("HttpEngineInfo(client)"))
        assertTrue("审计实现必须是 LogcatAuditLogger", container.contains("LogcatAuditLogger()"))
        assertTrue("下载器必须拿到审计通道", container.contains("LocalDirectDownloader(audit = audit)"))
        val route = File("$SRC_ROOT/ui/HomeScreen.kt").readText()
        assertTrue("首页工厂必须传入引擎事实", route.contains("container.engineInfo"))
        assertTrue("首页工厂必须传入审计", route.contains("container.audit"))
        assertEquals("真机取证只认这一个 TAG", "BgvAudit", LogcatAuditLogger.TAG)
    }

    @Test
    fun theWebViewShellFailsLocallyWithoutBorrowingAProtocolCode() = runBlocking {
        val events = LocalWebViewSniffer()
            .sniff(SniffRequest("https://example.com/page", CookiePolicy.NONE, "/tmp"))
            .toList()

        val failed = events.single() as SniffEvent.Failed
        val local = failed.failure as BackendFailure.Local
        assertEquals(LocalReason.WEBVIEW_UNAVAILABLE, local.reason)
        assertNull("本地判定不得借 §5 码", failed.failure.toSniffPhase().code)
        assertTrue("文案要引导到连接电脑", local.message.contains("请连接电脑"))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
