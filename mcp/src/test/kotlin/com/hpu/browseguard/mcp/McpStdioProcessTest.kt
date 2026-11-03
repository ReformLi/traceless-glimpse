package com.hpu.browseguard.mcp

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * 真实子进程验收：initialize 握手、首行必须是合法 JSON-RPC、
 * stdout 不得出现任何非 JSON 行，且工具清单为固定的五个。
 */
class McpStdioProcessTest {

    private class LineStream(stream: InputStream) : AutoCloseable {
        private val queue = LinkedBlockingQueue<String>()
        private val all = mutableListOf<String>()
        private val reader = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8))
        private val thread = thread(isDaemon = true, name = "mcp-stdout") {
            reader.useLines { lines ->
                lines.forEach { line ->
                    synchronized(all) { all += line }
                    queue.put(line)
                }
            }
        }

        fun take(timeoutMs: Long = 20_000): String? =
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun poll(timeoutMs: Long): String? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

        fun allLines(): List<String> = synchronized(all) { all.toList() }

        fun awaitEof(timeoutMs: Long = 10_000) {
            thread.join(timeoutMs)
        }

        override fun close() = thread.join(2_000)
    }

    private class CapturedStderr(stream: InputStream) : AutoCloseable {
        private val buffer = StringBuilder()
        private val thread = thread(isDaemon = true, name = "mcp-stderr") {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    synchronized(buffer) { buffer.appendLine(line) }
                }
            }
        }

        fun text(): String = synchronized(buffer) { buffer.toString() }

        override fun close() = thread.join(2_000)
    }

    @Test
    fun initializeHandshakeKeepsStdoutPureAndExposesFiveTools() {
        MockEngine().use { engine ->
            val process = startServer(engine)
            val stdout = LineStream(process.inputStream)
            val stderr = CapturedStderr(process.errorStream)
            try {
                send(
                    process,
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"bgv-test","version":"1.0.0"}}}""",
                )

                val first = stdout.take()
                    ?: fail("未收到 initialize 响应；stderr:\n${stderr.text()}")
                assertFalse(first.isBlank(), "stdout 第一行不得为空")
                val handshake = Json.parseToJsonElement(first).jsonObject
                assertEquals("2.0", handshake["jsonrpc"]!!.jsonPrimitive.content)
                assertEquals(1, handshake["id"]!!.jsonPrimitive.int)
                assertFalse(handshake.containsKey("method"), "首行必须是响应而不是通知/请求")
                val initResult = handshake["result"]!!.jsonObject
                assertTrue(initResult["protocolVersion"]!!.jsonPrimitive.content.isNotBlank())
                assertEquals(
                    "traceless-glimpse",
                    initResult["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content,
                )
                assertNotNull(initResult["capabilities"]!!.jsonObject["tools"])

                send(process, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")

                send(process, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")
                val listed = awaitResponse(stdout, 2, stderr)
                val names = listed["result"]!!.jsonObject["tools"]!!.jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    .toSet()
                assertEquals(setOf("sniff", "probe", "download", "status", "cancel"), names)

                send(
                    process,
                    """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"sniff","arguments":{"url":"https://example.com/watch","engineUrl":"${engine.baseUrl}"}}}""",
                )
                val sniffResult = awaitResponse(stdout, 3, stderr)["result"]!!.jsonObject
                assertEquals(false, sniffResult["isError"]!!.jsonPrimitive.boolean)
                val sniffPayload = toolJson(sniffResult)
                assertTrue(sniffPayload["taskId"]!!.jsonPrimitive.content.isNotBlank())
                assertEquals(
                    2,
                    sniffPayload["formats"]!!.jsonObject["items"]!!.jsonArray.size,
                )

                send(
                    process,
                    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"status","arguments":{"taskId":"missing","engineUrl":"${engine.baseUrl}"}}}""",
                )
                val notFound = awaitResponse(stdout, 4, stderr)["result"]!!.jsonObject
                assertEquals(true, notFound["isError"]!!.jsonPrimitive.boolean)
                assertEquals("NOT_FOUND", toolJson(notFound)["code"]!!.jsonPrimitive.content)

                val closedPort = ServerSocket(0).use { it.localPort }
                send(
                    process,
                    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"status","arguments":{"taskId":"t-1","engineUrl":"http://127.0.0.1:$closedPort"}}}""",
                )
                val unreachable = awaitResponse(stdout, 5, stderr)["result"]!!.jsonObject
                assertEquals(true, unreachable["isError"]!!.jsonPrimitive.boolean)
                val unreachableText = unreachable["content"]!!.jsonArray
                    .first().jsonObject["text"]!!.jsonPrimitive.content
                assertEquals("""{"localError":"engine_unreachable"}""", unreachableText)

                assertNull(
                    stdout.poll(400),
                    "握手与工具调用完成后 stdout 不得出现多余内容；stderr:\n${stderr.text()}",
                )

                process.outputStream.close()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "stdin EOF 后进程未退出")
                assertEquals(0, process.exitValue(), "stderr:\n${stderr.text()}")
                stdout.awaitEof()

                val lines = stdout.allLines()
                assertTrue(lines.isNotEmpty())
                lines.forEachIndexed { index, line ->
                    assertFalse(line.isBlank(), "stdout 第 ${index + 1} 行为空")
                    val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                        ?: fail("stdout 第 ${index + 1} 行不是 JSON：$line")
                    assertEquals("2.0", message["jsonrpc"]!!.jsonPrimitive.content)
                }
            } finally {
                stdout.close()
                stderr.close()
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun startServer(engine: MockEngine): Process {
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        ).toString()
        val classpath = testClasspath()
        val process = ProcessBuilder(java, "-cp", classpath, "com.hpu.browseguard.mcp.MainKt")
            .redirectErrorStream(false)
            .apply {
                environment()["BGV_ENGINE_URL"] = engine.baseUrl
                environment()["BGV_MCP_POLL_MS"] = "5"
                environment()["BGV_MCP_SNIFF_TIMEOUT_MS"] = "5000"
                environment()["BGV_MCP_DOWNLOAD_TIMEOUT_MS"] = "5000"
            }
            .start()
        return process
    }

    private fun testClasspath(): String {
        val file = System.getProperty("mcp.test.classpathFile")
        val classpath = if (file != null) File(file).readText(StandardCharsets.UTF_8) else
            System.getProperty("java.class.path")
        assertFalse(classpath.isNullOrBlank(), "测试 classpath 不能为空")
        return classpath
    }

    private fun send(process: Process, line: String) {
        process.outputStream.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        process.outputStream.flush()
    }

    private fun awaitResponse(
        stdout: LineStream,
        id: Int,
        stderr: CapturedStderr,
    ): JsonObject {
        while (true) {
            val line = stdout.take()
                ?: fail("等待 id=$id 的响应超时；stderr:\n${stderr.text()}")
            val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: fail("stdout 出现非 JSON 行：$line")
            if (message["id"]?.jsonPrimitive?.content?.toIntOrNull() == id) return message
        }
    }

    private fun toolJson(result: JsonObject): JsonObject {
        val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }
}
