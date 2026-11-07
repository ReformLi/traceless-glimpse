package com.hpu.browseguard.data.keepalive

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.hpu.browseguard.BgvApplication
import com.hpu.browseguard.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 下载还在跑时把进程钉住的前台服务。
 *
 * 它一行下载都不做，也不改队列：只订阅 [AppContainer.history] 那张已经每秒写一次的心跳流，
 * 按 [KeepAlivePolicy] 决定继续挂着还是收摊。所以这条流的节拍就是通知的节拍——
 * 加第二个定时器只会让通知和下载页各说各话。
 *
 * 由 [DownloadKeepAliveStarter] 在用户按下「开始下载」的那一刻拉起来；
 * 队列一空就 `stopSelf`，不需要任何界面记得来关它。
 */
class DownloadKeepAliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    private var attached = false
    private var last: KeepAliveSnapshot? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as BgvApplication).container
        DownloadKeepAliveNotification.ensureChannel(this)
        // 第一条心跳还没来之前也得先把通知挂上：5 秒内不调 startForeground，系统直接把服务判死。
        show(KeepAliveSnapshotOf.of(container.downloads.activeCount(), emptyList()), force = true)
        watch?.cancel()
        watch = scope.launch { watchQueue(container) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 心跳流每落一笔重新判一次。
     *
     * 拿记录流当节拍而不是自己数时间：终态那一笔（完成/失败/取消）一定会落库，服务因此
     * 不会漏掉「活儿干完了」；而 `activeCount` 归零才是停的判据，记录里可能滞后的状态不参与决定。
     */
    private suspend fun watchQueue(container: AppContainer) {
        container.history.records.collect { records ->
            val active = container.downloads.activeCount()
            if (!KeepAlivePolicy.shouldRun(container.settings.keepAliveWhileDownloading, active)) {
                stopSelf()
                return@collect
            }
            show(KeepAliveSnapshotOf.of(active, records), force = false)
        }
    }

    /** 内容一个字没变的那一秒不必再贴一次；`force` 只给刚起来那一次，保证 5 秒内挂上。 */
    private fun show(snapshot: KeepAliveSnapshot, force: Boolean) {
        if (!force && attached && snapshot == last) return
        last = snapshot
        val notification = DownloadKeepAliveNotification.build(this, snapshot)
        if (!attached) {
            attached = true
            ServiceCompat.startForeground(
                this,
                DownloadKeepAliveNotification.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            getSystemService(NotificationManager::class.java)
                .notify(DownloadKeepAliveNotification.ID, notification)
        }
    }

    companion object {
        /** 点通知栏那一条：把用户送回下载页。与 `MainActivity` 认的是同一个 action。 */
        const val ACTION_OPEN_DOWNLOADS = "com.hpu.browseguard.action.OPEN_DOWNLOADS"
    }
}
