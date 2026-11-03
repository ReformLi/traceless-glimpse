package com.hpu.browseguard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器模式的接线钉子（JVM 跑不动 WebView，只能钉源码）。
 *
 * `NavigationPolicy` 的判定本身有 `BrowserNavigationPolicyTest` 逐条测；这里只保证
 * 那条判定真的挂在主框架导航上、拦下来真的留得下一行——缺任何一半，广告劫持就只是「测过了」。
 */
class BrowserWiringTest {

    @Test
    fun mainFrameNavigationGoesThroughTheGateAndLeavesATrail() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val controller = File("$SRC_ROOT/data/browser/BrowserSniffController.kt").readText()

        assertTrue("主框架跳转必须先过闸门", screen.contains("controller.onMainFrameNavigation("))
        assertTrue("拦下来的 URL 要交给策略判定", controller.contains("NavigationPolicy.shouldBlock("))
        assertTrue("静默丢弃也要留一行", controller.contains("""audit.log("navigation_blocked""""))
    }

    @Test
    fun theDrawerAndTheCookieSummaryAreBothWired() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val provider = File("$SRC_ROOT/data/backend/CookieProvider.kt").readText()
        val downloader = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()

        assertTrue("把手拖拽要收在最小与最大之间", screen.contains(".coerceIn(drawerMin, drawerMax)"))
        assertTrue("排序必须用带发现先后那条 Comparator", controllerUsesOrder())
        assertTrue("被抑制的读取次数靠汇总交代", provider.contains("\"cookie_audit_summary\""))
        assertTrue("下载结束（含失败与取消）都要收尾", downloader.contains(".onCompletion { cookies.finish() }"))
    }

    private fun controllerUsesOrder(): Boolean {
        val controller = File("$SRC_ROOT/data/browser/BrowserSniffController.kt").readText()
        return controller.contains("sortedWith(BROWSER_CANDIDATE_ORDER)") &&
            controller.contains(".thenBy { it.discoveredAt }")
    }

    /** 浏览器会话关闭时的报账（M7 裁决 1）：名字是自己的，时机是关会话，不是等下载。 */
    @Test
    fun closingTheBrowserWritesItsOwnCookieSummary() {
        val controller = File("$SRC_ROOT/data/browser/BrowserSniffController.kt").readText()
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val container = File("$SRC_ROOT/data/AppContainer.kt").readText()

        assertTrue("退出浏览器要补一行 browser_close_summary", controller.contains("""cookies.finish("browser_close_summary")"""))
        assertTrue("会话中枢离开界面时必须被关掉", screen.contains("controller.close()"))
        assertTrue("浏览器会话有属于自己的 Cookie 账本", container.contains("cookies = ConsentCookieProvider(cookieVault, cookieConsents, audit),"))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
