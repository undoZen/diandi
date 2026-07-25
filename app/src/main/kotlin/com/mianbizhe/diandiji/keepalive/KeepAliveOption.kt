package com.mianbizhe.diandiji.keepalive

import android.content.Context

/**
 * 「无声音乐保活」开关（默认关，持久化）。
 *
 * 对应 SmsForwarder 的 Cactus 开关：用户自己决定是否付电费换存活率。
 * 只控制无声音乐（[SilentMusicPlayer]，持 PARTIAL_WAKE_LOCK 费电）；
 * JobScheduler 周期拉活（[PullAliveJobService]）代价可忽略，默认常开，
 * 由 WebServerService/BootReceiver 直接调度，不经本开关。
 *
 * 开关状态存独立 SharedPreferences，MainActivity 切换、WebServerService/BootReceiver
 * 启动时读取并应用。
 */
object KeepAliveOption {

    private const val PREF_NAME = "diandi_keepalive"
    private const val KEY_ENHANCED = "enhanced_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENHANCED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENHANCED, on).apply()
        if (on) apply(context) else disable(context)
    }

    /** 开启状态生效：启动无声音乐（幂等） */
    fun apply(context: Context) {
        SilentMusicPlayer.start(context.applicationContext)
    }

    /** 服务/广播启动时调用：开关开着才应用 */
    fun applyIfEnabled(context: Context) {
        if (isEnabled(context)) apply(context)
    }

    fun disable(context: Context) {
        SilentMusicPlayer.stop()
    }
}
