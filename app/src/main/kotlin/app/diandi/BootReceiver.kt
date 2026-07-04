package app.diandi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：重启后把 Ktor 前台服务拉起来（参考 SmsForwarder BootCompletedReceiver 思路）。
 * 注意：将来 targetSdk 升 35 后要核对 BOOT_COMPLETED 启动 FGS 的类型限制（specialUse 目前不在禁止列表）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startForegroundService(Intent(context, WebServerService::class.java))
    }
}
