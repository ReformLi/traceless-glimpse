package com.hpu.browseguard

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hpu.browseguard.data.LogcatAuditLogger
import com.hpu.browseguard.data.backend.LocalWebViewSniffer
import com.hpu.browseguard.data.backend.SniffEvent
import com.hpu.browseguard.data.backend.SniffRequest
import com.hpu.browseguard.shared.model.CookiePolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机观测件：只证明「WebView 在这个设备上真能拦到直链，并留下了头日志」。
 *
 * 页面地址由 instrumentation 参数 `pageUrl` 传入，默认走 `adb reverse` 的
 * media_server（`http://127.0.0.1:8731/page-bulk.html`）。断言之外的观察
 * 一律走 `BgvAudit`，用 `adb logcat -s BgvAudit` 取证。
 */
@RunWith(AndroidJUnit4::class)
class LocalWebViewSnifferDeviceTest {

    @Test
    fun pageBulkYieldsADirectCandidate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pageUrl = InstrumentationRegistry.getArguments().getString("pageUrl")
            ?: "http://127.0.0.1:8731/page-bulk.html"

        val events = LocalWebViewSniffer(
            context = context,
            audit = LogcatAuditLogger(),
            timeoutMs = 20_000L,
            quietMs = 2_000L,
        ).sniff(SniffRequest(pageUrl, CookiePolicy.NONE, context.cacheDir.absolutePath)).toList()

        events.forEach { Log.w("BgvAudit", "device_event=${it.javaClass.simpleName}") }
        assertFalse("WebView 必须给出结果或可辨认的本地失败：$events", events.isEmpty())
        val results = events.filterIsInstance<SniffEvent.Results>().singleOrNull()
        assertTrue("WebView 没有拦到直链：$events", results != null && results.results.isNotEmpty())
        val first = results!!.results.first()
        Log.w(
            "BgvAudit",
            "device_result_url=${first.mediaUrl.substringBefore('?')} " +
                "headers=${first.headersHint.keys.sorted()}",
        )
        assertTrue("候选必须是单文件直链：${first.mediaUrl}", first.mediaUrl.contains(".mp4"))
        assertTrue("Referer 必须合成", first.headersHint.containsKey("referer"))
        assertTrue("UA 必须合成", first.headersHint.containsKey("user-agent"))
        assertFalse("Range 不得进入 headersHint", first.headersHint.containsKey("range"))
        assertFalse("Cookie 不得进入 headersHint", first.headersHint.containsKey("cookie"))
    }
}
