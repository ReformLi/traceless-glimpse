package com.hpu.browseguard.data.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SAF 落点的纯逻辑：命名避让与 MIME 推断，不需要 Android Context。 */
class OutputTargetTest {

    @Test
    fun keepsTheNameWhenNothingElseHasIt() {
        assertEquals("clip.mp4", uniqueFileName("clip.mp4") { false })
    }

    @Test
    fun appendsIndexBeforeTheExtension() {
        val taken = setOf("clip.mp4", "clip (1).mp4")
        assertEquals("clip (2).mp4", uniqueFileName("clip.mp4") { it in taken })
    }

    @Test
    fun handlesNamesWithoutExtension() {
        assertEquals("clip (1)", uniqueFileName("clip") { it == "clip" })
    }

    /** 序号耗尽后必须还能落一个唯一名，而不是覆盖或抛错。 */
    @Test
    fun fallsBackToTimestampWhenEveryCandidateIsTaken() {
        val name = uniqueFileName("clip.mp4") { true }
        assertTrue("回退名应带时间戳：$name", name.startsWith("clip-"))
        assertTrue("扩展名不能丢：$name", name.endsWith(".mp4"))
    }

    @Test
    fun mimeFollowsTheExtension() {
        assertEquals("video/mp4", mimeOf("clip.MP4"))
        assertEquals("video/webm", mimeOf("clip.webm"))
        assertEquals("audio/mpeg", mimeOf("clip.mp3"))
        assertEquals("*/*", mimeOf("clip.unknown"))
    }

    @Test
    fun locationIsOnlyConfiguredWithATreeUri() {
        assertFalse(OutputLocation().configured)
        assertFalse(OutputLocation(treeUri = "  ").configured)
        assertTrue(OutputLocation(treeUri = "content://tree/primary%3ADownload").configured)
    }
}
