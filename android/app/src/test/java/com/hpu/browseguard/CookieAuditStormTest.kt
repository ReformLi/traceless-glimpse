package com.hpu.browseguard

import com.hpu.browseguard.data.backend.ConsentCookieProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.cookie.cookieHostOf
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.fakes.FakeCookieConsentStore
import com.hpu.browseguard.fakes.FakeCookieVault
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Cookie 审计的暴击抑制（M7 真机第一轮现场问题 4）。
 *
 * 一条 703 段的 HLS 就是 703 次 `cookieFor`，逐次打行把 256KB 的 logcat 环形缓冲整个冲掉，
 * 同一轮里真正要看的 `browser_probe`、失败原因全被挤没了。这里改的是「留几行」不是「记多少」：
 * 重复行按域名压掉，总数交给下载结束时的那一条 `cookie_audit_summary`。
 */
class CookieAuditStormTest {

    private val segmentUrl = "https://cdn.example.com/v/seg0001.ts"
    private val host = "cdn.example.com"
    private val cookieValue = "sid=good-cookie; token=also-secret"
    private val payload = "0123456789".repeat(4)

    private fun provider(
        vault: FakeCookieVault,
        consent: FakeCookieConsentStore,
        audit: FakeAuditLogger,
    ): ConsentCookieProvider = ConsentCookieProvider(vault, consent, audit)

    @Test
    fun sevenHundredReadsStillLeaveOneBlockedLine() {
        val vault = FakeCookieVault(mapOf(segmentUrl to cookieValue))
        val audit = FakeAuditLogger()
        val gated = provider(vault, FakeCookieConsentStore(), audit)

        repeat(703) { assertEquals(null, gated.cookieFor(segmentUrl)) }

        assertEquals("拦下的事实只留一行", 1, audit.of("cookie_blocked").size)
        assertEquals(host, audit.of("cookie_blocked").single().fields["host"])
        assertTrue("门槛之前照样不碰 CookieStore", vault.readUrls.isEmpty())

        gated.finish()
        val summary = audit.of("cookie_audit_summary").single()
        assertEquals("被抑制的次数不能凭空蒸发", "703", summary.fields["blocked"])
        assertEquals("0", summary.fields["injected"])
    }

    @Test
    fun grantedReadsCollapseToOnePresenceLineAndCountInTheSummary() {
        val consent = FakeCookieConsentStore().also { it.grant(host) }
        val vault = FakeCookieVault(mapOf(segmentUrl to cookieValue))
        val audit = FakeAuditLogger()
        val gated = provider(vault, consent, audit)

        repeat(703) { assertEquals(cookieValue, gated.cookieFor(segmentUrl)) }

        assertEquals("同一种存在性只报一次", 1, audit.of("cookie_read").size)
        assertEquals("present", audit.of("cookie_read").single().fields["cookie"])
        assertEquals("每一次读仍然各记一次使用", 703, consent.markUsedCount)

        gated.finish()
        val summary = audit.of("cookie_audit_summary").single()
        assertEquals("0", summary.fields["blocked"])
        assertEquals("703", summary.fields["injected"])
    }

    /** 「有没有值」翻了面就还得留一行——一键清除之后界面要能看出变化。 */
    @Test
    fun aPresenceFlipIsStillAudible() {
        val consent = FakeCookieConsentStore().also { it.grant(host) }
        val vault = FakeCookieVault(mapOf(segmentUrl to cookieValue))
        val audit = FakeAuditLogger()
        val gated = provider(vault, consent, audit)

        repeat(300) { gated.cookieFor(segmentUrl) }
        vault.clearAll()
        repeat(300) { gated.cookieFor(segmentUrl) }

        assertEquals(
            listOf("present", "absent"),
            audit.of("cookie_read").map { it.fields["cookie"] },
        )
        gated.finish()
        assertEquals("读到值的那 300 次才算注入", "300", audit.of("cookie_audit_summary").single().fields["injected"])
    }

    @Test
    fun finishingStartsTheNextRoundAndStaysQuietWithoutTraffic() {
        val audit = FakeAuditLogger()
        val gated = provider(FakeCookieVault(), FakeCookieConsentStore(), audit)

        gated.finish()
        assertTrue("没读就没汇总，别造空行", audit.events.isEmpty())

        repeat(5) { gated.cookieFor(segmentUrl) }
        gated.finish()
        assertEquals("5", audit.of("cookie_audit_summary").single().fields["blocked"])

        repeat(5) { gated.cookieFor(segmentUrl) }
        gated.finish()
        assertEquals("下一轮重新留行：抑制是每轮一次，不是每生命周期一次", 2, audit.of("cookie_blocked").size)
        assertEquals(2, audit.of("cookie_audit_summary").size)
    }

    /**
     * 浏览器会话收尾换自己的事件名（M7 验收裁决 1）。
     *
     * 排查「这个网站的 Cookie 为什么没注入」时，`browser_close_summary blocked>0`
     * 说明读了但被门槛拦下；这一行整个缺失才是根本没读（这一站没探出清单）。
     */
    @Test
    fun aBrowserSessionReportsItsOwnBlockedTotal() {
        val audit = FakeAuditLogger()
        val gated = provider(FakeCookieVault(mapOf(segmentUrl to cookieValue)), FakeCookieConsentStore(), audit)

        repeat(18) { gated.cookieFor(segmentUrl) }
        gated.finish("browser_close_summary")

        assertEquals("下载侧的名字不能被浏览器借用", 0, audit.of("cookie_audit_summary").size)
        val summary = audit.of("browser_close_summary").single()
        assertEquals("18", summary.fields["blocked"])
        assertEquals("0", summary.fields["injected"])
        assertEquals(1, audit.of("cookie_blocked").size)
    }

    @Test
    fun aBrowserSessionThatNeverTouchedCookiesLeavesNoLine() {
        val audit = FakeAuditLogger()
        val gated = provider(FakeCookieVault(), FakeCookieConsentStore(), audit)

        gated.finish("browser_close_summary")

        assertTrue("缺行本身就是「根本没读」的证据，不能造空行污染它", audit.events.isEmpty())
    }

    private val engine = MockWebServer()
    private lateinit var out: File

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun startSite() {
        engine.start()
        out = folder.newFolder("download")
    }

    @After
    fun stopSite() {
        engine.close()
    }

    /** 汇总必须真的挂在下载结束时：不然上面几条只证明了类自己会算数。 */
    @Test
    fun theSummaryIsWrittenWhenTheDownloadEnds() {
        val mediaUrl = engine.url("/media/protected.mp4").toString()
        val consent = FakeCookieConsentStore()
        consent.grant(cookieHostOf(mediaUrl))
        val audit = FakeAuditLogger()
        val gated = ConsentCookieProvider(FakeCookieVault(mapOf(mediaUrl to cookieValue)), consent, audit)
        engine.enqueue(MockResponse.Builder().code(200).body(payload).build())

        val events = runBlocking {
            LocalDirectDownloader(audit = audit, cookies = gated)
                .start(StartRequest.ViaLocal(link(mediaUrl), out.absolutePath, listOf(out.absolutePath)))
                .toList()
        }

        assertEquals(TaskStatus.DONE, (events.last() as DownloadEvent.Terminal).task.status)
        assertEquals(payload, File(out, "protected.mp4").readText())

        val summary = audit.of("cookie_audit_summary").single()
        assertEquals("这一趟没被拦", "0", summary.fields["blocked"])
        assertEquals("读到并发出去的那一次要算进总数", "1", summary.fields["injected"])
        assertEquals(1, audit.of("cookie_read").size)
    }

    /** 没授权也要有收尾行：否则「一次都没注进去」和「压根没跑完」在日志里长得一样。 */
    @Test
    fun anUngrantedDownloadStillSummarises() {
        val mediaUrl = engine.url("/media/open.mp4").toString()
        val audit = FakeAuditLogger()
        val gated = ConsentCookieProvider(FakeCookieVault(mapOf(mediaUrl to cookieValue)), FakeCookieConsentStore(), audit)
        engine.enqueue(MockResponse.Builder().code(200).body(payload).build())

        runBlocking {
            LocalDirectDownloader(audit = audit, cookies = gated)
                .start(StartRequest.ViaLocal(link(mediaUrl), out.absolutePath, listOf(out.absolutePath)))
                .toList()
        }

        val summary = audit.of("cookie_audit_summary").single()
        assertEquals("1", summary.fields["blocked"])
        assertEquals("0", summary.fields["injected"])
        assertEquals("被拦只留一行", 1, audit.of("cookie_blocked").size)
        assertNull("没点头就一个字都不发", engine.takeRequest().headers["Cookie"])
    }

    private fun link(url: String) = SniffResult(
        taskId = "local",
        mediaUrl = url,
        type = MediaStreamType.MP4,
        container = "mp4",
        estimatedQuality = EstimatedQuality(height = 720, bitrateKbps = 2500, label = "720p"),
        drmDetected = false,
        discoveredAt = "2026-11-03T19:01:00Z",
    )
}
