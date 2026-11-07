package com.hpu.browseguard.data

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * HTTP 明文「零信任安检门」（M19）。
 *
 * 背景：`network_security_config.xml` 为了让内置浏览器打开全网 HTTP 视频站，
 * 必须**全局**放行明文——它没法在运行时按「当前配置的引擎 IP」动态收窄。
 * 于是把收窄下沉到 OkHttp 应用层：这台 App 里唯一允许主动发 HTTP 请求的
 * 组件是 [EngineClient]，它的每一个请求都会先过这一关。
 *
 * 规则（对 `http://` 请求生效，`https://` 一律放行）：
 * - 回环主机（127.x / localhost / ::1）→ 放行（adb reverse 默认通道）；
 * - Host 等于 [allowedHttpHost] 返回值（用户当前保存的引擎 Host）→ 放行，
 *   这就是「自定义 IP 按需放行」：改设置里的引擎地址，白名单立刻跟着变；
 * - 其余一律拦截，抛 [CleartextBlockedByNetworkGuardException]——即使 App
 *   内部某段代码被注入去请求别的 HTTP 站点，也会在这一层被钉死。
 *
 * [allowedHttpHost] 是惰性求值：每次请求读一次当前配置，而不是构造时快照，
 * 所以设置页保存新地址后无需重建客户端。
 */
class NetworkGuardInterceptor(
    private val allowedHttpHost: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.isHttp && !isLoopbackHost(url.host) && url.host != allowedHttpHost()) {
            throw CleartextBlockedByNetworkGuardException(url)
        }
        return chain.proceed(request)
    }

    companion object {
        /** 回环名单：adb reverse / 本机模拟器通道，永远可用。 */
        private val LOOPBACK = setOf("localhost", "::1", "[::1]")

        fun isLoopbackHost(host: String): Boolean =
            host in LOOPBACK || host.startsWith("127.")
    }
}

/** 明文 HTTP 请求未命中白名单被安检门拦下。 */
class CleartextBlockedByNetworkGuardException(url: HttpUrl) : IOException(
    "网络安检门已拦截明文 HTTP 请求：${url.redact()} " +
        "仅允许回环与当前配置的引擎地址（M19 NetworkGuard）",
)

private val HttpUrl.isHttp: Boolean
    get() = scheme == "http"
