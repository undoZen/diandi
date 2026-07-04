package app.diandi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 前台 Service，内嵌 Ktor(CIO) HTTP Server。
 * 第一阶段目标：真机上跑通 hello world，验证 APK 体积 / 启动 / 后台存活。
 */
class WebServerService : Service() {

    companion object {
        const val PORT = 8899
        private const val CHANNEL_ID = "web_server"
        private const val NOTIFICATION_ID = 1

        /** 供 MainActivity 简单判断服务是否在跑（demo 阶段够用） */
        @Volatile
        var isRunning = false
            private set
    }

    private var server: EmbeddedServer<*, *>? = null
    private val startedAt = System.currentTimeMillis()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (server == null) {
            // host = 0.0.0.0：本机 127.0.0.1 和局域网都能访问
            server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                routing {
                    get("/") {
                        call.respondText(indexHtml(), ContentType.Text.Html)
                    }
                    get("/api/ping") {
                        call.respondText(
                            """{"status":"ok","time":${System.currentTimeMillis()},"uptimeMs":${System.currentTimeMillis() - startedAt}}""",
                            ContentType.Application.Json,
                        )
                    }
                }
            }.also { it.start(wait = false) }
        }
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Web 服务", NotificationManager.IMPORTANCE_LOW)
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("diandi 服务运行中")
            .setContentText("http://127.0.0.1:$PORT")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun indexHtml(): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>diandi</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 0; min-height: 100vh;
                       display: flex; align-items: center; justify-content: center;
                       background: linear-gradient(160deg, #0f2027, #203a43, #2c5364); color: #fff; }
                .card { text-align: center; padding: 2rem 3rem; border-radius: 16px;
                        background: rgba(255,255,255,.08); backdrop-filter: blur(8px); }
                h1 { margin: 0 0 .5rem; font-size: 2.2rem; }
                p  { margin: .3rem 0; opacity: .85; }
                code { background: rgba(255,255,255,.15); padding: .1rem .4rem; border-radius: 6px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>&#128167; Hello, diandi!</h1>
                <p>Ktor 正在你的手机上运行</p>
                <p>设备：${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})</p>
                <p>页面生成时间：$now</p>
                <p>接口示例：<code>GET /api/ping</code></p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }
}
