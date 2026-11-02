package com.hpu.browseguard.ui

import com.hpu.browseguard.shared.model.ErrorCode
import com.hpu.browseguard.shared.model.TaskStatus

/**
 * 协议 §5 错误码的线上写法。
 *
 * 只用于「拿到一个码字符串后判断它是哪一条」以及 Preview 取样；
 * 本地失败不得用这个反推码——本地失败的类型里根本没有码（见 `LocalTimeoutTest`）。
 * 枚举常量名与线上值完全一致（`ErrorPayload.kt` 的协议约束），所以这里直接取常量名。
 */
fun ErrorCode.wireCode(): String = name

/**
 * 协议 §4 状态机的界面措辞。线名（`awaiting_selection` 之类）继续随文案一起显示，
 * 真机取证的 grep 口径不因中文化而断。
 */
fun TaskStatus.displayLabel(): String = when (this) {
    TaskStatus.QUEUED -> "排队中，等引擎领取"
    TaskStatus.SNIFFING -> "嗅探中"
    TaskStatus.AWAITING_SELECTION -> "等待选择画质"
    TaskStatus.DOWNLOADING -> "下载中"
    TaskStatus.MERGING -> "合并音视频中"
    TaskStatus.POST_PROCESSING -> "后处理中"
    TaskStatus.DONE -> "已完成"
    TaskStatus.CANCELLED -> "已取消"
    TaskStatus.FAILED -> "已失败"
}

/** 协议线上的小写蛇形名，与 `@SerialName` 取值一致。 */
fun TaskStatus.wireName(): String = name.lowercase()

/** 「状态中文 + 线名」一行显示，供界面与 ViewModel 共用。 */
fun TaskStatus.displayWithWire(): String = "${displayLabel()}（${wireName()}）"
