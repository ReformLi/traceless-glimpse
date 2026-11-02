package com.hpu.browseguard.data

import android.content.Context
import android.os.Environment
import com.hpu.browseguard.data.backend.BackendProvider
import com.hpu.browseguard.data.backend.DownloadBackend
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.HttpDownloader
import com.hpu.browseguard.data.backend.HttpSniffer
import com.hpu.browseguard.data.backend.LocalDirectDownloader
import com.hpu.browseguard.data.backend.LocalWebViewSniffer
import com.hpu.browseguard.data.backend.SniffBackend
import com.hpu.browseguard.data.backend.SniffExecutor

/**
 * 组装根：全仓唯一允许 new 具体后端的地方。
 *
 * 后端按调用时刻的 [settings] 构造——改引擎地址立即生效，与改造前一致；
 * 不做单例，因为本地下载器的取消表本来就在进程级（[LocalDirectDownloader] 的 `Cancellations`）。
 */
class AppContainer(context: Context) : BackendProvider {

    private val app = context.applicationContext
    val settings: EngineSettings = EngineConfig(app)
    private val client = EngineClient()
    private val webViewSniffer = LocalWebViewSniffer()

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
        DownloadExecutor.LOCAL_DIRECT -> LocalDirectDownloader()
    }
}
