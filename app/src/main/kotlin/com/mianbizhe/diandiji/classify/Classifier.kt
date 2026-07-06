package com.mianbizhe.diandiji.classify

import android.content.Context
import com.mianbizhe.diandiji.db.AppDatabase
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/** 一次预测的输出 */
data class Prediction(
    val category: String,
    val confidence: Double,
    val uncertain: Boolean,
    /** top-3 候选（含 category 本身），UI 弹层置顶用 */
    val candidates: List<Pair<String, Double>>,
)

/**
 * 训练数据 + 朴素贝叶斯多类分类器（思路源自 PG spam.html / better.html，改造为多类）。
 *
 * 训练输入两路合并计数：
 * - assets/training.json：历史账本样本（权重 1）
 * - Room category_correction：用户修正（权重 CORRECTION_WEIGHT=3，PG 的"偏置用户标注"）
 *
 * 分层决策见 [classify]。全部纯计数，无 ML 依赖；2k 样本全量重建 <100ms，
 * 修正后直接 retrain。
 */
object Classifier {

    private const val CORRECTION_WEIGHT = 3
    private const val ALPHA = 0.5           // Laplace 平滑
    private const val TOP_TOKENS = 15       // PG "只用最有趣的 N 个 token"
    private const val MIN_TOKEN_COUNT = 2   // 语料总次数低于此的 token 忽略
    private const val EXACT_SHARE = 0.8     // 精确商户层：份额阈值
    private const val UNCERTAIN_TOP = 0.5   // uncertain 判定：top 置信下限
    private const val UNCERTAIN_MARGIN = 0.15 // uncertain 判定：与第二名最小差距

    const val UNKNOWN = "Unknown"

    private class Model(
        val categories: List<String>,
        val docCount: DoubleArray,           // 每类文档数（加权）
        val tokenCount: Map<String, DoubleArray>, // token → 每类出现数（加权）
        val tokenTotal: DoubleArray,         // 每类 token 总数
        val vocabSize: Int,
        val merchantIndex: Map<String, DoubleArray>, // 归一化商户全名 → 每类计数
        val keywords: List<Pair<String, Int>>,       // (关键词, 类别idx)，按长度降序
    )

    @Volatile
    private var model: Model? = null

    val categories: List<String>
        get() = model?.categories ?: emptyList()

    /** 进程内首次使用时加载；修正后调用 [retrain] 重建 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (model == null) model = build(context)
    }

    @Synchronized
    fun retrain(context: Context) {
        model = build(context)
    }

    private fun build(context: Context): Model {
        // 优先加载预训练模型（不含原始记录），其次训练数据
        val json = try {
            context.assets.open("model.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            context.assets.open("training.json").bufferedReader().use { it.readText() }
        }
        // 如果是 model.json 格式（有 tokenCount 字段），直接走模型加载
        val root = JSONObject(json)
        val corrections = kotlinx.coroutines.runBlocking {
            AppDatabase.get(context).spendingDao().allCorrections()
        }.mapNotNull { c -> c.merchant?.let { it to c.category } }
        return if (root.has("tokenCount")) {
            loadModelFromJson(json, corrections)
        } else {
            buildFromJson(json, corrections)
        }
    }

    /** 单测入口：直接喂 JSON 与修正对，绕开 Android Context/Room */
    @Synchronized
    fun loadFromJson(json: String, corrections: List<Pair<String, String>> = emptyList()) {
        model = if (JSONObject(json).has("tokenCount")) {
            loadModelFromJson(json, corrections)
        } else {
            buildFromJson(json, corrections)
        }
    }

    /** 从预训练模型 JSON 加载（脱敏后的聚合统计，不含原始商户记录） */
    private fun loadModelFromJson(json: String, corrections: List<Pair<String, String>>): Model {
        val root = JSONObject(json)
        val cats = root.getJSONArray("categories").let { a -> List(a.length()) { a.getString(it) } }
        val k = cats.size

        val docCount = DoubleArray(k)
        val arr = root.getJSONArray("docCount")
        for (i in 0 until k) docCount[i] = arr.getDouble(i)

        val tokenTotal = DoubleArray(k)
        val arr2 = root.getJSONArray("tokenTotal")
        for (i in 0 until k) tokenTotal[i] = arr2.getDouble(i)

        val tokenCount = HashMap<String, DoubleArray>()
        val tc = root.getJSONObject("tokenCount")
        for (key in tc.keys()) {
            val v = tc.getJSONArray(key)
            val da = DoubleArray(k)
            for (i in 0 until k) da[i] = v.getDouble(i)
            tokenCount[key] = da
        }

        val merchantIndex = HashMap<String, DoubleArray>()
        val mi = root.getJSONObject("merchantIndex")
        for (key in mi.keys()) {
            val v = mi.getJSONArray(key)
            val da = DoubleArray(k)
            for (i in 0 until k) da[i] = v.getDouble(i)
            merchantIndex[key] = da
        }

        val kw = mutableListOf<Pair<String, Int>>()
        val kwa = root.getJSONArray("keywords")
        for (i in 0 until kwa.length()) {
            val pair = kwa.getJSONArray(i)
            kw += pair.getString(0) to pair.getInt(1)
        }
        // 合并用户修正
        for ((merchant, category) in corrections) {
            val idx = cats.indexOf(category)
            if (idx >= 0) {
                val normalized = Tokenizer.normalize(merchant)
                docCount[idx] += CORRECTION_WEIGHT
                merchantIndex.getOrPut(normalized) { DoubleArray(k) }[idx] += CORRECTION_WEIGHT.toDouble()
                for (t in Tokenizer.tokenize(merchant)) {
                    tokenCount.getOrPut(t) { DoubleArray(k) }[idx] += CORRECTION_WEIGHT.toDouble()
                    tokenTotal[idx] += CORRECTION_WEIGHT.toDouble()
                }
            }
        }

        return Model(cats, docCount, tokenCount, tokenTotal, tokenCount.size, merchantIndex, kw)
    }

    /** 导出训练后的模型为 JSON（纯聚合统计，不含原始记录，可安全公开） */
    fun exportModel(): String {
        val m = model ?: return "{}"
        val root = JSONObject()
        root.put("version", 2)
        root.put("categories", org.json.JSONArray(m.categories))
        root.put("docCount", org.json.JSONArray(m.docCount.toList()))
        root.put("tokenTotal", org.json.JSONArray(m.tokenTotal.toList()))
        val tc = JSONObject()
        for ((k, v) in m.tokenCount) tc.put(k, org.json.JSONArray(v.toList()))
        root.put("tokenCount", tc)
        val mi = JSONObject()
        for ((k, v) in m.merchantIndex) mi.put(k, org.json.JSONArray(v.toList()))
        root.put("merchantIndex", mi)
        val kw = org.json.JSONArray()
        for ((word, idx) in m.keywords) kw.put(org.json.JSONArray(listOf(word, idx)))
        root.put("keywords", kw)
        return root.toString()
    }

    private fun buildFromJson(json: String, corrections: List<Pair<String, String>>): Model {
        val root = JSONObject(json)
        val cats = root.getJSONArray("categories").let { a -> List(a.length()) { a.getString(it) } }
        val k = cats.size

        val docCount = DoubleArray(k)
        val tokenCount = HashMap<String, DoubleArray>()
        val tokenTotal = DoubleArray(k)
        val merchantIndex = HashMap<String, DoubleArray>()

        fun addSample(payee: String, cat: Int, weight: Double) {
            docCount[cat] += weight
            val normalized = Tokenizer.normalize(payee)
            merchantIndex.getOrPut(normalized) { DoubleArray(k) }[cat] += weight
            for (t in Tokenizer.tokenize(payee)) {
                tokenCount.getOrPut(t) { DoubleArray(k) }[cat] += weight
                tokenTotal[cat] += weight
            }
        }

        val samples = root.getJSONArray("samples")
        for (i in 0 until samples.length()) {
            val pair = samples.getJSONArray(i)
            addSample(pair.getString(0), pair.getInt(1), 1.0)
        }

        for ((merchant, category) in corrections) {
            val idx = cats.indexOf(category)
            if (idx >= 0) addSample(merchant, idx, CORRECTION_WEIGHT.toDouble())
        }

        // 关键词表：catIdx → words，铺平后按长度降序（多命中取最长）
        val kw = mutableListOf<Pair<String, Int>>()
        val kwJson = root.getJSONObject("keywords")
        for (key in kwJson.keys()) {
            val idx = key.toInt()
            val arr = kwJson.getJSONArray(key)
            for (i in 0 until arr.length()) kw += Tokenizer.normalize(arr.getString(i)) to idx
        }
        kw.sortByDescending { it.first.length }

        return Model(cats, docCount, tokenCount, tokenTotal, tokenCount.size, merchantIndex, kw)
    }

    /**
     * 分层决策：
     * L0 无商户 → Unknown（等去重救回或人工修正）
     * L1 精确商户命中（历史/修正里见过这家店，且 ≥80% 归同一类）→ 直接用
     * L2 关键词表子串命中 → 0.95（人工规则，最长关键词优先）
     * L3 朴素贝叶斯 bigram 兜底
     */
    fun classify(merchant: String?): Prediction {
        val m = model ?: return Prediction(UNKNOWN, 0.0, true, emptyList())
        if (merchant.isNullOrBlank()) return Prediction(UNKNOWN, 0.0, true, emptyList())

        val normalized = Tokenizer.normalize(merchant)

        // L1 精确商户
        m.merchantIndex[normalized]?.let { counts ->
            val total = counts.sum()
            if (total > 0) {
                val best = counts.indices.maxBy { counts[it] }
                val share = counts[best] / total
                if (share >= EXACT_SHARE) {
                    return Prediction(m.categories[best], share, false, listOf(m.categories[best] to share))
                }
            }
        }

        // L2 关键词
        for ((word, idx) in m.keywords) {
            if (word.isNotEmpty() && normalized.contains(word)) {
                return Prediction(m.categories[idx], 0.95, false, listOf(m.categories[idx] to 0.95))
            }
        }

        // L3 贝叶斯
        return bayes(m, merchant)
    }

    private fun bayes(m: Model, merchant: String): Prediction {
        val k = m.categories.size
        val n = m.docCount.sum()

        val all = Tokenizer.tokenize(merchant)
        // token 筛选：掉线的（语料太少）不投票；剩下按"区分度"取 top N。
        // 区分度 = 各类条件概率的极差（PG "interesting = 离中性最远"的多类版）
        data class Scored(val token: String, val interest: Double)
        val scored = all.distinct().mapNotNull { t ->
            val counts = m.tokenCount[t] ?: return@mapNotNull null
            if (counts.sum() < MIN_TOKEN_COUNT) return@mapNotNull null
            val probs = DoubleArray(k) { c -> (counts[c] + ALPHA) / (m.tokenTotal[c] + ALPHA * m.vocabSize) }
            val norm = probs.sum()
            val shares = probs.map { it / norm }
            Scored(t, shares.max() - shares.min())
        }.sortedByDescending { it.interest }
        // M: token 恒保留（若在语料中），其余取 top
        val selected = (scored.filter { it.token.startsWith("M:") } +
            scored.filter { !it.token.startsWith("M:") }.take(TOP_TOKENS)).map { it.token }.distinct()

        if (selected.isEmpty()) {
            // 一个已知 token 都没有：无证据，按先验给但标 uncertain
            val best = m.docCount.indices.maxBy { m.docCount[it] }
            return Prediction(m.categories[best], m.docCount[best] / n, true, emptyList())
        }

        // log 后验
        val logScore = DoubleArray(k) { c -> ln((m.docCount[c] + 1) / (n + k)) }
        for (t in selected) {
            val counts = m.tokenCount[t]!!
            for (c in 0 until k) {
                logScore[c] += ln((counts[c] + ALPHA) / (m.tokenTotal[c] + ALPHA * m.vocabSize))
            }
        }
        // softmax（先移轴防溢出）
        val maxLog = logScore.max()
        val expScores = DoubleArray(k) { exp(logScore[it] - maxLog) }
        val sum = expScores.sum()
        val posterior = DoubleArray(k) { expScores[it] / sum }

        val order = posterior.indices.sortedByDescending { posterior[it] }
        val top = order[0]
        val topP = posterior[top]
        val secondP = if (k > 1) posterior[order[1]] else 0.0
        val uncertain = topP < UNCERTAIN_TOP || (topP - secondP) < UNCERTAIN_MARGIN
        val candidates = order.take(3).map { m.categories[it] to posterior[it] }

        return Prediction(m.categories[top], topP, uncertain, candidates)
    }
}
