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

    suspend fun markDone(id: String, outputPath: String?, format: String?)

    /**
     * 记一次失败。[markDone] 之后的记录是终态，实现必须拒绝把它改写成失败——
     * 重试走同一行，而第一次的产物还在盘上。
     */
    suspend fun markFailed(id: String, errorCode: String?)

    /** 同 [markFailed]：已完成的记录不因后来的取消而改写。 */
    suspend fun markCancelled(id: String)

    /**
     * 删除一条记录并尽力清理沙箱内的本地文件。
     * 返回是否真的删掉了文件；记录行本身一定删除。
     */
    suspend fun delete(id: String): Boolean

    /** 清空全部记录，并清理应用目录里不再被引用的产物；返回删掉的记录数。 */
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
