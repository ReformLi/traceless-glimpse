package com.hpu.browseguard

import com.hpu.browseguard.data.CleartextBlockedByNetworkGuardException
import com.hpu.browseguard.data.EngineClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * M19 网络安检门：`http://` 只放行回环与白名单 Host，其余拦截。
 *
 * 全程离线：DNS 用假解析指到回环，MockWebServer 收到的请求 Host 头
 * 可以是任意外部域名——拦截发生在应用拦截器层，先于 DNS 与 TCP。
 */
class NetworkGuardTest {

    private lateinit var server: MockWebServer

    /** 假 DNS：任何域名都解析到回环，让「外部域名」请求真的能到本地假服务器。 */
    private val loopbackDns = Dns { hostname -> listOf(InetAddress.getByName("127.0.0.1")) }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun clientWith(allowedHost: () -> String?): OkHttpClient =
        EngineClient.defaultHttp(allowedHost)
            .newBuilder()
            .dns(loopbackDns)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

    private fun stubOk() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader(EngineClient.PROTOCOL_HEADER, EngineClient.PROTOCOL_VERSION)
                .body("{}")
                .build(),
        )
    }

    @Test
    fun `non-whitelisted http host is blocked`() {
        val client = clientWith { "192.168.1.50" } // 白名单是引擎 IP，与目标无关
        stubOk() // 真到了服务器反而说明拦截失败
        try {
            client.newCall(Request.Builder().url("http://www.qq.com/path").build())
                .execute()
            fail("非白名单 http 请求未被拦截")
        } catch (e: CleartextBlockedByNetworkGuardException) {
            assertTrue(e.message!!.contains("www.qq.com"))
        }
        assertEquals("拦截必须发生在到达网络之前", 0, server.requestCount)
    }

    @Test
    fun `whitelisted engine host passes through`() {
        val client = clientWith { "engine.example.com" }
        stubOk()
        val resp = client.newCall(
            Request.Builder().url("http://engine.example.com:${server.port}/v1/health").build(),
        ).execute()
        assertEquals(200, resp.code)
        resp.use { it.body.string() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `empty whitelist only allows loopback`() {
        val client = clientWith { null }
        try {
            client.newCall(Request.Builder().url("http://www.qq.com/").build()).execute()
            fail("空白名单时外部 http 请求未被拦截")
        } catch (_: CleartextBlockedByNetworkGuardException) {
            // 预期
        }
        // 回环仍然放行（MockWebServer 的 host 是 localhost/127.0.0.1）
        stubOk()
        val loopback = client.newCall(
            Request.Builder().url(server.url("/v1/health")).build(),
        ).execute()
        assertEquals(200, loopback.code)
        loopback.use { it.body.string() }
    }

    @Test
    fun `loopback hosts are always allowed without whitelist`() {
        val client = clientWith { null }
        assertTrue(com.hpu.browseguard.data.NetworkGuardInterceptor.isLoopbackHost("127.0.0.1"))
        assertTrue(com.hpu.browseguard.data.NetworkGuardInterceptor.isLoopbackHost("127.9.9.9"))
        assertTrue(com.hpu.browseguard.data.NetworkGuardInterceptor.isLoopbackHost("localhost"))
        assertTrue(com.hpu.browseguard.data.NetworkGuardInterceptor.isLoopbackHost("::1"))
        stubOk()
        val resp = client.newCall(
            Request.Builder().url("http://127.0.0.1:${server.port}/").build(),
        ).execute()
        assertEquals(200, resp.code)
        resp.use { it.body.string() }
    }

    @Test
    fun `https requests bypass the guard`() {
        // https 不在安检门管辖内（TLS 自带传输保护）。不 enqueue 响应：
        // 只要抛的不是 Cleartext 异常（连接失败/超时都算），就证明请求走出了安检门。
        val client = clientWith { null }
        try {
            client.newCall(Request.Builder().url("https://www.qq.com/").build()).execute()
            // 真网环境可能成功也可能失败，两者都证明没走拦截路径
        } catch (e: CleartextBlockedByNetworkGuardException) {
            fail("https 不应被安检门拦截")
        } catch (_: java.io.IOException) {
            // 连接层失败：请求已越过安检门（拦截器先于一切网络 IO）
        }
    }

    @Test
    fun `default http client guards with empty whitelist`() {
        // EngineClient.DEFAULT_HTTP 自带空白名单安检门：外部 http 必拦。
        try {
            EngineClient.DEFAULT_HTTP.newBuilder().dns(loopbackDns).build()
                .newCall(Request.Builder().url("http://www.qq.com/").build()).execute()
            fail("DEFAULT_HTTP 未拦截外部 http 请求")
        } catch (_: CleartextBlockedByNetworkGuardException) {
            // 预期
        }
    }
}
