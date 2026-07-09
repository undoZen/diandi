package com.mianbizhe.diandiji

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mianbizhe.diandiji.alert.AlertGate
import com.mianbizhe.diandiji.ble.AncsClient
import com.mianbizhe.diandiji.classify.Classifier
import com.mianbizhe.diandiji.db.AppDatabase
import com.mianbizhe.diandiji.db.CorrectionEntity
import com.mianbizhe.diandiji.db.NotificationEntity
import com.mianbizhe.diandiji.db.SpendingEntity
import com.mianbizhe.diandiji.spend.ParsedSpend
import com.mianbizhe.diandiji.spend.SpendParser
import com.mianbizhe.diandiji.spend.SpendPipeline
import com.mianbizhe.diandiji.web.Pages
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台 Service，内嵌 Ktor(CIO) HTTP Server。
 * 第一阶段目标：真机上跑通 hello world，验证 APK 体积 / 启动 / 后台存活。
 */
class WebServerService : Service() {

    companion object {
        private const val TAG = "diandi-web"

        @Volatile
        var PORT = 8899
            private set
        private const val CHANNEL_ID = "web_server"
        private const val NOTIFICATION_ID = 1

        /** 供 MainActivity 简单判断服务是否在跑（demo 阶段够用） */
        @Volatile
        var isRunning = false
            private set

        // ---- 局域网 IP（网络变化时自动刷新，仅 WiFi/以太网时展示） ----
        @Volatile
        private var lanIp: String? = null // 启动时由 startNetworkMonitor 首次探测
        val lanUrl: String get() = if (lanIp != null) "http://$lanIp:$PORT" else "http://127.0.0.1:$PORT"

        fun getLanIp(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()?.asSequence()
                    ?.flatMap { it.inetAddresses.asSequence() }
                    ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                    ?.hostAddress
            } catch (_: Exception) { null }
        }

        // ---- 前台通知动态文案计数 ----
        private var msgCached = 0
        private var spendingCached = 0
        private val counterLock = Any()

        private fun notificationText(msg: Int, spending: Int): String = when {
            msg == 0 && spending == 0 -> "正在准备关注通知后续记录"
            msg == 0 && spending > 0 -> "已缓存 $spending 条消费记录"
            spending == 0 -> "已缓存 $msg 条消息"
            else -> "已缓存 $msg 条消息，$spending 条消费记录"
        }

        /** 采集层每收到一条通知 / 消费记录就调用，更新前台通知文案 */
        fun onCollect(context: android.content.Context, isSpending: Boolean) {
            val (msg, spending) = synchronized(counterLock) {
                if (isSpending) spendingCached++ else msgCached++
                msgCached to spendingCached
            }
            postNotification(context, notificationText(msg, spending))
        }

        /** 网络变化或启动时刷新通知（不修改计数器，但 IP 变了所以通知文案 subtext 更新） */
        fun refreshNotificationText(context: android.content.Context) {
            val (msg, spending) = synchronized(counterLock) { msgCached to spendingCached }
            postNotification(context, notificationText(msg, spending))
        }

        private fun postNotification(
            context: android.content.Context,
            contentText: String,
        ) {
            val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotificationStatic(context, contentText))
        }

        /** 构建前台通知（内容文案可定制，初始调用传默认值） */
        private fun buildNotificationStatic(
            context: android.content.Context,
            contentText: String,
        ): android.app.Notification {
            val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(
                android.app.NotificationChannel(CHANNEL_ID, "Web 服务", android.app.NotificationManager.IMPORTANCE_LOW)
            )
            val contentIntent = android.app.PendingIntent.getActivity(
                context, 0,
                android.content.Intent(context, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            return android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.mianbizhe.diandiji.R.drawable.ic_launcher_fg)
                .setContentTitle("点滴集 服务运行中")
                .setContentText(contentText)
                .setSubText(lanUrl)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        }
    }

    private var server: EmbeddedServer<*, *>? = null
    private val startedAt = System.currentTimeMillis()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var backfillStarted = false

    // ---- BLE ANCS（iPhone 通知采集） ----
    private var ancsClient: AncsClient? = null

    /** 包名 → App 显示名缓存（查 PackageManager 不便宜，通知列表会高频用） */
    private val appLabelCache = ConcurrentHashMap<String, String>()

    private fun appLabel(pkg: String): String = appLabelCache[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
            .toString().also { appLabelCache[pkg] = it }
    } catch (e: Exception) {
        pkg // 查不到就显示包名；不缓存失败结果（可能是暂时性的，如包可见性）
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PORT = findAvailablePort()
        startForeground(NOTIFICATION_ID, buildNotification())
        startNetworkMonitor()
        // 消费回填：把历史财务通知解析进 spending 表（幂等，进程生命周期跑一次）
        if (!backfillStarted) {
            backfillStarted = true
            scope.launch { SpendPipeline.backfill(this@WebServerService) }
        }
        if (server == null) {
            // host = 0.0.0.0：本机 127.0.0.1 和局域网都能访问
            server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                routing {
                    get("/api/model/export") {
                        Classifier.ensureLoaded(this@WebServerService)
                        call.respondText(Classifier.exportModel(), ContentType.Application.Json)
                    }
                    get("/") {
                        call.respondText(Pages.index(lanUrl), ContentType.Text.Html)
                    }
                    get("/api/ping") {
                        call.respondText(
                            """{"status":"ok","time":${System.currentTimeMillis()},"uptimeMs":${System.currentTimeMillis() - startedAt}}""",
                            ContentType.Application.Json,
                        )
                    }
                    get("/ble") {
                        call.respondText(Pages.bleControl(), ContentType.Text.Html)
                    }
                    // ---- BLE ANCS（iPhone 通知） ----
                    get("/api/ble/status") {
                        val c = ancsClient
                        call.respondText(JSONObject().apply {
                            put("state", c?.state?.name ?: "IDLE")
                            put("isAdvertising", c?.isAdvertising ?: false)
                            put("isConnected", c?.isConnected ?: false)
                            put("isSubscribed", c?.isSubscribed ?: false)
                            put("deviceName", c?.deviceName)
                            put("pairedAddress", c?.pairedAddress)
                            put("lastError", c?.lastError)
                        }.toString(), ContentType.Application.Json)
                    }
                    post("/api/ble/pair/start") {
                        val c = ancsClient
                        if (c == null) {
                            call.respondText("""{"error":"ANCS not initialized"}""",
                                ContentType.Application.Json, HttpStatusCode.InternalServerError)
                            return@post
                        }
                        try {
                            c.startPairing()
                            call.respondText("""{"advertising":true}""", ContentType.Application.Json)
                        } catch (e: Throwable) {
                            call.respondText("""{"error":"${e.javaClass.simpleName}: ${e.message?.replace("\"","'")}"}""",
                                ContentType.Application.Json, HttpStatusCode.InternalServerError)
                        }
                    }
                    post("/api/ble/pair/stop") {
                        ancsClient?.stopPairing()
                        call.respondText("""{"advertising":false}""", ContentType.Application.Json)
                    }
                    get("/api/ble/bonded") {
                        val list = (ancsClient?.getBondedDevices() ?: emptyList()).map { JSONObject(it) }
                        call.respondText(JSONArray(list).toString(), ContentType.Application.Json)
                    }
                    // 连接已配对的 iPhone 读 ANCS；body 可选 address，缺省用 pairedAddress
                    post("/api/ble/connect") {
                        val body = runCatching { JSONObject(call.receiveText()) }.getOrNull()
                        val addr = body?.optString("address")?.takeIf { it.isNotBlank() }
                        ancsClient?.connectAncs(addr)
                        call.respondText("""{"connecting":true}""", ContentType.Application.Json)
                    }
                    post("/api/ble/disconnect") {
                        ancsClient?.disconnect()
                        call.respondText("""{"disconnected":true}""", ContentType.Application.Json)
                    }
                    get("/api/ble/logs") {
                        val logs = ancsClient?.getLogs() ?: emptyList()
                        call.respondText(JSONArray(logs).toString(), ContentType.Application.Json)
                    }

                    // 里程碑 2 验证用：确认通知在落库
                    get("/api/notifications/count") {
                        val dao = AppDatabase.get(this@WebServerService).notificationDao()
                        call.respondText(
                            """{"count":${dao.count()},"latestPostTime":${dao.latestPostTime()}}""",
                            ContentType.Application.Json,
                        )
                    }
                    // 调试：模拟一条银行消费通知走完整 AlertGate 路径（不落库）。
                    // AlertActivity exported=false，adb 拉不起来，只能从进程内触发
                    get("/api/test-alert") {
                        val fake = NotificationEntity(
                            packageName = AppWhitelist.finance.first(),
                            postTime = System.currentTimeMillis(),
                            sbnKey = "test", sbnId = 9999, tag = null, uid = null,
                            isClearable = true, isOngoing = false, groupKey = null,
                            whenTime = System.currentTimeMillis(),
                            category = null, channelId = null, priority = 0, flags = 0,
                            template = null, actions = null,
                            title = "测试提醒", titleBig = null,
                            text = "您尾号1234的信用卡消费￥88.00（测试）",
                            bigText = null, subText = null, infoText = null,
                            summaryText = null, textLines = null, messages = null,
                            people = null, progress = null, progressMax = null,
                            showChronometer = false, hasPicture = false, importance = null,
                            receivedAt = System.currentTimeMillis(), contentHash = 0,
                        )
                        val hit = AlertGate.shouldAlert(fake)
                        if (hit) AlertGate.fireAlert(this@WebServerService, fake)
                        call.respondText(
                            """{"shouldAlert":$hit}""",
                            ContentType.Application.Json,
                        )
                    }
                    // 里程碑 3 第一步：最近通知查询（倒序，limit 上限 500）
                    // ?filter=finance 只看财务组；缺省/未知值 = 全部（采集本来就全量落库）
                    get("/api/notifications") {
                        val limit = call.request.queryParameters["limit"]
                            ?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                        val dao = AppDatabase.get(this@WebServerService).notificationDao()
                        val rows = when (call.request.queryParameters["filter"]) {
                            "finance" -> dao.recentByPackages(AppWhitelist.finance, limit)
                            else -> dao.recent(limit)
                        }
                        val arr = JSONArray()
                        for (e in rows) {
                            // 掌上生活等 App 会发无标题无内容的占位通知（自定义 RemoteViews，
                            // extras 里抓不到文本），列表里没意义，跳过
                            val hasContent = !e.title.isNullOrBlank() || !e.text.isNullOrBlank() ||
                                !e.bigText.isNullOrBlank() || !e.textLines.isNullOrBlank() ||
                                !e.messages.isNullOrBlank()
                            if (!hasContent) continue
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
                        call.respondText(Pages.notifications(), ContentType.Text.Html)
                    }

                    // ---- 消费（/spending）----
                    get("/spending") {
                        call.respondText(Pages.spending(), ContentType.Text.Html)
                    }
                    get("/dashboard") {
                        call.respondText(Pages.dashboard(), ContentType.Text.Html)
                    }
                    get("/api/spending") {
                        Classifier.ensureLoaded(this@WebServerService)
                        val limit = call.request.queryParameters["limit"]
                            ?.toIntOrNull()?.coerceIn(1, 500) ?: 200
                        // ?days=N：只取最近 N 天（dashboard 统计用；缺省不限）
                        val days = call.request.queryParameters["days"]?.toIntOrNull()
                        val dao = AppDatabase.get(this@WebServerService).spendingDao()
                        val rows = if (days != null && days > 0) {
                            dao.recentSince(System.currentTimeMillis() - days * 86_400_000L, limit)
                        } else {
                            dao.recent(limit)
                        }
                        val arr = JSONArray()
                        for (s in rows) {
                            arr.put(spendingJson(s))
                        }
                        call.respondText(arr.toString(), ContentType.Application.Json)
                    }
                    get("/api/spending/categories") {
                        Classifier.ensureLoaded(this@WebServerService)
                        val arr = JSONArray()
                        Classifier.categories.forEach { arr.put(it) }
                        arr.put(Classifier.UNKNOWN)
                        call.respondText(arr.toString(), ContentType.Application.Json)
                    }
                    post("/api/spending/{id}/category") {
                        Classifier.ensureLoaded(this@WebServerService)
                        val id = call.parameters["id"]?.toLongOrNull()
                        val category = runCatching {
                            JSONObject(call.receiveText()).getString("category")
                        }.getOrNull()
                        val valid = category != null &&
                            (category in Classifier.categories || category == Classifier.UNKNOWN)
                        if (id == null || !valid) {
                            call.respondText(
                                """{"error":"bad id or category"}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest,
                            )
                            return@post
                        }
                        val dao = AppDatabase.get(this@WebServerService).spendingDao()
                        val row = dao.get(id)
                        if (row == null) {
                            call.respondText(
                                """{"error":"not found"}""",
                                ContentType.Application.Json, HttpStatusCode.NotFound,
                            )
                            return@post
                        }
                        dao.updateCategory(id, category!!, 1.0, corrected = true)
                        dao.insertCorrection(
                            CorrectionEntity(
                                spendingId = id, merchant = row.merchant, rawText = row.rawText,
                                category = category, createdAt = System.currentTimeMillis(),
                            )
                        )
                        // 修正立即反哺模型（全量重建 <100ms）
                        Classifier.retrain(this@WebServerService)
                        call.respondText(spendingJson(dao.get(id)!!).toString(), ContentType.Application.Json)
                    }
                    // 调试：任意文本走解析+分类（不落库），curl 迭代正则/分类器用
                    get("/api/spending/test-parse") {
                        Classifier.ensureLoaded(this@WebServerService)
                        val pkg = call.request.queryParameters["pkg"] ?: ""
                        val text = call.request.queryParameters["text"] ?: ""
                        val fake = NotificationEntity(
                            packageName = pkg, postTime = System.currentTimeMillis(),
                            sbnKey = "test-parse", sbnId = 0, tag = null, uid = null,
                            isClearable = true, isOngoing = false, groupKey = null,
                            whenTime = 0, category = null, channelId = null, priority = 0,
                            flags = 0, template = null, actions = null, title = null,
                            titleBig = null, text = text, bigText = null, subText = null,
                            infoText = null, summaryText = null, textLines = null,
                            messages = null, people = null, progress = null, progressMax = null,
                            showChronometer = false, hasPicture = false, importance = null,
                            receivedAt = 0, contentHash = 0,
                        )
                        val parsed = SpendParser.parse(fake)
                        val out = JSONObject()
                        if (parsed == null) {
                            out.put("parsed", false)
                        } else {
                            val pred = Classifier.classify(parsed.merchant)
                            out.put("parsed", true)
                                .put("amountCents", parsed.amountCents)
                                .put("merchant", parsed.merchant)
                                .put("channel", parsed.channel)
                                .put("category", pred.category)
                                .put("confidence", pred.confidence)
                                .put("uncertain", pred.uncertain)
                        }
                        call.respondText(out.toString(), ContentType.Application.Json)
                    }
                    // 手动录入：粘贴任意消费文本 → 解析 → 分类 → spending 表
                    post("/api/spending/manual") {
                        Classifier.ensureLoaded(this@WebServerService)
                        val text = call.receiveText().trim()
                        if (text.isBlank()) {
                            call.respondText("""{"error":"请输入消费通知文本"}""",
                                ContentType.Application.Json, HttpStatusCode.BadRequest)
                            return@post
                        }
                        val now = System.currentTimeMillis()
                        // 先用银行正则精确匹配，失配再用通用解析兜底
                        var parsed: ParsedSpend? = null
                        for (pkg in AppWhitelist.finance) {
                            val fake = NotificationEntity(
                                packageName = pkg, postTime = now,
                                sbnKey = "manual-$now", sbnId = 0,
                                tag = null, uid = null, isClearable = true, isOngoing = false,
                                groupKey = null, whenTime = now,
                                category = null, channelId = null, priority = 0, flags = 0,
                                template = null, actions = null, title = null, titleBig = null,
                                text = text, bigText = null, subText = null, infoText = null,
                                summaryText = null, textLines = null, messages = null,
                                people = null, progress = null, progressMax = null,
                                showChronometer = false, hasPicture = false, importance = null,
                                receivedAt = now, contentHash = 0,
                            )
                            parsed = SpendParser.parse(fake)
                            if (parsed != null) break
                        }
                        // 银行格式失配 → 通用文本解析
                        val manual = if (parsed != null) {
                            SpendParser.ManualParse(parsed.amountCents, parsed.merchant, parsed.channel, now, text)
                        } else {
                            SpendParser.parseManual(text, now)
                                ?: return@post call.respondText(
                                    """{"error":"找不到金额。请确保文本含金额（如 36.30元 / ¥50.80 / -10.00）"}""",
                                    ContentType.Application.Json, HttpStatusCode.BadRequest)
                        }
                        val prediction = Classifier.classify(manual.merchant)
                        val dao = AppDatabase.get(this@WebServerService).spendingDao()
                        val entity = SpendingEntity(
                            notificationId = 0, sourcePackage = "manual",
                            txTime = manual.txTime,
                            amountCents = manual.amountCents,
                            merchant = manual.merchant, channel = manual.channel,
                            rawText = text,
                            category = prediction.category,
                            confidence = prediction.confidence,
                            uncertain = prediction.uncertain,
                            hidden = false, createdAt = now,
                        )
                        val id = dao.insert(entity)
                        onCollect(this@WebServerService, isSpending = true)
                        call.respondText(spendingJson(dao.get(id)!!).toString(), ContentType.Application.Json)
                    }
                    // 清理无商户的招行重复消费（debug 用）
                    post("/api/spending/cleanup") {
                        val dao = AppDatabase.get(this@WebServerService).spendingDao()
                        dao.hideEmptyCmbs()
                        call.respondText("""{"hidden":true}""", ContentType.Application.Json)
                    }
                    // 数据导出：全量 dump notification_history + spending + category_correction
                    get("/api/export") {
                        val db = AppDatabase.get(this@WebServerService)
                        val notifications = db.notificationDao().recent(Int.MAX_VALUE)
                        val spending = db.spendingDao().recent(Int.MAX_VALUE)
                        val corrections = db.spendingDao().allCorrections()
                        val out = JSONObject()
                        out.put("exportedAt", System.currentTimeMillis())
                        out.put("appVersion", try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                        } catch (_: Exception) { "?" })
                        out.put("notifications", JSONArray().apply {
                            for (e in notifications) put(JSONObject().apply {
                                put("id", e.id); put("packageName", e.packageName)
                                put("postTime", e.postTime); put("sbnKey", e.sbnKey)
                                put("sbnId", e.sbnId); put("tag", e.tag); put("uid", e.uid)
                                put("isClearable", e.isClearable); put("isOngoing", e.isOngoing)
                                put("groupKey", e.groupKey); put("whenTime", e.whenTime)
                                put("category", e.category); put("channelId", e.channelId)
                                put("priority", e.priority); put("flags", e.flags)
                                put("template", e.template); put("actions", e.actions)
                                put("title", e.title); put("titleBig", e.titleBig)
                                put("text", e.text); put("bigText", e.bigText)
                                put("subText", e.subText); put("infoText", e.infoText)
                                put("summaryText", e.summaryText)
                                put("textLines", e.textLines); put("messages", e.messages)
                                put("people", e.people); put("progress", e.progress)
                                put("progressMax", e.progressMax)
                                put("showChronometer", e.showChronometer)
                                put("hasPicture", e.hasPicture); put("importance", e.importance)
                                put("receivedAt", e.receivedAt)
                                put("contentHash", e.contentHash)
                                put("removedAt", e.removedAt)
                                put("removeReason", e.removeReason)
                            })
                        })
                        out.put("spending", JSONArray().apply {
                            for (s in spending) put(spendingJson(s))
                        })
                        out.put("corrections", JSONArray().apply {
                            for (c in corrections) put(JSONObject().apply {
                                put("id", c.id); put("spendingId", c.spendingId)
                                put("merchant", c.merchant); put("rawText", c.rawText)
                                put("category", c.category); put("createdAt", c.createdAt)
                            })
                        })
                        call.respondText(out.toString(), ContentType.Application.Json)
                    }
                    // 数据导入：POST JSON 全量回写三张表（保留原始 ID）
                    post("/api/import") {
                        try {
                        val json = JSONObject(call.receiveText())
                        val db = AppDatabase.get(this@WebServerService).openHelper.writableDatabase
                        // 先清空（逆依赖序）
                        db.execSQL("DELETE FROM category_correction")
                        db.execSQL("DELETE FROM spending")
                        db.execSQL("DELETE FROM notification_history")
                        // 恢复通知
                        val notifs = json.getJSONArray("notifications")
                        for (i in 0 until notifs.length()) {
                            val n = notifs.getJSONObject(i)
                            db.execSQL(
                                "INSERT INTO notification_history(id,packageName,postTime,sbnKey,sbnId,tag,uid," +
                                    "isClearable,isOngoing,groupKey,whenTime,category,channelId,priority,flags," +
                                    "template,actions,title,titleBig,text,bigText,subText,infoText,summaryText," +
                                    "textLines,messages,people,progress,progressMax,showChronometer,hasPicture," +
                                    "importance,receivedAt,contentHash,removedAt,removeReason) " +
                                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                arrayOf(
                                    n.getLong("id"), n.getString("packageName"), n.getLong("postTime"),
                                    n.getString("sbnKey"), n.getInt("sbnId"),
                                    n.optString("tag").takeIf { it.isNotEmpty() }, n.optInt("uid").takeIf { n.has("uid") && !n.isNull("uid") },
                                    n.getBoolean("isClearable") as Any, n.getBoolean("isOngoing") as Any,
                                    n.optString("groupKey").takeIf { it.isNotEmpty() }, n.getLong("whenTime"),
                                    n.optString("category").takeIf { it.isNotEmpty() }, n.optString("channelId").takeIf { it.isNotEmpty() },
                                    n.getInt("priority"), n.getInt("flags"),
                                    n.optString("template").takeIf { it.isNotEmpty() }, n.optString("actions").takeIf { it.isNotEmpty() },
                                    n.optString("title").takeIf { it.isNotEmpty() }, n.optString("titleBig").takeIf { it.isNotEmpty() },
                                    n.optString("text").takeIf { it.isNotEmpty() }, n.optString("bigText").takeIf { it.isNotEmpty() },
                                    n.optString("subText").takeIf { it.isNotEmpty() }, n.optString("infoText").takeIf { it.isNotEmpty() },
                                    n.optString("summaryText").takeIf { it.isNotEmpty() }, n.optString("textLines").takeIf { it.isNotEmpty() },
                                    n.optString("messages").takeIf { it.isNotEmpty() }, n.optString("people").takeIf { it.isNotEmpty() },
                                    n.optInt("progress").takeIf { n.has("progress") && !n.isNull("progress") },
                                    n.optInt("progressMax").takeIf { n.has("progressMax") && !n.isNull("progressMax") },
                                    n.getBoolean("showChronometer") as Any, n.getBoolean("hasPicture") as Any,
                                    n.optInt("importance").takeIf { n.has("importance") && !n.isNull("importance") },
                                    n.getLong("receivedAt"), n.getInt("contentHash"),
                                    n.optLong("removedAt").takeIf { n.has("removedAt") && !n.isNull("removedAt") },
                                    n.optInt("removeReason").takeIf { n.has("removeReason") && !n.isNull("removeReason") },
                                )
                            )
                        }
                        // 恢复消费记录
                        val spending = json.getJSONArray("spending")
                        for (i in 0 until spending.length()) {
                            val s = spending.getJSONObject(i)
                            db.execSQL(
                                "INSERT INTO spending(id,notificationId,sourcePackage,txTime,amountCents," +
                                    "merchant,channel,rawText,category,confidence,uncertain,corrected,hidden,createdAt) " +
                                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                arrayOf(
                                    s.getLong("id"), s.getLong("notificationId"), s.getString("sourcePackage"),
                                    s.getLong("txTime"), s.getLong("amountCents"),
                                    s.optString("merchant").takeIf { it.isNotEmpty() }, s.optString("channel").takeIf { it.isNotEmpty() },
                                    "", // rawText not in export, don't need for re-import
                                    s.optString("category").takeIf { it.isNotEmpty() }, s.getDouble("confidence"),
                                    s.getBoolean("uncertain") as Any, s.getBoolean("corrected") as Any,
                                    s.optBoolean("hidden", false) as Any, s.getLong("createdAt"),
                                )
                            )
                        }
                        // 恢复修正日志
                        val corrections = json.getJSONArray("corrections")
                        for (i in 0 until corrections.length()) {
                            val c = corrections.getJSONObject(i)
                            db.execSQL(
                                "INSERT INTO category_correction(id,spendingId,merchant,rawText,category,createdAt) " +
                                    "VALUES(?,?,?,?,?,?)",
                                arrayOf(
                                    c.getLong("id"), c.getLong("spendingId"),
                                    c.optString("merchant").takeIf { it.isNotEmpty() }, c.optString("rawText", ""),
                                    c.getString("category"), c.getLong("createdAt"),
                                )
                            )
                        }
                        // 去重清理：隐藏招行无商户记录（与掌上生活重复，同 SpendPipeline 逻辑）
                        val hidden = db.compileStatement(
                            "UPDATE spending SET hidden = 1 WHERE id IN (" +
                                "SELECT s1.id FROM spending s1 INNER JOIN spending s2 " +
                                "ON s1.amountCents = s2.amountCents " +
                                "AND ABS(s2.txTime - s1.txTime) <= 180000 " +
                                "WHERE s1.hidden = 0 AND s2.hidden = 0 " +
                                "AND s1.sourcePackage = 'cmb.pb' " +
                                "AND s2.sourcePackage = 'com.cmbchina.ccd.pluto.cmbActivity' " +
                                "AND s1.merchant IS NULL AND s2.merchant IS NOT NULL" +
                            ")"
                        ).executeUpdateDelete()
                        // 导入后重训分类器
                        scope.launch { Classifier.retrain(this@WebServerService) }
                        val imported = notifs.length()
                        call.respondText(
                            """{"imported":true,"notifications":$imported,"spending":${spending.length()},"corrections":${corrections.length()},"dedupHidden":$hidden}""",
                            ContentType.Application.Json,
                        )
                        } catch (e: Exception) {
                            Log.e(TAG, "import failed", e)
                            call.respondText(
                                """{"error":"${e.javaClass.simpleName}: ${e.message?.replace("\"","'")}"}""",
                                ContentType.Application.Json, HttpStatusCode.InternalServerError,
                            )
                        }
                    }
                }
            }.also { it.start(wait = false) }
        }
        // 初始化 ANCS BLE 客户端（后台等指令，不自动连接）
        if (ancsClient == null) {
            ancsClient = AncsClient(
                context = this,
                onNotification = { entity ->
                    scope.launch {
                        try {
                            val dao = AppDatabase.get(this@WebServerService).notificationDao()
                            if (dao.lastContentHash(entity.sbnKey) != entity.contentHash) {
                                val insertedId = dao.insert(entity)
                                val withId = entity.copy(id = insertedId)
                                // 更新前台通知计数
                                onCollect(this@WebServerService, isSpending = false)
                                Log.i(TAG, "ANCS stored: ${entity.packageName} / ${entity.title?.take(40)}")
                                // 全屏提醒（如信用卡消费）
                                if (AlertGate.shouldAlert(withId)) {
                                    AlertGate.fireAlert(this@WebServerService, withId)
                                }
                                // 走消费管线：SpendParser 按文本判断是否消费，
                                // 不依赖包名白名单（iOS bundle ID 与 Android 不同，白名单会漏）
                                try {
                                    val created = SpendPipeline.process(this@WebServerService, withId)
                                    if (created) {
                                        onCollect(this@WebServerService, isSpending = true)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "ANCS spend pipeline failed", e)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "ANCS insert failed", e)
                        }
                    }
                },
                onStatus = { msg -> Log.i(TAG, "ANCS: $msg") },
            )
        }
        // 已配对则自动连 ANCS（autoConnect=true：iPhone 可达时由系统自动连上）
        ancsClient?.let { c ->
            if (!c.pairedAddress.isNullOrEmpty() && c.state == AncsClient.State.IDLE) {
                Log.i(TAG, "ANCS: paired address found, auto-connecting")
                c.connectAncs(autoConnect = true)
            }
        }
        isRunning = true
        return START_STICKY
    }

    /** 寻找可用端口：从 8899 往下试 5 次，全占满则让系统随机分配 */
    private fun findAvailablePort(): Int {
        for (offset in 0..5) {
            val port = 8899 - offset
            try {
                java.net.ServerSocket(port).use { it.close() }
                Log.i(TAG, "using port $port")
                return port
            } catch (_: Exception) {
                Log.w(TAG, "port $port unavailable, trying next")
            }
        }
        // 全占满：让 OS 选一个
        val fallback = try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) { 8890 }
        Log.w(TAG, "all preferred ports occupied, using OS-assigned port $fallback")
        return fallback
    }

    // ---- 网络状态监听（WiFi 连上/断开 → 自动刷新 LAN IP） ----

    private fun startNetworkMonitor() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // 首次探测
        refreshLanIp(cm.activeNetwork, cm)
        // 监听变化
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshLanIp(network, cm)
            }
            override fun onLost(network: Network) {
                refreshLanIp(null, cm)
            }
        }
        cm.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun stopNetworkMonitor() {
        networkCallback?.let {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    /** 仅 WiFi 或以太网时探测 LAN IP，否则清除（蜂窝网络 IP 对外不可达） */
    private fun refreshLanIp(network: Network?, cm: ConnectivityManager) {
        val ip = if (network != null) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            ) {
                getLanIp()
            } else null // 蜂窝 / VPN → 不显示
        } else null // 无网络 → 不显示
        val prev = lanIp
        lanIp = ip
        if (prev != ip) {
            Log.i(TAG, "LAN IP: $prev → $ip")
            refreshNotificationText(this)
        }
    }

    override fun onDestroy() {
        isRunning = false
        ancsClient?.close()
        ancsClient = null
        stopNetworkMonitor()
        scope.cancel()
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        server = null
        super.onDestroy()
    }

    /** 用户从最近任务划掉 App 时，服务进程可能被顺带杀死；主动请求重启（配合 START_STICKY） */
    override fun onTaskRemoved(rootIntent: Intent?) {
        startForegroundService(Intent(this, WebServerService::class.java))
        super.onTaskRemoved(rootIntent)
    }

    /** SpendingEntity → API JSON（列表与修正响应共用） */
    private fun spendingJson(s: SpendingEntity): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("notificationId", s.notificationId)
        put("sourcePackage", s.sourcePackage)
        put("txTime", s.txTime)
        put("amountCents", s.amountCents)
        put("merchant", s.merchant)
        put("channel", s.channel)
        put("category", s.category)
        put("confidence", s.confidence)
        put("uncertain", s.uncertain)
        put("corrected", s.corrected)
        put("hidden", s.hidden)
        put("createdAt", s.createdAt) // 落库时间，前端"未读"标记用
        // 候选：实时重算（模型可能已被修正更新，存库里的会过期）
        val cands = JSONArray()
        if (s.merchant != null) {
            for ((cat, prob) in Classifier.classify(s.merchant).candidates) {
                cands.put(JSONObject().put("category", cat).put("prob", prob))
            }
        }
        put("candidates", cands)
    }

    private fun buildNotification(): Notification = buildNotificationStatic(this, "正在准备关注通知后续记录")
}
