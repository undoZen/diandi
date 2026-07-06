package com.mianbizhe.diandiji

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活 = 引导用户给三个白名单，没有任何隐藏 API（SmsForwarder 亦如此）：
 * 1. 忽略电池优化（系统标准对话框）
 * 2. 厂商「自启动」白名单（各家安全中心，只能跳转让用户手动开）
 * 3. 厂商「后台弹出界面」权限（国产 ROM 特有，弹全屏提醒需要；无独立 API，与自启动同页）
 */
object KeepAlive {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** 弹系统「允许忽略电池优化」对话框 */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }

    /**
     * 跳厂商自启动管理页。硬编码 ComponentName 是业界通用做法（SmsForwarder 同款思路），
     * ROM 升级可能失效，逐个尝试，最后退化到本 App 详情页。
     * 「后台弹出界面」权限（ColorOS 等）也在同一个应用权限页里，引导文案合并处理。
     */
    fun openAutoStartSettings(context: Context) {
        // 先试厂商安全中心的自启动页（覆盖 OPPO/一加/realme(ColorOS)、vivo、小米、华为）
        val candidates = listOf(
            // ColorOS 新版：隐私权限页（含自启动、后台弹出界面）
            "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity",
            // vivo
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            // 小米
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        for (flat in candidates) {
            try {
                context.startActivity(
                    Intent()
                        .setComponent(ComponentName.unflattenFromString(flat))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: Exception) {
                // 该 ROM 没有此组件，试下一个
            }
        }
        // 全部失败 → 本 App 详情页（自启动/后台弹出等权限入口都在里面）
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
