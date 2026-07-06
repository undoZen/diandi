package com.mianbizhe.diandiji.classify

import com.mianbizhe.diandiji.spend.SpendParser

/**
 * 商户名 → token 列表。中文无空格 → CJK 连跑取字符 bigram；
 * 拉丁/数字连跑取整词；另加两个强 token：
 * - "M:全商户名"——PG degeneration 思想里最特异的一层，精确命中时证据最强
 * - "C:渠道"——支付渠道本身就有类别倾向（美团支付≈吃喝，京东支付≈网购）
 */
object Tokenizer {

    fun tokenize(merchant: String): List<String> {
        val normalized = normalize(merchant)
        if (normalized.isEmpty()) return emptyList()

        val tokens = mutableListOf("M:$normalized")
        val (channel, body) = SpendParser.extractChannel(normalized)
        if (channel != null) tokens += "C:$channel"

        // 正文切连跑：CJK / 拉丁数字 / 其他(分隔)
        var run = StringBuilder()
        var runIsCjk = false
        fun flush() {
            if (run.isEmpty()) return
            val s = run.toString()
            if (runIsCjk) {
                if (s.length == 1) tokens += s
                else for (i in 0 until s.length - 1) tokens += s.substring(i, i + 2)
            } else {
                tokens += s
            }
            run = StringBuilder()
        }
        for (ch in body) {
            val cjk = ch.code in 0x4E00..0x9FFF
            val word = cjk || ch.isLetterOrDigit() || ch == '*'
            if (!word) { flush(); continue }
            if (run.isNotEmpty() && cjk != runIsCjk) flush()
            runIsCjk = cjk
            run.append(ch)
        }
        flush()
        return tokens
    }

    /** 全角→半角、大写、修剪；去尾部不配对的左括号段（journal 商户名被截断的常见形态） */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s.trim()) {
            val c = when {
                ch == '（' -> '('
                ch == '）' -> ')'
                ch == '　' -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar() // 全角 ASCII 区
                else -> ch
            }
            sb.append(c.uppercaseChar())
        }
        var out = sb.toString().trim()
        // "美团App龙诚健康旗舰店（快递电商" 被截断：尾部有 ( 无配对 ) → 砍掉尾段
        val lp = out.lastIndexOf('(')
        if (lp >= 0 && out.indexOf(')', lp) < 0) out = out.substring(0, lp).trim()
        return out
    }
}
