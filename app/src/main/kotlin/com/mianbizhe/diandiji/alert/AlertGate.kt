package com.mianbizhe.diandiji.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.mianbizhe.diandiji.AppWhitelist
import com.mianbizhe.diandiji.db.NotificationEntity

/**
 * 提醒规则 + 双通道弹出。
 *
 * 弹出走两条路（都试）：
 * 1. fullScreenIntent 高优先级通知：锁屏/灭屏时系统直接全屏拉起 AlertActivity，
 *    亮屏使用中则显示悬浮横幅（heads-up）。需要 USE_FULL_SCREEN_INTENT 权限。
 *    Android 14 起需用户在系统设置确认「允许全屏通知」。
 * 2. 直接 startActivity：ColorOS/vivo 授予「后台弹出界面」后可用，亮屏时也能全屏弹。
 *    无权限时系统静默拦截（Android 10+ 后台 Activity 启动限制），不 crash。
 *
 * 规则先硬编码信用卡/银行消费类关键词，后续做成用户可配置（存 Room）。
 */
object AlertGate {

    private const val TAG = "diandi-alert"
    private const val CHANNEL_ID = "alert_fullscreen"
    private var nextNotificationId = 100

    /** 命中提醒规则？仅 finance 组（三家银行）且文本含消费关键词 */
    fun shouldAlert(e: NotificationEntity): Boolean {
        if (e.packageName !in AppWhitelist.finance) return false
        val text = listOfNotNull(e.title, e.text, e.bigText).joinToString(" ")
        if (text.isBlank()) return false
        val keywords = listOf("消费", "支出", "交易", "信用卡", "扣款", "支付成功", "转账")
        return keywords.any { text.contains(it) }
    }

    fun fireAlert(context: Context, e: NotificationEntity) {
        val intent = Intent(context, AlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlertActivity.EXTRA_TITLE, e.title)
            .putExtra(AlertActivity.EXTRA_TEXT, e.bigText ?: e.text)
            .putExtra(AlertActivity.EXTRA_APP, e.packageName)
            .putExtra(AlertActivity.EXTRA_POST_TIME, e.postTime)

        // 路 2：后台直接弹（有「后台弹出界面」权限就成功，无权限被系统静默拦截）
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }

        // 路 1：fullScreenIntent 通知兜底（锁屏时主力；亮屏时退化为 heads-up 横幅）
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "重要提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "命中规则的通知全屏提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 400)
                setBypassDnd(true)
            }
        )
        // Android 14+：USE_FULL_SCREEN_INTENT 需用户在系统设置手动允许
        if (Build.VERSION.SDK_INT >= 34) {
            val canUseFsi = manager.canUseFullScreenIntent()
            if (!canUseFsi) {
                Log.w(TAG, "USE_FULL_SCREEN_INTENT not granted; fullScreenIntent degraded to heads-up." +
                    " 请在 系统设置→应用→点滴集→通知 中开启「全屏通知」")
            }
        }
        val fullScreenPi = PendingIntent.getActivity(
            context, e.sbnId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.mianbizhe.diandiji.R.drawable.ic_launcher_fg)
            .setContentTitle(e.title ?: "重要提醒")
            .setContentText(e.text ?: "")
            .setContentIntent(fullScreenPi)
            .setFullScreenIntent(fullScreenPi, true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL) // 提高 heads-up/全屏概率
            .setVibrate(longArrayOf(0, 200, 100, 400))
            .setTimeoutAfter(15_000) // 15 秒后自动消失（API 26+），避免锁屏时通知残留
            .build()
        manager.notify(nextNotificationId++, notification)
        Log.i(TAG, "alert fired: title=${e.title}, text=${e.text}")
    }
}
