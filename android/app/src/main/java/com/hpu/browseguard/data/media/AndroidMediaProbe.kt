package com.hpu.browseguard.data.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hpu.browseguard.data.history.ArtifactEvidence
import com.hpu.browseguard.data.history.ArtifactPresence
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordLocation
import com.hpu.browseguard.data.history.locationOf
import com.hpu.browseguard.data.history.presenceOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 平台探测的外壳：只负责「问文件系统」和「问 retriever」，判定全部交给纯函数。
 *
 * 这一层 JVM 测不到（没有 `MediaMetadataRetriever`），所以被压到最薄：读不到时
 * 宁可返回 null 加一句原因，也不返回 0——详情面板少一行是真话，写一行 `0:00` 是假话。
 */
class AndroidMediaProbe(
    context: Context,
    private val sandboxRoot: String?,
) : MediaProbe {

    private val app = context.applicationContext

    override suspend fun probe(record: DownloadRecord): ArtifactSnapshot = withContext(Dispatchers.IO) {
        val path = record.outputPath
        val location = locationOf(path, sandboxRoot)
        if (path == null || location == RecordLocation.ENGINE || location == RecordLocation.MISSING) {
            return@withContext ArtifactSnapshot(record, ArtifactPresence.NOT_ON_PHONE)
        }
        if (location == RecordLocation.PRIVATE) {
            probePrivate(record, path)
        } else {
            probeDocument(record, path)
        }
    }

    private fun probePrivate(record: DownloadRecord, path: String): ArtifactSnapshot {
        val file = File(path)
        if (!file.isFile) {
            return ArtifactSnapshot(record, presenceOf(RecordLocation.PRIVATE, ArtifactEvidence.Gone))
        }
        val (facts, reason) = readFacts { it.setDataSource(path) }
        return ArtifactSnapshot(
            record = record,
            presence = ArtifactPresence.PRESENT,
            facts = facts,
            fileName = file.name,
            sizeBytes = file.length().takeIf { it > 0L },
            unreadableReason = reason,
        )
    }

    private fun probeDocument(record: DownloadRecord, uriString: String): ArtifactSnapshot {
        val document = try {
            DocumentFile.fromSingleUri(app, Uri.parse(uriString))
        } catch (e: SecurityException) {
            // 文件多半还在用户相册里，只是这份授权被撤了：说成「已删除」会害他白找一遍。
            return ArtifactSnapshot(record, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.PermissionLost))
        } catch (e: Exception) {
            return ArtifactSnapshot(record, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.Gone))
        } ?: return ArtifactSnapshot(record, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.Gone))

        if (!runCatching { document.exists() }.getOrDefault(false)) {
            return ArtifactSnapshot(record, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.Gone))
        }
        val size = runCatching { document.length() }.getOrNull()?.takeIf { it > 0L }
        val name = document.name?.takeIf { it.isNotBlank() }
        val (facts, reason) = readFacts { it.setDataSource(app, document.uri) }
        return ArtifactSnapshot(
            record = record,
            presence = ArtifactPresence.PRESENT,
            facts = facts,
            fileName = name,
            sizeBytes = size,
            unreadableReason = reason,
        )
    }

    /**
     * 打开一次、读三个键、release 掉。
     *
     * 平台对读不出的容器既可能抛异常、也可能三个键全回空：两种都归成
     * 「空事实 + 一句原因」，由详情面板决定怎么向用户解释。
     */
    private fun readFacts(setup: (MediaMetadataRetriever) -> Unit): Pair<MediaFacts?, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            setup(retriever)
            val facts = parseMediaFacts(
                durationMs = retriever.readValue(MediaMetadataRetriever.METADATA_KEY_DURATION),
                width = retriever.readValue(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?: retriever.readValue(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH),
                height = retriever.readValue(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?: retriever.readValue(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT),
            )
            val readable = facts.durationMs != null || facts.width != null || facts.height != null
            (if (readable) facts else null) to if (readable) null else "系统没能读出这个容器的属性"
        } catch (e: Exception) {
            null to (e.message ?: "系统读不出这个文件")
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 一个键读一次。
     *
     * `runCatching` 收的是 Throwable：这台机器的平台若不提供这个重载，
     * 详情面板要退回「读不出来 + 一句原因」，而不是把界面点崩。
     */
    private fun MediaMetadataRetriever.readValue(key: Int): String? =
        runCatching { extractMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }
}
