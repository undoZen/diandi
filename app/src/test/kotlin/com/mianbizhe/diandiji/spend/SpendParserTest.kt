package com.mianbizhe.diandiji.spend

import com.mianbizhe.diandiji.db.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpendParserTest {

    private fun notif(pkg: String, text: String, title: String? = null) = NotificationEntity(
        packageName = pkg, postTime = 0, sbnKey = "k", sbnId = 1, tag = null, uid = null,
        isClearable = true, isOngoing = false, groupKey = null, whenTime = 0,
        category = null, channelId = null, priority = 0, flags = 0, template = null,
        actions = null, title = title, titleBig = null, text = text, bigText = null,
        subText = null, infoText = null, summaryText = null, textLines = null,
        messages = null, people = null, progress = null, progressMax = null,
        showChronometer = false, hasPicture = false, importance = null,
        receivedAt = 0, contentHash = 0,
    )

    // ---- 四条真机实测样文 ----

    @Test
    fun cmbCardNoMerchant() {
        val p = SpendParser.parse(notif("cmb.pb", "信用卡通知：您尾号0000的招行信用卡消费13.10人民币。"))!!
        assertEquals(1310, p.amountCents)
        assertNull(p.merchant)
    }

    @Test
    fun cmbQuickPayWithMerchant() {
        val p = SpendParser.parse(
            notif("cmb.pb", "您账户0000于07月05日10:41在【财付通-微信支付-M Stand】发生快捷支付扣款，人民币27.08")
        )!!
        assertEquals(2708, p.amountCents)
        assertEquals("财付通-微信支付-M Stand", p.merchant)
        assertEquals("财付通-微信支付", p.channel)
    }

    @Test
    fun cmbLife() {
        val p = SpendParser.parse(
            notif(
                "com.cmbchina.ccd.pluto.cmbActivity",
                "您在财付通-上海全家便利店有一笔13.10人民币的消费已成功，点击查看详情【799积分可兑星巴克中杯饮品，点击查看】"
            )
        )!!
        assertEquals(1310, p.amountCents)
        assertEquals("财付通-上海全家便利店", p.merchant)
        assertEquals("财付通", p.channel)
    }

    @Test
    fun icbc() {
        val p = SpendParser.parse(
            notif("com.icbc", "尾号0000卡7月5日00:26支出(消费联通支付-中国联通北京分公司手机缴费)47元。请点击查看详情。")
        )!!
        assertEquals(4700, p.amountCents)
        assertEquals("联通支付-中国联通北京分公司手机缴费", p.merchant)
    }

    // ---- 拒绝样例 ----

    @Test
    fun rejectRepayment() {
        assertNull(SpendParser.parse(notif("cmb.pb", "您尾号0000的招行信用卡还款1000.00人民币已入账。")))
    }

    @Test
    fun rejectBill() {
        assertNull(SpendParser.parse(notif("com.cmbchina.ccd.pluto.cmbActivity", "您的10月账单已出，应还5432.10元", title = "账单提醒")))
    }

    @Test
    fun rejectTestAlertFakeText() {
        // /api/test-alert 的伪造文本不匹配任何银行正则 → 不会混进 spending
        assertNull(SpendParser.parse(notif("com.icbc", "您尾号1234的信用卡消费￥88.00（测试）")))
    }

    @Test
    fun rejectUnknownPackage() {
        assertNull(SpendParser.parse(notif("com.android.mms", "您尾号0000的招行信用卡消费13.10人民币。")))
    }

    // ---- 金额边界 ----

    @Test
    fun amountWithComma() {
        assertEquals(123456L, SpendParser.toCents("1,234.56"))
    }

    @Test
    fun amountSingleFractionDigit() {
        assertEquals(150L, SpendParser.toCents("1.5"))
    }

    @Test
    fun amountZeroRejected() {
        assertNull(SpendParser.toCents("0"))
        assertNull(SpendParser.toCents("0.00"))
    }
}
