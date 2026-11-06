package com.hpu.browseguard.data.browser

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 轻量广告拦截：只按域名硬拦，不做路径/正则规则。
 *
 * 视频站的广告层多半是第三方脚本与 iframe，域名集中且稳定；按域后缀（相等或 `.` 子域）
 * 命中就够用。匹配必须用后缀而不是 `contains`，否则 `notdoubleclick.net` 会被误杀。
 *
 * 主文档不拦：用户在地址栏主动打开某个域时要能看到结果，白屏比广告更难解释。
 */
internal object AdBlockRules {

    /** 命中返回规范化后的主机名（审计用），不命中返回 null。 */
    fun blockedHostOf(url: String): String? {
        val host = url.toHttpUrlOrNull()?.host
            ?.lowercase(Locale.ROOT)
            ?.trimEnd('.')
            ?: return null
        if (host.isEmpty()) return null
        return host.takeIf { candidate ->
            BLOCKED_DOMAINS.any { candidate == it || candidate.endsWith(".$it") }
        }
    }

    /**
     * 只收「服务广告/统计」这一层的域名。刻意不碰 `facebook.net`、`google-analytics.com`
     * 这类登录或站点自身埋点：拦错会让页面功能静默失效，比漏一条广告更难排查。
     */
    private val BLOCKED_DOMAINS = setOf(
        // Google 广告链路
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "googletagmanager.com",
        "2mdn.net",
        // 国内常见广告/统计
        "umeng.com",
        "umengcloud.com",
        "cnzz.com",
        "cnzz.net",
        "51.la",
        "adsame.com",
        "allyes.com",
        "ipinyou.com",
        "mediav.com",
        "miaozhen.com",
        "admaster.com.cn",
        "tanx.com",
        "alimama.com",
        "mmstat.com",
        "pos.baidu.com",
        "cpro.baidu.com",
        "union.baidu.com",
        "e.qq.com",
        "gdt.qq.com",
        // 海外与移动广告交易平台
        "adnxs.com",
        "appnexus.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "casalemedia.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "quantserve.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "inmobi.com",
        "adcolony.com",
    )
}
