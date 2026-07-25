package com.mianbizhe.diandiji.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.mianbizhe.diandiji.BuildConfig
import com.mianbizhe.diandiji.KeepAlive
import com.mianbizhe.diandiji.MainActivity
import com.mianbizhe.diandiji.WebServerService
import com.mianbizhe.diandiji.collect.NotificationCollectorService
import com.mianbizhe.diandiji.keepalive.KeepAliveOption

/**
 * 设置与引导屏的 RN 原生模块（JS 侧名 DiandiSetup）。
 * 暴露权限状态查询和各系统设置页跳转；所有动作都是 fire-and-forget，
 * 状态由 JS 侧轮询 getStatus() 刷新。
 */
class SetupModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "DiandiSetup"

    @ReactMethod
    fun getStatus(promise: Promise) {
        val map = Arguments.createMap()
        map.putBoolean("serviceRunning", WebServerService.isRunning)
        map.putBoolean("collectorConnected", NotificationCollectorService.isConnected)
        map.putBoolean("notifAccess", KeepAlive.isNotifAccessGranted(reactContext))
        map.putBoolean(
            "postNotifs",
            Build.VERSION.SDK_INT < 33 ||
                reactContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        map.putBoolean("batteryIgnored", KeepAlive.isIgnoringBatteryOptimizations(reactContext))
        map.putBoolean("musicEnabled", KeepAliveOption.isEnabled(reactContext))
        map.putString("lanUrl", WebServerService.lanUrl)
        map.putString("version", BuildConfig.VERSION_NAME)
        val blePrefs = reactContext.getSharedPreferences("diandi_ancs", Context.MODE_PRIVATE)
        map.putString("blePairedAddress", blePrefs.getString("paired_addr", null))
        promise.resolve(map)
    }

    @ReactMethod
    fun openNotifAccess() {
        start(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    /** 权限在但系统未绑定监听器时（debug 装机常见），强制系统重新绑定 */
    @ReactMethod
    fun rebindCollector() {
        val cn = android.content.ComponentName(
            reactContext,
            com.mianbizhe.diandiji.collect.NotificationCollectorService::class.java,
        )
        try {
            // 禁用→启用组件：通知监听器相当于被系统重新发现，从未绑定过的状态也有效
            // （requestRebind 只对"绑定过又断开"有效，新装未绑定场景是空操作）
            val pm = reactContext.packageManager
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (_: Exception) {
        }
        try {
            android.service.notification.NotificationListenerService.requestRebind(cn)
        } catch (_: Exception) {
        }
    }

    @ReactMethod
    fun requestBattery() {
        try {
            start(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${reactContext.packageName}"),
                )
            )
        } catch (_: Exception) {
            openAppDetails() // 个别 ROM 没有该对话框，退化到详情页
        }
    }

    @ReactMethod
    fun openAutoStart() {
        KeepAlive.openAutoStartSettings(reactContext)
    }

    /** App 系统详情页（BLE 权限、通知渠道等杂项都在里面） */
    @ReactMethod
    fun openAppDetails() {
        start(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${reactContext.packageName}"),
            )
        )
    }

    @ReactMethod
    fun requestPostNotifications() {
        val activity = currentActivity ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @ReactMethod
    fun setMusicEnabled(enabled: Boolean) {
        KeepAliveOption.setEnabled(reactContext, enabled)
    }

    /** 打开主界面并加载 /ble 管理页 */
    @ReactMethod
    fun openBlePage() {
        val intent = Intent(reactContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_URL, "http://127.0.0.1:${WebServerService.PORT}/ble")
        reactContext.startActivity(intent)
    }

    private fun start(action: String) = start(Intent(action))

    private fun start(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        reactContext.startActivity(intent)
    }
}
