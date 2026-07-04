package app.diandi.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * notification_history 宽表：一行 = 一次通知投递，四层字段尽量抓全（见 DECISIONS.md 第三节）。
 * 原则：通知被清除即消失，所以宁多勿少、先落库后理解；理解层只读此表。
 */
@Entity(
    tableName = "notification_history",
    indices = [
        Index("packageName"),
        Index("postTime"),
        Index("sbnKey"),
    ],
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // ---- 第一层：StatusBarNotification ----
    val packageName: String,
    /** 通知真实发出时间（务必用它做时间轴，而非收到时刻） */
    val postTime: Long,
    /** 系统去重键（同一条通知更新时 key 不变） */
    val sbnKey: String,
    val sbnId: Int,
    val tag: String?,
    /** API 29+ 才能取到，低版本为 null */
    val uid: Int?,
    val isClearable: Boolean,
    /** 常驻通知（音乐播放/下载/本 App 前台服务等），分析时通常单独处理 */
    val isOngoing: Boolean,
    val groupKey: String?,

    // ---- 第二层：Notification 本体 ----
    /** Notification.when，App 声称的事件时间（聊天消息常用） */
    val whenTime: Long,
    /** 系统预定义语义分类：msg/email/call/alarm/event/… 一级分类直接用它 */
    val category: String?,
    val channelId: String?,
    val priority: Int,
    val flags: Int,
    /** EXTRA_TEMPLATE：样式类名（MessagingStyle/BigTextStyle/…），决定解析策略 */
    val template: String?,
    /** 动作按钮标题，JSON 数组字符串 */
    val actions: String?,

    // ---- 第三层：extras（信息量最大）----
    val title: String?,
    val titleBig: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val summaryText: String?,
    /** EXTRA_TEXT_LINES（收件箱样式多行），\n 连接 */
    val textLines: String?,
    /** MessagingStyle 消息列表（谁/何时/说了什么），JSON 数组字符串——聊天类金矿 */
    val messages: String?,
    /** 关联联系人（EXTRA_PEOPLE_LIST），JSON 数组字符串 */
    val people: String?,
    val progress: Int?,
    val progressMax: Int?,
    val showChronometer: Boolean,
    /** 只记录有没有图，不存位图本体 */
    val hasPicture: Boolean,

    // ---- 第四层：Ranking ----
    /** 系统评定的渠道重要度 NotificationManager.IMPORTANCE_* */
    val importance: Int?,

    // ---- 采集元数据 ----
    /** 本 App 收到并落库的时刻（诊断用；时间轴用 postTime） */
    val receivedAt: Long,
    /** 通知被移除的时刻（用户划掉/App 撤回），null = 尚在通知栏或未观测到移除 */
    val removedAt: Long? = null,
    /** 移除原因 NotificationListenerService.REASON_*（区分用户划掉 vs App 自己撤回） */
    val removeReason: Int? = null,
    /** 文本内容哈希（不含 progress），用于跳过连续重复投递 */
    val contentHash: Int,
)
