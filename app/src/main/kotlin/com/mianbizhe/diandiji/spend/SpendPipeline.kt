package com.mianbizhe.diandiji.spend

import android.content.Context
import android.util.Log
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

    /** 唯一会互发重复通知的组合：招行 App + 掌上生活 + iPhone 招行（同一张信用卡） */
    private val CMB_PAIR = setOf(
        "cmb.pb",
        "com.cmbchina.ccd.pluto.cmbActivity",
        "com.cmbchina.MPBBank",
    )

    /** 串行化处理：防止招行+掌上生活双通知并发入库导致去重失效 */
    private val mutex = Mutex()

    /** @return true if a spending record was actually inserted */
    suspend fun process(context: Context, e: NotificationEntity): Boolean = mutex.withLock {
        val parsed = SpendParser.parse(e) ?: return@withLock false
        Classifier.ensureLoaded(context)
        val dao = AppDatabase.get(context).spendingDao()

        // ---- 去重：同金额 ±3 分钟内、且两个包名都在招行系（招行/掌上生活/iPhone招行） ----
        var hidden = false
        val twins = dao.findDedupCandidates(
            parsed.amountCents, e.postTime - DEDUP_WINDOW_MS, e.postTime + DEDUP_WINDOW_MS,
        ).filter {
            it.sourcePackage != e.packageName &&
                it.sourcePackage in CMB_PAIR && e.packageName in CMB_PAIR
        }
        for (twin in twins) {
            if (parsed.merchant != null && twin.merchant == null) {
                dao.markHidden(twin.id) // 新记录有商户，旧的无 → 隐藏旧的
            } else if (parsed.merchant == null && twin.merchant != null) {
                hidden = true // 反之隐藏自己
            }
            // 都有/都无商户：都保留（该组合实际不会出现这种情况）
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
