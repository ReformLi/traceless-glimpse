package com.hpu.browseguard.data.backend

import com.hpu.browseguard.shared.model.ErrorCode
import kotlinx.serialization.json.JsonObject

/**
 * 失败的二元归属：协议码只在「引擎真的这么答」时出现，本地判断一律没有码。
 *
 * [Protocol] 只允许从引擎响应的 `ErrorPayload` 构造（唯一落点见
 * [toBackendFailure]）；客户端拿 `ErrorCode.X` 手工拼一个，屏上就会出现
 * 引擎审计行里查无此码的失败——§19.3 那三条伪造都是这么来的。
 */
sealed interface BackendFailure {

    /** 引擎按 PROTOCOL §5 回了错误对象，字段原样取自响应。 */
    data class Protocol(
        val code: ErrorCode,
        val retryable: Boolean,
        val message: String,
        val detail: JsonObject?,
    ) : BackendFailure

    /** 客户端本地判定：超时、连不上、引擎回空数组——协议里没有对应的码可言。 */
    data class Local(
        val reason: LocalReason,
        val message: String,
        val retryable: Boolean = true,
    ) : BackendFailure
}

/** 本地失败的种类。新增一条就意味着界面上多一种「引擎没说、是我们自己判的」情况。 */
enum class LocalReason {
    TIMEOUT,

    /** 连不上 / 协议版本不符 / 响应体不是协议 JSON：传输层观察，不是引擎的诊断。 */
    TRANSPORT,
    NO_SNIFF_RESULTS,
    NO_FORMATS,

    /** 本地直链下载器拿到的东西它下不了：非 HTTP(S)、或全是 DRM。 */
    UNSUPPORTED_LINK,

    /** 直链服务端回了 4xx/5xx：这是**站点**说的，不是引擎说的，所以仍不带 §5 码。 */
    LINK_UNLOADABLE,

    /** 手机本地写文件失败：目录不可写、空间不足、存储权限被拒。 */
    DISK,

    /**
     * 写盘路径没通过客户端沙箱校验：不在允许的根之内、或根本没有可用的根。
     *
     * 与 [DISK] 分开是因为事实不同——[DISK] 是「试过了，写失败」，
     * 这里是「没试，先拒」，界面文案与排查方向都不一样。
     */
    OUTPUT_DIR_REJECTED,

    /** 手机侧 WebView 嗅探不可用：尚未接线，或该页面只能从 PC 侧嗅。 */
    WEBVIEW_UNAVAILABLE,
}
