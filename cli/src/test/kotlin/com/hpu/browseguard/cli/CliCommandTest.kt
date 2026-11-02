package com.hpu.browseguard.cli

import com.hpu.browseguard.shared.model.DownloadRequest
import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.ErrorEnvelope
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskCreateRequest
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliCommandTest {

    private data class CliResult(val code: Int, val stdout: String, val stderr: String)

    private val fast = CliRuntime(
        pollIntervalMs = 1,
        sniffTimeoutMs = 3_000,
        downloadTimeoutMs = 3_000,
    )

    private fun invoke(vararg args: String, tty: Boolean = false): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = CliEnv(
            out = PrintStream(out, true, StandardCharsets.UTF_8),
            err = PrintStream(err, true, StandardCharsets.UTF_8),
            tty = tty,
        )
        val code = runCli(args.toList(), env, fast)
        return CliResult(
            code = code,
            stdout = out.toByteArray().toString(Charsets.UTF_8),
            stderr = err.toByteArray().toString(Charsets.UTF_8),
        )
    }

    private fun json(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject

    private fun sniff(engine: MockEngine, url: String = "https://example.com/watch"): String {
        val result = invoke(
            "--engine", engine.baseUrl,
            "sniff", url,
            "--json",
        )
        assertEquals(0, result.code, result.stderr)
        return json(result.stdout)["taskId"]!!.jsonPrimitive.content
    }

    private fun MockEngine.createRequests(): List<RecordedRequest> =
        requests.filter { it.method == "POST" && it.path == "/v1/tasks" }

    @Test
    fun rootHelpListsAllCommandsAndExitCodes() {
        val result = invoke("--help")

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("sniff"))
        assertTrue(result.stdout.contains("download-url"))
        assertTrue(result.stdout.contains("退出码"))
        assertTrue(result.stdout.contains("8 用户主动取消"))
        assertEquals("", result.stderr)
    }

    @Test
    fun sniffHelpExplainsSelectionSemantics() {
        val result = invoke("sniff", "--help")

        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("页面链接"))
        assertTrue(result.stdout.contains("awaiting_selection"))
        assertEquals("", result.stderr)
    }

    @Test
    fun sniffCreatesDownloadModeTaskWithoutSelectorAndReturnsCandidates() {
        MockEngine().use { engine ->
            val result = invoke(
                "--engine", engine.baseUrl,
                "sniff", "https://example.com/watch",
                "--json",
            )

            assertEquals(0, result.code, result.stderr)
            val output = json(result.stdout)
            assertEquals("t-1", output["taskId"]!!.jsonPrimitive.content)
            assertEquals(1, output["sniffResults"]!!.jsonObject["items"]!!.jsonArray.size)
            assertEquals(2, output["formats"]!!.jsonObject["items"]!!.jsonArray.size)

            val create = engine.createRequests().single()
            val request = CliJson.decodeFromString(TaskCreateRequest.serializer(), create.body)
            assertEquals(TaskMode.DOWNLOAD, request.mode)
            assertEquals(null, request.formatSelector)
            assertFalse(create.body.contains("formatSelector"))
            assertTrue(engine.requests.any { it.path == "/v1/tasks/t-1/formats" })
            assertTrue(engine.requests.any { it.path == "/v1/tasks/t-1/sniff-results" })
        }
    }

    @Test
    fun invalidUrlExitsTwoWithoutTouchingEngine() {
        MockEngine().use { engine ->
            val result = invoke("--engine", engine.baseUrl, "sniff", "ftp://example.com/x")

            assertEquals(2, result.code)
            assertTrue(result.stderr.contains("http/https"))
            assertTrue(engine.requests.isEmpty())
        }
    }

    @Test
    fun downloadRequiresFormatOption() {
        val result = invoke("download", "t-1")

        assertEquals(2, result.code)
        assertTrue(result.stderr.lowercase().contains("format"))
    }

    @Test
    fun invalidFormatSelectorExitsTwoWithoutTouchingEngine() {
        MockEngine().use { engine ->
            val result = invoke(
                "--engine", engine.baseUrl,
                "download", "t-1", "--format=weird",
            )

            assertEquals(2, result.code)
            assertTrue(result.stderr.contains("不支持的格式选择器"))
            assertTrue(engine.requests.isEmpty())
        }
    }

    @Test
    fun formatsTableContainsProtocolFields() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val human = invoke("--engine", engine.baseUrl, "formats", id)
            assertEquals(0, human.code, human.stderr)
            assertTrue(human.stdout.contains("137"))
            assertTrue(human.stdout.contains("1920x1080"))

            val machine = invoke("--engine", engine.baseUrl, "formats", id, "--json")
            assertEquals(0, machine.code, machine.stderr)
            assertEquals(2, json(machine.stdout)["items"]!!.jsonArray.size)
        }
    }

    @Test
    fun downloadPollsUntilDoneAndKeepsJsonStdoutPure() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val result = invoke(
                "--engine", engine.baseUrl,
                "download", id, "--format=best", "--json", "--watch",
            )

            assertEquals(0, result.code, result.stderr)
            assertEquals(1, result.stdout.trim().lines().size)
            val task = CliJson.decodeFromString(Task.serializer(), result.stdout)
            assertEquals(TaskStatus.DONE, task.status)
            assertNotNull(task.outputPath)
            assertTrue(result.stderr.contains("[downloading]"))
            assertTrue(result.stderr.contains("[done]"))

            val download = engine.requests.single { it.path == "/v1/tasks/$id/download" }
            val request = CliJson.decodeFromString(DownloadRequest.serializer(), download.body)
            assertEquals(SelectionStrategy.BEST, request.formatSelector.strategy)
        }
    }

    @Test
    fun downloadUrlAppliesMaxHeightSelector() {
        MockEngine().use { engine ->
            val result = invoke(
                "--engine", engine.baseUrl,
                "download-url", "https://example.com/watch",
                "--format=max_height=720", "--json",
            )

            assertEquals(0, result.code, result.stderr)
            val task = CliJson.decodeFromString(Task.serializer(), result.stdout)
            assertEquals(TaskStatus.DONE, task.status)

            val create = engine.createRequests().single()
            val request = CliJson.decodeFromString(TaskCreateRequest.serializer(), create.body)
            assertEquals(SelectionStrategy.MAX_HEIGHT, request.formatSelector?.strategy)
            assertEquals(720, request.formatSelector?.maxHeight)
        }
    }

    @Test
    fun downloadUrlDefaultsToBest() {
        MockEngine().use { engine ->
            val result = invoke(
                "--engine", engine.baseUrl,
                "download-url", "https://example.com/watch", "--json",
            )

            assertEquals(0, result.code, result.stderr)
            val create = engine.createRequests().single()
            val request = CliJson.decodeFromString(TaskCreateRequest.serializer(), create.body)
            assertEquals(SelectionStrategy.BEST, request.formatSelector?.strategy)
        }
    }

    @Test
    fun drmProtectedExitsFourWithProtocolCodeOnStderr() {
        MockEngine().use { engine ->
            engine.drm = true

            val result = invoke(
                "--engine", engine.baseUrl,
                "download-url", "https://example.com/watch", "--json",
            )

            assertEquals(4, result.code)
            assertEquals("", result.stdout)
            val error = CliJson.decodeFromString(ErrorEnvelope.serializer(), result.stderr.trim())
            assertEquals(ErrorCode.DRM_PROTECTED, error.error.code)
        }
    }

    @Test
    fun missingMediaExitsFour() {
        MockEngine().use { engine ->
            engine.failCreateWith = ErrorCode.NO_MEDIA_FOUND

            val result = invoke(
                "--engine", engine.baseUrl,
                "sniff", "https://example.com/watch", "--json",
            )

            assertEquals(4, result.code)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("NO_MEDIA_FOUND"))
        }
    }

    @Test
    fun ssrfBlockedExitsThreeWithProtocolCodeOnStderr() {
        MockEngine().use { engine ->
            engine.failCreateWith = ErrorCode.SSRF_BLOCKED

            val result = invoke(
                "--engine", engine.baseUrl,
                "sniff", "https://blocked.test/x", "--json",
            )

            assertEquals(3, result.code)
            assertEquals("", result.stdout)
            val error = CliJson.decodeFromString(ErrorEnvelope.serializer(), result.stderr.trim())
            assertEquals(ErrorCode.SSRF_BLOCKED, error.error.code)
        }
    }

    @Test
    fun diskFullDuringDownloadExitsSix() {
        MockEngine().use { engine ->
            val id = sniff(engine)
            engine.failDownloadWith = ErrorCode.DISK_FULL

            val result = invoke(
                "--engine", engine.baseUrl,
                "download", id, "--format=best", "--json",
            )

            assertEquals(6, result.code)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("DISK_FULL"))
        }
    }

    @Test
    fun offlineEngineExitsFiveWithoutFabricatingProtocolCode() {
        val url = MockEngine().use { it.baseUrl }

        val result = invoke("--engine", url, "status", "t-1", "--json")

        assertEquals(5, result.code)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("无法连接引擎"))
        listOf(
            "NETWORK_TIMEOUT", "DNS_FAILURE", "TLS_ERROR", "NETWORK_HTTP_ERROR", "UNKNOWN",
        ).forEach { code ->
            assertFalse(result.stderr.contains(code), "本地不可达不得伪装协议码 $code")
        }
    }

    @Test
    fun protocolVersionMismatchExitsSeven() {
        MockEngine().use { engine ->
            engine.protocolVersion = "9.9"

            val result = invoke("--engine", engine.baseUrl, "status", "t-1")

            assertEquals(7, result.code)
            assertTrue(result.stderr.contains("协议版本不符"))
        }
    }

    @Test
    fun cancelMovesTaskToCancelled() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val result = invoke("--engine", engine.baseUrl, "cancel", id, "--json")

            assertEquals(0, result.code, result.stderr)
            val task = CliJson.decodeFromString(Task.serializer(), result.stdout)
            assertEquals(TaskStatus.CANCELLED, task.status)
        }
    }

    @Test
    fun statusExposesNullOutputPathBeforeDone() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val result = invoke("--engine", engine.baseUrl, "status", id, "--json")

            assertEquals(0, result.code, result.stderr)
            val output = json(result.stdout)
            assertEquals("awaiting_selection", output["status"]!!.jsonPrimitive.content)
            assertTrue(output["outputPath"] is JsonNull)
        }
    }

    @Test
    fun tasksRespectsLimit() {
        MockEngine().use { engine ->
            sniff(engine)
            sniff(engine)

            val result = invoke(
                "--engine", engine.baseUrl,
                "tasks", "--limit", "1", "--json",
            )

            assertEquals(0, result.code, result.stderr)
            val output = json(result.stdout)
            assertEquals(1, output["items"]!!.jsonArray.size)
            assertTrue(output["nextCursor"] is JsonNull)
        }
    }

    @Test
    fun tasksRejectsOutOfRangeLimitLocally() {
        MockEngine().use { engine ->
            val result = invoke("--engine", engine.baseUrl, "tasks", "--limit", "0")

            assertEquals(2, result.code)
            assertTrue(result.stderr.contains("1..200"))
            assertTrue(engine.requests.isEmpty())
        }
    }

    @Test
    fun formatsOnNonSelectableTaskExitsSeven() {
        MockEngine().use { engine ->
            val id = sniff(engine)
            val download = invoke(
                "--engine", engine.baseUrl,
                "download", id, "--format=best", "--json",
            )
            assertEquals(0, download.code, download.stderr)

            val result = invoke("--engine", engine.baseUrl, "formats", id, "--json")

            assertEquals(7, result.code)
            assertEquals("", result.stdout)
            val error = CliJson.decodeFromString(ErrorEnvelope.serializer(), result.stderr.trim())
            assertEquals(ErrorCode.TASK_STATE_CONFLICT, error.error.code)
        }
    }

    @Test
    fun globalJsonOptionBeforeSubcommandWorks() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val result = invoke(
                "--json", "--engine", engine.baseUrl,
                "status", id,
            )

            assertEquals(0, result.code, result.stderr)
            assertEquals("t-1", CliJson.decodeFromString(Task.serializer(), result.stdout).id)
        }
    }

    @Test
    fun localEngineOptionAfterSubcommandWorks() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val result = invoke("status", id, "--engine", engine.baseUrl, "--json")

            assertEquals(0, result.code, result.stderr)
            assertEquals(TaskStatus.AWAITING_SELECTION, CliJson.decodeFromString(Task.serializer(), result.stdout).status)
        }
    }

    @Test
    fun humanOutputUsesColorOnTtyButJsonNeverDoes() {
        MockEngine().use { engine ->
            val id = sniff(engine)

            val human = invoke("--engine", engine.baseUrl, "status", id, tty = true)
            assertEquals(0, human.code, human.stderr)
            assertTrue(human.stdout.contains("\u001B["))

            val machine = invoke("--engine", engine.baseUrl, "status", id, "--json", tty = true)
            assertEquals(0, machine.code, machine.stderr)
            assertFalse(machine.stdout.contains("\u001B["))
            assertEquals(TaskStatus.AWAITING_SELECTION, CliJson.decodeFromString(Task.serializer(), machine.stdout).status)
        }
    }

    @Test
    fun watchPrintsTerminalProgressWhenSubmitCompletesSynchronously() {
        MockEngine().use { engine ->
            val id = sniff(engine)
            engine.completeDownloadOnSubmit = true

            val result = invoke(
                "--engine", engine.baseUrl,
                "download", id, "--format=best", "--json", "--watch",
            )

            assertEquals(0, result.code, result.stderr)
            assertEquals(TaskStatus.DONE, CliJson.decodeFromString(Task.serializer(), result.stdout).status)
            assertTrue(result.stderr.contains("[done]"))
        }
    }

    @Test
    fun cancelledDownloadExitsEightWithLocalMessage() {
        MockEngine().use { engine ->
            val id = sniff(engine)
            engine.cancelDuringDownload = true

            val result = invoke(
                "--engine", engine.baseUrl,
                "download", id, "--format=best", "--json",
            )

            assertEquals(8, result.code)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("任务已取消"))
            assertFalse(result.stderr.contains("UNKNOWN"))
        }
    }
}
