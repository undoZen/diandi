package com.mianbizhe.diandiji.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 类别修正日志（append-only）。
 * 独立成表的原因：spending 表可从 notification_history 重导随时可弃，
 * 但用户的手动修正是不可再生的劳动，也是分类器的高权重训练数据——
 * 重新回填后依然凭本表恢复训练效果。
 */
@Entity(tableName = "category_correction")
data class CorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val spendingId: Long,
    /** 修正时该行的商户名（训练输入；无商户的修正训练不了，只改行） */
    val merchant: String?,
    val rawText: String,
    /** 用户选定的类别 */
    val category: String,
    val createdAt: Long,
)
