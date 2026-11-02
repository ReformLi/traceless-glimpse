package com.hpu.browseguard.data.cookie

import android.content.Context
import com.hpu.browseguard.data.AuditLogger
import com.hpu.browseguard.data.NoopAuditLogger
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 「本机下载可以读这一站的 Cookie 吗」——答案存在这里，值一个都不存。
 *
 * 默认关闭：没有授权记录就是不许读。这里只有域名与时间戳，Cookie 值始终留在
 * WebView 自己的 CookieStore 里，由 `ConsentCookieProvider` 在授权成立后读取。
 * 授权/撤销立即落盘（安全事实不能只活到进程结束），[markUsed] 相反——首次使用写一次
 * 底，之后的更新只改内存，由 [flushUsage] 在生命周期边界统一写（一次 403 重试会连着打好几次）。
 */
interface CookieConsentStore {
    fun isGranted(host: String): Boolean

    fun grant(host: String)

    /** 撤销只收回「读」的许可：下一次访问重新弹窗。Cookie 值要靠一键清除才抹得掉。 */
    fun revoke(host: String)

    /** 已授权域名，按字母序；设置页列的就是这一份。 */
    fun grantedHosts(): List<String>

    /** 最近一次真正被读取注入的时刻（毫秒）；没用过返回 `null`。 */
    fun lastUsedAt(host: String): Long?

    fun markUsed(host: String)

    fun flushUsage()
}

/** 站点的授权键：与 `CookieManager` 一致地按域名（不含端口）算。 */
fun cookieHostOf(rawUrl: String): String = rawUrl.toHttpUrlOrNull()?.host ?: ""

fun CookieConsentStore.isGrantedFor(url: String): Boolean = isGranted(cookieHostOf(url))

/** 默认「谁都没授权」：给不接线授权门槛的构造方（单测、旧调用点）用。 */
internal object DenyAllCookieConsent : CookieConsentStore {
    override fun isGranted(host: String): Boolean = false
    override fun grant(host: String): Unit = Unit
    override fun revoke(host: String): Unit = Unit
    override fun grantedHosts(): List<String> = emptyList()
    override fun lastUsedAt(host: String): Long? = null
    override fun markUsed(host: String): Unit = Unit
    override fun flushUsage(): Unit = Unit
}

/** SharedPreferences 实现：`host:*` 存布尔授权，`used:*` 存最近使用时间。 */
class PrefsCookieConsentStore(
    context: Context,
    private val audit: AuditLogger = NoopAuditLogger,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieConsentStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 内存里的「最近使用时间」：写它不等于写盘。 */
    private val usage = ConcurrentHashMap<String, Long>()

    override fun isGranted(host: String): Boolean =
        host.isNotBlank() && prefs.getBoolean(hostKey(host), false)

    override fun grant(host: String) {
        if (host.isBlank()) return
        prefs.edit().putBoolean(hostKey(host), true).apply()
        usage[host] = now()
        audit.log("audit_cookie_consent_granted", mapOf("host" to host))
    }

    override fun revoke(host: String) {
        if (host.isBlank()) return
        prefs.edit().remove(hostKey(host)).remove(usedKey(host)).apply()
        usage.remove(host)
        audit.log("audit_cookie_consent_revoked", mapOf("host" to host))
    }

    override fun grantedHosts(): List<String> =
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(HOST_PREFIX) }
            .map { it.removePrefix(HOST_PREFIX) }
            .sorted()
            .toList()

    override fun lastUsedAt(host: String): Long? {
        usage[host]?.let { return it }
        return prefs.getLong(usedKey(host), 0L).takeIf { it > 0L }
    }

    override fun markUsed(host: String) {
        if (!isGranted(host)) return
        val millis = now()
        val firstEver = prefs.getLong(usedKey(host), 0L) == 0L
        usage[host] = millis
        // 第一次使用立即落盘：进程被杀（重装/崩溃）之后设置页不该说「尚未使用过」。
        // 之后的更新仍然只改内存，由 flushUsage 在生命周期边界统一写。
        if (firstEver) prefs.edit().putLong(usedKey(host), millis).apply()
    }

    override fun flushUsage() {
        if (usage.isEmpty()) return
        val pending = usage.entries.toList()
        val editor = prefs.edit()
        pending.forEach { (host, millis) -> editor.putLong(usedKey(host), millis) }
        editor.apply()
        // 落盘后仍留在内存里：同一前台周期再打开设置页不该退回旧时间。
        audit.log("audit_cookie_usage_flushed", mapOf("hosts" to pending.size.toString()))
    }

    private fun hostKey(host: String): String = "$HOST_PREFIX$host"

    private fun usedKey(host: String): String = "$USED_PREFIX$host"

    private companion object {
        const val PREFS_NAME = "bgv_cookie"
        const val HOST_PREFIX = "host:"
        const val USED_PREFIX = "used:"
    }
}
