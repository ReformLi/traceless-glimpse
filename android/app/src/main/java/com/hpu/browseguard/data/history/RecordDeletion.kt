package com.hpu.browseguard.data.history

import java.io.File

/** 产物此刻落在哪里——删除与移动的所有分支都由它决定。 */
enum class RecordLocation {
    /** 应用专属目录：手机上的文件，App 自己有权直接删。 */
    PRIVATE,

    /** SAF 公开目录：用户相册/文件管理器里的文件，删之前必须让他点头。 */
    PUBLIC,

    /** 引擎（电脑）上的路径：手机上压根没有这个文件。 */
    ENGINE,

    /** 还没有产物：嗅探过但没下成，或下载没落地。 */
    MISSING,
}

/**
 * 产物落点判定。
 *
 * 只有规范化后仍在 [sandboxRoot] 之下的本地路径才算私有目录；其余本地路径
 * （引擎在电脑上的 `E:\Videos\BGV\...`）一律算 ENGINE，任何删除都不许碰。
 */
fun locationOf(outputPath: String?, sandboxRoot: String?): RecordLocation {
    val path = outputPath?.takeIf { it.isNotBlank() } ?: return RecordLocation.MISSING
    if (path.startsWith("content://")) return RecordLocation.PUBLIC
    val root = sandboxRoot?.takeIf { it.isNotBlank() }?.let(::File) ?: return RecordLocation.ENGINE
    return try {
        val rootPath = root.canonicalPath
        val file = File(path).canonicalPath
        if (file == rootPath || file.startsWith(rootPath + File.separator)) {
            RecordLocation.PRIVATE
        } else {
            RecordLocation.ENGINE
        }
    } catch (e: Exception) {
        RecordLocation.ENGINE
    }
}

/** 删除时怎么处理那份文件。 */
enum class DeleteChoice {
    /** 只删记录行，手机里的视频留在原地。 */
    RECORD_ONLY,

    /** 记录和视频一起删。 */
    RECORD_AND_FILE,
}

/**
 * 删除策略。
 *
 * 私有目录是 App 自己的地盘，一次确认就删干净；公开目录里的文件是用户相册的一部分，
 * 必须由他在「留文件」和「连文件一起删」之间亲手选一个。引擎侧与没有产物的行
 * 没有第二个选项——手机上没有文件可删。
 */
object DeletionPolicy {

    /** 公开目录必须弹二选一；其余目录直接按 [defaultChoice] 走。 */
    fun requiresChoice(location: RecordLocation): Boolean = location == RecordLocation.PUBLIC

    fun defaultChoice(location: RecordLocation): DeleteChoice = when (location) {
        RecordLocation.PRIVATE -> DeleteChoice.RECORD_AND_FILE
        RecordLocation.PUBLIC -> DeleteChoice.RECORD_ONLY
        RecordLocation.ENGINE,
        RecordLocation.MISSING,
        -> DeleteChoice.RECORD_ONLY
    }

    /** 这一刀会不会真的去动文件；ENGINE 与 MISSING 永远只动记录。 */
    fun deletesFile(choice: DeleteChoice, location: RecordLocation): Boolean =
        choice == DeleteChoice.RECORD_AND_FILE &&
            (location == RecordLocation.PRIVATE || location == RecordLocation.PUBLIC)

    /**
     * 批量删除的问法：只要挑进来的行里有一条落在公开目录，就必须摆出二选一。
     *
     * 不能按「多数行在哪个目录」来定——一桶里有一颗公开目录的文件，
     * 替他删掉用户相册里的那个视频就是越权。
     */
    fun batchRequiresChoice(locations: List<RecordLocation>): Boolean =
        locations.any(::requiresChoice)
}

/**
 * 手机侧产物的存在性证据。
 *
 * 三种都要分开：`PRESENT` 才能打开/改名/移动，`GONE` 走「外部删除对账」，
 * `REVOKED` 是文件多半还在、只是这个 App 不再被允许进去看——把它说成「文件已不存在」
 * 会让人白跑一趟文件管理器。
 */
enum class ArtifactEvidence { Present, Gone, PermissionLost }

/** 一行记录此刻到底指向什么。 */
enum class ArtifactPresence {
    PRESENT,

    /** 记录说有产物，手机上已经没有这个文件了。 */
    GONE,

    /** 授权失效的 SAF 产物：不能操作，但也不该宣称文件没了。 */
    REVOKED,

    /** 手机上从来没有这个文件：产物在电脑上，或还没落地。 */
    NOT_ON_PHONE,
}

/**
 * 落点 + 一次存在性判定的合成。
 *
 * 文件系统与 `DocumentFile` 由调用方去碰，这里只做判定——JVM 才测得动，
 * 界面也才敢把判定放在「用户点那一下」，而不是每行都 stat 一遍拖慢列表。
 */
fun presenceOf(location: RecordLocation, evidence: ArtifactEvidence): ArtifactPresence = when (location) {
    RecordLocation.ENGINE, RecordLocation.MISSING -> ArtifactPresence.NOT_ON_PHONE
    RecordLocation.PRIVATE -> if (evidence == ArtifactEvidence.Present) {
        ArtifactPresence.PRESENT
    } else {
        ArtifactPresence.GONE
    }

    // 私有目录不存在「授权失效」这回事：拿不到就当文件没了。
    RecordLocation.PUBLIC -> when (evidence) {
        ArtifactEvidence.Present -> ArtifactPresence.PRESENT
        ArtifactEvidence.Gone -> ArtifactPresence.GONE
        ArtifactEvidence.PermissionLost -> ArtifactPresence.REVOKED
    }
}
