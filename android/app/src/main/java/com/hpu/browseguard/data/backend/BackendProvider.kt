package com.hpu.browseguard.data.backend

import com.hpu.browseguard.data.output.OutputPublish
import java.io.File

/**
 * 嗅探执行者。
 *
 * 现在没有界面选项：唯一的调用方固定传 [ENGINE]（§21 裁决 2）。
 * [LOCAL_WEBVIEW] 的实现目前是空壳，等第 3 步③接线后才可能被选中。
 */
enum class SniffExecutor {
    ENGINE,
    LOCAL_WEBVIEW,
}

/**
 * 后端组装点。
 *
 * 界面层只依赖这个接口，不 import 任何具体后端；谁 new 实现、按什么设置选，
 * 全部收在组装根里。这样 FakeBackend 才能在 JVM 单测里替换掉真实网络。
 */
interface BackendProvider {

    /** 手机侧唯一允许的写盘根；`null` 表示应用专属外部存储不可用。 */
    val localSandboxRoot: String?

    /**
     * 每次调用按当前设置构造，改引擎地址立即生效（与改造前一致）。
     * 默认值只是省掉调用方的重复实参，不代表嗅探已经可切。
     */
    fun sniff(executor: SniffExecutor = SniffExecutor.ENGINE): SniffBackend

    fun download(executor: DownloadExecutor): DownloadBackend

    /**
     * 手机侧产物落盘后的最终去向：没选 SAF 目录就原样留在应用目录。
     * 默认「未配置」，JVM 单测不为这条路径准备目录也能跑。
     */
    suspend fun publishOutput(source: File): OutputPublish = OutputPublish.NotConfigured
}
