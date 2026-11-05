package com.hpu.browseguard.data.history

import com.hpu.browseguard.shared.model.Task
import kotlinx.coroutines.flow.Flow

/** 冷启动对账结果，只用于审计与界面提示，不参与状态机。 */
data class ReconcileReport(
    val scanned: Int,
    val interrupted: Int,
    val recovered: Int,
    /** 引擎此刻答不上来、因而原样保留的记录数（状态未知，等下次对账再判）。 */
    val skipped: Int = 0,
    /** 应用专属目录里没人引用、这次直接清掉的文件数。 */
    val orphansRemoved: Int = 0,
)

/** 单条任务回查的裁决，必须把「任务不存在」与「引擎答不上来」分开。 */
sealed interface TaskLookup {
    data class Found(val task: Task) : TaskLookup

    /** 引擎明确回答 404：任务真的不存在。 */
    data object Missing : TaskLookup

    /** 不可达、超时、协议不符或临时错误：事实未知，不能据此写中断。 */
    data object Unknown : TaskLookup
}

/**
 * 下载历史的写入面。
 *
 * 嗅探开始就落一行（此时还没有引擎 taskId），下载阶段再回填 taskId/路径；
 * 冷启动用 [reconcile] 把进程死亡后遗留的进行中记录改成事实状态，
 * 绝不让历史页出现「永远 99%」的假进度。
 */
interface HistoryRepository {
    val records: Flow<List<DownloadRecord>>

    suspend fun beginSniff(id: String, url: String)

    /** 记下引擎侧 taskId（嗅探或下载任务），供冷启动对账回查。 */
    suspend fun attachTask(id: String, taskId: String)

    suspend fun markReady(id: String, format: String?)

    suspend fun markDownloading(
        id: String,
        taskId: String?,
        format: String?,
        outputPath: String?,
    )

    /**
     * 下载心跳：协调器约每秒写一次，下载 Tab 直接 observe 这张表拿进度。
     * 不碰 `updatedAt`，否则任务会被自己的心跳一路顶到列表最上面。
     *
     * [note] 跟着每次心跳整体覆盖（见 [DownloadRecord.note]）：转封装阶段只发一事件、
     * 之后没有心跳，所以那句「正在转封装」能一直挂到终态被 [markDone] 重新判定。
     */
    suspend fun markProgress(
        id: String,
        downloadedBytes: Long,
        bytesTotal: Long,
        speedBps: Long,
        note: String? = null,
    )

    /**
     * 完成。[note] 见 [DownloadRecord.note]：产物没问题但界面仍需提醒时传一个 [RecordNotes] 里的 key，
     * 传 null 表示清空——每一次完成都重新判一次，不给旧提示留位置。
     */
    suspend fun markDone(
        id: String,
        outputPath: String?,
        format: String?,
        sizeBytes: Long? = null,
        note: String? = null,
    )

    /**
     * 同 [markDone]：已完成的记录是终态，实现必须拒绝把它改写成失败——
     * 重试走同一行，而第一次的产物还在盘上。
     *
     * [errorCode] 只收引擎写的 §5 码，本机失败传 null；[errorMessage] 是给人看的那一句。
     */
    suspend fun markFailed(id: String, errorCode: String?, errorMessage: String? = null)

    /** 同 [markFailed]：已完成的记录不因后来的取消而改写。 */
    suspend fun markCancelled(id: String)

    /**
     * 作废一行：授权续跑换了新行，旧行不会再有产物。
     *
     * 没有产物的行**直接删掉**——下载管理里挂一条谁也不认识的「已取消」空行，
     * 比少一条记录更糟；已经写了产物路径的行仍退回「已取消」，产物是用户的东西，不能替他抹掉线索。
     * 已完成的行照旧不动。返回是否真的删掉了行。
     */
    suspend fun abandon(id: String): Boolean

    /**
     * 产物换了名字或位置（重命名 / 移动），文件本身由调用方先搬完再来回填这一行。
     * [title]、[sizeBytes] 传 null 表示保持原样。
     */
    suspend fun updateArtifact(id: String, outputPath: String, title: String?, sizeBytes: Long?)

    /**
     * 删除一条记录。
     *
     * [choice] 决定动不动文件：[DeleteChoice.RECORD_ONLY] 永远只删行；
     * [DeleteChoice.RECORD_AND_FILE] 只在 [DeletionPolicy.deletesFile] 允许的落点上删文件。
     * 还有别的记录指着同一份产物时，任何选择都退化成只删行。
     * 返回是否真的删掉了文件。
     */
    suspend fun delete(id: String, choice: DeleteChoice): Boolean

    /**
     * 清空全部记录，并清理应用专属目录里不再被引用的产物；返回删掉的记录数。
     *
     * SAF 公开目录里的视频**一个都不动**：那是用户相册的一部分，
     * 一次「清空历史」不该替他决定删掉多少个视频。
     */
    suspend fun deleteAll(): Int

    /**
     * 冷启动对账。
     *
     * - 本地句柄或尚未拿到 taskId 的行：进程已死就没人能推进，直接记中断；
     * - [engineReachable] 为 false 时，远端行一律原样保留（状态未知），绝不写成中断；
     * - 可达时按 [lookup] 裁决：[TaskLookup.Missing] 才记中断，[TaskLookup.Unknown] 原样保留。
     */
    suspend fun reconcile(
        engineReachable: Boolean,
        lookup: suspend (taskId: String) -> TaskLookup,
    ): ReconcileReport
}
