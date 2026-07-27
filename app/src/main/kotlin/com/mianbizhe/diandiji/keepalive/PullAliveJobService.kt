package com.mianbizhe.diandiji.keepalive

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mianbizhe.diandiji.WebServerService

/**
 * 周期拉活任务（默认常开，代价可忽略：15 分钟醒一次、执行几毫秒）。
 *
 * 选 JobScheduler 而非 WorkManager 的原因：零新依赖（WorkManager 要联网下载，
 * 本机离线构建链不支持），15 分钟最小周期对这个场景够用。
 *
 * 进程被杀后，系统到点会拉起本服务执行 onStartJob —— 这是进程复活入口：
 * 顺手把 WebServerService 拉起来 + 重启无声音乐（若开关开着）。
 *
 * 注意：国产 ROM 上 JobScheduler 触发受「自启动」白名单影响，不开自启动时
 * 周期可能被系统拉长甚至跳过——与自启动授权是配套关系。
 */
class PullAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "pull-alive job fired, webServer running=${WebServerService.isRunning}")
        if (!WebServerService.isRunning) {
            try {
                startForegroundService(Intent(this, WebServerService::class.java))
            } catch (e: Exception) {
                // 后台拉起 FGS 在原生 Android 12+ 受限；国产 ROM 给了自启动权限即可行
                Log.w(TAG, "restart web server failed", e)
            }
        }
        if (KeepAliveOption.isEnabled(this)) {
            SilentMusicPlayer.start(applicationContext)
        }
        // 15 分钟周期自检采集器：有权限但掉绑则强制重绑
        com.mianbizhe.diandiji.KeepAlive.ensureCollectorBound(this)
        jobFinished(params, false) // 无异步工作，同步完成
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false // 不需要重试

    companion object {
        private const val TAG = "diandi-keepalive"
        private const val JOB_ID = 1001
        private const val PERIOD_MS = 15 * 60 * 1000L // JobScheduler 允许的最小周期

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(JOB_ID) != null) return // 幂等
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PullAliveJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true) // 重启后保留（需 RECEIVE_BOOT_COMPLETED，已有）
                .build()
            js.schedule(job)
            Log.i(TAG, "pull-alive job scheduled")
        }

        fun cancel(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(JOB_ID)
            Log.i(TAG, "pull-alive job cancelled")
        }
    }
}
