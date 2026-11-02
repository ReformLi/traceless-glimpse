package com.hpu.browseguard.data

import android.content.Context
import android.os.Environment
import com.hpu.browseguard.data.backend.BackendProvider
import com.hpu.browseguard.data.backend.ConsentCookieProvider
import com.hpu.browseguard.data.backend.DownloadBackend
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.HttpDownloader
import com.hpu.browseguard.data.backend.HttpSniffer
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalWebViewSniffer
import com.hpu.browseguard.data.backend.SniffBackend
import com.hpu.browseguard.data.backend.SniffExecutor
import com.hpu.browseguard.data.cookie.CookieConsentStore
import com.hpu.browseguard.data.cookie.CookieVault
import com.hpu.browseguard.data.cookie.PrefsCookieConsentStore
import com.hpu.browseguard.data.cookie.WebViewCookieVault
import com.hpu.browseguard.data.history.BrowseGuardDatabase
import com.hpu.browseguard.data.history.HistoryRepository
import com.hpu.browseguard.data.history.ReconcileReport
import com.hpu.browseguard.data.history.RoomHistoryRepository
import com.hpu.browseguard.data.history.TaskLookup
import com.hpu.browseguard.data.output.OutputPublish
import com.hpu.browseguard.data.output.SafOutputDirectory
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * 组装根：全仓唯一允许 new 具体后端的地方。
 *
 * 后端按调用时刻的 [settings] 构造——改引擎地址立即生效，与改造前一致；
 * 不做单例，因为本地下载器的取消表本来就在进程级（[LocalDirectDownloader] 的 `Cancellations`）。
 */
class AppContainer(context: Context) : BackendProvider {

    private val app = context.applicationContext
    val settings: EngineSettings = EngineConfig(app)
    val audit: AuditLogger = LogcatAuditLogger()
    private val client = EngineClient()
    val engineInfo: EngineInfo = HttpEngineInfo(client)
    val outputs = SafOutputDirectory(app, audit)

    /** 「这一站的 Cookie 可以读吗」——只有域名和时间，值不在这层。 */
    val cookieConsents: CookieConsentStore = PrefsCookieConsentStore(app, audit)

    /** WebView 的 Cookie 容器；`CookieManager.getInstance()` 到第一次用时再取。 */
    val cookieVault: CookieVault by lazy { WebViewCookieVault(audit = audit) }

    private val webViewSniffer = LocalWebViewSniffer(app, audit, cookieConsents)

    /** 应用专属外部存储不需要任何存储权限，是手机侧能给出的最窄写盘根。 */
    override val localSandboxRoot: String? = try {
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }?.absolutePath
    } catch (e: SecurityException) {
        null
    }

    override fun sniff(executor: SniffExecutor): SniffBackend = when (executor) {
        SniffExecutor.ENGINE -> HttpSniffer(settings.baseUrl, client)
        SniffExecutor.LOCAL_WEBVIEW -> webViewSniffer
    }

    override fun download(executor: DownloadExecutor): DownloadBackend = when (executor) {
        DownloadExecutor.ENGINE -> HttpDownloader(settings.baseUrl, client)
        DownloadExecutor.LOCAL_DIRECT -> LocalDirectDownloader(
            audit = audit,
            cookies = ConsentCookieProvider(cookieVault, cookieConsents, audit),
        )
    }

    override suspend fun publishOutput(source: File): OutputPublish = outputs.publish(source)

    private val database by lazy { BrowseGuardDatabase.build(app) }

    val history: HistoryRepository by lazy {
        RoomHistoryRepository(
            dao = database.downloadRecords(),
            sandboxRoot = localSandboxRoot,
            deleteDocument = outputs::delete,
            audit = audit,
        )
    }

    /**
     * 冷启动对账：把上次进程遗留的进行中记录落到事实状态。
     *
     * 先探一次引擎可达性：「连不上」只代表状态未知，不能据此把任务写成中断；
     * 只有引擎明确回答 404 才证明任务真的不存在。引擎离线时远端行原样保留，
     * 下次冷启动可达后再恢复，避免用户没开电脑就看见满屏「已中断」。
     */
    suspend fun reconcileHistory(): ReconcileReport {
        val reachable = engineReachable()
        val report = history.reconcile(reachable) { taskId -> lookupTask(taskId) }
        if (!reachable) {
            audit.log(
                "audit_history_reconcile_skipped",
                mapOf(
                    "reason" to "engine_unreachable",
                    "preserved" to report.skipped.toString(),
                    "local_interrupted" to report.interrupted.toString(),
                ),
            )
        }
        if (report.scanned > 0) {
            audit.log(
                "audit_history_reconcile",
                mapOf(
                    "scanned" to report.scanned.toString(),
                    "interrupted" to report.interrupted.toString(),
                    "recovered" to report.recovered.toString(),
                    "skipped" to report.skipped.toString(),
                ),
            )
        }
        return report
    }

    private suspend fun engineReachable(): Boolean = try {
        engineInfo.health(settings.baseUrl)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun lookupTask(taskId: String): TaskLookup = try {
        TaskLookup.Found(client.task(settings.baseUrl, taskId)) // one-shot：冷启动对账只查一次，不是轮询环
    } catch (e: CancellationException) {
        throw e
    } catch (e: EngineException) {
        val failure = e.failure
        if (failure is EngineFailure.Protocol && failure.httpStatus == 404) {
            TaskLookup.Missing
        } else {
            TaskLookup.Unknown
        }
    } catch (e: Exception) {
        TaskLookup.Unknown
    }
}
