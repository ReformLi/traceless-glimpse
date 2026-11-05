package com.hpu.browseguard

import com.hpu.browseguard.data.EngineClient
import com.hpu.browseguard.data.backend.HttpDownloader
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.SelectionStrategy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 本地句柄不许上行（细节裁决 2 的下游一半）。
 *
 * `local_` 前缀是命名空间，不是装饰：`HttpDownloader` 的 `start` 与 `cancel` 在**发出请求前**
 * 就对这个前缀抛错。放过去的后果不是「多一个 404」——界面上会显示成引擎说「任务不存在」，
 * 把客户端拿错句柄这件事伪装成引擎的诊断。动态守卫用真 `HttpDownloader` 证明它确实抛，
 * 静态守卫证明两个入口都还在拦（`requireRemoteTaskId()` 恰好 2 个调用点）。
 */
class LocalHandleIsolationTest {

    private lateinit var server: MockWebServer
    private lateinit var downloader: HttpDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = HttpDownloader(server.url("/").toString().trimEnd('/'), EngineClient())
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun startRefusesALocalHandleBeforeTouchingTheNetwork() {
        val boom = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                downloader.start(
                    StartRequest.ViaEngine(
                        taskId = "local_deadbeef",
                        formatSelector = FormatSelector(strategy = SelectionStrategy.BEST),
                        namingTemplate = null,
                        postActions = null,
                    ),
                ).toList()
            }
        }
        assertTrue("异常要说清是哪道门拦的：${boom.message}", boom.message!!.contains("本地任务句柄不得发往引擎"))
        assertEquals("拦在发请求之前", 0, server.requestCount)
    }

    @Test
    fun cancelRefusesALocalHandleBeforeTouchingTheNetwork() {
        val boom = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { downloader.cancel("local_deadbeef") }
        }
        assertTrue(boom.message!!.contains("本地任务句柄不得发往引擎"))
        assertEquals("拦在发请求之前", 0, server.requestCount)
    }

    /**
     * 画质表那个占位 id 也会被回填进历史行的 `taskId`，它同样没有远端对应物：
     * 发过去只会让引擎回一句「任务不存在」，把本机拿错句柄伪装成引擎的诊断。
     */
    @Test
    fun cancelRefusesTheLocalFormatTablePlaceholderToo() {
        val boom = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { downloader.cancel("local") }
        }
        assertTrue(boom.message!!.contains("本地任务句柄不得发往引擎"))
        assertEquals("拦在发请求之前", 0, server.requestCount)
    }

    /** 守卫别只剩一个入口：定义 1 处 + 调用 2 处，且都在 `HttpDownloader.kt` 里。 */
    @Test
    fun bothHttpEntriesStillCallTheGuard() {
        val hits = File("src/main/java/com/hpu/browseguard").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if ("requireRemoteTaskId()" in line) "${file.name}:${index + 1}" else null
                }
            }
            .toList()
        assertEquals("守卫点数量变了：$hits", 3, hits.size)
        assertTrue("守卫必须留在 HttpDownloader.kt：$hits", hits.all { it.startsWith("HttpDownloader.kt") })
    }

    /** 本地后端对引擎请求支抛错是对称的另一半。 */
    @Test
    fun localDownloaderRefusesAnEngineRequest() {
        val boom = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                com.hpu.browseguard.data.backend.LocalDirectDownloader().start(
                    StartRequest.ViaEngine(
                        taskId = "01a1",
                        formatSelector = FormatSelector(strategy = SelectionStrategy.BEST),
                        namingTemplate = null,
                        postActions = null,
                    ),
                ).toList()
            }
        }
        assertTrue(boom.message!!.contains("组错了 StartRequest"))
    }
}
