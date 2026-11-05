package com.hpu.browseguard.ui

import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordLocation
import com.hpu.browseguard.data.history.RecordStatus
import com.hpu.browseguard.data.history.locationOf
import com.hpu.browseguard.data.output.ArtifactChange
import java.util.Locale

/** 下载管理页的状态过滤栏。 */
enum class DownloadFilter {
    ALL,

    /** 还在跑的：嗅探中 + 下载中。 */
    ACTIVE,
    DONE,

    /** 没成的：失败、取消、以及进程死亡留下的已中断。 */
    FAILED,
}

/**
 * 过滤 + 排序。
 *
 * 一律按 `updatedAt` 倒序——「最近有动静的在最上面」才是文件清单要的答案；
 * 一条上周开始、刚才下完的任务不该按创建时间沉到列表底部。
 */
fun filterRecords(records: List<DownloadRecord>, filter: DownloadFilter): List<DownloadRecord> =
    records.asSequence()
        .filter { record ->
            when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> record.status == RecordStatus.DOWNLOADING ||
                    record.status == RecordStatus.SNIFFING

                DownloadFilter.DONE -> record.status == RecordStatus.DONE
                DownloadFilter.FAILED -> record.status == RecordStatus.FAILED ||
                    record.status == RecordStatus.CANCELLED ||
                    record.status == RecordStatus.INTERRUPTED
            }
        }
        .sortedByDescending { it.updatedAt }
        .toList()

/**
 * 顶部容量统计：只汇总记录里已经落定的产物字节数。
 *
 * 没下完的行算 0——半成品随时会被清掉，把它算进「占用」只会让用户以为删了记录能省空间。
 */
fun usedBytes(records: List<DownloadRecord>): Long = records
    .filter { it.status == RecordStatus.DONE }
    .sumOf { it.sizeBytes ?: 0L }

/** 下载中那一行的人读进度：`12.3 MB / 80.0 MB · 4.1 MB/s`；容量与速度沿用首页同一个 [formatBytes]。 */
fun progressLabel(downloadedBytes: Long, bytesTotal: Long, speedBps: Long): String = buildString {
    append(formatBytes(downloadedBytes))
    if (bytesTotal > 0L) {
        append(" / ")
        append(formatBytes(bytesTotal))
    }
    if (speedBps > 0L) {
        append(" · ")
        append(formatBytes(speedBps))
        append("/s")
    }
}

/** 0..1；不知道总量时返回 null，界面据此换成不确定进度条。 */
fun progressFraction(downloadedBytes: Long, bytesTotal: Long): Float? {
    if (bytesTotal <= 0L) return null
    return (downloadedBytes.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

/**
 * 这一行的产物能不能改名/移动：只有已经完成、且落点在手机上（私有目录或 SAF）的行可以。
 *
 * 判定刻意不碰文件系统——列表每行都 stat 一次会把滚动拖成 IO；文件真不在时搬运工
 * 会回一句「文件已不在原位置」，操作汇总照样把这句人话原样交给用户。
 */
fun fileOperable(record: DownloadRecord, sandboxRoot: String?): Boolean =
    record.status == RecordStatus.DONE &&
        when (locationOf(record.outputPath, sandboxRoot)) {
            RecordLocation.PRIVATE, RecordLocation.PUBLIC -> true
            RecordLocation.ENGINE,
            RecordLocation.MISSING,
            -> false
        }

/** 文件操作的措辞：界面按它挑那一句「已删除 / 已移动 / 已改名」。 */
enum class OperationVerb { DELETE, MOVE, RENAME }

/**
 * 一次（批量）文件操作的结果。
 *
 * `skipped` 单算：选中 5 行里有 2 行压根没有手机侧产物（引擎任务、没下完的行），
 * 那既不是失败也不算搬成了——混进成功里像「搬了 5 个」，混进失败里又像搬运出了毛病。
 */
data class OperationReport(
    val verb: OperationVerb,
    val succeeded: Int,
    val skipped: Int = 0,
    val failures: List<String> = emptyList(),
)

/**
 * 把搬运工的返回值压成一份汇总。
 *
 * 失败原因不做二次加工：那一层写出来的已经是给用户看的一句话（「目标文件夹已不可用」
 * 「没能删掉公开目录里的原文件」），在这里换个说法只会丢掉它原本有多具体。
 */
fun reportOf(verb: OperationVerb, changes: List<ArtifactChange>, skipped: Int = 0): OperationReport =
    OperationReport(
        verb = verb,
        succeeded = changes.count { it is ArtifactChange.Changed },
        skipped = skipped,
        failures = changes.mapNotNull { (it as? ArtifactChange.Failed)?.reason },
    )

/**
 * 时长 `14:23`，超过一小时写 `1:02:03`。
 *
 * 读不到就返回 null 让界面省略这一项——写一个 `0:00` 会被读成「这是个大截图」，
 * 而真相只是平台探测没能读出这个容器。
 */
fun durationLabel(durationMs: Long?): String? {
    val total = durationMs?.takeIf { it > 0L } ?: return null
    val seconds = total / 1000
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val rest = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, rest)
    }
}

/** 分辨率 `1280×720`；任何一边缺失或为 0 就整个不写，绝不猜一个。 */
fun resolutionLabel(width: Int?, height: Int?): String? {
    val w = width?.takeIf { it > 0 } ?: return null
    val h = height?.takeIf { it > 0 } ?: return null
    return "$w×$h"
}
