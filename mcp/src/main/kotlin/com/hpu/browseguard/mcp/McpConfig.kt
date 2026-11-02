package com.hpu.browseguard.mcp

/** MCP 进程级配置：引擎地址、轮询间隔与两类超时，均可由环境变量微调。 */
data class McpConfig(
    val defaultEngineUrl: String = DEFAULT_ENGINE_URL,
    val pollIntervalMs: Long = 250,
    val sniffTimeoutMs: Long = 60_000,
    val downloadTimeoutMs: Long = 6 * 60 * 60 * 1000,
) {
    companion object {
        const val DEFAULT_ENGINE_URL = "http://127.0.0.1:8730"

        fun fromEnv(env: Map<String, String> = System.getenv()): McpConfig = McpConfig(
            defaultEngineUrl = env["BGV_ENGINE_URL"]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENGINE_URL,
            pollIntervalMs = env.positiveLong("BGV_MCP_POLL_MS") ?: 250,
            sniffTimeoutMs = env.positiveLong("BGV_MCP_SNIFF_TIMEOUT_MS") ?: 60_000,
            downloadTimeoutMs = env.positiveLong("BGV_MCP_DOWNLOAD_TIMEOUT_MS")
                ?: (6 * 60 * 60 * 1000),
        )

        private fun Map<String, String>.positiveLong(key: String): Long? =
            this[key]?.toLongOrNull()?.takeIf { it > 0 }
    }
}
