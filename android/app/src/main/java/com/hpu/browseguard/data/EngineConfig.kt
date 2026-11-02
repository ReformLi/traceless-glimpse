package com.hpu.browseguard.data

import android.content.Context
import com.hpu.browseguard.data.backend.DownloadExecutor
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
}

/**
 * 引擎地址存在 SharedPreferences：只存地址，不含令牌。
 *
 * 手机连 PC 上的引擎走 `adb reverse`，模拟器走 `10.0.2.2`，两者地址不同，故开放给用户覆盖。
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
     * 只切下载执行者——嗅探固定走引擎，所以「引擎离线时本机上有没有媒体可下」
     * 仍然取决于引擎，这是 M4 第 3 步（`LocalWebViewSniffer`）才补的那一段。
     */
    override var downloadExecutor: DownloadExecutor
        get() = DownloadExecutor.values().firstOrNull { it.name == prefs.getString(KEY_DOWNLOAD_EXECUTOR, null) }
            ?: DownloadExecutor.ENGINE
        set(value) {
            prefs.edit().putString(KEY_DOWNLOAD_EXECUTOR, value.name).apply()
        }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_DOWNLOAD_EXECUTOR = "download_executor"
    }
}
