package com.mianbizhe.diandiji.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SpendingDao {

    @Insert
    suspend fun insert(e: SpendingEntity): Long

    @Query("SELECT * FROM spending WHERE id = :id")
    suspend fun get(id: Long): SpendingEntity?

    @Query("SELECT * FROM spending WHERE hidden = 0 ORDER BY txTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SpendingEntity>

    /** 时间范围版（dashboard 统计用） */
    @Query(
        "SELECT * FROM spending WHERE hidden = 0 AND txTime >= :fromTime " +
            "ORDER BY txTime DESC LIMIT :limit"
    )
    suspend fun recentSince(fromTime: Long, limit: Int): List<SpendingEntity>

    @Query(
        "UPDATE spending SET category = :category, confidence = :confidence, " +
            "uncertain = 0, corrected = :corrected WHERE id = :id"
    )
    suspend fun updateCategory(id: Long, category: String, confidence: Double, corrected: Boolean)

    /** 去重候选：同金额、时间窗内、未隐藏 */
    @Query(
        "SELECT * FROM spending WHERE hidden = 0 AND amountCents = :amountCents " +
            "AND txTime BETWEEN :fromTime AND :toTime"
    )
    suspend fun findDedupCandidates(amountCents: Long, fromTime: Long, toTime: Long): List<SpendingEntity>

    @Query("UPDATE spending SET hidden = 1 WHERE id = :id")
    suspend fun markHidden(id: Long)

    /** 回填水位：已处理过的最大通知 id */
    @Query("SELECT MAX(notificationId) FROM spending")
    suspend fun maxNotificationId(): Long?

    // ---- 修正日志 ----

    @Insert
    suspend fun insertCorrection(e: CorrectionEntity): Long

    @Query("SELECT * FROM category_correction ORDER BY id ASC")
    suspend fun allCorrections(): List<CorrectionEntity>
}
