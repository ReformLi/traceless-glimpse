package com.hpu.browseguard.shared

import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.ErrorCategory
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorPayload
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.PostAction
import com.hpu.browseguard.shared.model.PostActionType
import com.hpu.browseguard.shared.model.PreferCodec
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskProgress
import com.hpu.browseguard.shared.model.TaskStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BgvJson 行为测试：唯一序列化入口，ignoreUnknownKeys=false（拒绝未知字段）。
 * 注意区分两类场景：
 * - 「未知字段」（如 cookieFile、foo）→ 必须抛 SerializationException
 * - 「缺失可选字段」→ 使用默认值，不报错
 */
class BgvJsonTest {

    /** 最小合法 Task：仅含 8 个必填字段（id/sourceUrl/mode/cookiePolicy/outputDir/status/createdAt/updatedAt）。 */
    private val minimalTaskJson = """
        {"id":"0192f3a4-7c1e-7b2a-9d3f-1a2b3c4d5e6f",
         "sourceUrl":"https://example.com/watch?v=abc",
         "mode":"download",
         "cookiePolicy":"lazy",
         "outputDir":"/home/user/Videos/BGV",
         "status":"queued",
         "createdAt":"2026-10-30T08:00:00Z",
         "updatedAt":"2026-10-30T08:00:00Z"}
    """.trimIndent()

    @Test
    fun `未知字段 cookieFile 必须被拒绝`() {
        // 协议层安全约束：Task 不存在 cookieFile 字段（PROTOCOL §1）
        val json = minimalTaskJson.dropLast(1) + ",\"cookieFile\":\"C:/cookies.txt\"}"
        kotlin.test.assertFailsWith<SerializationException> {
            BgvJson.decodeFromString<Task>(json)
        }
    }

    @Test
    fun `任意未知字段同样被拒绝（行为验证 ignoreUnknownKeys=false）`() {
        // 不读配置属性，用行为证明：任意未知字段 foo 也抛异常
        val json = minimalTaskJson.dropLast(1) + ",\"foo\":123}"
        kotlin.test.assertFailsWith<SerializationException> {
            BgvJson.decodeFromString<Task>(json)
        }
    }

    @Test
    fun `缺失全部可选字段的 Task JSON 解析成功且默认值正确`() {
        val task = BgvJson.decodeFromString<Task>(minimalTaskJson)
        // 必填字段解析正确
        assertEquals("0192f3a4-7c1e-7b2a-9d3f-1a2b3c4d5e6f", task.id)
        assertEquals(TaskMode.DOWNLOAD, task.mode)
        assertEquals(CookiePolicy.LAZY, task.cookiePolicy)
        assertEquals(TaskStatus.QUEUED, task.status)
        assertEquals("/home/user/Videos/BGV", task.outputDir)
        // 可选字段缺失 → 默认值，不报错
        assertNull(task.formatSelector)
        assertNull(task.namingTemplate)
        assertEquals(emptyList(), task.postActions)
        assertNull(task.progress)
        assertNull(task.error)
    }

    @Test
    fun `全字段 Task round-trip 编解码值一致`() {
        val task = Task(
            id = "0192f3a4-7c1e-7b2a-9d3f-1a2b3c4d5e6f",
            sourceUrl = "https://example.com/watch?v=abc",
            mode = TaskMode.DOWNLOAD,
            formatSelector = FormatSelector(
                strategy = SelectionStrategy.MAX_HEIGHT,
                maxHeight = 1080,
                preferCodec = PreferCodec.H264,
                maxSizeBytes = 2147483648L,
            ),
            namingTemplate = "{platform}-{date}-{title}-{height}p",
            postActions = listOf(
                PostAction(type = PostActionType.MERGE),
                PostAction(type = PostActionType.EXTRACT_SUBTITLES, langs = listOf("zh", "en")),
            ),
            cookiePolicy = CookiePolicy.LAZY,
            outputDir = "/home/user/Videos/BGV",
            status = TaskStatus.DOWNLOADING,
            progress = TaskProgress(
                phase = "downloading",
                bytesDownloaded = 104857600L,
                bytesTotal = 524288000L,
                speedBps = 5242880L,
                etaSeconds = 80,
                percent = 20.0,
            ),
            error = ErrorPayload(
                code = ErrorCode.SSRF_BLOCKED,
                category = ErrorCategory.SECURITY,
                message = "目标地址指向受限网络，已拒绝",
                retryable = false,
            ),
            createdAt = "2026-10-30T08:00:00Z",
            updatedAt = "2026-10-30T08:01:20Z",
        )
        val encoded = BgvJson.encodeToString(task)
        assertEquals(task, BgvJson.decodeFromString<Task>(encoded))
    }
}
