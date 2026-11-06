package com.hpu.browseguard

import com.hpu.browseguard.data.browser.AdBlockLedger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广告拦截报账的「一页」边界（M12.5 裁决）。
 *
 * M12 真机复现过两半：同一广告域在一页里出两行，因为页起点原本定在 `onPageStarted`，
 * 而 WebView 的 preload 请求比它早；重新加载同一地址时，子资源请求又横跨
 * `onPageStarted` 前后落到网络线程，清账若看「第几次导航」就再出两行。
 * 裁决要的是「每域每页恰好一行」——这里按**地址**认页，把两种顺序都钉住。
 */
class AdBlockLedgerTest {

    @Test
    fun aHostIsReportedOncePerPageEvenWhenThePageAsksManyTimes() {
        val ledger = AdBlockLedger()
        ledger.openPage(PAGE)
        assertTrue("首请要报", ledger.report("doubleclick.net"))
        assertFalse("同域第二次不重复报", ledger.report("doubleclick.net"))
        assertFalse("同域第三次不重复报", ledger.report("doubleclick.net"))
        assertTrue("不同域各留自己的行", ledger.report("cnzz.com"))
    }

    @Test
    fun thePreloadRequestsThatBeatOnPageStartedStillCountAsTheSamePage() {
        val ledger = AdBlockLedger()
        ledger.openPage(PAGE)
        assertTrue("preload 抢在文档之前报了一行", ledger.report("doubleclick.net"))
        ledger.openPage(PAGE)
        assertFalse("文档开始不能把这一页的账重开，否则同域两行", ledger.report("doubleclick.net"))
    }

    @Test
    fun aReloadThatStraddlesPageStartedAddsNoSecondLine() {
        val ledger = AdBlockLedger()
        ledger.openPage(PAGE)
        assertTrue("首访留一行", ledger.report("ad.doubleclick.net"))
        assertFalse(ledger.report("ad.doubleclick.net"))

        // 真机复现的顺序：主文档重新验证时，一个子资源先落进拦截器，onPageStarted 随后才到。
        assertFalse("重载时先落地的同域请求不重报", ledger.report("ad.doubleclick.net"))
        ledger.openPage(PAGE)
        assertFalse("跨界之后才到的同域请求也不重报", ledger.report("ad.doubleclick.net"))
    }

    @Test
    fun aDifferentUrlLoadedFromTheAddressBarStartsAFreshLedger() {
        val ledger = AdBlockLedger()
        ledger.openPage(PAGE)
        assertTrue(ledger.report("doubleclick.net"))
        ledger.openPage(PAGE)

        val next = "http://127.0.0.1:8000/second.html"
        ledger.openPage(next)
        ledger.openPage(next)
        assertTrue("换页后同域要重新有一行，不然新页面的拦截成了漏证", ledger.report("doubleclick.net"))
    }

    @Test
    fun navigationsWeDidNotInitiateAlsoOpenANewPage() {
        val ledger = AdBlockLedger()
        ledger.openPage(PAGE)
        assertTrue(ledger.report("cnzz.com"))

        // 页面自己跳走：调用方不会打 onLoadRequested，只能靠 onPageStarted 认下这一页。
        ledger.openPage("http://127.0.0.1:8000/linked.html")
        assertTrue("点链接进去的页面另开一页", ledger.report("cnzz.com"))
    }

    @Test
    fun aboutBlankDoesNotStealTheRealPagesLedger() {
        val ledger = AdBlockLedger()
        ledger.openPage("about:blank")
        assertTrue("空白页报过账", ledger.report("doubleclick.net"))

        ledger.openPage(PAGE)
        assertTrue("真实页面重新开张后同域仍要有一行", ledger.report("doubleclick.net"))
        assertFalse("这一页内保持去重", ledger.report("doubleclick.net"))
    }

    private companion object {
        const val PAGE = "http://127.0.0.1:8000/m12_ads.html"
    }
}
