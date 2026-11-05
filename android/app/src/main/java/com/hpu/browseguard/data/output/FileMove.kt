package com.hpu.browseguard.data.output

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import com.hpu.browseguard.data.history.DownloadRecord
import com.hpu.browseguard.data.history.RecordLocation
import com.hpu.browseguard.data.history.locationOf
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 移动的目标位置。 */
sealed interface MoveTarget {

    /** 应用专属目录：手机上其他 App 看不见的沙箱。 */
    data object PrivateSandbox : MoveTarget

    /** 某个 SAF 文件夹（用户在系统选择器里现选的，不一定是下载默认目录）。 */
    data class PublicTree(val treeUri: String) : MoveTarget
}

enum class MoveDirection {
    PRIVATE_TO_PUBLIC,
    PUBLIC_TO_PUBLIC,
    PUBLIC_TO_PRIVATE,
}

/**
 * 这次移动算不算一次真移动。
 *
 * 私有目录内部不存在「移动」（产物名就是它现在的路径），引擎侧产物不在手机上，
 * 两者都返回 null 让界面直接说明原因，而不是跑一遍复制再告诉用户没动。
 */
fun moveDirection(from: RecordLocation, target: MoveTarget): MoveDirection? = when {
    from == RecordLocation.PRIVATE && target is MoveTarget.PublicTree -> MoveDirection.PRIVATE_TO_PUBLIC
    from == RecordLocation.PUBLIC && target is MoveTarget.PublicTree -> MoveDirection.PUBLIC_TO_PUBLIC
    from == RecordLocation.PUBLIC && target is MoveTarget.PrivateSandbox -> MoveDirection.PUBLIC_TO_PRIVATE
    else -> null
}

/** 产物换名字或换位置的结果。[Changed] 里的 [sizeBytes] 为 null 表示沿用记录里已有的数字。 */
sealed interface ArtifactChange {
    data class Changed(val outputPath: String, val name: String, val sizeBytes: Long?) : ArtifactChange
    data class Failed(val reason: String) : ArtifactChange
}

/**
 * 文件与 SAF 文档的实际搬运工。
 *
 * 三条方向都是「先复制成功、再删源」：中途失败时原文件一定还在，
 * 记录行也不会被改写——搬一半、清单却说搬完了，比搬不动更糟。
 * 目标同名一律走 [FileNaming.unique] 追加 `(1)`，绝不覆盖用户已有的文件。
 */
class SafFileMover(
    context: Context,
    private val sandboxRoot: String?,
    private val audit: AuditLogger = NoopAuditLogger,
) : ArtifactMover {
    private val app = context.applicationContext

    override fun permitTarget(treeUri: String): String? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return "所选文件夹地址无法识别"
        val granted = runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!granted) return "系统没有授予该文件夹的持久权限"
        val tree = runCatching { DocumentFile.fromTreeUri(app, uri) }.getOrNull()
            ?: return "无法访问所选文件夹"
        if (!tree.canWrite()) return "所选文件夹不可写"
        audit.log("audit_move_target_adopted", mapOf("name" to tree.name.orEmpty()))
        return null
    }

    override suspend fun rename(record: DownloadRecord, newBaseName: String): ArtifactChange =
        withContext(Dispatchers.IO) {
            val path = record.outputPath ?: return@withContext ArtifactChange.Failed("这条记录还没有产物")
            val invalid = FileNaming.invalidReason(newBaseName)
            if (invalid != null) return@withContext ArtifactChange.Failed(invalid)
            val outcome = when (locationOf(path, sandboxRoot)) {
                RecordLocation.PRIVATE -> renamePrivate(path, newBaseName.trim())
                RecordLocation.PUBLIC -> renamePublic(path, newBaseName.trim())
                RecordLocation.ENGINE -> ArtifactChange.Failed("产物在电脑上，手机改不了它的名字")
                RecordLocation.MISSING -> ArtifactChange.Failed("这条记录还没有产物")
            }
            audit.log(
                "audit_artifact_rename",
                mapOf("result" to if (outcome is ArtifactChange.Changed) "ok" else "failed"),
            )
            outcome
        }

    override suspend fun move(record: DownloadRecord, target: MoveTarget): ArtifactChange =
        withContext(Dispatchers.IO) {
            val path = record.outputPath ?: return@withContext ArtifactChange.Failed("这条记录还没有产物")
            val direction = moveDirection(locationOf(path, sandboxRoot), target)
                ?: return@withContext ArtifactChange.Failed("这一条不能移动到那里")
            val outcome = when (direction) {
                MoveDirection.PRIVATE_TO_PUBLIC -> privateToPublic(path, target)
                MoveDirection.PUBLIC_TO_PUBLIC -> publicToPublic(path, target)
                MoveDirection.PUBLIC_TO_PRIVATE -> publicToPrivate(path)
            }
            audit.log(
                "audit_artifact_move",
                mapOf(
                    "direction" to direction.name,
                    "result" to if (outcome is ArtifactChange.Changed) "ok" else "failed",
                ),
            )
            outcome
        }

    private fun renamePrivate(path: String, newBase: String): ArtifactChange {
        val source = File(path)
        if (!source.isFile) return ArtifactChange.Failed("文件已不在原位置")
        val extension = FileNaming.extensionOf(source.name)
        val unique = FileNaming.unique(newBase, extension) { File(source.parentFile, it).exists() }
        val destination = File(source.parentFile, unique)
        if (!source.renameTo(destination)) return ArtifactChange.Failed("重命名失败，文件可能正被其他应用占用")
        return ArtifactChange.Changed(destination.absolutePath, unique, destination.length().takeIf { it > 0L })
    }

    private fun renamePublic(uriString: String, newBase: String): ArtifactChange {
        val document = fromSingleUri(uriString) ?: return ArtifactChange.Failed("文件已不存在，或授权已失效")
        val current = document.name.orEmpty()
        if (current.isBlank()) return ArtifactChange.Failed("系统没有给出这个文件的名称")
        val extension = FileNaming.extensionOf(current)
        val unique = FileNaming.unique(newBase, extension) { name ->
            document.parentFile?.findFile(name) != null
        }
        val renamed = try {
            document.renameTo(unique)
        } catch (e: Exception) {
            false
        } catch (e: Error) {
            false
        }
        if (!renamed) return ArtifactChange.Failed("重命名失败，所选文件夹可能不允许改名")
        return ArtifactChange.Changed(uriString, unique, document.length().takeIf { it > 0 })
    }

    private fun privateToPublic(path: String, target: MoveTarget): ArtifactChange {
        val source = File(path)
        if (!source.isFile) return ArtifactChange.Failed("文件已不在原位置")
        val tree = treeOf(target) ?: return ArtifactChange.Failed("目标文件夹已不可用，请重新选择")
        val sizeBeforeDelete = source.length().takeIf { it > 0 }
        val copied = SafCopy.intoTree(app, tree, source)
        return when (copied) {
            is SafCopyOutcome.Published -> {
                source.delete()
                File("$path.part").delete()
                ArtifactChange.Changed(copied.uri, copied.name, sizeBeforeDelete ?: copied.sizeBytes)
            }

            is SafCopyOutcome.Failed -> ArtifactChange.Failed(copied.reason)
        }
    }

    private fun publicToPublic(uriString: String, target: MoveTarget): ArtifactChange {
        val source = fromSingleUri(uriString) ?: return ArtifactChange.Failed("文件已不存在，或授权已失效")
        val tree = treeOf(target) ?: return ArtifactChange.Failed("目标文件夹已不可用，请重新选择")
        val name = source.name?.takeIf { it.isNotBlank() } ?: return ArtifactChange.Failed("系统没有给出这个文件的名称")
        val size = source.length().takeIf { it > 0 }
        val resolver = app.contentResolver
        val unique = FileNaming.unique(FileNaming.baseOf(name), FileNaming.extensionOf(name)) {
            tree.findFile(it) != null
        }
        val created = try {
            tree.createFile(mimeOf(unique), unique)
        } catch (e: Exception) {
            null
        } ?: return ArtifactChange.Failed("无法在目标文件夹创建文件")
        try {
            val input = resolver.openInputStream(source.uri) ?: throw IOException("读不到原文件")
            val output = resolver.openOutputStream(created.uri) ?: throw IOException("写不进目标文件夹")
            input.use { from -> output.use { to -> from.copyTo(to) } }
        } catch (e: Exception) {
            created.delete()
            return ArtifactChange.Failed(e.message ?: "复制失败")
        }
        // 复制成功后源文档才删；删不掉（授权只读）就报失败，但新文件已经在了——
        // 让用户自己决定留哪一份，比悄悄留两份或一份都没有更诚实。
        val sourceRemoved = try {
            source.delete()
        } catch (e: Exception) {
            false
        }
        if (!sourceRemoved) return ArtifactChange.Failed("已复制到目标文件夹，但原文件没能删掉，请手动确认")
        return ArtifactChange.Changed(created.uri.toString(), unique, size)
    }

    private fun publicToPrivate(uriString: String): ArtifactChange {
        val root = sandboxRoot?.let(::File) ?: return ArtifactChange.Failed("应用专属目录不可用")
        val source = fromSingleUri(uriString) ?: return ArtifactChange.Failed("文件已不存在，或授权已失效")
        val name = source.name?.takeIf { it.isNotBlank() } ?: return ArtifactChange.Failed("系统没有给出这个文件的名称")
        val unique = FileNaming.unique(FileNaming.baseOf(name), FileNaming.extensionOf(name)) { File(root, it).exists() }
        val destination = File(root, unique)
        try {
            val input = app.contentResolver.openInputStream(source.uri) ?: throw IOException("读不到原文件")
            destination.outputStream().use { to -> input.use { from -> from.copyTo(to) } }
        } catch (e: Exception) {
            destination.delete()
            return ArtifactChange.Failed(e.message ?: "写入应用目录失败")
        }
        val sourceRemoved = try {
            source.delete()
        } catch (e: Exception) {
            false
        }
        if (!sourceRemoved) {
            destination.delete()
            return ArtifactChange.Failed("没能删掉公开目录里的原文件，已回退，未移动")
        }
        return ArtifactChange.Changed(destination.absolutePath, unique, destination.length().takeIf { it > 0 })
    }

    private fun treeOf(target: MoveTarget): DocumentFile? = when (target) {
        is MoveTarget.PublicTree -> runCatching { DocumentFile.fromTreeUri(app, Uri.parse(target.treeUri)) }
            .getOrNull()
            ?.takeIf { it.canWrite() }

        MoveTarget.PrivateSandbox -> null
    }

    private fun fromSingleUri(uriString: String): DocumentFile? = runCatching {
        DocumentFile.fromSingleUri(app, Uri.parse(uriString))?.takeIf { it.exists() }
    }.getOrNull()
}

/** 复制进 SAF 树的结果；[Published] 只说复制，删不删源由调用方决定。 */
internal sealed interface SafCopyOutcome {
    data class Published(val uri: String, val name: String, val sizeBytes: Long?) : SafCopyOutcome
    data class Failed(val reason: String) : SafCopyOutcome
}

/**
 * SAF 树内复制：下载发布与「移动到公开目录」共用同一条规则。
 *
 * 同名追加 `(1)`、创建失败不留半个空文件、写一半就删掉刚建的文件，
 * 这两处要是不一致，用户会在两个地方看到两种不同的重名行为。
 */
internal object SafCopy {

    fun intoTree(context: Context, tree: DocumentFile, source: File): SafCopyOutcome {
        if (!source.isFile) return SafCopyOutcome.Failed("待复制的文件不存在")
        if (!tree.canWrite()) return SafCopyOutcome.Failed("目标文件夹不可写，授权可能已失效")
        val name = FileNaming.unique(FileNaming.baseOf(source.name), FileNaming.extensionOf(source.name)) {
            tree.findFile(it) != null
        }
        val document = try {
            tree.createFile(mimeOf(name), name)
        } catch (e: Exception) {
            null
        } ?: return SafCopyOutcome.Failed("无法在所选文件夹创建文件")
        try {
            val output = context.contentResolver.openOutputStream(document.uri)
                ?: throw IOException("无法写入所选文件夹")
            output.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        } catch (e: Exception) {
            document.delete()
            return SafCopyOutcome.Failed(e.message ?: "写入所选文件夹失败")
        }
        return SafCopyOutcome.Published(document.uri.toString(), name, source.length().takeIf { it > 0 })
    }
}
