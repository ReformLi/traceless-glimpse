package com.hpu.browseguard.data.history

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 删除的每一条分支都由落点决定——这里把矩阵钉死，界面无需再自己猜。 */
class DeletionPolicyTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun safDocumentsCountAsPublicAndEnginePathsAsRemote() {
        val sandbox = folder.newFolder("Download")
        assertEquals(RecordLocation.PUBLIC, locationOf("content://tree/doc/clip.mp4", sandbox.absolutePath))
        assertEquals(RecordLocation.PRIVATE, locationOf(File(sandbox, "clip.mp4").absolutePath, sandbox.absolutePath))
        assertEquals(RecordLocation.ENGINE, locationOf("E:\\Videos\\BGV\\clip.mp4", sandbox.absolutePath))
        assertEquals(RecordLocation.MISSING, locationOf(null, sandbox.absolutePath))
        assertEquals(RecordLocation.MISSING, locationOf("  ", sandbox.absolutePath))
    }

    /** 外部删除对账的前提：文件没了不改落点。落点一退成引擎侧，界面就会把人支去电脑前。 */
    @Test
    fun anExternallyDeletedArtifactKeepsItsPrivateLocation() {
        val sandbox = folder.newFolder("Download")
        val clip = File(sandbox, "clip.mp4").apply { writeText("x") }
        assertEquals(RecordLocation.PRIVATE, locationOf(clip.absolutePath, sandbox.absolutePath))
        assertTrue(clip.delete())
        assertEquals(RecordLocation.PRIVATE, locationOf(clip.absolutePath, sandbox.absolutePath))
    }

    @Test
    fun withoutASandboxEveryLocalPathIsRemote() {
        assertEquals(
            "沙箱根拿不到时，本地路径一律按引擎侧处理，绝不当成自己家目录去删",
            RecordLocation.ENGINE,
            locationOf("/storage/emulated/0/Android/data/com.hpu.browseguard/files/Download/clip.mp4", null),
        )
    }

    /** 越界路径（`..`）规范化后不在沙箱里，就不算私有。 */
    @Test
    fun pathTraversalIsNotPrivateJustBecauseItStartsWithTheSandbox() {
        val sandbox = folder.newFolder("Download")
        val sneaky = File(sandbox, "../outside.mp4").absolutePath
        assertEquals(RecordLocation.ENGINE, locationOf(sneaky, sandbox.absolutePath))
    }

    @Test
    fun onlyPublicNeedsAUserChoice() {
        assertTrue(DeletionPolicy.requiresChoice(RecordLocation.PUBLIC))
        assertFalse("应用专属目录是 App 自己的地盘，一次确认即可", DeletionPolicy.requiresChoice(RecordLocation.PRIVATE))
        assertFalse(DeletionPolicy.requiresChoice(RecordLocation.ENGINE))
        assertFalse(DeletionPolicy.requiresChoice(RecordLocation.MISSING))
    }

    @Test
    fun defaultsMatchTheDirectoryOwnship() {
        assertEquals(DeleteChoice.RECORD_AND_FILE, DeletionPolicy.defaultChoice(RecordLocation.PRIVATE))
        assertEquals("公开目录默认留文件", DeleteChoice.RECORD_ONLY, DeletionPolicy.defaultChoice(RecordLocation.PUBLIC))
        assertEquals(DeleteChoice.RECORD_ONLY, DeletionPolicy.defaultChoice(RecordLocation.ENGINE))
        assertEquals(DeleteChoice.RECORD_ONLY, DeletionPolicy.defaultChoice(RecordLocation.MISSING))
    }

    @Test
    fun onlyPhoneSideFilesCanBeDeletedForReal() {
        assertTrue(DeletionPolicy.deletesFile(DeleteChoice.RECORD_AND_FILE, RecordLocation.PRIVATE))
        assertTrue(DeletionPolicy.deletesFile(DeleteChoice.RECORD_AND_FILE, RecordLocation.PUBLIC))
        assertFalse(DeletionPolicy.deletesFile(DeleteChoice.RECORD_ONLY, RecordLocation.PRIVATE))
        assertFalse(DeletionPolicy.deletesFile(DeleteChoice.RECORD_ONLY, RecordLocation.PUBLIC))
        assertFalse("电脑上文件不在手机里，勾了也没用", DeletionPolicy.deletesFile(DeleteChoice.RECORD_AND_FILE, RecordLocation.ENGINE))
        assertFalse(DeletionPolicy.deletesFile(DeleteChoice.RECORD_AND_FILE, RecordLocation.MISSING))
    }

    /** 批量删除的问法由「这一桶里有没有公开目录的文件」决定，而不是按多数行落在哪。 */
    @Test
    fun onePublicRowInABatchForcesTheTwoChoiceDialog() {
        assertTrue(
            "一桶里有一颗用户相册里的文件，替他删掉就是越权",
            DeletionPolicy.batchRequiresChoice(
                listOf(RecordLocation.PRIVATE, RecordLocation.PRIVATE, RecordLocation.PUBLIC),
            ),
        )
        assertFalse(
            "全在应用专属目录就一次确认，不该拿二选一消遣用户",
            DeletionPolicy.batchRequiresChoice(listOf(RecordLocation.PRIVATE, RecordLocation.PRIVATE)),
        )
        assertFalse(DeletionPolicy.batchRequiresChoice(listOf(RecordLocation.ENGINE, RecordLocation.MISSING)))
        assertFalse("空批次不问", DeletionPolicy.batchRequiresChoice(emptyList()))
    }

    /**
     * 存在性判定的三种「不在」必须分开说。
     *
     * 授权失效的 SAF 产物大概率还在那个文件夹里，把它报成「文件已不存在」
     * 会让人白跑一趟文件管理器；私有目录则没有「授权失效」这回事。
     */
    @Test
    fun revokedPermissionIsNotReportedAsAMissingFile() {
        assertEquals(ArtifactPresence.PRESENT, presenceOf(RecordLocation.PRIVATE, ArtifactEvidence.Present))
        assertEquals(ArtifactPresence.GONE, presenceOf(RecordLocation.PRIVATE, ArtifactEvidence.PermissionLost))
        assertEquals(ArtifactPresence.GONE, presenceOf(RecordLocation.PRIVATE, ArtifactEvidence.Gone))

        assertEquals(ArtifactPresence.PRESENT, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.Present))
        assertEquals(ArtifactPresence.REVOKED, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.PermissionLost))
        assertEquals(ArtifactPresence.GONE, presenceOf(RecordLocation.PUBLIC, ArtifactEvidence.Gone))

        assertEquals("电脑上的产物手机上从来没有，不叫「丢了」", ArtifactPresence.NOT_ON_PHONE, presenceOf(RecordLocation.ENGINE, ArtifactEvidence.Gone))
        assertEquals(ArtifactPresence.NOT_ON_PHONE, presenceOf(RecordLocation.MISSING, ArtifactEvidence.Present))
    }
}
