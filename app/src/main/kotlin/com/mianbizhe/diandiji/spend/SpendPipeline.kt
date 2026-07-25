package com.mianbizhe.diandiji.spend

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.mianbizhe.diandiji.AppWhitelist
import com.mianbizhe.diandiji.classify.Classifier
import com.mianbizhe.diandiji.db.AppDatabase
import com.mianbizhe.diandiji.db.NotificationEntity
import com.mianbizhe.diandiji.db.SpendingEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 消费管线：通知 → 解析 → 去重 → 分类 → spending 表。
 * 幂等回填：水位 = spending.maxNotificationId，历史通知全量扫一遍；
 * 解析失败的行不占水位记录，下次回填会重扫——无害（parse 纯函数）且文案改版后自愈。
 */
object SpendPipeline {

    private const val TAG = "diandi-spend"

    /** 去重时间窗：同一笔交易两条通知的最大间隔 */
    private const val DEDUP_WINDOW_MS = 3 * 60 * 1000L

    /**
     * 家族去重清理 SQL（运行时从 AppWhitelist.BANK_FAMILIES 生成，单一来源）：
     * 家族内隐藏「无商户且有有商户孪生」的记录。
     * 注意必须有 EXISTS 孪生条件：无商户且无孪生的是真实独立交易
     * （如 iPhone 掌上生活没推通知时），不能误隐藏。
     */
    fun hideFamilyDuplicatesQuery(): SupportSQLiteQuery {
        val list = AppWhitelist.FAMILY_PACKAGES.joinToString(",") { "'$it'" }
        return SimpleSQLiteQuery(
            "UPDATE spending SET hidden = 1 WHERE hidden = 0 AND merchant IS NULL " +
                "AND sourcePackage IN ($list) " +
                "AND EXISTS (SELECT 1 FROM spending s2 WHERE s2.hidden = 0 " +
                "AND s2.merchant IS NOT NULL AND s2.sourcePackage != spending.sourcePackage " +
                "AND s2.sourcePackage IN ($list) " +
                "AND s2.amountCents = spending.amountCents " +
                "AND ABS(s2.txTime - spending.txTime) <= $DEDUP_WINDOW_MS)"
        )
    }

    /** 串行化处理：防止招行+掌上生活双通知并发入库导致去重失效 */
    private val mutex = Mutex()

    /** @return true if a spending record was actually inserted */
    suspend fun process(context: Context, e: NotificationEntity): Boolean = mutex.withLock {
        val parsed = SpendParser.parse(e) ?: return@withLock false
        Classifier.ensureLoaded(context)
        val dao = AppDatabase.get(context).spendingDao()

        // ---- 去重：同金额 ±3 分钟内、且与已有记录属于同一银行家族（不同包名） ----
        var hidden = false
        val myFamily = AppWhitelist.familyOf(e.packageName)
        val twins = dao.findDedupCandidates(
            parsed.amountCents, e.postTime - DEDUP_WINDOW_MS, e.postTime + DEDUP_WINDOW_MS,
        ).filter {
            it.sourcePackage != e.packageName && myFamily != null &&
                AppWhitelist.familyOf(it.sourcePackage) == myFamily
        }
        for (twin in twins) {
            when {
                // 一方有商户一方没有：隐藏没商户的（商户是分类的关键信息）
                parsed.merchant != null && twin.merchant == null -> dao.markHidden(twin.id)
                parsed.merchant == null && twin.merchant != null -> hidden = true
                // 都有商户（如 iPhone 招行"财付通-微信支付-扫二维码付款"撞上掌上生活的具体商户）：
                // 留信息量大的——更长的商户名通常更具体
                parsed.merchant != null && twin.merchant != null -> {
                    if (twin.merchant!!.length >= parsed.merchant.length) hidden = true
                    else dao.markHidden(twin.id)
                }
                // 都无商户：判断不了优劣，都保留（人工确认）
            }
        }

        val prediction = Classifier.classify(parsed.merchant)
        dao.insert(
            SpendingEntity(
                notificationId = e.id,
                sourcePackage = e.packageName,
                txTime = e.postTime,
                amountCents = parsed.amountCents,
                merchant = parsed.merchant,
                channel = parsed.channel,
                rawText = parsed.rawText,
                category = prediction.category,
                confidence = prediction.confidence,
                uncertain = prediction.uncertain,
                hidden = hidden,
                createdAt = System.currentTimeMillis(),
            )
        )
        true
    }

    /** 全量回填（幂等）：处理水位之后的所有财务通知。服务启动时跑一次 */
    suspend fun backfill(context: Context) {
        try {
            val db = AppDatabase.get(context)
            val watermark = db.spendingDao().maxNotificationId() ?: 0
            val pending = db.notificationDao().financeAfterId(AppWhitelist.finance, watermark)
            if (pending.isEmpty()) return
            Log.i(TAG, "backfill: ${pending.size} finance notifications after id=$watermark")
            var inserted = 0
            for (e in pending) {
                try {
                    process(context, e)
                    inserted++
                } catch (ex: Exception) {
                    Log.w(TAG, "backfill row ${e.id} failed", ex)
                }
            }
            Log.i(TAG, "backfill done: $inserted processed")
        } catch (ex: Exception) {
            Log.e(TAG, "backfill failed", ex)
        }
    }
}
