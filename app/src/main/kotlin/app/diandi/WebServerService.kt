package app.diandi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.diandi.db.AppDatabase
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    /** 包名 → App 显示名缓存（查 PackageManager 不便宜，通知列表会高频用） */
    private val appLabelCache = ConcurrentHashMap<String, String>()

    private fun appLabel(pkg: String): String = appLabelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg // 已卸载或查不到就显示包名
        }
    }

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
                    // 里程碑 2 验证用：确认通知在落库
                    get("/api/notifications/count") {
                        val dao = AppDatabase.get(this@WebServerService).notificationDao()
                        call.respondText(
                            """{"count":${dao.count()},"latestPostTime":${dao.latestPostTime()}}""",
                            ContentType.Application.Json,
                        )
                    }
                    // 里程碑 3 第一步：最近通知查询（倒序，limit 上限 500）
                    // ?all=1 绕过白名单看全量（采集本来就是全量落库的）
                    get("/api/notifications") {
                        val limit = call.request.queryParameters["limit"]
                            ?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                        val showAll = call.request.queryParameters["all"] == "1"
                        val dao = AppDatabase.get(this@WebServerService).notificationDao()
                        val rows = if (AppWhitelist.isEmpty() || showAll) {
                            dao.recent(limit)
                        } else {
                            dao.recentByPackages(AppWhitelist.packages, limit)
                        }
                        val arr = JSONArray()
                        for (e in rows) {
                            arr.put(JSONObject().apply {
                                put("id", e.id)
                                put("packageName", e.packageName)
                                put("appName", appLabel(e.packageName))
                                put("postTime", e.postTime)
                                put("category", e.category)
                                put("title", e.title)
                                put("text", e.text)
                                put("bigText", e.bigText)
                                put("textLines", e.textLines)
                                // 库里存的是 JSON 字符串，这里还原成 JSON 数组再下发
                                put("messages", e.messages?.let { runCatching { JSONArray(it) }.getOrNull() })
                                put("isOngoing", e.isOngoing)
                                put("removedAt", e.removedAt)
                            })
                        }
                        call.respondText(arr.toString(), ContentType.Application.Json)
                    }
                    get("/notifications") {
                        call.respondText(notificationsHtml(), ContentType.Text.Html)
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

    /** 用户从最近任务划掉 App 时，服务进程可能被顺带杀死；主动请求重启（配合 START_STICKY） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        startForegroundService(Intent(this, WebServerService::class.java))
        super.onTaskRemoved(rootIntent)
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
                <p><a href="/notifications" style="color:#8fd3ff">→ 通知历史列表</a></p>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * 通知历史列表页：纯静态 HTML + fetch /api/notifications 渲染。
     * 注意 JS 里不用模板字符串（避免和 Kotlin 的 $ 冲突），全部字符串拼接。
     */
    private fun notificationsHtml(): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>diandi · 通知历史</title>
          <style>
            :root { color-scheme: dark; }
            body { font-family: system-ui, sans-serif; margin: 0; background: #14181d; color: #e8eaed; }
            header { position: sticky; top: 0; background: #1c2128; padding: .8rem 1rem;
                     display: flex; align-items: baseline; gap: .8rem; box-shadow: 0 1px 4px rgba(0,0,0,.4); }
            header h1 { font-size: 1.1rem; margin: 0; }
            header .count { opacity: .6; font-size: .85rem; }
            header a { margin-left: auto; color: #8fd3ff; font-size: .85rem; text-decoration: none; }
            ul { list-style: none; margin: 0; padding: .5rem; }
            li { background: #1c2128; border-radius: 10px; padding: .7rem .9rem; margin-bottom: .5rem; }
            li.removed { opacity: .55; }
            .row1 { display: flex; gap: .6rem; align-items: baseline; flex-wrap: wrap; }
            .app { font-weight: 600; font-size: .8rem; color: #8fd3ff; }
            .cat { font-size: .7rem; padding: .05rem .45rem; border-radius: 999px;
                   background: #2d3644; color: #a9c7e8; }
            .time { margin-left: auto; font-size: .75rem; opacity: .55; white-space: nowrap; }
            .title { font-weight: 600; margin-top: .25rem; }
            .text { margin-top: .15rem; font-size: .9rem; opacity: .85; white-space: pre-wrap;
                    word-break: break-word; }
            .msgs { margin-top: .3rem; font-size: .85rem; border-left: 2px solid #2d3644;
                    padding-left: .6rem; opacity: .9; }
            .msgs .sender { color: #a9c7e8; }
            .pkg { margin-top: .35rem; font-size: .7rem; opacity: .45; font-family: monospace;
                   user-select: all; } /* user-select:all 点一下全选，方便复制进白名单 */
            .empty { text-align: center; opacity: .5; padding: 3rem 1rem; }
          </style>
        </head>
        <body>
          <header>
            <h1>&#128276; 通知历史</h1>
            <span class="count" id="count"></span>
            <a href="/">首页</a>
          </header>
          <ul id="list"></ul>
          <div class="empty" id="empty" hidden>还没有通知，等几条进来再刷新</div>
          <script>
            function esc(s) {
              return String(s).replace(/[&<>"]/g, function (c) {
                return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
              });
            }
            function fmt(ts) {
              var d = new Date(ts), now = new Date();
              var hm = ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
              return d.toDateString() === now.toDateString()
                ? hm
                : (d.getMonth() + 1) + '-' + d.getDate() + ' ' + hm;
            }
            fetch('/api/notifications?limit=200')
              .then(function (r) { return r.json(); })
              .then(function (items) {
                document.getElementById('count').textContent = '最近 ' + items.length + ' 条';
                if (!items.length) { document.getElementById('empty').hidden = false; return; }
                var html = '';
                items.forEach(function (n) {
                  var body = n.bigText || n.text || n.textLines || '';
                  html += '<li' + (n.removedAt ? ' class="removed"' : '') + '>'
                    + '<div class="row1">'
                    + '<span class="app">' + esc(n.appName) + '</span>'
                    + (n.category ? '<span class="cat">' + esc(n.category) + '</span>' : '')
                    + (n.isOngoing ? '<span class="cat">常驻</span>' : '')
                    + '<span class="time">' + fmt(n.postTime) + '</span>'
                    + '</div>'
                    + (n.title ? '<div class="title">' + esc(n.title) + '</div>' : '')
                    + (body ? '<div class="text">' + esc(body) + '</div>' : '');
                  if (n.messages && n.messages.length) {
                    html += '<div class="msgs">';
                    n.messages.forEach(function (m) {
                      html += '<div><span class="sender">' + esc(m.sender || '?') + '</span>：'
                        + esc(m.text || '') + '</div>';
                    });
                    html += '</div>';
                  }
                  html += '<div class="pkg">' + esc(n.packageName) + '</div>';
                  html += '</li>';
                });
                document.getElementById('list').innerHTML = html;
              });
          </script>
        </body>
        </html>
    """.trimIndent()
}
