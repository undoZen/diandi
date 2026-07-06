package com.mianbizhe.diandiji.spend

import com.mianbizhe.diandiji.db.NotificationEntity

/** 解析结果：金额（分）+ 可选商户/渠道 + 原文 */
data class ParsedSpend(
    val amountCents: Long,
    val merchant: String?,
    val channel: String?,
    val rawText: String,
)

/**
 * 银行消费通知 → ParsedSpend。纯函数，可单测。
 * 三家银行四种文案（实测样文见单测），正则失配返回 null——
 * rawText 全程留在 notification_history，文案改版后可重解析。
 */
object SpendParser {

    /** 手动录入解析结果（比 ParsedSpend 多一个提取到的时间戳） */
    data class ManualParse(
        val amountCents: Long,
        val merchant: String?,
        val channel: String?,
        val txTime: Long,
        val rawText: String,
    )

    /**
     * 通用文本解析（手动录入）：不限定银行，自由文本中提取金额+商户+时间。
     * - 金额：优先靠近"消费/支出/支付/扣款"的数字，其次所有"元/¥"附近数字
     * - 商户：优先"渠道-商户"结构，其次取金额附近的文字片段
     * - 时间：日期/时间正则匹配，找不到用 fallbackTimeMs
     */
    fun parseManual(text: String, fallbackTimeMs: Long): ManualParse? {
        val t = normalize(text)

        // 1) 提取金额
        val amountCents = extractAmount(t) ?: return null

        // 2) 提取商户
        val rawMerchant = extractMerchantManual(t)
        val (channel, merchant) = rawMerchant?.let { extractChannel(it) } ?: (null to rawMerchant)

        // 3) 提取时间
        val txTime = extractTime(t) ?: fallbackTimeMs

        return ManualParse(amountCents, merchant, channel, txTime, text)
    }

    /** 从自由文本中找最像消费金额的数字 */
    private fun extractAmount(text: String): Long? {
        // 候选金额：(数字串) 附额外分数（越像消费金额分越高）
        data class Hit(val s: String, val start: Int, val score: Int)
        val hits = mutableListOf<Hit>()

        // 模式 1：数字 + 元（最可靠）
        Regex("""(\d+\.?\d{0,2})\s*元""").findAll(text).forEach { m ->
            val ctx = text.substring(maxOf(0, m.range.first - 30), minOf(text.length, m.range.last + 30))
            var score = 5
            if (Regex("消费|支出|支付|扣款|花了|付费|付款").containsMatchIn(ctx)) score += 4
            if (Regex("还款|退款|转入|入账|收入|收到|存入|提现").containsMatchIn(ctx)) score -= 6
            hits.add(Hit(m.groupValues[1], m.range.first, score))
        }
        // 模式 2：¥ / ￥ 后面紧跟的数字
        Regex("""[￥¥]\s*(\d+\.?\d{0,2})""").findAll(text).forEach { m ->
            val ctx = text.substring(maxOf(0, m.range.first - 30), minOf(text.length, m.range.last + 30))
            var score = 3
            if (Regex("消费|支出|支付|扣款|花了|付费|付款").containsMatchIn(ctx)) score += 4
            hits.add(Hit(m.groupValues[1], m.range.first, score))
        }
        // 模式 3：-xx.xx 形式的减项
        Regex("""[-−]\s*(\d+\.?\d{0,2})""").findAll(text).forEach { m ->
            hits.add(Hit(m.groupValues[1], m.range.first, 2))
        }

        if (hits.isEmpty()) {
            // 最后兜底：找恰好两位小数的数字
            Regex("""(?<!\d)(\d+\.\d{2})(?!\d)""").findAll(text).forEach { m ->
                hits.add(Hit(m.groupValues[1], m.range.first, 1))
            }
        }

        if (hits.isEmpty()) return null
        // 按分数 desc → 金额 desc 排序
        hits.sortWith(compareByDescending<Hit> { it.score }
            .thenByDescending { it.s.replace(",", "").toDoubleOrNull() ?: 0.0 })
        return toCents(hits.first().s)
    }

    /** 从文本里抓一个商户名：尽量用"渠道-商户"结构，其次取金额前文字 */
    private fun extractMerchantManual(text: String): String? {
        // 先尝试银行级别的具体商户模式（工行/掌上生活/招行快捷 仍然精确）
        for (regex in listOf(
            Regex("""支出[(（]消费(.+?)[)）]"""),             // 工行
            Regex("""在【(.+?)】发生"""),                     // 招行快捷
            Regex("""您在(.+?)有一笔"""),                     // 掌上生活
        )) {
            regex.find(text)?.let { return it.groupValues[1].trim().takeIf { it.isNotEmpty() } }
        }
        // "渠道-商户" 松散结构
        Regex("""([一-龥a-zA-Z]+-[一-龥a-zA-Z()（）…]+)""").find(text)?.let {
            val m = it.groupValues[1]
            // 过滤掉明显不是商户的（如尾号、卡号）
            if (!Regex("尾号|卡号|\\d{4}").containsMatchIn(m)) return m.take(50)
        }
        // 中文名：连续中文字符 ≥4 个，出现在"在/向"之后
        Regex("""[在向]([一-龥]{4,20})[花消费支付购买]""").find(text)?.let {
            return it.groupValues[1]
        }
        return null
    }

    /** 尝试从文本中提取日期时间（返回 epoch millis），失败返回 null */
    private fun extractTime(text: String): Long? {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()

        // "7月6日" 或 "07月06日"
        Regex("""(\d{1,2})月(\d{1,2})日""").find(text)?.let {
            val month = it.groupValues[1].toInt()
            val day = it.groupValues[2].toInt()
            cal.timeInMillis = now
            cal.set(java.util.Calendar.MONTH, month - 1)
            cal.set(java.util.Calendar.DAY_OF_MONTH, day)
            // 时分：后面可能跟着 "11:36"
            Regex("""(\d{1,2}):(\d{2})""").find(text)?.let { tm ->
                cal.set(java.util.Calendar.HOUR_OF_DAY, tm.groupValues[1].toInt())
                cal.set(java.util.Calendar.MINUTE, tm.groupValues[2].toInt())
                cal.set(java.util.Calendar.SECOND, 0)
            }
            return cal.timeInMillis
        }
        // "2026-07-06" ISO 格式
        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(text)?.let {
            cal.timeInMillis = now
            cal.set(it.groupValues[1].toInt(), it.groupValues[2].toInt() - 1, it.groupValues[3].toInt())
            return cal.timeInMillis
        }
        return null
    }

    /** 非消费类通知，整条拒绝 */
    private val REJECT = Regex("还款|入账|转入|退款|退货|发放|利息|账单|逾期|分期|转账")

    private val AMOUNT = """([\d,]+(?:\.\d{1,2})?)"""

    // 招行 A：仅金额无商户。如"信用卡通知：您尾号0000的招行信用卡消费13.10人民币。"
    private val CMB_CARD = Regex("""您尾号\d{4}的招行信用卡消费$AMOUNT(?:元|人民币)""")

    // 招行 B：快捷支付带商户。如"您账户0000于07月05日10:41在【财付通-微信支付-M Stand】发生快捷支付扣款，人民币27.08"
    private val CMB_QUICK = Regex("""在【(.+?)】发生(?:快捷支付)?扣款[，,]\s*人民币$AMOUNT""")

    // 掌上生活。"您在财付通-上海全家便利店有一笔13.10人民币的消费已成功，点击查看详情【...】"
    private val CMB_LIFE = Regex("""您在(.+?)有一笔${AMOUNT}人民币的消费已成功""")

    // 工行。如"尾号0000卡7月5日00:26支出(消费联通支付-中国联通北京分公司手机缴费)47元。"
    private val ICBC = Regex("""支出[(（]消费(.+?)[)）]${AMOUNT}元""")

    /** 支付渠道前缀（出现在商户名 "渠道-商户" 结构里） */
    private val CHANNELS = setOf(
        "财付通", "支付宝", "京东支付", "美团支付", "云闪付", "翼支付", "抖音支付", "度小满", "苏宁易付宝",
    )

    fun parse(e: NotificationEntity): ParsedSpend? {
        val raw = e.bigText ?: e.text ?: return null
        // 标题也参与拒绝词筛查（如"还款提醒"标题+正文只有金额）
        if (REJECT.containsMatchIn("${e.title.orEmpty()} $raw")) return null
        val text = normalize(raw)

        val (merchant, amountStr) = when (e.packageName) {
            "cmb.pb" -> {
                CMB_QUICK.find(text)?.let { it.groupValues[1] to it.groupValues[2] }
                    ?: CMB_CARD.find(text)?.let { null to it.groupValues[1] }
                    ?: return null
            }
            "com.cmbchina.ccd.pluto.cmbActivity" ->
                CMB_LIFE.find(text)?.let { it.groupValues[1] to it.groupValues[2] } ?: return null
            "com.icbc" ->
                ICBC.find(text)?.let { it.groupValues[1] to it.groupValues[2] } ?: return null
            else -> return null
        }

        val cents = toCents(amountStr) ?: return null
        return ParsedSpend(
            amountCents = cents,
            merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
            channel = merchant?.let { extractChannel(it).first },
            rawText = raw,
        )
    }

    /** 全角标点→半角（银行文案两种混用） */
    private fun normalize(s: String): String =
        s.replace('（', '(').replace('）', ')').replace('：', ':').replace('，', ',')

    /** "1,234.5" → 123450 分。整数运算，绝不过浮点 */
    fun toCents(s: String): Long? {
        val clean = s.replace(",", "")
        val parts = clean.split('.')
        if (parts.size > 2) return null
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = (parts.getOrNull(1) ?: "").padEnd(2, '0').take(2).toLongOrNull() ?: return null
        val cents = whole * 100 + frac
        // 0 元或超 100 万元视为解析错误
        return cents.takeIf { it in 1..100_000_000 }
    }

    /**
     * "财付通-微信支付-M Stand" → ("财付通-微信支付", "M Stand")；无渠道前缀 → (null, 原串)。
     * 分类器 tokenizer 也用它。
     */
    fun extractChannel(merchant: String): Pair<String?, String> {
        val i = merchant.indexOf('-')
        if (i <= 0) return null to merchant
        val prefix = merchant.substring(0, i)
        if (prefix !in CHANNELS) return null to merchant
        var channel = prefix
        var body = merchant.substring(i + 1)
        // 财付通-微信支付-X：微信支付折叠进渠道
        if (body.startsWith("微信支付-")) {
            channel = "$channel-微信支付"
            body = body.removePrefix("微信支付-")
        }
        return channel to body
    }
}
