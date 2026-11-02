package com.hpu.browseguard.data.output

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 手机侧输出位置。`treeUri` 为空表示仍用应用专属目录。 */
data class OutputLocation(
    val treeUri: String? = null,
    val displayName: String? = null,
) {
    val configured: Boolean
        get() = !treeUri.isNullOrBlank()
}

sealed interface OutputAdoptResult {
    data class Adopted(val displayName: String) : OutputAdoptResult
    data class Rejected(val reason: String) : OutputAdoptResult
}

sealed interface OutputPublish {
    /** 没有选 SAF 目录：产物留在应用专属目录，行为与接入前一致。 */
    data object NotConfigured : OutputPublish

    data class Published(val uri: String, val name: String) : OutputPublish

    /** 复制失败：应用目录里的文件仍在，调用方必须保留它并让用户看见警告。 */
    data class Failed(val reason: String) : OutputPublish
}

/**
 * SAF 输出目录：用户选中的文件夹 + 持久读写授权。
 *
 * 下载仍先落到应用专属目录（保留 `.part` 断点续传与沙箱），完成后由 [publish] 复制过去；
 * 复制成功才删除 staging，失败则保留 staging 并把原因交回界面，不谎报「已保存」。
 */
class SafOutputDirectory(
    context: Context,
    private val audit: AuditLogger = NoopAuditLogger,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("bgv_output", Context.MODE_PRIVATE)
    private val _location = MutableStateFlow(readLocation())
    val location: StateFlow<OutputLocation> = _location.asStateFlow()

    fun adopt(uri: Uri): OutputAdoptResult {
        val resolver = app.contentResolver
        return try {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val dir = DocumentFile.fromTreeUri(app, uri)
            when {
                dir == null -> {
                    release(uri)
                    OutputAdoptResult.Rejected("无法访问所选文件夹")
                }

                !dir.canWrite() -> {
                    release(uri)
                    OutputAdoptResult.Rejected("所选文件夹不可写")
                }

                else -> {
                    val name = dir.name?.takeIf { it.isNotBlank() }
                        ?: uri.lastPathSegment
                        ?: "已选文件夹"
                    prefs.edit()
                        .putString(KEY_TREE_URI, uri.toString())
                        .putString(KEY_DISPLAY_NAME, name)
                        .apply()
                    _location.value = OutputLocation(uri.toString(), name)
                    audit.log("audit_output_dir_selected", mapOf("name" to name))
                    OutputAdoptResult.Adopted(name)
                }
            }
        } catch (e: SecurityException) {
            OutputAdoptResult.Rejected("系统没有授予该文件夹的持久权限")
        } catch (e: Exception) {
            OutputAdoptResult.Rejected(e.message ?: "无法使用所选文件夹")
        }
    }

    fun forget() {
        _location.value.treeUri?.let { runCatching { release(Uri.parse(it)) } }
        prefs.edit().clear().apply()
        _location.value = OutputLocation()
        audit.log("audit_output_dir_forgot")
    }

    /** 删除 SAF 文档；没有持久授权时安静返回 false，由历史仓库只删记录行。 */
    fun delete(uriString: String): Boolean = runCatching {
        DocumentFile.fromSingleUri(app, Uri.parse(uriString))?.delete() == true
    }.getOrDefault(false)

    suspend fun publish(source: File): OutputPublish = withContext(Dispatchers.IO) {
        val treeUri = _location.value.treeUri ?: return@withContext OutputPublish.NotConfigured
        if (!source.isFile) {
            return@withContext OutputPublish.Failed("应用目录中的产物不存在，未复制")
        }
        val tree = try {
            DocumentFile.fromTreeUri(app, Uri.parse(treeUri))
        } catch (e: Exception) {
            null
        } ?: return@withContext OutputPublish.Failed("所选文件夹已不可用，请在设置中重新选择")
        if (!tree.canWrite()) {
            return@withContext OutputPublish.Failed("所选文件夹的授权已失效，请在设置中重新选择")
        }

        val name = uniqueFileName(source.name) { tree.findFile(it) != null }
        val document = try {
            tree.createFile(mimeOf(name), name)
        } catch (e: Exception) {
            null
        } ?: return@withContext OutputPublish.Failed("无法在所选文件夹创建文件")

        try {
            val out = app.contentResolver.openOutputStream(document.uri)
                ?: throw IOException("无法写入所选文件夹")
            out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        } catch (e: Exception) {
            document.delete()
            audit.log(
                "audit_output_publish",
                mapOf("result" to "failed", "reason" to (e.message ?: "io")),
            )
            return@withContext OutputPublish.Failed(e.message ?: "写入所选文件夹失败")
        }

        // 复制成功才动 staging：失败路径必须留着原文件。
        source.delete()
        File(source.path + ".part").delete()
        audit.log("audit_output_publish", mapOf("result" to "ok", "name" to name))
        OutputPublish.Published(document.uri.toString(), name)
    }

    private fun readLocation(): OutputLocation = OutputLocation(
        treeUri = prefs.getString(KEY_TREE_URI, null),
        displayName = prefs.getString(KEY_DISPLAY_NAME, null),
    )

    private fun release(uri: Uri) {
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private companion object {
        const val KEY_TREE_URI = "saf_tree_uri"
        const val KEY_DISPLAY_NAME = "saf_display_name"
    }
}

/** 同名文件已存在时追加序号，绝不覆盖用户已有文件。 */
internal fun uniqueFileName(name: String, exists: (String) -> Boolean): String {
    if (!exists(name)) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    for (index in 1 until MAX_NAME_ATTEMPTS) {
        val candidate = "$base ($index)$extension"
        if (!exists(candidate)) return candidate
    }
    return "$base-${System.currentTimeMillis()}$extension"
}

private const val MAX_NAME_ATTEMPTS = 1_000

internal fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "ts" -> "video/mp2t"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "aac" -> "audio/aac"
    "opus", "ogg" -> "audio/ogg"
    else -> "*/*"
}
