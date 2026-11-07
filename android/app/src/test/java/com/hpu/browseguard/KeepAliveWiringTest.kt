package com.hpu.browseguard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活这条链路的接线钉子（M13.1）。
 *
 * 前台服务、通知、PendingIntent 在 JVM 里一律跑不动，能钉的就是源码本身：
 * 清单少了 `dataSync` 是安装即崩，打卡点漏一处是「下载在跑但没有通知」，
 * 这两类都只会在真机上响，所以先把它们钉在编译期之前。
 */
class KeepAliveWiringTest {

    @Test
    fun theManifestCarriesEveryPermissionTheServiceNeeds() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue("前台服务本身要权限", manifest.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue("Android 14 起 dataSync 要单独授权", manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue("通知栏那一条要 POST_NOTIFICATIONS", manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue("组装根必须建在进程里，服务才看得到同一个队列", manifest.contains("""android:name=".BgvApplication""""))
        assertTrue("服务要声明出来", manifest.contains("""android:name=".data.keepalive.DownloadKeepAliveService""""))
        assertTrue("类型就是 dataSync，别写成别的", manifest.contains("""android:foregroundServiceType="dataSync""""))
        assertTrue("服务不对外", manifest.contains("""android:exported="false""""))
    }

    /** 服务只当观察者：底层那三个下载器一次都不许出现在这一层。 */
    @Test
    fun theServiceNeverTouchesTheDownloadItself() {
        val sources = File("$SRC_ROOT/data/keepalive").walkTopDown().filter { it.isFile }.toList()
        assertTrue("keepalive 目录一个文件都没有，说明扫描本身失效了", sources.isNotEmpty())
        val all = sources.joinToString("\n") { it.readText() }

        listOf("LocalDirectDownloader", "HlsDownloader", "TsRemuxer", "DownloadCoordinator(").forEach {
            assertFalse("保活层不许碰 $it", all.contains(it))
        }
        assertTrue("节拍来自记录流那一次心跳，不是新起的定时器", all.contains("history.records.collect"))
        assertFalse("不许再开第二个轮询环", all.contains("delay("))
    }

    @Test
    fun theServiceGoesForegroundWithTheDeclaredTypeAndStopsItself() {
        val service = File("$SRC_ROOT/data/keepalive/DownloadKeepAliveService.kt").readText()

        assertTrue("起来那一刻就得挂通知，5 秒是硬线", service.contains("ServiceCompat.startForeground("))
        assertTrue("挂的类型要和清单一致", service.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC"))
        assertTrue("停的判据只有那一条纯函数", service.contains("KeepAlivePolicy.shouldRun("))
        assertTrue("队列空了自己收摊，不等界面来关", service.contains("stopSelf()"))
        assertTrue("被系统杀掉不该凭空重建：进程都没了，重建也接不上队列", service.contains("START_NOT_STICKY"))
    }

    @Test
    fun theNotificationIsQuietAndLandsOnTheDownloadsTab() {
        val notification = File("$SRC_ROOT/data/keepalive/DownloadKeepAliveNotification.kt").readText()

        assertTrue("渠道不响不弹", notification.contains("NotificationManager.IMPORTANCE_LOW"))
        assertTrue("常驻：用户划不掉，也不该被当成普通通知清掉", notification.contains(".setOngoing(true)"))
        assertTrue("每秒刷新不许出声", notification.contains(".setOnlyAlertOnce(true)"))
        assertTrue("PendingIntent 必须显式可变性，Android 12 起不给就崩", notification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertTrue("活动还活着就复用它，不再叠一层栈", notification.contains("FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertFalse("通知栏一律不放操作按钮：划开列表时误触一次就是一次破坏", notification.contains(".addAction("))
    }

    /** M13.2：通知的进度与措辞——数字在这里算，句子全部留在文案表。 */
    @Test
    fun theNotificationReadsItsNumbersFromTheSnapshotAndItsWordsFromTheTable() {
        val notification = File("$SRC_ROOT/data/keepalive/DownloadKeepAliveNotification.kt").readText()
        val service = File("$SRC_ROOT/data/keepalive/DownloadKeepAliveService.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("有百分比画定量，没有画不确定模式", notification.contains("snapshot.percent ?: 0"))
        assertTrue("总量未知画成不确定进度条，别画一根停在 0 的实心条", notification.contains("snapshot.percent == null"))
        assertTrue("队列里剩几条写进标题，数字由快照给", notification.contains("R.string.keep_alive_title, snapshot.active"))
        assertTrue("快照要在进场那一刻就算一次：5 秒内必须有通知", service.contains("KeepAliveSnapshotOf.of("))
        assertTrue("内容没变不重复刷通知", service.contains("snapshot == last"))

        val referenced = Regex("""R\.string\.(keep_alive_[a-z_]+)""").findAll(notification)
            .map { it.groupValues[1] }.toSet()
        assertTrue("一条文案都没引用，说明扫描本身失效了", referenced.isNotEmpty())
        referenced.forEach {
            assertTrue("$it 没进文案表：措辞只许写死在 strings.xml", strings.contains("""name="$it""""))
        }
    }

    /** 设置页那行开关：默认值、读写口、以及关掉之后谁负责收摊。 */
    @Test
    fun theSwitchIsOnTheSettingsPageAndOnByDefault() {
        val settings = File("$SRC_ROOT/ui/SettingsScreen.kt").readText()
        val viewModel = File("$SRC_ROOT/ui/HomeViewModel.kt").readText()
        val config = File("$SRC_ROOT/data/EngineConfig.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("开关挂在「嗅探与下载」那一张卡里", settings.contains("stringResource(R.string.settings_keep_alive_label)"))
        assertTrue("界面拨的是同一份偏好，不自己存一份", viewModel.contains("settings.keepAliveWhileDownloading = value"))
        assertTrue("默认开：用户下载就是为了切后台，不能让他先去设置里找开关", config.contains("prefs.getBoolean(KEY_KEEP_ALIVE, true)"))
        assertTrue("界面只认状态这一处，不自己留副本", settings.contains("checked = state.keepAliveWhileDownloading"))
        assertFalse("关开关不直接伸手进服务：服务下一次心跳自己收摊", settings.contains("DownloadKeepAliveService"))
        assertTrue("这一行要有一句人话说明白", strings.contains("settings_keep_alive_hint"))
    }

    /** 通知权限：申请一次、被拒不影响保活、被拒之后要说实话并给一条出路。 */
    @Test
    fun aDeniedNotificationPermissionIsToldStraightAndGivenAWayOut() {
        val activity = File("$SRC_ROOT/MainActivity.kt").readText()
        val gate = File("$SRC_ROOT/data/keepalive/NotificationPermission.kt").readText()
        val settings = File("$SRC_ROOT/ui/SettingsScreen.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue("13 以下通知默认就是开的，不打扰", activity.contains("NotificationPermission.needsRuntimeGrant()"))
        assertTrue("申请的就是通知权限", activity.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue("保活关着就不该问", activity.contains("container.settings.keepAliveWhileDownloading"))
        assertTrue("回到前台重读一次：授权弹窗和系统设置页回来都不留旧结论", activity.contains("container.notificationsVisible.value ="))
        assertTrue("读数用系统那把总尺子，含渠道总开关", gate.contains("areNotificationsEnabled()"))
        assertTrue("出路只有系统设置页这一条", gate.contains("ACTION_APP_NOTIFICATION_SETTINGS"))
        assertTrue("被拒时如实说：保活照跑，只是通知栏看不见", settings.contains("R.string.settings_keep_alive_notify_off"))
        assertTrue("并且给出去设置的入口", settings.contains("R.string.settings_keep_alive_open_settings"))
    }

    @Test
    fun theCardIsStruckWhereverWorkEntersTheQueue() {
        val viewModel = File("$SRC_ROOT/ui/HomeViewModel.kt").readText()

        assertEquals(
            "入队、授权续跑、现场打开开关各打一次卡；少一处就是「下载在跑但没有通知」",
            3,
            Regex("keepAlive\\.onRequestStart\\(\\)").findAll(viewModel).count(),
        )
        assertTrue("打开开关时队列空着就别打卡", viewModel.contains("if (value && downloads.activeCount() > 0) keepAlive.onRequestStart()"))
        assertTrue("工厂要把真实现传进来，别让它悄悄用空实现", viewModel.contains("keepAlive: KeepAliveStarter,"))
        assertTrue("传的是组装根那一只", viewModel.contains("settings, backends, engineInfo, audit, history, consent, downloads, keepAlive,"))
    }

    @Test
    fun tappingTheNotificationReachesTheDownloadsTabOnce() {
        val activity = File("$SRC_ROOT/MainActivity.kt").readText()
        val app = File("$SRC_ROOT/ui/BgvApp.kt").readText()

        assertTrue("活动从进程唯一的组装根取容器", activity.contains("(application as BgvApplication).container"))
        assertTrue("冷启动与 onNewIntent 两条路都认这个 action", activity.contains("DownloadKeepAliveService.ACTION_OPEN_DOWNLOADS"))
        assertTrue("界面点亮下载那一格", app.contains("destination = AppDestination.HISTORY"))
        assertTrue("消费完清零：转屏不该再把用户踹一次", app.contains("container.showDownloadsTab.value = false"))
        assertTrue("界面拿的是同一个打卡器", app.contains("container.keepAlive,"))
    }

    /** 判据不看记录状态：记录是心跳写的，会滞后一秒。 */
    @Test
    fun thePredicateIgnoresTheLaggingRecordStatus() {
        val policy = File("$SRC_ROOT/data/keepalive/KeepAlivePolicy.kt").readText()

        assertFalse("判据里不许出现 RecordStatus", policy.contains("RecordStatus"))
        assertTrue("判据只看队列", policy.contains("activeCount: Int"))
    }

    private companion object {
        const val SRC_ROOT = "src/main/java/com/hpu/browseguard"
    }
}
