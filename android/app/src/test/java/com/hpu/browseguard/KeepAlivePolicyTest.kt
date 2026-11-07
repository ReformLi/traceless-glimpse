package com.hpu.browseguard

import com.hpu.browseguard.data.keepalive.KeepAlivePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台服务启停判据（M13.1）。
 *
 * 这一条判据决定「进程被冻住」和「省电额度被白占」哪一头发生，所以它必须能被 JVM 说清楚：
 * 只看队列里还有几条活着的流，加上用户那个开关。
 */
class KeepAlivePolicyTest {

    @Test
    fun oneLiveStreamIsEnoughToKeepTheProcessAnchored() {
        assertTrue(KeepAlivePolicy.shouldRun(enabled = true, activeCount = 1))
        assertTrue("并发分片时队列里可能同时有好几条", KeepAlivePolicy.shouldRun(enabled = true, activeCount = 3))
    }

    /**
     * 队列空了就收摊。
     *
     * 等 Cookie 授权那一档也落在这里：协调器挂起等待时已经把订阅从 jobs 里摘出去，
     * 此刻没有任何字节在动，占着 `dataSync` 的时长额度换不到任何进度。
     */
    @Test
    fun anEmptyQueueStopsTheService() {
        assertFalse(KeepAlivePolicy.shouldRun(enabled = true, activeCount = 0))
    }

    /** 用户关掉保活，正在下的那些也不该挂服务——下载照跑，只是没有通知栏。 */
    @Test
    fun theSwitchOutranksTheQueue() {
        assertFalse(KeepAlivePolicy.shouldRun(enabled = false, activeCount = 2))
    }
}
