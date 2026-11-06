package com.hpu.browseguard

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hpu.browseguard.data.AppContainer
import com.hpu.browseguard.ui.BgvApp
import com.hpu.browseguard.ui.theme.BgvTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(applicationContext) }

    /**
     * 播放器在场时挂上。点 Home/最近任务时只由它决定要不要进小窗：
     * 其他页面没有视频，直接按系统默认行为退到后台。
     */
    var onUserLeavePlayer: (() -> Boolean)? = null

    /** 小窗进出都要告诉播放器，界面才知道该不该收起控制层。 */
    var onPipModeChanged: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BgvTheme {
                BgvApp(container)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onUserLeavePlayer?.invoke()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        onPipModeChanged?.invoke(isInPictureInPictureMode)
    }

    /** 「最近使用时间」只在内存里滚，切后台这一刀才落盘（M6-5 D3）。 */
    override fun onStop() {
        super.onStop()
        container.cookieConsents.flushUsage()
    }
}
