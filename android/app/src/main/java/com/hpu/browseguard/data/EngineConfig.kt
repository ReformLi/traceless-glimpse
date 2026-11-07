package com.hpu.browseguard.data

import android.content.Context
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.SniffExecutor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 引擎地址校验与持久化。 */
object EngineUrl {
    const val DEFAULT = "http://127.0.0.1:8730"

    /** 归一化为无尾斜杠的 `scheme://host[:port]`；不合法返回 null。 */
    fun normalize(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        if (url.host.isEmpty()) return null
        if (url.pathSegments.any { it.isNotBlank() }) return null
        if (url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }
}

/**
 * 设置读写的最小接口。
 *
 * `HomeViewModel` 只依赖这一层，JVM 单测可以给假实现——不必为了一个地址字段
 * 把整台 Android 运行时拖进单测。
 */
interface EngineSettings {
    val baseUrl: String

    /** 返回 false 表示地址不合法，调用方保持原值并提示。 */
    fun save(raw: String): Boolean

    var downloadExecutor: DownloadExecutor

    /** 嗅探交给电脑引擎还是手机 WebView；默认本机，界面可显式切换。 */
    var sniffExecutor: SniffExecutor

    /** 进入浏览器时是否直接露出网页；默认关闭，只留地址栏与「显示网页」。 */
    var showPageByDefault: Boolean

    /** 嗅探时强制静音网页；默认开启，隐藏深嗅绝不能出声。 */
    var muteWhileSniffing: Boolean

    /** 下载期间挂前台服务保活；默认开启——用户下载就是为了切后台，不能让他先去设置里找开关。 */
    var keepAliveWhileDownloading: Boolean
}

/**
 * 引擎地址存在 SharedPreferences：只存地址，不含令牌。
 *
 * 手机连 PC 上的引擎走 `adb reverse`，所以默认地址就是回环；局域网部署请配 TLS 后用 `https://`
 * （明文只放行回环，见 `res/xml/network_security_config.xml`）。地址开放给用户覆盖。
 */
class EngineConfig(context: Context) : EngineSettings {

    private val prefs = context.applicationContext.getSharedPreferences("bgv_engine", Context.MODE_PRIVATE)

    override val baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, EngineUrl.DEFAULT) ?: EngineUrl.DEFAULT

    /** 返回 false 表示地址不合法，调用方保持原值并提示。 */
    override fun save(raw: String): Boolean {
        val normalized = EngineUrl.normalize(raw) ?: return false
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        return true
    }

    /**
     * 下载交给谁：引擎任务还是本机直链。全局开关，默认引擎。
     *
     * 与 [sniffExecutor] 分开：嗅探可以在手机 WebView 跑、下载仍交给电脑引擎
     * （电脑可达时）；自动降级到「电脑不可达」时两者会一起切到本机。
     */
    override var downloadExecutor: DownloadExecutor
        get() = DownloadExecutor.values().firstOrNull { it.name == prefs.getString(KEY_DOWNLOAD_EXECUTOR, null) }
            ?: DownloadExecutor.ENGINE
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_EXECUTOR, value.name).apply()
        }

    override var sniffExecutor: SniffExecutor
        get() = SniffExecutor.values().firstOrNull { it.name == prefs.getString(KEY_SNIFF_EXECUTOR, null) }
            ?: SniffExecutor.LOCAL_WEBVIEW
        set(value) {
            prefs.edit().putString(KEY_SNIFF_EXECUTOR, value.name).apply()
        }

    override var showPageByDefault: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PAGE_BY_DEFAULT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_PAGE_BY_DEFAULT, value).apply()
        }

    override var muteWhileSniffing: Boolean
        get() = prefs.getBoolean(KEY_MUTE_WHILE_SNIFFING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MUTE_WHILE_SNIFFING, value).apply()
        }

    override var keepAliveWhileDownloading: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()
        }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_DOWNLOAD_EXECUTOR = "download_executor"
        const val KEY_SNIFF_EXECUTOR = "sniff_executor"
        const val KEY_SHOW_PAGE_BY_DEFAULT = "show_page_by_default"
        const val KEY_MUTE_WHILE_SNIFFING = "mute_while_sniffing"
        const val KEY_KEEP_ALIVE = "keep_alive_while_downloading"
    }
}
