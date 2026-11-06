package com.hpu.browseguard.data.browser

/**
 * 广告拦截的按页报账把手。
 *
 * 一个页面常会向同一个广告域打十几个请求，逐条写审计会把 logcat 里其他取证行冲掉，
 * 所以同一域名在一页之内只留一行。难点在「一页从哪儿算起」：
 * `loadUrl` 之后、`onPageStarted` 之前，WebView 已经把这一页的资源请求打出来了
 * （preload scanner 抢跑），**以 `onPageStarted` 为页起点会把这批漏在上一页的集合里**，
 * 于是同一个域在一页里出两行。真机 M12 复现过：3 个同域请求 → 2 行。
 *
 * M12.5 又复现了另一半：重新加载同一地址时，Chromium 会重新验证主文档，子资源请求
 * 有的先落地、有的排在 `onPageStarted` 之后，**清账如果看「第几次导航」，这批跨界的
 * 请求就会被重报一遍**（真机：reload 广告页 → 同域两行）。
 *
 * 所以一页用**地址**认，不用导航次数认：地址变了才重开账，同址重载保持闭账。
 * 代价是「重新加载同一页不再补一行」——审计要证明的是「这页拦了这个域」，
 * 不是「拦了几次」，补行没有取证价值。
 *
 * `shouldInterceptRequest` 跑在 WebView 的网络线程上，而 `loadUrl` 来自主线程，
 * 所有状态都在 [lock] 里读写。
 */
internal class AdBlockLedger {

    private val lock = Any()
    private var page: String? = null
    private val reportedHosts = mutableSetOf<String>()

    /**
     * 认下当前这一页。我方发起的 `loadUrl` 和回调里的 `onPageStarted` 都打这里，
     * 谁先到算谁开张；地址没变就是同一页，不动已报的账。
     */
    fun openPage(url: String) {
        synchronized(lock) {
            if (url != page) {
                page = url
                reportedHosts.clear()
            }
        }
    }

    /** 这一页里这个域还没报过账就记下并返回 `true`；拦不拦由调用方决定，这里只管报账。 */
    fun report(host: String): Boolean = synchronized(lock) { reportedHosts.add(host) }
}
