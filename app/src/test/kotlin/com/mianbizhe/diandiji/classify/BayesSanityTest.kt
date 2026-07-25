package com.mianbizhe.diandiji.classify

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 贝叶斯回归线。
 * - holdoutAccuracy：需要 training.json（含原始样本）。
 * - correctionOverridesBulk：用预训练 model.json 验证分层决策。
 */
class BayesSanityTest {

    @Test
    fun holdoutAccuracy() {
        val f = File("src/main/assets/training.json")
        if (!f.exists()) {
            println("holdoutAccuracy: skipped (training.json not present, model.json is the source of truth)")
            return
        }
        val root = JSONObject(f.readText())
        val samples = root.getJSONArray("samples")
        val cats = root.getJSONArray("categories")

        val train = JSONArray()
        val test = mutableListOf<Pair<String, Int>>()
        for (i in 0 until samples.length()) {
            val pair = samples.getJSONArray(i)
            if (i % 10 == 9) test += pair.getString(0) to pair.getInt(1)
            else train.put(pair)
        }

        val trainJson = JSONObject()
            .put("version", 1)
            .put("categories", cats)
            .put("keywords", root.getJSONObject("keywords"))
            .put("samples", train)
        Classifier.loadFromJson(trainJson.toString())

        var correct = 0
        for ((payee, catIdx) in test) {
            val pred = Classifier.classify(payee)
            if (pred.category == cats.getString(catIdx)) correct++
        }
        val acc = correct.toDouble() / test.size
        println("holdout accuracy: $correct/${test.size} = ${"%.3f".format(acc)}")
        assertTrue("holdout top-1 acc $acc <= 0.7，分类器或 tokenizer 退化", acc > 0.7)
    }

    @Test
    fun correctionOverridesBulk() {
        val src = File("src/main/assets/model.json").takeIf { it.exists() }?.readText()
            ?: File("src/main/assets/training.json").readText()
        // 一个虚构商户：无历史 → 先验/未知；加 1 条修正后 → 精确层直接命中
        Classifier.loadFromJson(src)
        val before = Classifier.classify("XX虚构测试商户XX")
        assertTrue(before.uncertain || before.category != "医药")

        Classifier.loadFromJson(src, listOf("XX虚构测试商户XX" to "医药"))
        val after = Classifier.classify("XX虚构测试商户XX")
        assertTrue("修正后应精确命中医药，实际 ${after.category}", after.category == "医药" && !after.uncertain)
    }
}
