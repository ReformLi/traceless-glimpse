package com.hpu.browseguard

import android.app.Application
import com.hpu.browseguard.data.AppContainer

/**
 * 进程唯一的组装根。
 *
 * 前台服务不是界面拉起来的组件，它要判断「队列里还有几条在跑」就必须和界面看到同一个
 * [AppContainer]；各建各的等于两套内存状态，服务永远读不到真实进度，也永远不知道该什么时候停。
 * 建在 Application 里而不是活动里，还顺手把「活动重建 = 重开一次数据库」这条隐账消掉。
 */
class BgvApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
