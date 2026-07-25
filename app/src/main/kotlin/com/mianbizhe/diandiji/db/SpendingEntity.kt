package com.mianbizhe.diandiji.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * spending 表：一行 = 一笔解析成功的消费（来自银行通知）。
 * 本表可从 notification_history 重导（回填幂等），丢了不心疼；
 * 用户修正的类别单独存 category_correction 表（不可再生）。
 */
@Entity(
    tableName = "spending",
    indices = [
        Index("txTime"),
        Index("notificationId"),
        Index(value = ["amountCents", "txTime"]), // 去重查询用
    ],
)
data class SpendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 来源通知行 id（也是回填水位） */
    val notificationId: Long,
    val sourcePackage: String,
    /** 交易时间 = 通知 postTime（v1 简化；文内时间无年份，暂不解析） */
    val txTime: Long,
    /** 金额，单位分。绝不用浮点 */
    val amountCents: Long,
    /** 商户名；招行"信用卡消费xx元"格式无商户 → null */
    val merchant: String?,
    /** 支付渠道前缀（财付通/支付宝/…），展示用 */
    val channel: String?,
    /** 原始通知文本，调试 + 将来重解析用 */
    val rawText: String,
    /** 当前类别（预测值，被修正后 = 用户选择）；null = 无法分类 */
    val category: String?,
    /** 预测时的后验概率 */
    val confidence: Double,
    /** 低置信标记（top<阈值 或与第二名差距小），UI 高亮请用户看一眼 */
    val uncertain: Boolean,
    /** 用户手动改过类别 */
    val corrected: Boolean = false,
    /** 去重败者：留库可审计，一切列表查询过滤掉 */
    val hidden: Boolean = false,
    val createdAt: Long,
)
