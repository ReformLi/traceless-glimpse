package com.hpu.browseguard

import java.io.File
import org.junit.Assert.assertFalse
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
    fun theResultsFabAndDialogAreBothWired() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val provider = File("$SRC_ROOT/data/backend/CookieProvider.kt").readText()
        val downloader = File("$SRC_ROOT/data/backend/LocalDirectDownloader.kt").readText()

        assertTrue("抓到的媒体要收进悬浮球", screen.contains("FloatingActionButton("))
        assertTrue("悬浮球必须能打开结果弹窗", screen.contains("SniffResultsDialog("))
        assertTrue("每条候选都要能复制链接", screen.contains("R.string.browser_candidate_copy"))
        assertTrue("排序必须用带发现先后那条 Comparator", controllerUsesOrder())
        assertTrue("被抑制的读取次数靠汇总交代", provider.contains("\"cookie_audit_summary\""))
        assertTrue("下载结束（含失败与取消）都要收尾", downloader.contains(".onCompletion { cookies.finish() }"))
    }

    /** 隐身模式：WebView 仍然铺满渲染，只是盖一层提示，不缩成 0 也不设 INVISIBLE。 */
    @Test
    fun headlessModeKeepsTheWebViewAliveUnderACover() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()

        assertTrue("隐藏网页时 WebView 仍要挂在布局里", screen.contains("AndroidView("))
        assertTrue(
            "隐藏时用不透明提示层盖住",
            screen.contains("if (!pageVisible)") && screen.contains("BrowserHiddenCover("),
        )
        assertTrue("顶栏要有「显示网页」入口", screen.contains("R.string.browser_show_page"))
    }

    /** M10.5：外跳、权限与表单保存都要在 WebView 初始化处关闭，不能等页面自觉。 */
    @Test
    fun webViewCarriesItsSafetyArmor() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()

        assertTrue(
            "非 http(s) scheme 必须原地丢弃",
            screen.contains("""if (scheme != "http" && scheme != "https") return true"""),
        )
        assertTrue("摄像头/麦克风等权限一律拒绝", screen.contains("request?.deny()"))
        assertTrue("地理位置请求也要拒绝", screen.contains("callback?.invoke(origin, false, false)"))
        assertTrue("禁止脚本自动开窗", screen.contains("javaScriptCanOpenWindowsAutomatically = false"))
        assertTrue("禁止多窗口", screen.contains("setSupportMultipleWindows(false)"))
        assertTrue("不保存表单", screen.contains("setSaveFormData(false)"))
        assertTrue("不保存密码", screen.contains("setSavePassword(false)"))
    }

    /** M10.5：悬浮球在右下角，底部导航在浏览器里也必须常驻。 */
    @Test
    fun theFabMovedToBottomEndAndTheTabsStayVisible() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val app = File("$SRC_ROOT/ui/BgvApp.kt").readText()

        assertTrue("悬浮球要固定在右下角", screen.contains("Alignment.BottomEnd"))
        assertTrue("浏览器里不再把底栏条件隐藏", app.contains("bottomBar = {") && !app.contains("if (!browserActive)"))
    }

    /** M10.5：退出网页后主页面要常驻「显示网页」入口，否则用户进得去出不来。 */
    @Test
    fun homeOffersAWayBackIntoTheWebView() {
        val home = File("$SRC_ROOT/ui/HomeScreen.kt").readText()

        assertTrue("主页面要有「显示网页」按钮", home.contains("onOpenBrowser"))
        assertTrue("文案复用浏览器的「显示网页」", home.contains("R.string.browser_show_page"))
        assertTrue("只有本机模式才给这个入口", home.contains("state.sniffExecutor == SniffExecutor.LOCAL_WEBVIEW"))
    }

    /** 默认值与顺序（M10）：本机在前且是默认，隐藏网页的偏好要能落盘。 */
    @Test
    fun localIsTheDefaultAndSitsBeforeTheEngine() {
        val config = File("$SRC_ROOT/data/EngineConfig.kt").readText()
        val home = File("$SRC_ROOT/ui/HomeScreen.kt").readText()
        val settings = File("$SRC_ROOT/ui/SettingsScreen.kt").readText()

        assertTrue("恢复默认要落在本机嗅探", config.contains("?: SniffExecutor.LOCAL_WEBVIEW"))
        assertTrue("隐身偏好要有自己的持久化键", config.contains("KEY_SHOW_PAGE_BY_DEFAULT"))
        assertTrue(
            "首页本机要排在电脑前面",
            home.indexOf("onSelect(SniffExecutor.LOCAL_WEBVIEW)") < home.indexOf("onSelect(SniffExecutor.ENGINE)"),
        )
        assertTrue(
            "设置页本机要排在电脑前面",
            settings.indexOf("onSelectSniffExecutor(SniffExecutor.LOCAL_WEBVIEW)") <
                settings.indexOf("onSelectSniffExecutor(SniffExecutor.ENGINE)"),
        )
        assertTrue("设置页要有「默认直接显示网页」开关", settings.contains("settings_show_page_label"))
    }

    /** M10.6：销毁顺序与隐藏深嗅的静音，都要钉在源码上。 */
    @Test
    fun theSingleWebViewIsDetachedBeforeDestroyAndMutedByName() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()

        assertTrue("销毁前先从父容器摘下来", screen.contains("(parent as? ViewGroup)?.removeView(this)"))
        assertTrue(
            "摘除必须发生在 destroy 之前",
            screen.indexOf("removeView(this)") < screen.indexOf("destroy()"),
        )
        assertTrue("销毁要留一行可核对的日志", screen.contains("""Log.i(WEBVIEW_LOG_TAG, "destroy webview")"""))
        assertTrue(
            "隐藏深嗅要 Hook HTMLMediaElement.prototype.play",
            screen.contains("HTMLMediaElement") && screen.contains("proto.play = function"),
        )
        assertTrue("document-start 就注入静音脚本", screen.contains("addDocumentStartJavaScript(this, MUTE_SCRIPT"))
        assertTrue("老 WebView 由页面回调兜底", screen.contains("view?.evaluateJavascript(MUTE_SCRIPT, null)"))
        assertTrue("自动播放不依赖手势，否则隐藏页根本播不起来", screen.contains("mediaPlaybackRequiresUserGesture = false"))
    }

    /** 会话状态驱动 WebView 的生死：隐藏深嗅在后台组合，可见时主页让位。 */
    @Test
    fun theWebViewSessionIsSingleAndDrivenByState() {
        val home = File("$SRC_ROOT/ui/HomeScreen.kt").readText()
        val app = File("$SRC_ROOT/ui/BgvApp.kt").readText()
        val vm = File("$SRC_ROOT/ui/HomeViewModel.kt").readText()
        val settings = File("$SRC_ROOT/ui/SettingsScreen.kt").readText()

        assertTrue("隐藏深嗅期间 WebView 仍要组合", home.contains("if (state.browserSessionActive)"))
        assertTrue("主页盖在会话之上，而非替换", home.contains("if (!state.isWebViewVisible)"))
        assertTrue("切走 Tab 立刻结束会话", app.contains("homeViewModel.closeBrowserSession()"))
        assertTrue("会话回收覆盖可见态", vm.contains("browserSessionActive = false"))
        assertTrue("设置页有静音开关", settings.contains("settings_mute_label"))
        assertTrue("隐藏深嗅在跑时仍可切引擎", settings.contains("state.deepSniff == DeepSniffPhase.Off"))
    }

    /** M12：广告资源请求必须短路在嗅探之前，并留一行按域名去重的取证。 */
    @Test
    fun adRequestsAreShortCircuitedAndAuditedOncePerHost() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()
        val controller = File("$SRC_ROOT/data/browser/BrowserSniffController.kt").readText()

        assertTrue("请求过滤要真的接上域名表", screen.contains("AdBlockRules.blockedHostOf(rawUrl)"))
        assertTrue("主文档不能拦，否则手动打开广告域就是白屏", screen.contains("!current.isForMainFrame"))
        assertTrue("命中后返回空响应而不是继续出网", screen.contains("emptyAdResponse()"))
        assertTrue("被拦的资源要留一行", controller.contains("""audit.log("adblock_hit""""))
        assertTrue("同一域名每页只记一次", controller.contains("adLedger.report(host)"))
        assertTrue("页起点打在真正发起 loadUrl 的地方，不能等 onPageStarted", screen.contains("controller.onLoadRequested(url)"))
        assertTrue("初始那一次导航同样要开页", screen.contains("controller.onLoadRequested(first)"))
        assertTrue("文档开始时同样按地址认页", controller.contains("adLedger.openPage(url)"))
        assertFalse("候选重置不许顺手抹掉本页已报的域名", controller.contains("blockedHosts"))
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

    /**
     * MSE 伪装流放行（M7.5 需求 1）。
     *
     * 抽屉只有 220dp 时黄卡下面的候选卡会被裁掉「下载」按钮，所以强行下载入口必须
     * 做在黄卡自己身上——否则「卡片其实可点」在真机上等于不可点。
     */
    @Test
    fun theBlobHintCardCarriesItsOwnDownloadEntry() {
        val screen = File("$SRC_ROOT/ui/BrowserScreen.kt").readText()

        assertTrue("黄卡要拿到那条可下载的底层清单", screen.contains("hlsTarget = sniff.candidates.firstOrNull { it.downloadable }"))
        assertTrue("黄卡上的按钮必须真的触发下载", screen.contains("onDownload(hlsTarget.result)"))
        assertTrue("文案要改口：截获到清单就不再劝退", screen.contains("R.string.browser_blob_hls_caught"))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
