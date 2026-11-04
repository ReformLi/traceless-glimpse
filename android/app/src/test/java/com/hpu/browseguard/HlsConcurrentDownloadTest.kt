package com.hpu.browseguard

import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.CookieProvider
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.NoCookieProvider
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.hls.HlsDownloader
import com.hpu.browseguard.data.backend.hls.HlsKey
import com.hpu.browseguard.data.backend.hls.HlsOutcome
import com.hpu.browseguard.data.backend.hls.HlsPlan
import com.hpu.browseguard.data.backend.hls.HlsSegment
import com.hpu.browseguard.fakes.FakeAuditLogger
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * HLS 并发下载（M7.5）。
 *
 * M7 的 703 段是**串行**下的：一段一停，33 分钟全花在等 CDN 回包上。这一轮改成
 * 「4 个槽位并发取、按清单顺序写」，于是必须现场钉住三件并发最容易出错的事：
 * ① 完成顺序打乱也不能打乱落盘顺序（拼错一段就是坏片）；② 在飞段数不能超；
 * ③ 每段（连密钥都要）带同一套防盗链头。外加 403 不重试、瞬时失败要重试。
 */
class HlsConcurrentDownloadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @Before
    fun startServer() {
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun segmentUrl(index: Int) = server.url("/v/seg$index.ts").toString()

    private fun plan(count: Int, keyUri: String? = null) = HlsPlan(
        mediaUrl = server.url("/v/index.m3u8").toString(),
        segments = (0 until count).map { index ->
            HlsSegment(
                url = segmentUrl(index),
                key = keyUri?.let { HlsKey(method = "AES-128", uri = it, ivHex = null) },
                range = null,
                sequence = index.toLong(),
            )
        },
        initMap = null,
        encrypted = keyUri != null,
        container = "ts",
        durationSeconds = count * 4,
    )

    private fun link(url: String = server.url("/v/index.m3u8").toString()) = SniffResult(
        taskId = "local",
        mediaUrl = url,
        type = MediaStreamType.HLS,
        container = "m3u8",
        estimatedQuality = EstimatedQuality(height = 536, bitrateKbps = 1200, label = "536p"),
        drmDetected = false,
        headersHint = mapOf(
            "Referer" to "https://site.example.com/play/1.html",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 15) BgvTest",
        ),
        discoveredAt = "2026-11-04T09:00:00Z",
    )

    private fun segmentBody(index: Int) = "SEG-%03d".format(index).repeat(32)

    /** `/v/seg7.ts` → 7；Dispatcher 里解析失败会把连接掐掉，客户端只会看到传输错误。 */
    private fun segmentIndexOf(path: String): Int =
        path.substringAfterLast("seg").removeSuffix(".ts").toIntOrNull()
            ?: error("不是预期的切片地址：$path")

    private fun partFile(name: String) = File(folder.root, "$name.part")

    /** 段 i 越靠后回得越快：完成顺序与清单顺序完全相反。 */
    private fun reversedDelayDispatcher(count: Int) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val index = request.url.encodedPath.removePrefix("/v/seg").removeSuffix(".ts").toIntOrNull()
                ?: return MockResponse.Builder().code(404).build()
            return MockResponse.Builder()
                .body(segmentBody(index))
                .bodyDelay((count - index) * 25L, TimeUnit.MILLISECONDS)
                .build()
        }
    }

    @Test
    fun outOfOrderSegmentsLandInTheFileInPlaylistOrder() = runBlocking {
        val count = 8
        server.dispatcher = reversedDelayDispatcher(count)
        val out = partFile("ordered")
        val progress = mutableListOf<Int>()

        val outcome = HlsDownloader().download(plan(count), link(), out, { false }) { _, done, _ ->
            progress += done
        }

        assertTrue("应当成功，实际 $outcome", outcome is HlsOutcome.Done)
        assertEquals(
            (0 until count).joinToString("") { segmentBody(it) },
            out.readText(),
        )
        assertEquals("落盘顺序必须严格按清单", (1..count).toList(), progress)
    }

    @Test
    fun neverMoreThanFourSegmentsAreInFlight() = runBlocking {
        val count = 12
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val live = inFlight.incrementAndGet()
                peak.updateAndGet { maxOf(it, live) }
                return try {
                    Thread.sleep(50)
                    MockResponse.Builder().body("x".repeat(16)).build()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        val outcome = HlsDownloader().download(plan(count), link(), partFile("window"), { false }) { _, _, _ -> }

        assertTrue(outcome is HlsOutcome.Done)
        assertTrue("并发没真的跑起来：峰值只有 ${peak.get()} 段", peak.get() >= 2)
        assertTrue("同时在飞不能超过 4 段，实测 ${peak.get()}", peak.get() <= 4)
    }

    @Test
    fun aes128SegmentsAreDecryptedBeforeTheyAreMerged() = runBlocking {
        val key = ByteArray(16) { (it + 1).toByte() }
        val count = 6
        val plain = (0 until count).map { "PLAIN-$it-".repeat(20) }
        val keyHits = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path == "/v/key.bin") {
                    keyHits.incrementAndGet()
                    return MockResponse.Builder().body(Buffer().write(key)).build()
                }
                val index = segmentIndexOf(path)
                return MockResponse.Builder().body(Buffer().write(encrypt(plain[index], key, index))).build()
            }
        }

        val out = partFile("aes")
        val outcome = HlsDownloader().download(
            plan(count, keyUri = server.url("/v/key.bin").toString()),
            link(),
            out,
            { false },
        ) { _, _, _ -> }

        assertTrue("应当成功，实际 $outcome", outcome is HlsOutcome.Done)
        assertEquals(plain.joinToString(""), out.readText())
        assertEquals("4 个段同时在飞，密钥也只该取一次", 1, keyHits.get())
    }

    @Test
    fun aForbiddenSegmentFailsFastWithoutRetrying() = runBlocking {
        val hitsOnSeg2 = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/v/seg2.ts") {
                    hitsOnSeg2.incrementAndGet()
                    return MockResponse.Builder().code(403).build()
                }
                return MockResponse.Builder().body("x".repeat(16)).build()
            }
        }

        val outcome = HlsDownloader().download(plan(6), link(), partFile("forbidden"), { false }) { _, _, _ -> }

        val failed = outcome as HlsOutcome.Failed
        assertEquals(LocalReason.LINK_UNLOADABLE, failed.failure.reason)
        assertEquals("403 是站点说的，状态码要原样带回去", 403, failed.failure.status)
        assertEquals("Referer/Cookie 过期时重试只是继续刷 CDN", 1, hitsOnSeg2.get())
    }

    @Test
    fun aTransientFailureIsRetriedAndTheMergeStaysComplete() = runBlocking {
        val hitsOnSeg1 = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath == "/v/seg1.ts" && hitsOnSeg1.incrementAndGet() == 1) {
                    return MockResponse.Builder().code(500).build()
                }
                val index = segmentIndexOf(request.url.encodedPath)
                return MockResponse.Builder().body(segmentBody(index)).build()
            }
        }

        val out = partFile("retry")
        val outcome = HlsDownloader().download(plan(5), link(), out, { false }) { _, _, _ -> }

        assertEquals(
            (0 until 5).joinToString("") { segmentBody(it) },
            out.readText(),
        )
        assertEquals("一次 500 之后应当重试并补全", 2, hitsOnSeg1.get())
        assertEquals("重试次数要能被审计行交代", 1, (outcome as HlsOutcome.Done).retries)
    }

    @Test
    fun cancelStopsTheWindowRightAfterTheCurrentSegment() = runBlocking {
        val count = 10
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder()
                    .body(segmentBody(segmentIndexOf(request.url.encodedPath)))
                    .bodyDelay(20L, TimeUnit.MILLISECONDS)
                    .build()
        }
        var segmentsWritten = 0

        val outcome = HlsDownloader().download(
            plan(count),
            link(),
            partFile("cancel"),
            { segmentsWritten >= 3 },
        ) { _, done, _ -> segmentsWritten = done }

        val cancelled = outcome as HlsOutcome.Cancelled
        assertEquals("取消点之后的段不该再写进文件", 3, cancelled.segments)
        assertTrue(cancelled.bytes > 0L)
    }

    @Test
    fun everySegmentAndTheKeyCarryTheSameAntiHotlinkHeaders() = runBlocking {
        val key = ByteArray(16) { 7 }
        val seen = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return if (request.url.encodedPath == "/v/key.bin") {
                    MockResponse.Builder().body(Buffer().write(key)).build()
                } else {
                    val index = segmentIndexOf(request.url.encodedPath)
                    MockResponse.Builder().body(Buffer().write(encrypt(segmentBody(index), key, index))).build()
                }
            }
        }

        HlsDownloader(cookies = CookieProvider { "sid=page-cookie" })
            .download(plan(6, keyUri = server.url("/v/key.bin").toString()), link(), partFile("headers"), { false }) { _, _, _ -> }

        val page = "https://site.example.com/play/1.html"
        val agent = "Mozilla/5.0 (Linux; Android 15) BgvTest"
        assertEquals("6 段 + 1 次密钥", 7, seen.size)
        seen.forEach { request ->
            assertEquals("段请求漏掉 Referer 就会被防盗链拒掉", page, request.headers["Referer"])
            assertEquals(agent, request.headers["User-Agent"])
            assertEquals("sid=page-cookie", request.headers["Cookie"])
        }
    }

    /** 端到端：清单 → 并发段 → 落盘 → 分段进度 → 一行收尾审计。 */
    @Test
    fun theEndToEndTaskReportsSegmentProgressAndOneDoneLine() = runBlocking {
        val count = 5
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-TARGETDURATION:4")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            (0 until count).forEach { appendLine("#EXTINF:4.0,\nseg$it.ts") }
            appendLine("#EXT-X-ENDLIST")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("index.m3u8") -> MockResponse.Builder().body(playlist).build()
                    else -> MockResponse.Builder().body(segmentBody(segmentIndexOf(path))).build()
                }
            }
        }
        val out = folder.newFolder("sink")
        val audit = FakeAuditLogger()

        val events = LocalDirectDownloader(audit = audit, cookies = NoCookieProvider)
            .start(StartRequest.ViaLocal(link(), out.absolutePath, listOf(out.absolutePath)))
            .toList()

        val terminal = events.last() as DownloadEvent.Terminal
        assertEquals(TaskStatus.DONE, terminal.task.status)
        val phases = events.filterIsInstance<DownloadEvent.Progress>().mapNotNull { it.task.progress?.phase }
        assertEquals("进度必须是「hls 已下段数/总段数」，实际 $phases", "hls 5/5", phases.last())
        val product = requireNotNull(out.listFiles()?.singleOrNull()) { "产物应当只有一个文件：${out.list()?.toList()}" }
        assertEquals((0 until count).joinToString("") { segmentBody(it) }, product.readText())

        val done = audit.of("hls_download_done").single()
        assertEquals("5", done.fields["segments"])
        assertEquals("0", done.fields["retries"])
        assertNotNull(done.fields["bytes"])
    }

    private fun encrypt(plain: String, key: ByteArray, sequence: Int): ByteArray {
        val iv = ByteBuffer.allocate(16).putLong(sequence.toLong()).array()
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(plain.toByteArray())
    }
}
