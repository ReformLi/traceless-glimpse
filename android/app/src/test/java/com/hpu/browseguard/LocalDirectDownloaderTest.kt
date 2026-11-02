package com.hpu.browseguard

import com.hpu.browseguard.data.backend.BackendFailure
import com.hpu.browseguard.data.backend.DownloadEvent
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalReason
import com.hpu.browseguard.data.backend.PrepareRequest
import com.hpu.browseguard.data.backend.StartRequest
import com.hpu.browseguard.data.backend.localTaskId
import com.hpu.browseguard.shared.model.CookiePolicy
import com.hpu.browseguard.shared.model.DrmSystem
import com.hpu.browseguard.shared.model.EstimatedQuality
import com.hpu.browseguard.shared.model.FormatSelector
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SelectionStrategy
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import com.hpu.browseguard.ui.toDownloadPhase
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 本地直链下载器的行为单测（M4 第 2 步③ 的新实现，第 3 步 FakeBackend 之前的例外——
 * 它自己就是「假引擎」之外第一个不需要注入点的后端）。
 *
 * 钉住的语义，按裁决逐条：
 * - 单条 HTTP GET + `.part` 续传，`postActions` 在这一支压根不收（不跑 FFmpeg 合并）；
 * - 服务端回 4xx 是**站点**说的：`BackendFailure.Local(LINK_UNLOADABLE)`，界面阶段 `code == null`；
 * - 取消只登记标志，终态由 `start` 那条流写出，`.part` 留在原地；
 * - 合成的 `Task` 快照装的是本机事实（`local_` 前缀 id、`local_direct` 阶段、`error` 恒 null）。
 */
class LocalDirectDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var out: File
    private val downloader = LocalDirectDownloader()

    /** 100 字节可辨识正文：前 40 字节能当断点，剩下的能当续传段。 */
    private val payload = "0123456789".repeat(10)

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        out = folder.newFolder("download")
    }

    @After
    fun stopServer() {
        server.close()
    }

    @Test
    fun fullDownloadLandsTheFileAndWritesADoneTerminal() {
        server.enqueue(reply(200, payload))
        val events = runBlocking { downloader.start(viaLocal(link("/media/clip.mp4"))).toList() }

        val done = events.last() as DownloadEvent.Terminal
        assertEquals(TaskStatus.DONE, done.task.status)
        assertTrue("本地快照的 id 一眼可辨不是引擎任务：${done.task.id}", done.task.id.startsWith("local_"))
        assertEquals("local_direct", done.task.progress?.phase)
        assertEquals(100L, done.task.progress!!.bytesTotal)
        assertEquals(100.0, done.task.progress!!.percent, 0.0)
        assertNull("本地失败才走 Local，终态快照不得携带引擎的 §5 错误载荷", done.task.error)
        assertEquals(CookiePolicy.NONE, done.task.cookiePolicy)
        assertTrue("本地这一支不收 postActions，合并没有出口", done.task.postActions.isEmpty())

        assertEquals(payload, File(out, "clip.mp4").readText())
        assertFalse(".part 落地后必须改名，不留断点", File(out, "clip.mp4.part").exists())
    }

    @Test
    fun resumeSendsRangeAndAppsWithTheAlreadyDownloadedBytes() {
        val part = File(out, "clip.mp4.part")
        part.writeText(payload.take(40))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes 40-99/100")
                .body(payload.drop(40))
                .build(),
        )
        val events = runBlocking { downloader.start(viaLocal(link("/media/clip.mp4"))).toList() }

        assertEquals("bytes=40-", server.takeRequest().headers["Range"])
        assertEquals(payload, File(out, "clip.mp4").readText())
        assertFalse(part.exists())
        val started = events.first() as DownloadEvent.Started
        assertEquals("断点那 40 字节要算进已下载", 40L, started.task.progress!!.bytesDownloaded)
        assertEquals(100L, started.task.progress!!.bytesTotal)
        assertEquals(TaskStatus.DONE, (events.last() as DownloadEvent.Terminal).task.status)
    }

    /** 服务端不理 Range 时从头覆盖：旧断点不能再算作已下部分，否则进度虚高、文件错位。 */
    @Test
    fun serverIgnoringRangeRewindsInsteadOfStitchingStaleBytes() {
        File(out, "clip.mp4.part").writeText(payload.take(40))
        server.enqueue(reply(200, payload))
        val events = runBlocking { downloader.start(viaLocal(link("/media/clip.mp4"))).toList() }

        assertEquals("bytes=40-", server.takeRequest().headers["Range"])
        assertEquals(payload, File(out, "clip.mp4").readText())
        val started = events.first() as DownloadEvent.Started
        assertEquals("服务端没认续传，已下载必须回到 0", 0L, started.task.progress!!.bytesDownloaded)
    }

    /** 416：断点不小于服务端资源长度，清掉重来，不留一个永远续不动的死断点。 */
    @Test
    fun staleBreakpointBeyondServerLengthIsClearedAndRestarted() {
        val stale = payload + "beyond-the-server-length"
        File(out, "clip.mp4.part").writeText(stale)
        server.enqueue(
            MockResponse.Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */100")
                .build(),
        )
        server.enqueue(reply(200, payload))
        val events = runBlocking { downloader.start(viaLocal(link("/media/clip.mp4"))).toList() }

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("bytes=${stale.length}-", first.headers["Range"])
        assertNull("重来的那次不许再带 Range", second.headers["Range"])
        assertEquals(payload, File(out, "clip.mp4").readText())
        assertEquals(TaskStatus.DONE, (events.last() as DownloadEvent.Terminal).task.status)
    }

    @Test
    fun httpErrorFromTheSiteBecomesALocalFailureWithoutAProtocolCode() {
        server.enqueue(reply(403, "forbidden"))
        val events = runBlocking { downloader.start(viaLocal(link("/media/clip.mp4"))).toList() }

        val failed = events.single() as DownloadEvent.Failed
        val local = failed.failure as BackendFailure.Local
        assertEquals(LocalReason.LINK_UNLOADABLE, local.reason)
        assertEquals("本地直链取不到内容 · 服务端回 HTTP 403，客户端本地判定", local.message)
        assertNull("站点说的话不能借用 §5 引擎码", local.toDownloadPhase().code)
        assertFalse(File(out, "clip.mp4").exists())
    }

    /** 进度按 [LocalFailures] 的采样窗口发，不是每读一块就发一次。 */
    @Test
    fun progressIsSampledWhileBytesMonotonicallyGrow() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("x".repeat(4096))
                .throttleBody(256, 100, TimeUnit.MILLISECONDS)
                .build(),
        )
        val events = runBlocking { downloader.start(viaLocal(link("/media/big.mp4"))).toList() }

        val samples = events.filterIsInstance<DownloadEvent.Progress>().map { it.task.progress!!.bytesDownloaded }
        assertTrue("窗口 800ms、正文节流 1.6s，至少该采到一次进度，实际 $samples", samples.isNotEmpty())
        assertEquals("进度必须单调递增", samples.sorted(), samples)
        assertEquals(TaskStatus.DONE, (events.last() as DownloadEvent.Terminal).task.status)
    }

    @Test
    fun cancelOnlyRegistersAndTheStartedStreamWritesTheCancelledTerminal() {
        val part = File(out, "clip.mp4.part")
        part.writeText(payload.take(40))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .body(payload.drop(40))
                .throttleBody(8, 100, TimeUnit.MILLISECONDS)
                .build(),
        )

        val events = mutableListOf<DownloadEvent>()
        var cancelResult: BackendFailure? = BackendFailure.Local(LocalReason.DISK, "cancel 没返回")
        runBlocking {
            downloader.start(viaLocal(link("/media/clip.mp4"))).collect { event ->
                events += event
                if (event is DownloadEvent.Started) {
                    cancelResult = downloader.cancel(event.task.id)
                }
            }
        }

        assertNull("取消的终态归 start 那条流，两处同时改状态会打架", cancelResult)
        val terminal = events.last() as DownloadEvent.Terminal
        assertEquals(TaskStatus.CANCELLED, terminal.task.status)
        // flowOn(IO) 下生产端会跑在收集端前面，所以钉「落在断点与全文之间」而不是某个字节数。
        val frozen = terminal.task.progress!!.bytesDownloaded
        assertTrue("取消时至少握着断点、且没下完：$frozen", frozen in 40L until 100L)
        assertEquals("落盘字节必须与快照报的一致", frozen, part.length())
        assertEquals(payload.take(frozen.toInt()), part.readText())
        assertFalse(File(out, "clip.mp4").exists())
    }

    @Test
    fun prepareSynthesizesOneRowPerDownloadableLink() {
        val video = link("/media/clip.mp4")
        val audio = link("/media/voice.m4a", type = MediaStreamType.AUDIO)
        val protected = link("/media/protected.mp4", drm = true)

        val events = prepare(listOf(video, protected, audio))
        val selecting = events.single() as DownloadEvent.Selecting

        assertEquals("local", selecting.taskId)
        assertEquals(
            listOf(localTaskId(video.mediaUrl), localTaskId(audio.mediaUrl)),
            selecting.formats.map { it.formatId },
        )
        assertTrue("DRM 那条不许进表", selecting.formats.none { it.formatId == localTaskId(protected.mediaUrl) })
        assertEquals(listOf(true, false), selecting.formats.map { it.hasVideo })
        assertTrue(selecting.formats.all { it.fileSizeEstimateBytes == 0L })
    }

    /** 三条「没什么可下」各有各的措辞，且一律是本地判定、不带 §5 码。 */
    @Test
    fun prepareRefusesLocallyForEveryKindOfNothingToDownload() {
        val failures = listOf(
            (prepare(emptyList()).single() as DownloadEvent.Failed).failure,
            (prepare(listOf(link("/media/protected.mp4", drm = true))).single() as DownloadEvent.Failed).failure,
            (prepare(listOf(link("magnet:?xt=urn:btih:abc"))).single() as DownloadEvent.Failed).failure,
        ).map { it as BackendFailure.Local }

        assertEquals(LocalReason.NO_SNIFF_RESULTS, failures[0].reason)
        assertEquals(LocalReason.UNSUPPORTED_LINK, failures[1].reason)
        assertTrue("DRM 那条要说清是 DRM 挡住的：${failures[1].message}", failures[1].message.contains("DRM"))
        assertEquals(LocalReason.UNSUPPORTED_LINK, failures[2].reason)
        assertTrue("非 HTTP(S) 那条要说清是协议挡住的：${failures[2].message}", failures[2].message.contains("HTTP(S)"))

        failures.forEach {
            assertNull("本地判定不得携带 §5 协议码", it.toDownloadPhase().code)
            assertTrue("措辞要自证是我们自己判的", it.message.contains("客户端本地判定"))
        }
    }

    /** 非 HTTP(S) 的直链就算被选中也不能开下——表是合成出来的，但界面可能拿着过期的行点下去。 */
    @Test
    fun startRefusesALinkItCannotFetchLocally() {
        val events = runBlocking {
            downloader.start(
                StartRequest.ViaLocal(link("magnet:?xt=urn:btih:abc"), out.absolutePath, listOf(out.absolutePath)),
            ).toList()
        }
        val failed = events.single() as DownloadEvent.Failed
        assertEquals(LocalReason.UNSUPPORTED_LINK, (failed.failure as BackendFailure.Local).reason)
        assertEquals(0, out.list()!!.size)
    }

    /** 组错请求支是编程错误，不是用户能遇到的失败——用 `error()` 炸出来，别静默走另一支。 */
    @Test
    fun engineRequestAgainstTheLocalDownloaderFailsLoudly() {
        val engineRequest = StartRequest.ViaEngine(
            taskId = "01a1",
            formatSelector = FormatSelector(strategy = SelectionStrategy.BEST),
            namingTemplate = null,
            postActions = null,
        )
        val boom = assertThrows(IllegalStateException::class.java) {
            runBlocking { downloader.start(engineRequest).toList() }
        }
        assertTrue("异常要说清是调用方组错了请求：${boom.message}", boom.message!!.contains("组错了 StartRequest"))
    }

    /** 写盘前先过沙箱：根外的目录一个字节都不许写，也不许先发请求。 */
    @Test
    fun outputDirOutsideTheSandboxIsRefusedBeforeAnythingIsWritten() {
        val outside = folder.newFolder("outside")
        server.enqueue(reply(200, payload))
        val events = runBlocking {
            downloader.start(
                StartRequest.ViaLocal(link("/media/clip.mp4"), outside.absolutePath, listOf(out.absolutePath)),
            ).toList()
        }

        val failed = events.single() as DownloadEvent.Failed
        val local = failed.failure as BackendFailure.Local
        assertEquals(LocalReason.OUTPUT_DIR_REJECTED, local.reason)
        assertNull("沙箱判定是客户端本地决定，不带 §5 码", local.toDownloadPhase().code)
        assertTrue("措辞要自证", local.message.contains("沙箱校验"))
        assertEquals("根外不许落任何文件", 0, outside.list()!!.size)
        assertEquals("根内也不该多出东西", 0, out.list()!!.size)
        assertEquals("被拒发生在建连之前", 0, server.requestCount)
    }

    /** 白名单为空时直接拒，**不许**退回引擎目录或任意目录——那正是「乱写文件」的入口。 */
    @Test
    fun emptyAllowlistRefusesInsteadOfFallingBackToAnotherDirectory() {
        val events = runBlocking {
            downloader.start(StartRequest.ViaLocal(link("/media/clip.mp4"), out.absolutePath, emptyList())).toList()
        }
        val local = (events.single() as DownloadEvent.Failed).failure as BackendFailure.Local
        assertEquals(LocalReason.OUTPUT_DIR_REJECTED, local.reason)
        assertEquals(0, out.list()!!.size)
        assertEquals(0, server.requestCount)
    }

    /** 本地句柄必须在命名空间上就能区分：下游 `HttpDownloader` 靠这个前缀拒绝上行。 */
    @Test
    fun localTaskIdCarriesTheLocalNamespacePrefix() {
        assertTrue(localTaskId("https://m.example.com/clip.mp4").startsWith("local_"))
    }

    /** `headersHint` 协议上已脱敏，但那是引擎侧的约定；本机落地前自己再筛一遍。 */
    @Test
    fun sensitiveHintHeadersAreDroppedLocally() {
        server.enqueue(reply(200, payload))
        val link = link("/media/clip.mp4").copy(
            headersHint = mapOf(
                "referer" to "https://m.example.com",
                "user-agent" to "BGV/1",
                "cookie" to "sid=secret",
                "authorization" to "Bearer leaked",
                "proxy-authorization" to "Basic leaked",
                "set-cookie" to "leaked=1",
            ),
        )
        runBlocking { downloader.start(viaLocal(link)).toList() }

        val recorded = server.takeRequest()
        assertEquals("https://m.example.com", recorded.headers["referer"])
        assertEquals("BGV/1", recorded.headers["user-agent"])
        listOf("cookie", "authorization", "proxy-authorization", "set-cookie").forEach { name ->
            assertNull("$name 不许由本机带上行", recorded.headers[name])
        }
    }

    /**
     * 嗅探 hint 里的 `Range` 是浏览器探测留下的残留：回放它会得到 206 + 1 字节，
     * 然后被当成整段下载完成——真机 MuMu 实测踩到过（§21）。
     */
    @Test
    fun sniffedRangeHintIsNotReplayedAsTheDownloadRequest() {
        server.enqueue(reply(200, payload))
        val link = link("/media/clip.mp4").copy(
            headersHint = mapOf("range" to "bytes=0-0", "if-range" to "\"etag\""),
        )
        val events = runBlocking { downloader.start(viaLocal(link)).toList() }

        val done = events.last() as DownloadEvent.Terminal
        assertEquals(TaskStatus.DONE, done.task.status)
        assertEquals("没有断点就必须整段拿", 100L, done.task.progress!!.bytesDownloaded)
        val recorded = server.takeRequest()
        assertNull("Range 只能由下载器按 .part 自己决定", recorded.headers["range"])
        assertNull(recorded.headers["if-range"])
        assertEquals(payload, File(out, "clip.mp4").readText())
    }

    private fun prepare(results: List<SniffResult>) = runBlocking {
        downloader.prepare(
            PrepareRequest(
                sourceUrl = "https://m.example.com/live",
                cookiePolicy = CookiePolicy.NONE,
                outputDir = out.absolutePath,
                sniffResults = results,
            ),
        ).toList()
    }

    private fun viaLocal(l: SniffResult) =
        StartRequest.ViaLocal(l, out.absolutePath, allowedRoots = listOf(out.absolutePath))

    private fun link(path: String, drm: Boolean = false, type: MediaStreamType = MediaStreamType.MP4): SniffResult {
        val url = if (path.startsWith("magnet")) path else server.url(path).toString()
        return SniffResult(
            taskId = "01a1b2c3",
            mediaUrl = url,
            type = type,
            container = if (type == MediaStreamType.AUDIO) "m4a" else "mp4",
            estimatedQuality = EstimatedQuality(height = 720, bitrateKbps = 2000, label = "720p"),
            drmDetected = drm,
            drmSystem = if (drm) DrmSystem.WIDEVINE else null,
            discoveredAt = "2026-11-02T10:00:00Z",
        )
    }

    private fun reply(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()
}
