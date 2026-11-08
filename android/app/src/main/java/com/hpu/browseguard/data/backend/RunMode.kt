package com.hpu.browseguard.data.backend

/**
 * 嗅探与下载的统一模式（M23）。
 *
 * 历史上这是两个互不相干的开关（「嗅探方式」+「下载执行者」），于是存在
 * 「嗅探在手机 WebView、下载却发给电脑引擎」这种组合：用户在手机上看到结果、
 * 点下载，任务却落到电脑上——他以为本机在干活，电脑那头也看不出是谁发起的。
 *
 * 现在只剩一个模式，嗅探与下载必须同侧：
 * - [LOCAL]：手机 WebView 嗅探 + 手机直链下载，全程不碰电脑；
 * - [ENGINE]：电脑引擎嗅探 + 电脑引擎下载，两步都要引擎在线。
 *
 * [sniffExecutor] / [downloadExecutor] 只是给状态机与后端组装点的只读派生物，
 * 谁都不许再单独配置其中一侧。
 */
enum class RunMode {
    LOCAL,
    ENGINE;

    val sniffExecutor: SniffExecutor
        get() = when (this) {
            LOCAL -> SniffExecutor.LOCAL_WEBVIEW
            ENGINE -> SniffExecutor.ENGINE
        }

    val downloadExecutor: DownloadExecutor
        get() = when (this) {
            LOCAL -> DownloadExecutor.LOCAL_DIRECT
            ENGINE -> DownloadExecutor.ENGINE
        }

    companion object {
        /**
         * 旧偏好迁移（M23 之前存的是两个键）。
         *
         * 以「嗅探方式」为准：那是用户在界面上明确点过的那一格，下载执行者的默认值
         * （引擎）只是历史默认，跟着它走会把从未设置过的用户拖进电脑模式。
         * 嗅探侧没存过 → 本机：合并后的默认就是开箱即用、不需要电脑。
         */
        fun fromLegacy(sniff: String?, download: String?): RunMode = when {
            sniff == ENGINE.name -> ENGINE
            // 只改过下载执行者、且明确选过引擎：用户想要的就是引擎这条腿。
            sniff == null && download == ENGINE.name -> ENGINE
            else -> LOCAL
        }
    }
}
