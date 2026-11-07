package com.hpu.browseguard.data.keepalive

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * 「通知栏那一格看得见吗」——和服务在不在跑是两件事。
 *
 * `POST_NOTIFICATIONS` 被拒，或用户在系统里把本应用的通知整条关掉时，前台服务照样保活、
 * 进度照样落库，只是通知栏空着。界面必须把这句话如实说出来，否则用户会以为保活没生效；
 * 这里顺带给一条回到系统设置的路，因为 Android 13 起授权弹窗只给一次机会。
 */
object NotificationPermission {

    /** 该不该问用户要权限：13 以下通知默认就是开的，13 起才需要运行时授权。 */
    fun needsRuntimeGrant(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 通知到底能不能显示出来；系统里的总开关和渠道开关都算在这一个读数里。 */
    fun isVisibleToUser(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 本应用的通知设置页；弹窗机会用掉之后只有这条路能改回来。 */
    fun systemSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
