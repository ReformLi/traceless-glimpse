package com.hpu.browseguard

import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.EnginePayloadGuard
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.PostActionType
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上行载荷守卫（§25.6 裁决二）：发给 PC 引擎的 JSON 里不许出现 Cookie 字段。
 *
 * 协议本身没有 headers 字段，但守卫要能在「将来有人加字段」时先炸掉，
 * 而不是等审计发现网页凭据被交给了另一台机器。`cookiePolicy` 是协议字段，不算 Cookie。
 */
class EnginePayloadGuardTest {

    @Test
    fun realProtocolPayloadsPassIncludingCookiePolicy() {
        val create = TaskCreateRequest(
            sourceUrl = "https://example.com/watch",
            mode = TaskMode.SNIFF,
            cookiePolicy = CookiePolicy.LAZY,
            outputDir = "/engine/out",
        )
        val download = DownloadRequest(
            formatSelector = FormatSelector(strategy = SelectionStrategy.BEST),
            namingTemplate = "{title}.{ext}",
            postActions = listOf(PostAction(PostActionType.MERGE)),
        )

        val encodedCreate = EnginePayloadGuard.check(BgvJson.encodeToString(TaskCreateRequest.serializer(), create))
        val encodedDownload = EnginePayloadGuard.check(BgvJson.encodeToString(DownloadRequest.serializer(), download))

        assertTrue("协议字段 cookiePolicy 必须允许", encodedCreate.contains("\"cookiePolicy\""))
        assertTrue(encodedDownload.contains("\"formatSelector\""))
    }

    /** 守卫必须真的能咬住：大小写、嵌套头表、任意值都要命中。 */
    @Test
    fun cookieKeysAreRejectedWhereverTheyAppear() {
        listOf(
            """{"cookie":"sid=secret"}""",
            """{"Cookie":"sid=secret"}""",
            """{"headersHint":{"cookie":"sid=secret"}}""",
            """{"headersHint":{"Cookie":"sid=secret"}}""",
        ).forEach { payload ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                EnginePayloadGuard.check(payload)
            }
            assertTrue("报错要说清是 Cookie：${error.message}", error.message!!.contains("Cookie"))
        }
    }

    @Test
    fun cookiePolicyIsNotMistakenForACookieHeader() {
        assertEquals(
            """{"cookiePolicy":"lazy"}""",
            EnginePayloadGuard.check("""{"cookiePolicy":"lazy"}"""),
        )
    }
}
