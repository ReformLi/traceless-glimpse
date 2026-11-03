package com.hpu.browseguard

import com.hpu.browseguard.data.browser.NavigationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主框架导航闸门（M7 真机第一轮现场问题 3）。
 *
 * 验收时一次滑动把整页带走了：播放器广告层用一次手势把 `top.location` 指到站外落地页，
 * 回头页面变成 `about:blank`，攒了一页的嗅探结果全丢。规矩是「同注册域 + 有手势」，
 * 站外那一跳无论有没有手势都不许走。
 */
class BrowserNavigationPolicyTest {

    private val page = "https://www.bjjdsgjg.com/vodplay/120850-1-1.html"

    @Test
    fun aGenuineClickInsideTheSiteStillNavigates() {
        assertFalse(
            "同站点剧集链接是正常用法",
            NavigationPolicy.shouldBlock("https://www.bjjdsgjg.com/vodtype/1.html", page, hasGesture = true, isRedirect = false),
        )
        assertFalse(
            "子域名仍属同一注册域",
            NavigationPolicy.shouldBlock("https://player.bjjdsgjg.com/dplayer.html", page, hasGesture = true, isRedirect = false),
        )
    }

    @Test
    fun anOffSiteJumpIsBlockedEvenWithAGesture() {
        listOf(
            "https://zkdemi.7rqlnmc.com/click?u=1",
            "http://ad.example.net/landing",
            "https://bjjdsgjg.com.evil.test/x",
        ).forEach { target ->
            assertTrue("站外落地页必须拦：$target", blocked(target, gesture = true))
            assertTrue("站外无手势更要拦：$target", blocked(target, gesture = false))
        }
    }

    /** 站内的脚本跳转没有手势（`location.href=`），它不是用户想走，也不该走。 */
    @Test
    fun aScriptedSameSiteJumpNeedsAGestureToo() {
        assertTrue(
            blocked("https://www.bjjdsgjg.com/other.html", gesture = false),
        )
        assertFalse(
            "同站重定向（302）没有手势但没离开这一站",
            NavigationPolicy.shouldBlock("https://www.bjjdsgjg.com/other.html", page, hasGesture = false, isRedirect = true),
        )
    }

    @Test
    fun nonHttpTargetsNeverEnterTheMainFrame() {
        listOf("javascript:void(0)", "data:text/html,<script>alert(1)</script>", "intent://scan/#Intent", "").forEach { target ->
            assertTrue("解析不出域名的目标一律丢弃：$target", blocked(target, gesture = true))
        }
    }

    /** 空白起始页没有「本站」可比，此时用户自己按地址栏算手势；不拦。 */
    @Test
    fun aBlankStartPageHasNothingToBeHijackedFrom() {
        assertFalse(
            NavigationPolicy.shouldBlock("https://www.bjjdsgjg.com/home", "about:blank", hasGesture = true, isRedirect = false),
        )
    }

    @Test
    fun ipLiteralsAndTwoLevelSuffixesCompareTheRightWay() {
        assertEquals("127.0.0.1", NavigationPolicy.registrableDomain("127.0.0.1"))
        assertTrue(
            "本机引擎与站点不是一个站",
            blocked("http://192.168.31.57:8731/media.mp4", gesture = true, currentPage = "http://127.0.0.1:8731/media.mp4"),
        )
        assertFalse("com.cn 要按末三段算", blocked("https://b.player.com.cn/x", gesture = true, currentPage = "https://a.player.com.cn/y"))
        assertTrue("com.cn 换注册域就是站外", blocked("https://b.victim.com.cn/x", gesture = true, currentPage = "https://a.player.com.cn/y"))
        assertFalse("大小写与尾点不影响判定", blocked("https://WWW.BJJDsgjG.com./x", gesture = true, currentPage = page))
    }

    private fun blocked(
        target: String,
        gesture: Boolean,
        currentPage: String = page,
    ): Boolean = NavigationPolicy.shouldBlock(target, currentPage, hasGesture = gesture, isRedirect = false)
}
