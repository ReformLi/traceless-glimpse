package com.hpu.browseguard.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.hpu.browseguard.R
import com.hpu.browseguard.shared.model.Task
import java.io.File
import java.util.Locale

/** 打开手机侧文件时的三类失败；引擎侧路径不会走到这里。 */
internal enum class OpenFileProblem { NotFound, OutsideSandbox, NoApp }

/** 本地直链的产物路径：`outputDir + namingTemplate`（本地快照把产物名放在模板槽位）。 */
internal fun localOutputPath(task: Task): String? {
    val name = task.namingTemplate?.takeIf { it.isNotBlank() } ?: return null
    val dir = task.outputDir.takeIf { it.isNotBlank() } ?: return null
    return File(dir, name).absolutePath
}

/**
 * 打开手机侧产物（FileProvider `content://`）。
 * 引擎任务的路径在电脑上，调用方会先禁用按钮，不会走到这里。
 */
internal fun openLocalFile(context: Context, path: String): OpenFileProblem? {
    val file = File(path)
    if (!file.isFile) return OpenFileProblem.NotFound
    val uri = try {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    } catch (e: IllegalArgumentException) {
        return OpenFileProblem.OutsideSandbox
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(file.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        OpenFileProblem.NoApp
    }
}

@Composable
internal fun openProblemText(problem: OpenFileProblem): String = stringResource(
    when (problem) {
        OpenFileProblem.NotFound -> R.string.download_open_missing
        OpenFileProblem.OutsideSandbox -> R.string.download_open_outside
        OpenFileProblem.NoApp -> R.string.download_open_no_app
    },
)

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
