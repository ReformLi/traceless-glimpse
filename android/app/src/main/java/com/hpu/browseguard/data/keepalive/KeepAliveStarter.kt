package com.hpu.browseguard.data.keepalive

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.EngineSettings
import com.hpu.browseguard.data.NoopAuditLogger

/**
 * 界面把任务交进下载队列那一刻打的卡：让保活服务起来。
 *
 * 单测里给个空实现就够了——这一层唯一的事实是「用户刚刚在这个 App 里按了开始下载」。
 */
fun interface KeepAliveStarter {

    fun onRequestStart()

    companion object {
        val NOOP = KeepAliveStarter { }
    }
}

/**
 * 真实现：开着保活才起服务，起不来也只写审计行。
 *
 * 这张卡必须由用户动作那条路径打，不能由谁在后台观察队列观察出来：Android 12 起
 * 后台应用没有随意起前台服务的资格，`startForegroundService` 会直接抛异常。
 * 而服务起不来绝不等于下载失败——队列那条流照跑，只是这一趟没有保活和通知栏。
 */
class DownloadKeepAliveStarter(
    private val context: Context,
    private val settings: EngineSettings,
    private val audit: AuditLogger = NoopAuditLogger,
) : KeepAliveStarter {

    override fun onRequestStart() {
        if (!settings.keepAliveWhileDownloading) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadKeepAliveService::class.java))
        } catch (e: Exception) {
            audit.log("keep_alive_start_failed", mapOf("reason" to e.javaClass.simpleName))
        }
    }
}
