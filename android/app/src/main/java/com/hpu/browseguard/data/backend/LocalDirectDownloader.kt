package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地直链下载器：单条 HTTP GET 落到手机存储，`.part` 断点续传。
 *
 * 只做这一件事——不碰 yt-dlp（§18.5 已裁决放弃端侧内嵌），也不做 FFmpeg 合并：
 * 直链本身就是完整音视频文件，`postActions` 在这里没有对应动作，界面选了合并也不会
 * 由本实现执行，这条限制写进 `StartRequest.ViaLocal` 的形状里（它压根不收 postActions）。
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
) : DownloadBackend {

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
        val productName = link.productName()
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
    }.flowOn(Dispatchers.IO)

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

    /** 发一次带 Range 的 GET；返回 `null` 表示服务端回了 416，调用方需要清断点重来。 */
    private fun exchange(link: SniffResult, from: Long): okhttp3.Response? {
        val builder = Request.Builder().url(link.mediaUrl).header("Accept", "*/*")
        if (from > 0L) builder.header("Range", "bytes=$from-")
        SniffHeaderPolicy.auditStripped(audit, link.headersHint)
        SniffHeaderPolicy.sanitize(link.headersHint).forEach { (name, value) ->
            builder.header(name, value)
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

    private fun String.isHttpUrl(): Boolean =
        lowercase().startsWith("http://") || lowercase().startsWith("https://")

    private fun IOException.describe(): String = message ?: javaClass.simpleName

    private companion object {
        /** 画质表这一行的“任务”不属于引擎，用一个固定串占位，界面不拿它发请求。 */
        const val LOCAL_TABLE_ID = "local"

        val LOCAL_HTTP = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
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
