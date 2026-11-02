package com.hpu.browseguard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 合成的本地 `Task` 快照绝不上行（M4 第 2 步③ 裁决「合成 Task 快照 + 不回传守卫」）。
 *
 * 本地直链下载没有引擎任务，界面却看 `Task` 的形状，于是 `LocalTaskSnapshot` 用本机真实事实
 * 拼一个同形状的镜像塞进 `_state`。这个镜像一旦有机会上行，就是客户端在引擎面前伪造任务——
 * 所以钉住三件事：
 * 1. 出站编码只有两个请求体（`TaskCreateRequest` / `DownloadRequest`）；
 * 2. `Task.serializer()` 永远出现在 `decode(...)` 的位置，一次也不进 `encode(...)`；
 * 3. 拼快照与跑本地下载的两个文件，压根不认识引擎客户端。
 *
 * 为什么还是静态守卫：假引擎（FakeBackend）是 M4 第 3 步的活；这里先把「方向」钉死，
 * 免得第 3 步给 `EngineClient` 加注入点时顺手把快照塞进请求体。
 */
class TaskNeverGoesOnTheWireTest {

    /** 客户端只允许这两个请求体上行的白名单。 */
    private val allowedRequestSerializers = setOf("TaskCreateRequest.serializer()", "DownloadRequest.serializer()")

    @Test
    fun everyEncodeSiteUsesAnAllowedRequestBody() {
        val sites = encodeCallSites()
        assertTrue("客户端的出站编码点恰好两处（建任务 + 开下载），实际 $sites", sites.size == 2)
        val offenders = sites.filter { args -> allowedRequestSerializers.none { it in args } }
        assertTrue("出站请求体超出 §6 协议允许的两个：$offenders", offenders.isEmpty())
    }

    /** 语义核心：协议 `Task` 只从响应方向进来。 */
    @Test
    fun taskSerializerNeverFeedsAnEncode() {
        val insideEncode = encodeCallSites().filter { TASK_SERIALIZER in it }
        assertTrue("把协议 Task 编码成请求体了：$insideEncode", insideEncode.isEmpty())
        val users = scannedFiles().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (TASK_SERIALIZER in line) "${file.name}:${index + 1}" else null
            }
        }
        assertTrue(
            "Task.serializer() 只允许出现在 EngineClient 的 decode 位（读响应），实际 $users",
            users.isNotEmpty() && users.all { it.startsWith("EngineClient.kt") },
        )
    }

    /** 本地快照与本地下载器不认识引擎客户端——快照只进 `_state`。 */
    @Test
    fun localSideStaysOffTheWire() {
        val localFiles = listOf("LocalTaskSnapshot.kt", "LocalDirectDownloader.kt")
        val problems = localFiles.flatMap { name ->
            val file = requireNotNull(find(name)) { "$name 没找到（改名或搬走了？）" }
            file.readLines().mapIndexedNotNull { index, line ->
                if (!isCode(line)) return@mapIndexedNotNull null
                BANNED_IN_LOCAL.firstOrNull { banned -> banned in line }
                    ?.let { "$name:${index + 1} 引用了 $it" }
            }
        }
        assertTrue("本地链路里出现引擎客户端或请求体：$problems", problems.isEmpty())
    }

    /** 注释里提一句「不上行」是好事，不该被当成接线泄漏。 */
    private fun isCode(line: String): Boolean {
        val trimmed = line.trim()
        return !trimmed.startsWith("//") && !trimmed.startsWith("*") && !trimmed.startsWith("/*")
    }

    /** 每个 `encode(` 调用的实参文本（按括号配对截取，不用整行正则——同一行常既有 decode 又有 encode）。 */
    private fun encodeCallSites(): List<String> = scannedFiles().flatMap { file ->
        file.readLines().flatMap { line -> callArguments(line, "encode(") }
    }

    private fun callArguments(text: String, callee: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf(callee, from)
            if (at < 0) return out
            var depth = 1
            var i = at + callee.length
            while (i < text.length && depth > 0) {
                val c = text[i]
                if (c == '(') depth++
                if (c == ')') depth--
                i++
            }
            // `private fun <T> encode(value: T, ...)` 是声明，不是调用点
            val isDeclaration = Regex("fun(\\s+<[^>]*>)?\\s*$").containsMatchIn(text.substring(0, at).trimEnd())
            if (!isDeclaration) out += text.substring(at + callee.length, (i - 1).coerceAtLeast(at + callee.length))
            from = i
        }
    }

    private fun find(name: String): File? =
        File(SRC_ROOT).walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun scannedFiles(): List<File> = File(SRC_ROOT).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
        const val TASK_SERIALIZER = "Task.serializer()"
        val BANNED_IN_LOCAL = listOf("EngineClient", "client.", "TaskCreateRequest", "DownloadRequest", "encode(")
    }
}
