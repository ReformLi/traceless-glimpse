package com.hpu.browseguard.data.download

/**
 * HLS 分段并发（M25）：两端唯一的界限定义与校验入口。
 *
 * 引擎侧 `engine/services/hls_concurrency.py` 必须逐字对齐 DEFAULT/MIN/MAX 与字段名
 * `hlsConcurrency`——差一个数就是「手机存得下、引擎拒收」的悬空配置。
 * 越界一律拒绝而不是夹取：把 9 悄悄改成 8 等于改了用户意图还没留痕。
 *
 * 直链永远不消费这个值（PROTOCOL「流量特征边界」）：它是单连接顺序拉取，
 * 分片并发只作用于 HLS/DASH 分段。
 */
object HlsConcurrency {
    const val DEFAULT = 3
    const val MIN = 1
    const val MAX = 8

    /** 解析用户输入；非数字 / 越界 / 空一律返回 null，不夹取。 */
    fun parse(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in MIN..MAX }
    }

    /** 读取持久化值：旧版本缺失或存储被改坏时回落默认，绝不把非法值传下去。 */
    fun fromStored(value: Int?): Int =
        if (value != null && value in MIN..MAX) value else DEFAULT

    /**
     * 拒绝原因（供审计区分）；合法输入返回 null。范围判断只写在这一处，
     * [parse] 与这里共用 MIN..MAX，界面层不再复制一份 if。
     */
    fun rejectReason(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty() || trimmed.toIntOrNull() == null) return "not_a_number"
        return if (parse(trimmed) == null) "out_of_range" else null
    }
}
