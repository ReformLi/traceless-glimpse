package com.hpu.browseguard.data.keepalive

/**
 * 前台服务的启停判据：只有「用户没关掉保活」和「队列里真有一条在跑的流」两条。
 *
 * 判据刻意只看 DownloadCoordinator.activeCount()，不看历史记录里的状态：
 * 记录是每秒心跳写出来的，终态那一笔落库和协程结束之间总差一步——
 * 按记录判就会出现「下载早完了，通知栏还挂着 99%」，而前台服务多挂一分钟就多占一分钟的省电额度。
 *
 * 等 Cookie 授权那一档也算停：协调器在挂起等待时已经把那条订阅从 jobs 里摘出去，
 * 此刻没有任何字节在动，不该占着 `dataSync` 的时长额度。用户点「同意并继续」时
 * 会再打一次卡（见 KeepAliveStarter），服务自己起来接着跑。
 */
internal object KeepAlivePolicy {

    fun shouldRun(enabled: Boolean, activeCount: Int): Boolean = enabled && activeCount > 0
}
