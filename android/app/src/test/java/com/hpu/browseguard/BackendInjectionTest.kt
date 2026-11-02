package com.hpu.browseguard

import com.hpu.browseguard.data.LogcatAuditLogger
import com.hpu.browseguard.data.backend.LocalFailures
import com.hpu.browseguard.ui.toSniffPhase
import java.io.File
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

    /** WebView 起不来与页面没有媒体是两种事实，且都不得借 §5 码。 */
    @Test
    fun webViewFailuresAreLocalOnlyAndSayWhichFactTheyAre() {
        val unavailable = LocalFailures.webViewUnavailable()
        assertNull("本地判定不得借 §5 码", unavailable.toSniffPhase().code)
        assertTrue("不可用要引导到连接电脑", unavailable.message.contains("请连接电脑"))

        val noMedia = LocalFailures.webViewNoMedia()
        assertNull(noMedia.toSniffPhase().code)
        assertTrue("没有媒体要自证是本地判定", noMedia.message.contains("客户端本地判定"))
        assertFalse("没有媒体不等于不可用", noMedia.message.contains("不可用"))
    }

    /** 两个本地入口必须共用同一份头过滤规则，避免一边剥、一边放。 */
    @Test
    fun bothLocalBackendsUseTheSameHeaderPolicy() {
        val downloader = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()
        val mapping = File("$SRC_ROOT/data/backend/LocalSniffMapping.kt").readText()
        val sniffer = File("$SRC_ROOT/data/backend/LocalWebViewSniffer.kt").readText()
        assertTrue("下载侧必须走共享策略", downloader.contains("SniffHeaderPolicy.sanitize("))
        assertTrue("嗅探侧必须在构造 SniffResult 前走共享策略", mapping.contains("SniffHeaderPolicy.sanitize("))
        assertTrue("WebView 必须经纯映射层产结果", sniffer.contains("LocalSniffMapping.toResult("))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
