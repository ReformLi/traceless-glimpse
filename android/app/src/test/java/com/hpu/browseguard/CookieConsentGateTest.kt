package com.hpu.browseguard

import com.hpu.browseguard.data.backend.ConsentCookieProvider
import com.hpu.browseguard.data.cookie.cookieHostOf
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeCookieVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 读取门槛（M6-5 D1/D3）：默认谁都没授权，读一次就要留一行，且行里没有值。
 *
 * 这一层是「绝不上传」之外的另一半承诺——没点头时连本机 CookieStore 都不碰。
 * 审计只允许出现域名与 present/absent：值一旦进了 `BgvAudit`，logcat 就成了泄漏面。
 */
class CookieConsentGateTest {

    private val mediaUrl = "https://cdn.example.com/v/clip.mp4"
    private val host = "cdn.example.com"
    private val cookieValue = "sid=good-cookie; token=also-secret"

    private fun gate(
        vault: FakeCookieVault = FakeCookieVault(mapOf(mediaUrl to cookieValue)),
        consent: FakeCookieConsentStore = FakeCookieConsentStore(),
        audit: FakeAuditLogger = FakeAuditLogger(),
    ): Triple<ConsentCookieProvider, FakeCookieVault, FakeAuditLogger> =
        Triple(ConsentCookieProvider(vault, consent, audit), vault, audit)

    @Test
    fun anUngrantedHostIsNeverEvenRead() {
        val (provider, vault, audit) = gate()

        assertNull("默认关闭：没点头就返回 null", provider.cookieFor(mediaUrl))
        assertTrue("门槛之前根本不碰 CookieStore", vault.readUrls.isEmpty())

        val blocked = audit.of("cookie_blocked").single()
        assertEquals(host, blocked.fields["host"])
        assertEquals("拦下一次只留一行", 1, audit.of("cookie_blocked").size)
        assertTrue("拒绝时不该有读取行", audit.of("cookie_read").isEmpty())
    }

    @Test
    fun aGrantedHostGetsOneReadAndPresenceOnlyAudit() {
        val consent = FakeCookieConsentStore()
        consent.grant(host)
        val (provider, vault, audit) = gate(consent = consent)

        assertEquals(cookieValue, provider.cookieFor(mediaUrl))
        assertEquals(listOf(mediaUrl), vault.readUrls)

        val read = audit.of("cookie_read").single()
        assertEquals(host, read.fields["host"])
        assertEquals("present", read.fields["cookie"])
    }

    /** 审计行的唯一职责是「有没有读、读哪一站」，值出现就是 logcat 泄漏。 */
    @Test
    fun noAuditLineCarriesTheCookieValue() {
        val consent = FakeCookieConsentStore()
        val (provider, _, audit) = gate(consent = consent)
        consent.grant(host)
        provider.cookieFor(mediaUrl)
        consent.revoke(host)
        provider.cookieFor(mediaUrl)

        val dump = audit.events.joinToString("|") { entry ->
            entry.event + entry.fields.entries.joinToString("|") { (key, value) -> "$key=$value" }
        }
        listOf("sid=good-cookie", "token=also-secret", cookieValue).forEach { secret ->
            assertFalse("审计里搜得到凭据值：$dump", dump.contains(secret))
        }
        assertEquals("present", audit.of("cookie_read").single().fields["cookie"])
        assertEquals("撤销后那一次只留拦截行", 1, audit.of("cookie_blocked").size)
    }

    @Test
    fun revokingClosesTheGateWithoutDeletingTheValue() {
        val consent = FakeCookieConsentStore()
        val vault = FakeCookieVault(mapOf(mediaUrl to cookieValue))
        val (provider, _, audit) = gate(vault = vault, consent = consent)

        consent.grant(host)
        assertEquals(cookieValue, provider.cookieFor(mediaUrl))
        consent.revoke(host)

        assertNull("撤销只收回读取许可", provider.cookieFor(mediaUrl))
        assertEquals("撤销后不再碰 CookieStore", 1, vault.readUrls.size)
        assertEquals(1, audit.of("cookie_blocked").size)
    }

    /** 一键清除删的是值，授权位不动——界面文案就是这么向用户交代的。 */
    @Test
    fun clearingValuesLeavesTheConsentRowsAlone() {
        val consent = FakeCookieConsentStore()
        val vault = FakeCookieVault(mapOf(mediaUrl to cookieValue))
        consent.grant(host)
        val (provider, _, audit) = gate(vault = vault, consent = consent)

        assertEquals(cookieValue, provider.cookieFor(mediaUrl))
        vault.clearAll()

        assertNull("清完值就没了", provider.cookieFor(mediaUrl))
        assertTrue("但授权位还在：下一次不再问", consent.isGranted(host))
        assertEquals(listOf(host), consent.grantedHosts())
        assertEquals("absent", audit.of("cookie_read").last().fields["cookie"])
        assertEquals(1, vault.clearCount)
    }

    /** D3：每次读都在内存里记一次使用，落盘交给生命周期边界，不在下载循环里。 */
    @Test
    fun theGateTimesUsageWithoutEverFlushingIt() {
        val consent = FakeCookieConsentStore()
        consent.grant(host)
        val (provider, _, _) = gate(consent = consent)
        var last: Long? = null

        repeat(5) {
            assertNotNullValue(provider.cookieFor(mediaUrl))
            last = consent.lastUsedAt(host)
            assertTrue("使用时间只增不减", last!! > 0L)
        }

        assertEquals("每读一次记一次", 5, consent.markUsedCount)
        assertEquals("门槛自己一次盘都不写", 0, consent.flushCount)
    }

    /** 站点没写过 Cookie 也是正常路径：读一行 absent，别把「没有」报成异常。 */
    @Test
    fun anEmptyCookieJarReadsAsAbsent() {
        val consent = FakeCookieConsentStore()
        consent.grant(host)
        val (provider, _, audit) = gate(vault = FakeCookieVault(), consent = consent)

        assertNull(provider.cookieFor(mediaUrl))
        assertEquals("absent", audit.of("cookie_read").single().fields["cookie"])
    }

    @Test
    fun theConsentKeyIsTheHostWithoutPortOrScheme() {
        assertEquals("cdn.example.com", cookieHostOf("https://cdn.example.com:8443/v/clip.mp4"))
        assertEquals("127.0.0.1", cookieHostOf("http://127.0.0.1:8731/media/protected.mp4"))
        assertEquals("非 http(s) 没有域名可言", "", cookieHostOf("magnet:?xt=urn:btih:abc"))
    }

    private fun assertNotNullValue(value: String?) {
        assertTrue("授权后必须真读到值", value != null)
    }
}
