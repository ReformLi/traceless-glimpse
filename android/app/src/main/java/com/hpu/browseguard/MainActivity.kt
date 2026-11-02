package com.hpu.browseguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.ui.BgvApp
import com.hpu.browseguard.ui.theme.BgvTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BgvTheme {
                BgvApp(container)
            }
        }
    }

    /** 「最近使用时间」只在内存里滚，切后台这一刀才落盘（M6-5 D3）。 */
    override fun onStop() {
        super.onStop()
        container.cookieConsents.flushUsage()
    }
}
