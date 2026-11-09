package com.hpu.browseguard.data.backend

import android.os.StatFs
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.data.download.HlsConcurrency
import com.hpu.browseguard.data.backend.hls.HlsDownloader
import com.hpu.browseguard.data.backend.hls.HlsOutcome
import com.hpu.browseguard.data.backend.hls.HlsPrepare
import com.hpu.browseguard.data.backend.remux.AudioScan
import com.hpu.browseguard.data.backend.remux.RemuxAudioGuard
import com.hpu.browseguard.data.backend.remux.RemuxResult
import com.hpu.browseguard.data.backend.remux.TsRemuxer
import com.hpu.browseguard.shared.model.Codec
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.Resolution
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地直链下载器：单条 HTTP GET 落到手机存储，`.part` 断点续传。
 *
 * 只做这一件事——不碰 yt-dlp（§18.5 已裁决放弃端侧内嵌），也不跑 FFmpeg：
 * 直链本身就是完整音视频文件，`postActions` 在这里没有对应动作，界面选了合并也不会
 * 由本实现执行，这条限制写进 `StartRequest.ViaLocal` 的形状里（它压根不收 postActions）。
 * HLS 拼出的 `.ts` 只做容器转换（平台 muxer，copy remux），不是合并、不重编码。
 *
 * 失败一律发 [BackendFailure.Local]：403 是**站点**说的，不是引擎说的，
 * 借 §5 的 `AUTH_REQUIRED` 就等于替引擎发言（§19.3 那三条伪造的教训）。
 *
 * 取消是协作式的：[cancel] 只登记一个标志，终态由 [start] 那条流写出，
 * `.part` 保留在原处，下次点同一条直链就从断点接着下。
 *
 * 写盘前先过 [LocalOutputSandbox]：目录必须落在调用方声明的手机侧根之内，
 * 不通过就发 `OUTPUT_DIR_REJECTED`——不产生任何文件，也不把「没试」说成「写失败」。
 */
class LocalDirectDownloader(
    private val http: OkHttpClient = LOCAL_HTTP,
    private val audit: AuditLogger = NoopAuditLogger,
    private val cookies: CookieProvider = NoCookieProvider,
    private val hlsConcurrency: Int = HlsConcurrency.DEFAULT,
) : DownloadBackend {

    private val hls = HlsDownloader(http, cookies, concurrency = hlsConcurrency)
    private val remuxer = TsRemuxer()

    override fun prepare(request: PrepareRequest): Flow<DownloadEvent> = flow {
        val candidates = request.sniffResults.filter { !it.drmDetected && it.mediaUrl.isHttpUrl() }
        if (candidates.isEmpty()) {
            emit(
                DownloadEvent.Failed(
                    when {
                        request.sniffResults.isEmpty() -> LocalFailures.noSniffResults()
                        request.sniffResults.all { it.drmDetected } ->
                            LocalFailures.unsupportedLink("嗅探结果全部带 DRM 保护")
                        else -> LocalFailures.unsupportedLink("没有一条 HTTP(S) 直链")
                    },
                ),
            )
            return@flow
        }
        emit(DownloadEvent.Selecting(LOCAL_TABLE_ID, candidates.map { it.toDirectFormat() }))
    }.flowOn(Dispatchers.IO)

    override fun start(request: StartRequest): Flow<DownloadEvent> = flow {
        val target = when (request) {
            is StartRequest.ViaLocal -> request
            is StartRequest.ViaEngine ->
                error("LocalDirectDownloader 收到引擎任务请求：调用方组错了 StartRequest")
        }
        val link = target.link
        if (link.drmDetected) {
            emit(DownloadEvent.Failed(LocalFailures.unsupportedLink("这条结果带 DRM 保护，本机不做解密")))
            return@flow
        }
        if (!link.mediaUrl.isHttpUrl()) {
            emit(DownloadEvent.Failed(LocalFailures.unsupportedLink("链接不是 HTTP(S)")))
            return@flow
        }

        val handle = localTaskId(link.mediaUrl)
        if (link.isHls()) {
            // HLS 要先读清单才知道容器与段数，所以不能在这里先把产物名定死。
            downloadHls(target, link, handle) { emit(it) }
            return@flow
        }

        val productName = target.productName
            ?.let(::sanitizeProductName)
            ?.takeIf { it.isNotBlank() }
            ?.let { withContainer(it, link.container) }
            ?: link.productName()
        val partFile = java.io.File(target.outputDir, "$productName.part")
        val finalFile = java.io.File(target.outputDir, productName)

        // 写盘前先过沙箱：这一步不产生任何文件，失败也不该被当成 IO 错误。
        LocalOutputSandbox.reject(partFile, target.allowedRoots)?.let { reason ->
            emit(DownloadEvent.Failed(LocalFailures.outputDirRejected(reason)))
            return@flow
        }

        var resumedFrom = try {
            openForWrite(partFile)
        } catch (e: IOException) {
            emit(DownloadEvent.Failed(LocalFailures.disk(e.describe())))
            return@flow
        } catch (e: SecurityException) {
            emit(DownloadEvent.Failed(LocalFailures.disk(e.message ?: "存储权限被拒")))
            return@flow
        }

        var downloaded = resumedFrom
        try {
            var response = exchange(link, resumedFrom)
            if (response == null) {
                // 416：断点已不小于服务端资源长度。清掉重来一次，不留一个永远续不动的死断点。
                partFile.delete()
                resumedFrom = 0L
                downloaded = 0L
                response = exchange(link, 0L)
                if (response == null) {
                    emit(DownloadEvent.Failed(LocalFailures.linkUnloadable(416)))
                    return@flow
                }
            }
            response.use { resp ->
                if (!resp.isSuccessful) {
                    // 406 是最典型的「少了 accept」信号（§25.6 裁决）：先留痕，
                    // 真机确认后再决定是否把 accept 放进白名单。
                    if (resp.code == 406) {
                        audit.log(
                            "accept_missing_suspected",
                            mapOf("status" to "406", "host" to hostOf(link.mediaUrl)),
                        )
                    }
                    emit(DownloadEvent.Failed(LocalFailures.linkUnloadable(resp.code)))
                    return@flow
                }
                val serverResumed = resp.code == HttpURLConnection.HTTP_PARTIAL
                val contentLength = resp.body.contentLength()
                val total = if (contentLength > 0) contentLength + if (serverResumed) resumedFrom else 0L else 0L
                if (!serverResumed && resumedFrom > 0L) {
                    // 服务端不理 Range：从头覆盖，旧断点不能再算作已下部分。
                    downloaded = 0L
                }
                emit(DownloadEvent.Started(link.localSnapshot(target.outputDir, productName, downloaded, total)))

                val stream = resp.body.byteStream()
                RandomAccessFile(partFile, "rw").use { file ->
                    if (!serverResumed && resumedFrom > 0L) {
                        // 服务端不理 Range 整段重发：旧 .part 必须截断清空。光 seek(0)
                        // 覆写不够——新内容比旧文件短时旧尾巴会留在成品里，rename 后
                        // 就是损坏的媒体文件（封板审计·续传尾巴）。
                        file.setLength(0L)
                    }
                    file.seek(downloaded)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReport = System.currentTimeMillis()
                    var windowStartBytes = downloaded
                    var windowStartMillis = lastReport
                    while (true) {
                        if (Cancellations.cancelled(handle)) {
                            emit(
                                DownloadEvent.Terminal(
                                    link.localSnapshot(
                                        target.outputDir,
                                        productName,
                                        downloaded,
                                        total,
                                        TaskStatus.CANCELLED,
                                    ),
                                ),
                            )
                            return@flow
                        }
                        val read = stream.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= LocalFailures.DOWNLOAD_POLL_INTERVAL_MS) {
                            val elapsed = (now - windowStartMillis).coerceAtLeast(1L)
                            val speed = (downloaded - windowStartBytes) * 1000 / elapsed
                            emit(
                                DownloadEvent.Progress(
                                    link.localSnapshot(
                                        target.outputDir,
                                        productName,
                                        downloaded,
                                        total,
                                        TaskStatus.DOWNLOADING,
                                        speed,
                                    ),
                                ),
                            )
                            windowStartBytes = downloaded
                            windowStartMillis = now
                            lastReport = now
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // `.part` 留在原地：这是断点续传的语义，不是要掩盖的失败。
            emit(
                DownloadEvent.Failed(
                    LocalFailures.transport("本地直链传输中断 · ${e.describe()}，已写入 $downloaded 字节可续传"),
                ),
            )
            return@flow
        } finally {
            Cancellations.clear(handle)
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            emit(DownloadEvent.Failed(LocalFailures.disk("改名落地失败")))
            return@flow
        }
        emit(
            DownloadEvent.Terminal(
                link.localSnapshot(target.outputDir, productName, downloaded, downloaded, TaskStatus.DONE),
            ),
        )
    }
        // 收尾只补一行汇总：这一轮真实拦了几次、注了几次（重复行已被按域名抑制）。
        // 成功、失败、取消都走到这里，否则「抑制掉的次数」就成了无人认领的缺口。
        .onCompletion { cookies.finish() }
        .flowOn(Dispatchers.IO)

    /**
     * HLS 分支：先读清单（这一步就能拿到 403，因此会在写盘之前停下），
     * 再逐段拼接。取消/失败会删掉 `.part`——HLS 没有可续传的段索引，
     * 留一个半截文件只会让下一次以为能接着下。
     */
    private suspend fun downloadHls(
        target: StartRequest.ViaLocal,
        link: SniffResult,
        handle: String,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        try {
            val plan = when (val prepared = hls.prepare(link) { Cancellations.cancelled(handle) }) {
                is HlsPrepare.Failed -> {
                    emit(DownloadEvent.Failed(prepared.failure))
                    return
                }

                is HlsPrepare.Ready -> prepared.plan
            }
            val productName = target.productName
                ?.let(::sanitizeProductName)
                ?.takeIf { it.isNotBlank() }
                ?.let { withContainer(it, plan.container) }
                ?: withContainer(link.baseName(), plan.container)
            val partFile = java.io.File(target.outputDir, "$productName.part")
            val finalFile = java.io.File(target.outputDir, productName)

            LocalOutputSandbox.reject(partFile, target.allowedRoots)?.let { reason ->
                emit(DownloadEvent.Failed(LocalFailures.outputDirRejected(reason)))
                return
            }

            emit(
                DownloadEvent.Started(
                    link.hlsSnapshot(target.outputDir, productName, 0L, 0, plan.segments.size),
                ),
            )

            var lastReport = System.currentTimeMillis()
            var windowBytes = 0L
            var windowMillis = lastReport
            val outcome = hls.download(
                plan = plan,
                link = link,
                partFile = partFile,
                isCancelled = { Cancellations.cancelled(handle) },
            ) { bytes, done, total ->
                val now = System.currentTimeMillis()
                if (now - lastReport >= LocalFailures.DOWNLOAD_POLL_INTERVAL_MS || done == total) {
                    val elapsed = (now - windowMillis).coerceAtLeast(1L)
                    val speed = (bytes - windowBytes) * 1000 / elapsed
                    emit(
                        DownloadEvent.Progress(
                            link.hlsSnapshot(
                                target.outputDir,
                                productName,
                                bytes,
                                done,
                                total,
                                speedBps = speed,
                            ),
                        ),
                    )
                    windowBytes = bytes
                    windowMillis = now
                    lastReport = now
                }
            }

            when (outcome) {
                is HlsOutcome.Done -> {
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        emit(DownloadEvent.Failed(LocalFailures.disk("改名落地失败")))
                        return
                    }
                    // 一行交代这一趟的真实代价：段数、字节、重试了多少次。
                    // 33 分钟那种慢案场就靠这行判断「是 CDN 限速还是我们这边在反复重试」。
                    audit.log(
                        "hls_download_done",
                        mapOf(
                            "host" to hostOf(link.mediaUrl),
                            "segments" to outcome.segments.toString(),
                            "bytes" to outcome.bytes.toString(),
                            "retries" to outcome.retries.toString(),
                        ),
                    )

                    var terminalName = productName
                    var remuxFallback = false
                    var remuxAudioFallback = false
                    if (plan.container.equals("ts", ignoreCase = true)) {
                        val mp4Name = withContainer(productName, "mp4")
                        val mp4Part = java.io.File(target.outputDir, "$mp4Name.part")
                        val mp4Final = java.io.File(target.outputDir, mp4Name)
                        emit(
                            DownloadEvent.Progress(
                                link.hlsSnapshot(
                                    target.outputDir,
                                    productName,
                                    outcome.bytes,
                                    outcome.segments,
                                    plan.segments.size,
                                    phase = REMUX_PHASE,
                                ),
                            ),
                        )
                        val free = availableBytes(target.outputDir)
                        val result = if (free < outcome.bytes + REMUX_SPACE_MARGIN_BYTES) {
                            RemuxResult.Skipped("disk_low")
                        } else {
                            remuxer.remux(
                                source = finalFile,
                                target = mp4Part,
                                expectedDurationMs = plan.durationSeconds * 1000L,
                                segmentDurationMs = (plan.segmentDurationSeconds * 1000).toLong(),
                                isCancelled = { Cancellations.cancelled(handle) },
                            )
                        }
                        when (result) {
                            is RemuxResult.Done -> {
                                if (result.audioSamples <= 0) {
                                    // 转封装器已承诺 audioSamples > 0；删源之前再守一次，
                                    // 任何一点「可能没声音」都不允许丢掉 `.ts`。
                                    mp4Part.delete()
                                    remuxFallback = true
                                    remuxAudioFallback = true
                                    audit.log(
                                        "hls_remux_skipped",
                                        mapOf(
                                            "host" to hostOf(link.mediaUrl),
                                            "reason" to RemuxAudioGuard.AUDIO_EMPTY,
                                        ) + audioAuditFields(result.sourceAudio, result.outputAudio),
                                    )
                                } else {
                                    if (mp4Final.exists()) mp4Final.delete()
                                    if (mp4Part.renameTo(mp4Final)) {
                                        // 只有 `.mp4` 已经落地，才允许删源 `.ts`。
                                        finalFile.delete()
                                        terminalName = mp4Name
                                        audit.log(
                                            "hls_remux_done",
                                            mapOf(
                                                "host" to hostOf(link.mediaUrl),
                                                "bytes" to result.bytes.toString(),
                                                "ms" to result.durationMs.toString(),
                                                "video" to result.videoSamples.toString(),
                                                "audio" to result.audioSamples.toString(),
                                            ) + audioAuditFields(result.sourceAudio, result.outputAudio),
                                        )
                                    } else {
                                        mp4Part.delete()
                                        remuxFallback = true
                                        audit.log(
                                            "hls_remux_skipped",
                                            mapOf("host" to hostOf(link.mediaUrl), "reason" to "rename_failed"),
                                        )
                                    }
                                }
                            }

                            is RemuxResult.Skipped -> {
                                mp4Part.delete()
                                remuxFallback = true
                                remuxAudioFallback = result.reason in RemuxAudioGuard.AUDIO_FALLBACK_REASONS
                                audit.log(
                                    "hls_remux_skipped",
                                    mapOf(
                                        "host" to hostOf(link.mediaUrl),
                                        "reason" to result.reason,
                                    ) + audioAuditFields(result.sourceAudio, result.outputAudio),
                                )
                            }

                            RemuxResult.Cancelled -> {
                                mp4Part.delete()
                                finalFile.delete()
                                emit(
                                    DownloadEvent.Terminal(
                                        link.hlsSnapshot(
                                            target.outputDir,
                                            productName,
                                            outcome.bytes,
                                            outcome.segments,
                                            plan.segments.size,
                                            TaskStatus.CANCELLED,
                                        ),
                                    ),
                                )
                                return
                            }
                        }
                    }

                    emit(
                        DownloadEvent.Terminal(
                            link.hlsSnapshot(
                                target.outputDir,
                                terminalName,
                                outcome.bytes,
                                outcome.segments,
                                plan.segments.size,
                                TaskStatus.DONE,
                            ),
                            remuxFallback = remuxFallback,
                            remuxAudioFallback = remuxAudioFallback,
                        ),
                    )
                }

                is HlsOutcome.Cancelled -> {
                    partFile.delete()
                    emit(
                        DownloadEvent.Terminal(
                            link.hlsSnapshot(
                                target.outputDir,
                                productName,
                                outcome.bytes,
                                outcome.segments,
                                plan.segments.size,
                                TaskStatus.CANCELLED,
                            ),
                        ),
                    )
                }

                is HlsOutcome.Failed -> {
                    partFile.delete()
                    emit(DownloadEvent.Failed(outcome.failure))
                }
            }
        } finally {
            Cancellations.clear(handle)
        }
    }

    private fun SniffResult.hlsSnapshot(
        outputDir: String,
        productName: String,
        bytes: Long,
        done: Int,
        total: Int,
        status: TaskStatus = TaskStatus.DOWNLOADING,
        speedBps: Long = 0L,
        phase: String = "hls $done/$total",
    ) = localTask(
        link = this,
        outputDir = outputDir,
        productName = productName,
        status = status,
        progress = localHlsProgress(bytes, done, total, speedBps, phase),
    )

    override suspend fun cancel(taskId: String): BackendFailure? {
        Cancellations.request(taskId)
        return null
    }

    /** 打开（或保留）断点，返回已有字节数。 */
    private fun openForWrite(partFile: java.io.File): Long {
        partFile.parentFile?.mkdirs()
        RandomAccessFile(partFile, "rw").close()
        return partFile.length()
    }

    /** 查询不到就当作「不缺空间」：真正写失败时还有一次降级兜底。 */
    private fun availableBytes(dir: String): Long =
        runCatching { StatFs(dir).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /**
     * 转封装审计行里的音频对照：源 `.ts` 用 `src` 前缀、产物用 `out` 前缀，没扫到的那侧整组缺席。
     *
     * 只有数字，没有 URL 也没有字节内容。两边并排摆出来，才能分辨「下载下来就没声音」
     * 和「下载侧齐全、是 muxer 写丢/重铺」。
     */
    private fun audioAuditFields(source: AudioScan?, output: AudioScan?): Map<String, String> =
        (source?.auditMap("src") ?: emptyMap()) + (output?.auditMap("out") ?: emptyMap())

    /** 发一次带 Range 的 GET；返回 `null` 表示服务端回了 416，调用方需要清断点重来。 */
    private fun exchange(link: SniffResult, from: Long): okhttp3.Response? {
        val builder = Request.Builder().url(link.mediaUrl)
        if (from > 0L) builder.header("Range", "bytes=$from-")
        SniffHeaderPolicy.auditStripped(audit, link.headersHint)
        SniffHeaderPolicy.sanitize(link.headersHint).forEach { (name, value) ->
            builder.header(name, value)
        }
        // Cookie 只从这里来：页面侧 CookieStore → 本次本地 GET。绝不进 headersHint。
        cookies.cookieFor(link.mediaUrl)?.let { cookie ->
            builder.header("Cookie", cookie)
            audit.log("cookie_injected", mapOf("host" to hostOf(link.mediaUrl), "cookie" to "present"))
        }
        val response = http.newCall(builder.build()).execute()
        if (response.code == 416) {
            response.close()
            return null
        }
        return response
    }

    private fun SniffResult.toDirectFormat(): FormatInfo = FormatInfo(
        formatId = localTaskId(mediaUrl),
        container = container,
        resolution = Resolution(width = 0, height = estimatedQuality.height),
        bitrateKbps = estimatedQuality.bitrateKbps,
        codec = Codec(video = "unknown", audio = "unknown"),
        hasAudio = true,
        hasVideo = type != MediaStreamType.AUDIO,
        fileSizeEstimateBytes = 0L,
        source = FormatSource.SNIFF,
    )

    private fun SniffResult.localSnapshot(
        outputDir: String,
        productName: String,
        bytes: Long,
        total: Long,
        status: TaskStatus = TaskStatus.DOWNLOADING,
        speedBps: Long = 0L,
    ) = localTask(
        link = this,
        outputDir = outputDir,
        productName = productName,
        status = status,
        progress = localProgress(bytes, total, speedBps),
    )

    /** 产物名只取 URL 路径末段并剥掉查询串；拿不到就用容器名兜一个。 */
    private fun SniffResult.productName(): String {
        val raw = mediaUrl.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val cleaned = raw.filter { it.isLetterOrDigit() || it in ".-_" }.trimStart('.')
        return if (cleaned.contains('.')) cleaned else "$cleaned.${container.ifBlank { "bin" }}"
    }

    private fun SniffResult.isHls(): Boolean =
        type == MediaStreamType.HLS ||
            container.equals("m3u8", ignoreCase = true) ||
            mediaUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** HLS 的产物基名：路径末段去掉扩展名，页面标题优先由调用方传进来。 */
    private fun SniffResult.baseName(): String {
        val raw = mediaUrl.substringBefore('#').substringBefore('?').substringAfterLast('/')
        val cleaned = sanitizeProductName(raw.substringBeforeLast('.'))
        return cleaned.ifBlank { "video" }
    }

    private fun sanitizeProductName(raw: String): String = raw
        .map { ch -> if (ch.isISOControl() || ch in "\\/:*?\"<>|") ' ' else ch }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .take(80)

    private fun withContainer(name: String, container: String): String {
        val ext = container.ifBlank { "bin" }.lowercase()
        val lower = name.lowercase()
        val hit = MEDIA_EXTENSIONS.firstOrNull { lower.endsWith(".$it") }
        return if (hit != null) name.dropLast(hit.length + 1) + "." + ext else "$name.$ext"
    }

    private fun String.isHttpUrl(): Boolean =
        lowercase().startsWith("http://") || lowercase().startsWith("https://")

    private fun IOException.describe(): String = message ?: javaClass.simpleName

    private companion object {
        /** 画质表这一行的“任务”不属于引擎，用一个固定串占位，界面不拿它发请求。 */
        const val LOCAL_TABLE_ID = "local"

        val MEDIA_EXTENSIONS = listOf("ts", "mp4", "m3u8", "mkv", "webm", "mov", "m4v", "mp3", "m4a")

        /** 转封装期间 `.ts` 与 `.mp4` 会同时存在；余量不够就不赌，保留 `.ts`。 */
        const val REMUX_SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        val LOCAL_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        fun hostOf(rawUrl: String): String = rawUrl.toHttpUrlOrNull()?.host ?: "invalid"
    }
}

/**
 * 取消登记表。`start` 与 `cancel` 由界面在两个不同的时机各自构造后端实例，
 * 所以标志必须进程级——按 `localTaskId(mediaUrl)` 对齐，不靠对象身份。
 */
internal object Cancellations {
    private val flags = ConcurrentHashMap<String, Boolean>()

    fun request(handle: String) {
        flags[handle] = true
    }

    fun cancelled(handle: String): Boolean = flags[handle] == true

    fun clear(handle: String) {
        flags.remove(handle)
    }
}
