package com.hpu.browseguard.data.backend

/**
 * 客户端自己判出来的失败：措辞与窗口都在这里，`code` 天生缺席。
 *
 * 三条超时的窗口写进文案，界面就能自证「这是本地计时，不是引擎报的错」；
 * 两条空数组判定同理——引擎发了 200，没说失败，替它发一个 §5 码就是造假。
 *
 * 文案与 `LocalTimeoutTest` / `LocalRefusalTest` 的逐字断言同源，改这里必然先红测试。
 */
internal object LocalFailures {
    const val SNIFF_MS = 90_000L
    const val PREPARE_MS = 120_000L
    const val DOWNLOAD_MS = 30 * 60_000L

    const val POLL_INTERVAL_MS = 400L
    const val DOWNLOAD_POLL_INTERVAL_MS = 800L

    fun sniffTimeout(): BackendFailure.Local =
        BackendFailure.Local(LocalReason.TIMEOUT, "嗅探超时 · 客户端本地计时 ${seconds(SNIFF_MS)} 到点")

    fun prepareTimeout(): BackendFailure.Local =
        BackendFailure.Local(LocalReason.TIMEOUT, "画质解析超时 · 客户端本地计时 ${seconds(PREPARE_MS)} 到点")

    fun downloadTimeout(): BackendFailure.Local =
        BackendFailure.Local(
            LocalReason.TIMEOUT,
            "下载超时 · 客户端本地计时 ${seconds(DOWNLOAD_MS)} 到点，任务仍在引擎侧运行",
        )

    fun transport(reason: String): BackendFailure.Local =
        BackendFailure.Local(LocalReason.TRANSPORT, reason)

    fun noSniffResults(): BackendFailure.Local =
        BackendFailure.Local(LocalReason.NO_SNIFF_RESULTS, "未发现可用媒体 · 引擎回空结果表，客户端本地判定")

    fun noFormats(): BackendFailure.Local =
        BackendFailure.Local(LocalReason.NO_FORMATS, "未发现可用画质 · 引擎回空格式表，客户端本地判定")

    /** 本地直链下载器自有判定：措辞一律自证「客户端本地判定」，与 §19.9 同口径。 */
    fun unsupportedLink(what: String): BackendFailure.Local =
        BackendFailure.Local(LocalReason.UNSUPPORTED_LINK, "本地直链下载不了 · $what，客户端本地判定")

    fun linkUnloadable(status: Int): BackendFailure.Local =
        BackendFailure.Local(
            LocalReason.LINK_UNLOADABLE,
            "本地直链取不到内容 · 服务端回 HTTP $status，客户端本地判定",
        )

    fun disk(what: String): BackendFailure.Local =
        BackendFailure.Local(LocalReason.DISK, "本地写入失败 · $what，客户端本地判定")

    /** 写盘前的沙箱判定：**没有产出任何文件**，与「写了但失败」分开说。 */
    fun outputDirRejected(what: String): BackendFailure.Local =
        BackendFailure.Local(LocalReason.OUTPUT_DIR_REJECTED, "本地写入目录未通过沙箱校验 · $what，客户端本地判定")

    /** WebView 嗅探当前不可用；措辞引导用户回到「连接电脑」这条已知可行的路。 */
    fun webViewUnavailable(): BackendFailure.Local =
        BackendFailure.Local(
            LocalReason.WEBVIEW_UNAVAILABLE,
            "手机 WebView 嗅探不可用 · 请连接电脑，客户端本地判定",
        )

    private fun seconds(windowMs: Long): String = "${windowMs / 1000}s"
}
