package com.mianbizhe.diandiji.setup

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

/**
 * 设置与引导屏（React Native）。组件名 "Setup" 对应
 * mobile/index.js 里 AppRegistry.registerComponent 的注册名。
 * 由 MainActivity 拦截 /native/setup 拉起。
 */
class SetupActivity : ReactActivity() {
    override fun getMainComponentName(): String = "Setup"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
