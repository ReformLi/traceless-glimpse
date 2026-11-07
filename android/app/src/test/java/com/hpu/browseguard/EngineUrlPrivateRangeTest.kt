package com.hpu.browseguard

import com.hpu.browseguard.data.EngineUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M19：引擎 Host 提取与「公网明文」判定。 */
class EngineUrlPrivateRangeTest {

    // -- httpHostOf：安检门白名单的取值来源 --

    @Test
    fun extractsHostFromBaseUrl() {
        assertEquals("127.0.0.1", EngineUrl.httpHostOf("http://127.0.0.1:8730"))
        assertEquals("192.168.1.50", EngineUrl.httpHostOf("http://192.168.1.50:8730/"))
        assertEquals("engine.example.com", EngineUrl.httpHostOf("https://engine.example.com:8443"))
    }

    @Test
    fun hostOfRejectsInvalidBaseUrl() {
        assertNull(EngineUrl.httpHostOf("not a url"))
        assertNull(EngineUrl.httpHostOf("   "))
        assertNull(EngineUrl.httpHostOf(""))
    }

    // -- isRiskyCleartextHost：设置页公网警告的判定 --

    @Test
    fun loopbackAndPrivateRangesAreNotRisky() {
        assertFalse(EngineUrl.isRiskyCleartextHost("127.0.0.1"))
        assertFalse(EngineUrl.isRiskyCleartextHost("localhost"))
        assertFalse(EngineUrl.isRiskyCleartextHost("::1"))
        assertFalse(EngineUrl.isRiskyCleartextHost("10.0.2.2")) // 模拟器宿主
        assertFalse(EngineUrl.isRiskyCleartextHost("192.168.31.242"))
        assertFalse(EngineUrl.isRiskyCleartextHost("172.16.0.1"))
        assertFalse(EngineUrl.isRiskyCleartextHost("172.31.255.255"))
        assertFalse(EngineUrl.isRiskyCleartextHost("169.254.1.1")) // 链路本地
    }

    @Test
    fun publicIpsAndDomainsAreRisky() {
        assertTrue(EngineUrl.isRiskyCleartextHost("8.8.8.8"))
        assertTrue(EngineUrl.isRiskyCleartextHost("216.198.79.1"))
        assertTrue(EngineUrl.isRiskyCleartextHost("100.64.1.1")) // CGN 段不算私网
        assertTrue(EngineUrl.isRiskyCleartextHost("172.32.0.1")) // 172.16–31 之外
        assertTrue(EngineUrl.isRiskyCleartextHost("192.169.1.1")) // 不是 192.168
        assertTrue(EngineUrl.isRiskyCleartextHost("my-engine.example.com")) // 域名按公网对待
        assertTrue(EngineUrl.isRiskyCleartextHost("fd00::1")) // 非 ::1 的 IPv6 从严
    }

    @Test
    fun boundaryRejectsMalformedOctets() {
        // 四段但超范围 → 不是合法私网 IPv4 → 按公网对待（宁可多问）
        assertTrue(EngineUrl.isRiskyCleartextHost("300.1.1.1"))
        assertTrue(EngineUrl.isRiskyCleartextHost("192.168.1")) // 三段
        assertFalse(EngineUrl.isRiskyCleartextHost("")) // 空值不弹窗，交给 URL 校验
    }
}
