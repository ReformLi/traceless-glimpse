package com.hpu.browseguard.data

import com.hpu.browseguard.shared.model.HealthResponse
import com.hpu.browseguard.shared.model.SelfcheckResponse

/**
 * 首页只需要的两条引擎事实：活着没有、自检白名单是什么。
 *
 * 单独抽出来是为了让 [com.hpu.browseguard.ui.HomeViewModel] 不再直接 new
 * [EngineClient]——JVM 单测用假实现就能把连接态推到 `Connected`，
 * 不必为了一次状态流转起一台 MockWebServer。
 */
interface EngineInfo {
    suspend fun health(baseUrl: String): HealthResponse
    suspend fun selfcheck(baseUrl: String): SelfcheckResponse
}

class HttpEngineInfo(private val client: EngineClient = EngineClient()) : EngineInfo {
    override suspend fun health(baseUrl: String): HealthResponse = client.health(baseUrl)
    override suspend fun selfcheck(baseUrl: String): SelfcheckResponse = client.selfcheck(baseUrl)
}
