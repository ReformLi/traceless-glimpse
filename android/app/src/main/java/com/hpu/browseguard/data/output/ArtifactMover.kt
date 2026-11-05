package com.hpu.browseguard.data.output

import com.hpu.browseguard.data.history.DownloadRecord

/**
 * 产物搬运的界面视角接口。
 *
 * 界面只关心「改完/搬完之后落点是什么、没成是哪句原因」，不需要知道背后是
 * `File.renameTo` 还是 `DocumentFile`；有了这一层，下载管理页的操作编排能在 JVM 里测。
 */
interface ArtifactMover {

    /** 换成 [newBaseName]（不含扩展名，扩展名沿用现在那个）；成功给出新路径与新文件名。 */
    suspend fun rename(record: DownloadRecord, newBaseName: String): ArtifactChange

    /** 换目录；方向不合法或目标不可用时给回一句原因，绝不留下半个复制品。 */
    suspend fun move(record: DownloadRecord, target: MoveTarget): ArtifactChange

    /**
     * 用户现场选的文件夹能不能当移动目标。
     *
     * 返回 null 表示可以用；否则是给用户看的那句原因。活动结果带的 URI 权限是暂时的，
     * 而搬运跑在 IO 协程里、可能跨过界面重建，所以要先把权限抓牢再开始搬。
     */
    fun permitTarget(treeUri: String): String?
}
