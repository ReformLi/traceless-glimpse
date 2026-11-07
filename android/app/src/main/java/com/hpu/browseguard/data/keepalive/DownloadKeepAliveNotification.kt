package com.hpu.browseguard.data.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.hpu.browseguard.MainActivity
import com.hpu.browseguard.R

/**
 * 保活那一条通知长什么样。
 *
 * 只有一条、常驻、不响不弹：这一条的存在是为了让系统别把进程冻住、并给用户一个回到下载页的
 * 把手，不是为了打扰。所以渠道用 IMPORTANCE_LOW，且每次刷新都 `setOnlyAlertOnce`。
 */
internal object DownloadKeepAliveNotification {

    const val CHANNEL_ID = "download_keep_alive"
    const val ID = 1301

    private const val PROGRESS_MAX = 100

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 一条通知，内容跟着心跳走。
     *
     * 进度条用 `setProgress(100, …)`：总量未知时给不确定进度条，而不是画一根停在 0 的实心条——
     * 后者在通知栏里读起来就是「卡住了」。
     */
    fun build(context: Context, snapshot: KeepAliveSnapshot): Notification {
        val body = when {
            snapshot.current.isNullOrBlank() -> context.getString(R.string.keep_alive_body_preparing)
            snapshot.remuxing -> context.getString(R.string.keep_alive_body_remuxing, snapshot.current)
            snapshot.percent == null -> context.getString(R.string.keep_alive_body_unknown, snapshot.current)
            else -> context.getString(R.string.keep_alive_body_percent, snapshot.current, snapshot.percent)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.keep_alive_title, snapshot.active))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setProgress(PROGRESS_MAX, snapshot.percent ?: 0, snapshot.percent == null)
            .setContentIntent(openDownloadsIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    /**
     * 点通知回到下载页。
     *
     * `CLEAR_TOP|SINGLE_TOP`：活动还活着就把它带回来并走 `onNewIntent`，不为一次点击再叠一层栈；
     * 活动已经不在了（进程被回收）就正常新建，由 `onCreate` 认下这个 action。
     */
    private fun openDownloadsIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = DownloadKeepAliveService.ACTION_OPEN_DOWNLOADS
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}
