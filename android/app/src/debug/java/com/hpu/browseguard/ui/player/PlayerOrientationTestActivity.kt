package com.hpu.browseguard.ui.player

import androidx.activity.ComponentActivity

/**
 * 只服务方向测试的空 Activity（不画任何东西，只当 `requestedOrientation` 的落点）。
 *
 * 它必须待在 **debug** source set，不能放 androidTest：androidTest 里的组件属于测试 APK，
 * 与仪器所在的 `com.hpu.browseguard` 是两个进程，`ActivityScenario` 一启动就报
 * 「Intent resolved to different process」。放进 debug 就是为了让它落进被测的那个 APK。
 *
 * 为什么不能用测试规则默认那个宿主：它没声明 `configChanges`，`setRequestedOrientation` 一生效
 * 系统就销毁重建它，规则手里那只 Activity 立刻作废。声明见 `src/debug/AndroidManifest.xml`，
 * 与 `MainActivity` 一模一样。
 */
class PlayerOrientationTestActivity : ComponentActivity()
