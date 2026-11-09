package com.hpu.browseguard.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 落地前的探针：确认 compose runtime 的 snapshot state 能在纯 JVM 单测里读写。
 *
 * 本项目的 unit test 只带了 junit / coroutines-test / mockwebserver，没有 Robolectric，
 * 也没有任何 compose 测试依赖；`mutableStateOf` 若在 testRuntimeClasspath 上不可用，
 * 这个文件会直接编译失败或运行抛异常——那就说明状态类不能这么写，必须先回报。
 * 跑绿后此文件保留，作为「状态类可用纯 JVM 单测」的长期证据。
 */
class SnapshotStateProbeTest {

    @Test
    fun mutableStateOfReadsBackWhatWasWritten() {
        var flag by mutableStateOf(false)
        flag = true
        assertEquals(true, flag)
    }
}
