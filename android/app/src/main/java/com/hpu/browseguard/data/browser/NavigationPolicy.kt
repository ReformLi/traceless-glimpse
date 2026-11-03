package com.hpu.browseguard.data.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 内置浏览器的主框架导航闸门。
 *
 * 视频站的广告层最常干的事，就是把一次点击/滑动变成整页跳转到站外落地页；
 * 一跳走，当前页的嗅探结果按设计会被清空。这里的取舍很窄：
 * 只放行**同一注册域**上的**用户手势**导航，站外一律静默丢弃。
 *
 * 同站重定向单独放行：登录/播放页的 302 或脚本跳转没有手势，
 * 但目标没离开这一站，不构成劫持。真正的防盗链价值在站外那一半。
 */
internal object NavigationPolicy {

    fun shouldBlock(
        target: String,
        currentPage: String,
        hasGesture: Boolean,
        isRedirect: Boolean,
    ): Boolean {
        val targetHost = hostOf(target) ?: return true
        val pageHost = hostOf(currentPage) ?: return false
        if (registrableDomain(targetHost) != registrableDomain(pageHost)) return true
        return !hasGesture && !isRedirect
    }

    /** 注册域：主机名末两段；常见的二级公共后缀再多取一段。IP 字面量按整串比。 */
    fun registrableDomain(host: String): String {
        val lower = host.lowercase().trimEnd('.')
        if (lower.isEmpty() || IPV4.matches(lower) || lower.contains(':')) return lower
        val labels = lower.split('.')
        val take = if (labels.size >= 3 && labels.takeLast(2).joinToString(".") in TWO_LEVEL_SUFFIXES) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    private fun hostOf(raw: String): String? =
        raw.toHttpUrlOrNull()?.host?.takeIf { it.isNotBlank() }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** 不引 PSL：只覆盖这批站点常用的后缀；漏掉的按「末两段」处理，最坏是多拦一个站。 */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.hk", "com.tw", "com.au", "co.jp", "ne.jp", "or.jp", "co.kr",
    )
}
