package com.hpu.browseguard.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.system.exitProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

private const val SERVER_NAME = "browseguard-vid"
private const val SERVER_VERSION = "0.2.0"

/**
 * MCP Server 入口：stdin/stdout 只承载 JSON-RPC，日志一律走 stderr。
 *
 * 这里不调用 println；stdout 的写入仅发生在 SDK 的 StdioServerTransport 内部。
 */
fun main() {
    configureLogging()
    try {
        val toolset = McpToolset(McpConfig.fromEnv())
        val server = createServer(toolset)
        runBlocking {
            val transport = StdioServerTransport(
                System.`in`.asSource().buffered(),
                System.out.asSink().buffered(),
            )
            val closed = CompletableDeferred<Unit>()
            transport.onClose { closed.complete(Unit) }
            server.createSession(transport)
            closed.await()
        }
    } catch (e: Exception) {
        System.err.println("bgv-mcp 启动失败：${e.message ?: e::class.java.simpleName}")
        exitProcess(1)
    }
}

fun createServer(toolset: McpToolset): Server {
    val server = Server(
        serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = "先调用 sniff 获取 taskId 与格式表，再用 download 选择格式并等待完成；" +
            "probe 查看格式，status 查询进度，cancel 中止任务。",
    )
    registerTools(server, toolset)
    return server
}

/**
 * 必须在任何 Ktor / SDK 日志初始化之前执行：
 * kotlin-logging 的启动横幅默认走 stdout，必须关掉；SLF4J simple 固定绑定到 stderr。
 */
private fun configureLogging() {
    KotlinLoggingConfiguration.logStartupMessage = false
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    System.setProperty("org.slf4j.simpleLogger.showDateTime", "false")
    System.setProperty("org.slf4j.simpleLogger.showThreadName", "false")
}
