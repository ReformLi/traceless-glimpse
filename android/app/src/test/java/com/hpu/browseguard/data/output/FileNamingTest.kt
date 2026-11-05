package com.hpu.browseguard.data.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重命名与发布共用的文件名规则。
 *
 * 这里只测纯函数：真机上的 `renameTo` / SAF 文档改名要 Context，属于界面层验收。
 */
class FileNamingTest {

    @Test
    fun ordinaryNamesAreAccepted() {
        assertNull(FileNaming.invalidReason("Clips"))
        assertNull(FileNaming.invalidReason("第1集-终版.mp4"))
        assertNull(FileNaming.invalidReason("clip_2026-11-05"))
        assertNull("首尾空格会被裁掉，不算非法", FileNaming.invalidReason(" clip "))
    }

    @Test
    fun blankNamesAreRejected() {
        assertNotNull(FileNaming.invalidReason(""))
        assertNotNull(FileNaming.invalidReason("   "))
    }

    @Test
    fun everyIllegalCharacterIsRejected() {
        listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|').forEach { bad ->
            assertNotNull("名称里的 $bad 必须被挡下", FileNaming.invalidReason("clip$bad 1"))
        }
    }

    @Test
    fun controlCharactersAreRejected() {
        assertNotNull(FileNaming.invalidReason("clip\nmp4"))
        assertNotNull(FileNaming.invalidReason("clip\tmp4"))
        assertNotNull("结尾换行也一样挡：裁掉它等于替用户改名字", FileNaming.invalidReason("clip\n"))
    }

    @Test
    fun trailingDotIsRejected() {
        assertNotNull(FileNaming.invalidReason("clip."))
    }

    @Test
    fun overlongNamesAreRejected() {
        assertNull(FileNaming.invalidReason("a".repeat(120)))
        assertNotNull(FileNaming.invalidReason("a".repeat(121)))
    }

    @Test
    fun aFreeNameIsUsedVerbatim() {
        assertEquals("clip.mp4", FileNaming.unique("clip", ".mp4") { false })
    }

    /** 重名追加 `(1)`、`(2)`……与发布时同一套规则，绝不覆盖用户已有文件。 */
    @Test
    fun takenNamesAppendTheFirstFreeIndex() {
        val taken = setOf("clip.mp4", "clip (1).mp4")
        assertEquals("clip (2).mp4", FileNaming.unique("clip", ".mp4") { it in taken })
    }

    @Test
    fun namesWithoutExtensionStillDeduplicate() {
        assertEquals("clip (1)", FileNaming.unique("clip", "") { it == "clip" })
    }

    @Test
    fun anExhaustedListFallsBackToATimestamp() {
        val name = FileNaming.unique("clip", ".mp4") { true }
        assertTrue("全被占用时要退化成时间戳名：$name", name.startsWith("clip-") && name.endsWith(".mp4"))
    }

    @Test
    fun baseAndExtensionSplitKeepsTheDot() {
        assertEquals("clip", FileNaming.baseOf("clip.mp4"))
        assertEquals(".mp4", FileNaming.extensionOf("clip.mp4"))
        assertEquals("a.b", FileNaming.baseOf("a.b.c"))
        assertEquals(".c", FileNaming.extensionOf("a.b.c"))
        assertEquals("clip", FileNaming.baseOf("clip"))
        assertEquals("", FileNaming.extensionOf("clip"))
        assertEquals(".mp4", FileNaming.baseOf(".mp4"))
        assertEquals("", FileNaming.extensionOf(".mp4"))
    }

    /** SAF 发布与重命名共用同一个入口，两处必须给出同一个答案。 */
    @Test
    fun theWholeNameHelperSharesTheRule() {
        val taken = setOf("clip.mp4")
        assertEquals("clip (1).mp4", uniqueFileName("clip.mp4") { it in taken })
    }
}
