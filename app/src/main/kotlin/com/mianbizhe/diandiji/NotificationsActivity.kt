package com.mianbizhe.diandiji

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

/**
 * 通知列表原生屏（React Native）。组件名 "NotificationsList" 对应
 * mobile/index.js 里 AppRegistry.registerComponent 的注册名。
 * 由 MainActivity 状态栏的「通知」按钮 startActivity 拉起。
 */
class NotificationsActivity : ReactActivity() {
    override fun getMainComponentName(): String = "NotificationsList"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
