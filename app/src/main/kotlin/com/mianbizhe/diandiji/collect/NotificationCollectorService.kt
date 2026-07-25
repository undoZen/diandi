package com.mianbizhe.diandiji.collect

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.mianbizhe.diandiji.AppWhitelist
import com.mianbizhe.diandiji.WebServerService
import com.mianbizhe.diandiji.alert.AlertGate
import com.mianbizhe.diandiji.db.AppDatabase
import com.mianbizhe.diandiji.spend.SpendPipeline
import com.mianbizhe.diandiji.db.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 采集层核心：把每条通知的四层字段抓全并实时落库（DECISIONS.md 第三节）。
 * 设计要点：
 * - 时间轴一律用 sbn.postTime，不用收到时刻（SmsForwarder 用 Date() 的坑）。
 * - 通知更新会重复触发 onNotificationPosted：按 (sbnKey, contentHash) 去重，
 *   进度条类（下载/播放）只在文本变化时才落新行。
 * - 自己的通知（前台服务常驻通知）直接跳过。
 */
class NotificationCollectorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { AppDatabase.get(this).notificationDao() }

    companion object {
        private const val TAG = "diandi-collect"

        /**
         * 采集层丢弃名单（不落库）。加得极克制——本地优先原则是「先存全」，
         * 只丢确定无分析价值的系统杂音：
         * - "android"：系统 UI 常驻提示（正在运行的应用/USB 调试/充电等），量大且无信息量
         */
        private val DROP_PACKAGES = setOf("android")

        /** 监听器是否已连接（授权 + 系统已绑定），给 UI 显示状态用 */
        @Volatile
        var isConnected = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created")
    }

    override fun onListenerConnected() {
        isConnected = true
        Log.i(TAG, "listener connected")
        // 清掉丢弃名单的存量记录（幂等，连接时跑一次开销可忽略）
        scope.launch {
            try {
                val n = dao.deleteByPackages(DROP_PACKAGES)
                if (n > 0) Log.i(TAG, "purged $n rows from dropped packages")
            } catch (e: Exception) {
                Log.e(TAG, "purge failed", e)
            }
        }
    }

    override fun onListenerDisconnected() {
        isConnected = false
        // 系统偶发解绑后不会自动重绑，主动请求（API 24+）
        requestRebind(android.content.ComponentName(this, NotificationCollectorService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // 跳过自己（Ktor 前台服务通知）
        if (sbn.packageName in DROP_PACKAGES) return // 系统杂音直接丢弃

        val entity = try {
            sbn.toEntity()
        } catch (e: Exception) {
            // 单条解析失败不影响后续采集；宽容处理奇葩 App
            Log.w(TAG, "parse failed for ${sbn.packageName}", e)
            return
        }

        scope.launch {
            try {
                if (dao.lastContentHash(entity.sbnKey) == entity.contentHash) return@launch
                val insertedId = dao.insert(entity)
                // 更新前台通知计数
                WebServerService.onCollect(this@NotificationCollectorService, isSpending = false)
                // 命中提醒规则（如信用卡消费）→ 全屏弹出
                if (AlertGate.shouldAlert(entity)) {
                    AlertGate.fireAlert(this@NotificationCollectorService, entity)
                }
                // 财务通知 → 消费管线（解析/去重/分类落 spending 表）
                // 独立 try/catch：管线炸不能影响采集主链路
                if (entity.packageName in AppWhitelist.finance) {
                    try {
                        val created = SpendPipeline.process(applicationContext, entity.copy(id = insertedId))
                        if (created) {
                            WebServerService.onCollect(this@NotificationCollectorService, isSpending = true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "spend pipeline failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "insert failed", e)
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        if (sbn.packageName == packageName) return
        val removedAt = System.currentTimeMillis()
        scope.launch {
            try {
                dao.markRemoved(sbn.key, removedAt, reason)
            } catch (e: Exception) {
                Log.e(TAG, "markRemoved failed", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- 解析 ----------

    private fun StatusBarNotification.toEntity(): NotificationEntity {
        val n = notification
        val extras = n.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
        val messages = parseMessages(n)

        // 哈希覆盖全部语义文本，但不含 progress —— 进度推进不算新内容
        val contentHash = listOf(title, text, bigText, textLines, messages).hashCode()

        return NotificationEntity(
            // 第一层
            packageName = packageName,
            postTime = postTime,
            sbnKey = key,
            sbnId = id,
            tag = tag,
            uid = if (Build.VERSION.SDK_INT >= 29) uid else null,
            isClearable = isClearable,
            isOngoing = isOngoing,
            groupKey = groupKey,
            // 第二层
            whenTime = n.`when`,
            category = n.category,
            channelId = n.channelId,
            priority = n.priority,
            flags = n.flags,
            template = extras.getString(Notification.EXTRA_TEMPLATE)
                ?.substringAfterLast('$'), // 只留 MessagingStyle 等短名
            actions = n.actions
                ?.mapNotNull { it.title?.toString() }
                ?.takeIf { it.isNotEmpty() }
                ?.let { JSONArray(it).toString() },
            // 第三层
            title = title,
            titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString(),
            text = text,
            bigText = bigText,
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString(),
            textLines = textLines,
            messages = messages,
            people = parsePeople(n),
            progress = extras.takeIf { it.containsKey(Notification.EXTRA_PROGRESS) }
                ?.getInt(Notification.EXTRA_PROGRESS),
            progressMax = extras.takeIf { it.containsKey(Notification.EXTRA_PROGRESS_MAX) }
                ?.getInt(Notification.EXTRA_PROGRESS_MAX)?.takeIf { it > 0 },
            showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
            hasPicture = extras.containsKey(Notification.EXTRA_PICTURE),
            // 第四层
            importance = rankingImportance(key),
            // 元数据
            receivedAt = System.currentTimeMillis(),
            contentHash = contentHash,
        )
    }

    /** MessagingStyle 消息列表 → JSON：[{"sender":..,"time":..,"text":..},...] */
    private fun parseMessages(n: Notification): String? {
        val parcelables = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val arr = JSONArray()
        for (p in parcelables) {
            val bundle = p as? android.os.Bundle ?: continue
            // sender 两种形态：API 28+ 存 Person（key=sender_person），更早存 CharSequence（key=sender）
            val senderPerson = if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION")
                (bundle.getParcelable("sender_person") as? android.app.Person)?.name?.toString()
            } else null
            arr.put(JSONObject().apply {
                put("sender", senderPerson ?: bundle.getCharSequence("sender")?.toString())
                put("time", bundle.getLong("time"))
                put("text", bundle.getCharSequence("text")?.toString())
            })
        }
        return arr.takeIf { it.length() > 0 }?.toString()
    }

    private fun parsePeople(n: Notification): String? {
        val extras = n.extras
        if (Build.VERSION.SDK_INT >= 28) {
            val list = extras.getParcelableArrayList<android.app.Person>(
                Notification.EXTRA_PEOPLE_LIST
            )
            if (!list.isNullOrEmpty()) {
                return JSONArray(list.mapNotNull { it.name?.toString() ?: it.uri }).toString()
            }
        }
        @Suppress("DEPRECATION")
        val legacy = extras.getStringArray(Notification.EXTRA_PEOPLE)
        return legacy?.takeIf { it.isNotEmpty() }?.let { JSONArray(it.toList()).toString() }
    }

    private fun rankingImportance(key: String): Int? {
        val ranking = Ranking()
        if (currentRanking?.getRanking(key, ranking) != true) return null
        // getChannel() API 28+；26/27 退回监听器侧 importance
        return if (Build.VERSION.SDK_INT >= 28) {
            ranking.channel?.importance ?: ranking.importance
        } else {
            ranking.importance
        }
    }
}
