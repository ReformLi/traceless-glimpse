package com.hpu.browseguard

import com.hpu.browseguard.data.browser.AdBlockRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 广告域名匹配（M12）。
 *
 * 重点是「后缀而不是包含」：广告域名单很短，一次 `contains` 就会把
 * `notdoubleclick.net` 这类正常站点误杀，而误杀在页面上表现成资源神秘缺失。
 */
class AdBlockRulesTest {

    @Test
    fun exactDomainsAndTheirSubdomainsAreBlocked() {
        assertEquals("doubleclick.net", AdBlockRules.blockedHostOf("https://doubleclick.net/x.js"))
        assertEquals("ad.doubleclick.net", AdBlockRules.blockedHostOf("http://ad.doubleclick.net/pixel?id=1"))
        assertEquals("pos.baidu.com", AdBlockRules.blockedHostOf("https://pos.baidu.com/ac"))
        assertEquals(
            "大小写与结尾的点不影响判定",
            "cnzz.com",
            AdBlockRules.blockedHostOf("https://CNZZ.com./hm.js"),
        )
    }

    @Test
    fun lookalikeHostsAreNotBlocked() {
        assertNull(AdBlockRules.blockedHostOf("https://notdoubleclick.net/x.js"))
        assertNull(AdBlockRules.blockedHostOf("https://doubleclick.net.evil.test/x.js"))
        assertNull(AdBlockRules.blockedHostOf("https://umeng.com.cn/x.js"))
        assertNull(AdBlockRules.blockedHostOf("https://mycnzz.com/x.js"))
    }

    @Test
    fun ordinaryVideoSitesAndLocalAddressesPass() {
        assertNull(AdBlockRules.blockedHostOf("https://www.bjjdsgjg.com/vodplay/1.html"))
        assertNull(AdBlockRules.blockedHostOf("http://192.168.31.57:8731/media.mp4"))
        assertNull(AdBlockRules.blockedHostOf("http://127.0.0.1:8731/media.mp4"))
    }

    @Test
    fun malformedOrNonHttpTargetsNeverMatch() {
        assertNull(AdBlockRules.blockedHostOf(""))
        assertNull(AdBlockRules.blockedHostOf("about:blank"))
        assertNull(AdBlockRules.blockedHostOf("data:text/html,<script></script>"))
        assertNull(AdBlockRules.blockedHostOf("blob:https://example.com/uuid"))
    }
}
