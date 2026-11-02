package com.hpu.browseguard.engineclient

import com.hpu.browseguard.shared.Protocol
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

internal data class RecordedRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class StubResponse(
    val status: Int = 200,
    val body: String,
    val protocolVersion: String? = Protocol.PROTOCOL_VERSION,
)

/** 环回 JDK HttpServer：每个请求交给测试指定的 handler，并记录请求现场。 */
internal class TestHttpServer : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val recorded = mutableListOf<RecordedRequest>()

    var handler: (RecordedRequest) -> StubResponse = { StubResponse(body = "{}") }

    val requests: List<RecordedRequest>
        get() = synchronized(recorded) { recorded.toList() }

    init {
        server.createContext("/") { exchange ->
            val request = exchange.record()
            synchronized(recorded) { recorded += request }
            exchange.respond(handler(request))
        }
        server.start()
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)

    private fun HttpExchange.record(): RecordedRequest {
        val body = requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val headers = requestHeaders.entries.associate { (name, values) ->
            name.lowercase() to values.joinToString(",")
        }
        return RecordedRequest(requestMethod, requestURI.toString(), headers, body)
    }

    private fun HttpExchange.respond(response: StubResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        response.protocolVersion?.let { responseHeaders.add(Protocol.PROTOCOL_HEADER, it) }
        responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(response.status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
