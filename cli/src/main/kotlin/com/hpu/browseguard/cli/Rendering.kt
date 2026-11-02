package com.hpu.browseguard.cli

import com.hpu.browseguard.shared.BgvJson
import com.hpu.browseguard.shared.model.DrmSystem
import com.hpu.browseguard.shared.model.FormatInfo
import com.hpu.browseguard.shared.model.FormatSource
import com.hpu.browseguard.shared.model.MediaStreamType
import com.hpu.browseguard.shared.model.SniffResult
import com.hpu.browseguard.shared.model.Task
import com.hpu.browseguard.shared.model.TaskMode
import com.hpu.browseguard.shared.model.TaskStatus
import java.io.PrintStream
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** CLI 输出通道：结果写 stdout，提示/进度写 stderr。 */
class CliEnv(
    val out: PrintStream,
    val err: PrintStream,
    val tty: Boolean,
) {
    fun style(json: Boolean): Style = Style(enabled = !json && tty)
}

/** 仅对终端着色；管道与 --json 输出保持纯文本。 */
class Style(private val enabled: Boolean) {
    fun bold(text: String) = wrap("\u001B[1m", text)
    fun dim(text: String) = wrap("\u001B[2m", text)
    fun red(text: String) = wrap("\u001B[31m", text)
    fun green(text: String) = wrap("\u001B[32m", text)
    fun yellow(text: String) = wrap("\u001B[33m", text)
    fun cyan(text: String) = wrap("\u001B[36m", text)

    private fun wrap(code: String, text: String) = if (enabled) "$code$text\u001B[0m" else text

    fun status(status: TaskStatus): String = when (status) {
        TaskStatus.DONE -> green(status.wire())
        TaskStatus.FAILED -> red(status.wire())
        TaskStatus.CANCELLED -> yellow(status.wire())
        else -> cyan(status.wire())
    }
}

/**
 * --json 输出：补全默认可空字段，脚本拿到的就是完整协议对象。
 * 与 shared 的 [BgvJson] 严格反序列化不同，这里是纯输出配置。
 */
val CliJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
}

/**
 * 枚举的协议线值一律走序列化器，避免显示层手抄 @SerialName 与协议漂移。
 */
private fun <T> wireValue(serializer: KSerializer<T>, value: T): String =
    BgvJson.encodeToString(serializer, value).trim('"')

fun TaskStatus.wire(): String = wireValue(TaskStatus.serializer(), this)
fun TaskMode.wire(): String = wireValue(TaskMode.serializer(), this)
fun MediaStreamType.wire(): String = wireValue(MediaStreamType.serializer(), this)
fun FormatSource.wire(): String = wireValue(FormatSource.serializer(), this)
fun DrmSystem.wire(): String = wireValue(DrmSystem.serializer(), this)

private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*m")

/** 终端显示宽度：先剥离 ANSI，再按 CJK 双列计，避免彩色单元格把表格撑歪。 */
private fun displayWidth(text: String): Int =
    text.replace(ANSI_ESCAPE, "").codePoints().reduce(0) { acc, cp ->
        acc + when {
            cp in 0x1100..0x115F -> 2
            cp in 0x2E80..0xA4CF -> 2
            cp in 0xAC00..0xD7A3 -> 2
            cp in 0xF900..0xFAFF -> 2
            cp in 0xFE30..0xFE6F -> 2
            cp in 0xFF00..0xFF60 -> 2
            cp in 0xFFE0..0xFFE6 -> 2
            else -> 1
        }
    }

private fun pad(text: String, width: Int): String =
    text + " ".repeat((width - displayWidth(text)).coerceAtLeast(0))

/** UTF-8 字节数的可读表示。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}

private fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max - 1) + "…"

fun renderTable(
    env: CliEnv,
    style: Style,
    headers: List<String>,
    rows: List<List<String>>,
) {
    if (rows.isEmpty()) {
        env.out.println(style.dim("（无数据）"))
        return
    }
    val widths = headers.indices.map { index ->
        maxOf(displayWidth(headers[index]), rows.maxOf { displayWidth(it[index]) })
    }
    env.out.println(
        headers.mapIndexed { index, header -> style.bold(pad(header, widths[index])) }
            .joinToString("  "),
    )
    env.out.println(style.dim(widths.joinToString("  ") { "-".repeat(it) }))
    rows.forEach { row ->
        env.out.println(row.mapIndexed { index, cell -> pad(cell, widths[index]) }.joinToString("  "))
    }
}

fun renderSniffResults(env: CliEnv, style: Style, results: List<SniffResult>) {
    renderTable(
        env,
        style,
        listOf("类型", "容器", "质量", "码率", "DRM", "媒体地址"),
        results.map { result ->
            listOf(
                result.type.wire(),
                result.container,
                result.estimatedQuality.label.ifBlank { "${result.estimatedQuality.height}p" },
                "${result.estimatedQuality.bitrateKbps} kbps",
                if (result.drmDetected) result.drmSystem?.wire() ?: "yes" else "no",
                result.mediaUrl,
            )
        },
    )
}

fun renderFormats(env: CliEnv, style: Style, formats: List<FormatInfo>) {
    renderTable(
        env,
        style,
        listOf("格式ID", "容器", "分辨率", "码率", "视频编码", "音频编码", "类型", "预估体积"),
        formats.map { format ->
            val resolution =
                if (format.resolution.height == 0) "仅音频"
                else "${format.resolution.width}x${format.resolution.height}"
            val kind = when {
                format.hasVideo && format.hasAudio -> "音视频"
                format.hasVideo -> "视频"
                format.hasAudio -> "音频"
                else -> "未知"
            }
            listOf(
                format.formatId,
                format.container,
                resolution,
                "${format.bitrateKbps} kbps",
                format.codec.video,
                format.codec.audio,
                kind,
                formatBytes(format.fileSizeEstimateBytes),
            )
        },
    )
}

fun renderTaskList(env: CliEnv, style: Style, tasks: List<Task>) {
    renderTable(
        env,
        style,
        listOf("ID", "状态", "模式", "进度", "文件", "URL"),
        tasks.map { task ->
            listOf(
                task.id,
                style.status(task.status),
                task.mode.wire(),
                task.progress?.let { String.format(Locale.ROOT, "%.1f%%", it.percent) } ?: "-",
                task.outputPath ?: "-",
                truncate(task.sourceUrl, 56),
            )
        },
    )
}

fun printTaskDetails(env: CliEnv, style: Style, task: Task) {
    env.out.println("${style.bold("ID")}       ${task.id}")
    env.out.println("${style.bold("URL")}      ${task.sourceUrl}")
    env.out.println("${style.bold("模式")}     ${task.mode.wire()}")
    env.out.println("${style.bold("状态")}     ${style.status(task.status)}")
    val progress = task.progress
    if (progress != null) {
        env.out.println(
            "${style.bold("进度")}     " +
                String.format(Locale.ROOT, "%.1f%%", progress.percent) +
                "  ${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.bytesTotal)}" +
                "  ${formatBytes(progress.speedBps)}/s" +
                "  ETA ${progress.etaSeconds}s",
        )
    }
    task.outputPath?.let { env.out.println("${style.bold("文件")}     $it") }
    task.error?.let {
        env.out.println("${style.bold("错误")}     ${style.red(it.code.name)} ${it.message}")
    }
    env.out.println("${style.bold("创建")}     ${task.createdAt}")
    env.out.println("${style.bold("更新")}     ${task.updatedAt}")
}

/** 任务终态的人类可读摘要：只写 stdout。 */
fun printTerminalSummary(env: CliEnv, style: Style, task: Task) {
    env.out.println("${style.bold("任务")} ${task.id}  ${style.status(task.status)}")
    task.outputPath?.let { env.out.println("${style.bold("文件")} $it") }
    val progress = task.progress
    if (progress != null) {
        env.out.println("${style.bold("大小")} ${formatBytes(progress.bytesTotal)}")
    }
    task.error?.let {
        env.out.println("${style.bold("错误")} ${style.red(it.code.name)} ${it.message}")
    }
}
