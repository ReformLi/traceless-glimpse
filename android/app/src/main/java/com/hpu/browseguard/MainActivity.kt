package com.hpu.browseguard

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.data.keepalive.DownloadKeepAliveService
import com.hpu.browseguard.data.keepalive.NotificationPermission
import com.hpu.browseguard.ui.BgvApp
import com.hpu.browseguard.ui.theme.BgvTheme

class MainActivity : ComponentActivity() {

    /**
     * 组装根不在这个活动里建：前台服务要和界面看到同一个下载队列。
     *
     * 见 [BgvApplication]——活动重建时不再顺手重开一次数据库，服务读到的进度也不会是另一份内存。
     */
    private val container: AppContainer
        get() = (application as BgvApplication).container

    /**
     * 播放器在场时挂上。点 Home/最近任务时只由它决定要不要进小窗：
     * 其他页面没有视频，直接按系统默认行为退到后台。
     */
    var onUserLeavePlayer: (() -> Boolean)? = null

    /** 小窗进出都要告诉播放器，界面才知道该不该收起控制层。 */
    var onPipModeChanged: ((Boolean) -> Unit)? = null

    /** 授权结果不用处理：回来那一刻 onResume 会重新读一次可见性，界面自己把话说对。 */
    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTabFromIntent(intent)
        askNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            BgvTheme {
                BgvApp(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTabFromIntent(intent)
    }

    /**
     * 通知栏那一条点进来的，只点亮「去看下载页」这一个意图。
     *
     * 这里不直接切 Tab：切 Tab 是界面里的 `rememberSaveable` 状态，活动不知道它此刻在不在；
     * 把布尔值交给组装根，界面活着就立刻消费一次，进程刚被回收重建也照样认账。
     */
    private fun openTabFromIntent(intent: Intent?) {
        if (intent?.action == DownloadKeepAliveService.ACTION_OPEN_DOWNLOADS) {
            container.showDownloadsTab.value = true
        }
    }

    /**
     * 只在「下载保活」开着、通知还看不见时问一次。
     *
     * Android 13 起这个弹窗只给一次机会，答过之后再 launch 就是静默回调，不会变成骚扰；
     * 真被拒了也不影响保活本身，界面上会如实给出「去设置」那条路。
     */
    private fun askNotificationPermissionIfNeeded() {
        if (!NotificationPermission.needsRuntimeGrant()) return
        if (!container.settings.keepAliveWhileDownloading) return
        if (NotificationPermission.isVisibleToUser(this)) return
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** 授权弹窗、系统通知设置页回来都走这里：把可见性读回当下，别让用户对着旧结论猜。 */
    override fun onResume() {
        super.onResume()
        container.notificationsVisible.value = NotificationPermission.isVisibleToUser(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onUserLeavePlayer?.invoke()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        onPipModeChanged?.invoke(isInPictureInPictureMode)
    }

    /** 「最近使用时间」只在内存里滚，切后台这一刀才落盘（M6-5 D3）。 */
    override fun onStop() {
        super.onStop()
        container.cookieConsents.flushUsage()
    }
}
