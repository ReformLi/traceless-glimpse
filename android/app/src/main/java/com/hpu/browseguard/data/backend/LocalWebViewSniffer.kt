package com.hpu.browseguard.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 手机侧 WebView 嗅探（空壳）。
 *
 * 真正的 `shouldInterceptRequest` 接线在第 3 步③。现在只保证注入点存在，
 * 且被选中时给出可辨认的本地失败——不静默回空表，也不伪造 §5 协议码。
 */
class LocalWebViewSniffer : SniffBackend {

    override fun sniff(request: SniffRequest): Flow<SniffEvent> = flow {
        emit(SniffEvent.Failed(LocalFailures.webViewUnavailable()))
    }
}
