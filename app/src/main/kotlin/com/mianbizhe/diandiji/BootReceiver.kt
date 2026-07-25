package com.mianbizhe.diandiji

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mianbizhe.diandiji.keepalive.KeepAliveOption
import com.mianbizhe.diandiji.keepalive.PullAliveJobService

/**
 * 拉活广播：开机 + 解锁（参考 SmsForwarder 的 BootCompletedReceiver 覆盖面）。
 * - BOOT_COMPLETED：重启后把 Ktor 前台服务拉起来
 * - USER_PRESENT：用户解锁时顺手检查，服务掉了就拉起——国产 ROM 上
 *   解锁广播是少数 manifest 注册仍稳定送达的拉活时机
 * 注意：将来 targetSdk 升 35 后要核对 BOOT_COMPLETED 启动 FGS 的类型限制（specialUse 目前不在禁止列表）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_PRESENT
        ) return
        if (!WebServerService.isRunning) {
            try {
                context.startForegroundService(Intent(context, WebServerService::class.java))
            } catch (e: Exception) {
                Log.w("diandi-boot", "start service failed on ${intent.action}", e)
            }
        }
        // 周期拉活任务默认常开（persisted 重启后仍在，这里幂等兜底）；
        // 无声音乐由「保活+」开关控制
        PullAliveJobService.schedule(context)
        KeepAliveOption.applyIfEnabled(context)
    }
}
