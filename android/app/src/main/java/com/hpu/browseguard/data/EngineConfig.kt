package com.hpu.browseguard.data

import android.content.Context
import com.hpu.browseguard.data.backend.DownloadExecutor
import com.hpu.browseguard.data.backend.RunMode
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

    /** 取 baseUrl 的 Host（M19 网络安检门的白名单值）；baseUrl 不合法返回 null。 */
    fun httpHostOf(baseUrl: String): String? =
        baseUrl.trim().toHttpUrlOrNull()?.host?.takeIf { it.isNotEmpty() }

    /**
     * 明文 HTTP 连这个 Host 是否值得弹安全警告（M19）。
     *
     * 回环与局域网网段（192.168/10/172.16–31/169.254 链路本地）是引擎的设计
     * 部署面，不警告；其余——公网 IP 字面量、无法判定归属的域名——都按
     * 「公网明文」对待，交给设置页弹一次确认。IPv6 只认回环 `::1`，其余
     * 一律按公网处理（引擎场景不存在公网 IPv6 部署，宁可多问一次）。
     */
    fun isRiskyCleartextHost(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1") return false
        if (h.contains(":")) return true // 其余 IPv6 一律按公网对待
        val parts = h.split('.')
        val nums = parts.mapNotNull { it.toIntOrNull() }
        if (nums.size == 4 && parts.size == 4 && nums.all { it in 0..255 }) {
            val (a, b) = nums[0] to nums[1]
            return when {
                a == 127 || a == 10 || a == 192 && b == 168 -> false
                a == 172 && b in 16..31 -> false
                a == 169 && b == 254 -> false
                else -> true
            }
        }
        // 非 IP 字面量（域名）：引擎部署不会用域名，明文连域名按公网对待。
        return true
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

    /**
     * 嗅探与下载的统一模式（M23）。
     *
     * 这里只有这一个真值：嗅探侧与下载侧的「执行者」都是它的派生读法，
     * 不存在「手机嗅探 + 电脑下载」这种半侧组合。
     */
    var mode: RunMode

    /** 嗅探侧执行者，由 [mode] 派生；只读，写入请改 [mode]。 */
    val sniffExecutor: SniffExecutor
        get() = mode.sniffExecutor

    /** 下载侧执行者，由 [mode] 派生；只读，写入请改 [mode]。 */
    val downloadExecutor: DownloadExecutor
        get() = mode.downloadExecutor

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
 * 手机连 PC 上的引擎走 `adb reverse`，所以默认地址就是回环；局域网部署请配 TLS 后用 `https://`。
 * 地址开放给用户覆盖——M19 起，明文 HTTP 的放行白名单就是这里的 baseUrl
 * Host（`NetworkGuardInterceptor` 每次请求时读），设置页保存公网 IP 会先弹确认。
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
     * 嗅探与下载的模式，默认本机（开箱即用，不需要电脑）。
     *
     * 旧版本把这一项拆成 `sniff_executor` + `download_executor` 两个键，
     * 因此首次读取新键时要按旧键迁移一次——迁移规则见 [RunMode.fromLegacy]。
     * 迁移结果当场写回，下次启动不再走兼容分支。
     */
    override var mode: RunMode
        get() {
            prefs.getString(KEY_RUN_MODE, null)?.let { stored ->
                return RunMode.values().firstOrNull { it.name == stored } ?: RunMode.LOCAL
            }
            val migrated = RunMode.fromLegacy(
                sniff = prefs.getString(KEY_LEGACY_SNIFF_EXECUTOR, null),
                download = prefs.getString(KEY_LEGACY_DOWNLOAD_EXECUTOR, null),
            )
            prefs.edit().putString(KEY_RUN_MODE, migrated.name).apply()
            return migrated
        }
        set(value) {
            prefs.edit().putString(KEY_RUN_MODE, value.name).apply()
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
        const val KEY_RUN_MODE = "run_mode"

        /** M23 之后不再写入，只在首次读取时用于迁移。 */
        const val KEY_LEGACY_DOWNLOAD_EXECUTOR = "download_executor"
        const val KEY_LEGACY_SNIFF_EXECUTOR = "sniff_executor"
        const val KEY_SHOW_PAGE_BY_DEFAULT = "show_page_by_default"
        const val KEY_MUTE_WHILE_SNIFFING = "mute_while_sniffing"
        const val KEY_KEEP_ALIVE = "keep_alive_while_downloading"
    }
}
