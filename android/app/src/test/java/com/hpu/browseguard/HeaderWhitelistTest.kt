package com.hpu.browseguard

import com.hpu.browseguard.data.backend.SniffHeaderPolicy
import com.hpu.browseguard.fakes.FakeAuditLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 头白名单（§25.6 裁决一）：本地下载只上行 `referer` 与 `user-agent`。
 *
 * 之前是黑名单，`accept` / `accept-encoding` 会跟着浏览器一起上行；后者尤其危险，
 * 服务端一旦回压缩流，字节写进 `.mp4` 就是坏文件。现在改成白名单，
 * 所有被丢掉的头都留审计行，真机排查 403/406 时能看见客户端扔了什么。
 */
class HeaderWhitelistTest {

    @Test
    fun onlyRefererAndUserAgentSurvive() {
        val sanitized = SniffHeaderPolicy.sanitize(
            mapOf(
                "Referer" to "https://example.com/watch",
                "User-Agent" to "BGV-UA",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, br",
                "Origin" to "https://example.com",
            ),
        )

        assertEquals(mapOf("referer" to "https://example.com/watch", "user-agent" to "BGV-UA"), sanitized)
    }

    @Test
    fun droppedHeadersLeaveAnAuditRowThatSaysWhy() {
        val audit = FakeAuditLogger()
        SniffHeaderPolicy.auditStripped(
            audit,
            mapOf(
                "Referer" to "https://example.com/watch",
                "Range" to "bytes=0-0",
                "If-Range" to "\"etag\"",
                "Cookie" to "sid=secret",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip",
            ),
        )

        assertEquals(
            listOf("range", "if-range"),
            audit.of("range_stripped").map { it.fields["header"] },
        )
        assertEquals("cookie", audit.of("sensitive_header_stripped").single().fields["header"])
        assertEquals(
            setOf("accept", "accept-encoding"),
            audit.of("header_dropped").map { it.fields["header"] }.toSet(),
        )
        assertTrue("Referer 是白名单，不该被记成剥掉", audit.of("header_dropped").none { it.fields["header"] == "referer" })
    }

    /** 白名单是硬编码的：Cookie 无论从哪个入口来都不能被放行。 */
    @Test
    fun theWhitelistItselfCannotGrowIntoSensitiveHeadersByAccident() {
        assertEquals(setOf("referer", "user-agent"), SniffHeaderPolicy.ALLOWED_HEADERS)
        assertTrue(
            "凭据头不得出现在白名单：${SniffHeaderPolicy.ALLOWED_HEADERS}",
            SniffHeaderPolicy.ALLOWED_HEADERS.intersect(SniffHeaderPolicy.SENSITIVE_HEADERS).isEmpty(),
        )
        assertNull(SniffHeaderPolicy.sanitize(mapOf("Cookie" to "sid=secret"))["cookie"])
    }
}
