package com.hpu.browseguard.data

import android.util.Log

/**
 * 客户端审计行。
 *
 * 只记录客户端自己的决定（忽略了什么、剥掉了什么），不冒充引擎。
 * JVM 单测换一个记录用的假实现就能逐条断言；
 * 真机上 [LogcatAuditLogger] 统一打 `BgvAudit`，用
 * `adb logcat -s BgvAudit` 即可取证。
 */
interface AuditLogger {
    fun log(event: String, fields: Map<String, String> = emptyMap())
}

/** 默认实现：显式表示「这一处不采集」，避免调用方被迫传一个 null。 */
internal object NoopAuditLogger : AuditLogger {
    override fun log(event: String, fields: Map<String, String>) = Unit
}

/**
 * Logcat 实现。单行输出，字段值里的换行/制表符替换掉——
 * 一条审计行被拆成多行会让 `logcat -s` 的取证结果对不上事件名。
 */
class LogcatAuditLogger : AuditLogger {
    override fun log(event: String, fields: Map<String, String>) {
        val line = buildString {
            append(event)
            fields.forEach { (name, value) ->
                append(' ')
                append(name)
                append('=')
                append(value.replace(LINE_BREAK, "_"))
            }
        }
        Log.w(TAG, line)
    }

    companion object {
        const val TAG = "BgvAudit"
        private val LINE_BREAK = Regex("[\\r\\n\\t]")
    }
}
