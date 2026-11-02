package com.hpu.browseguard.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.hpu.browseguard.R
import com.hpu.browseguard.data.output.mimeOf
import com.hpu.browseguard.shared.model.Task
import java.io.File

/** 打开手机侧文件时的四类失败；引擎侧路径不会走到这里。 */
internal enum class OpenFileProblem { NotFound, OutsideSandbox, NoApp, Permission }

/** 本地直链的产物路径：`outputDir + namingTemplate`（本地快照把产物名放在模板槽位）。 */
internal fun localOutputPath(task: Task): String? {
    val name = task.namingTemplate?.takeIf { it.isNotBlank() } ?: return null
    val dir = task.outputDir.takeIf { it.isNotBlank() } ?: return null
    return File(dir, name).absolutePath
}

/**
 * 打开手机侧产物：应用目录走 FileProvider，SAF 产物直接打开 `content://`。
 * 引擎任务的路径在电脑上，调用方会先禁用按钮，不会走到这里。
 */
internal fun openLocalFile(context: Context, path: String): OpenFileProblem? =
    if (path.startsWith("content://")) {
        openContentUri(context, Uri.parse(path))
    } else {
        openFile(context, File(path))
    }

private fun openFile(context: Context, file: File): OpenFileProblem? {
    if (!file.isFile) return OpenFileProblem.NotFound
    val uri = try {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    } catch (e: IllegalArgumentException) {
        return OpenFileProblem.OutsideSandbox
    }
    return launchView(context, uri, mimeOf(file.name))
}

private fun openContentUri(context: Context, uri: Uri): OpenFileProblem? {
    val document = try {
        DocumentFile.fromSingleUri(context, uri)
    } catch (e: SecurityException) {
        return OpenFileProblem.Permission
    } catch (e: Exception) {
        return OpenFileProblem.NotFound
    } ?: return OpenFileProblem.NotFound
    if (!document.exists()) return OpenFileProblem.NotFound
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?: mimeOf(document.name.orEmpty())
    return launchView(context, uri, type)
}

private fun launchView(context: Context, uri: Uri, type: String): OpenFileProblem? {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        OpenFileProblem.NoApp
    } catch (e: SecurityException) {
        OpenFileProblem.Permission
    }
}

@Composable
internal fun openProblemText(problem: OpenFileProblem): String = stringResource(
    when (problem) {
        OpenFileProblem.NotFound -> R.string.download_open_missing
        OpenFileProblem.OutsideSandbox -> R.string.download_open_outside
        OpenFileProblem.NoApp -> R.string.download_open_no_app
        OpenFileProblem.Permission -> R.string.download_open_permission
    },
)
