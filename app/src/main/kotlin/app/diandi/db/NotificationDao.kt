package app.diandi.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NotificationDao {

    @Insert
    suspend fun insert(entity: NotificationEntity): Long

    /** 同一 sbnKey 最近一条的内容哈希，用于跳过连续重复投递（进程重启后代替内存缓存） */
    @Query("SELECT contentHash FROM notification_history WHERE sbnKey = :sbnKey ORDER BY id DESC LIMIT 1")
    suspend fun lastContentHash(sbnKey: String): Int?

    /** 通知被移除时，回填该 key 尚未标记移除的行 */
    @Query(
        "UPDATE notification_history SET removedAt = :removedAt, removeReason = :reason " +
            "WHERE sbnKey = :sbnKey AND removedAt IS NULL"
    )
    suspend fun markRemoved(sbnKey: String, removedAt: Long, reason: Int?): Int

    @Query("SELECT COUNT(*) FROM notification_history")
    suspend fun count(): Long

    @Query("SELECT MAX(postTime) FROM notification_history")
    suspend fun latestPostTime(): Long?

    /** 里程碑 3 数据 API 会正式分页；先留一个最近 N 条给调试 */
    @Query("SELECT * FROM notification_history ORDER BY postTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NotificationEntity>

    /** 白名单过滤版：只取指定包名的通知 */
    @Query(
        "SELECT * FROM notification_history WHERE packageName IN (:packages) " +
            "ORDER BY postTime DESC LIMIT :limit"
    )
    suspend fun recentByPackages(packages: Set<String>, limit: Int): List<NotificationEntity>
}
