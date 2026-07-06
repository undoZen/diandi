package com.mianbizhe.diandiji.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {

    @Test
    fun channelFolding() {
        val tokens = Tokenizer.tokenize("财付通-微信支付-M Stand")
        assertTrue("C:财付通-微信支付" in tokens)
        assertTrue("M STAND" in tokens || "M" in tokens && "STAND" in tokens)
    }

    @Test
    fun cjkBigrams() {
        val tokens = Tokenizer.tokenize("上海全家便利店")
        assertTrue("全家" in tokens)
        assertTrue("便利" in tokens)
        assertTrue("利店" in tokens)
    }

    @Test
    fun merchantStrongToken() {
        val tokens = Tokenizer.tokenize("财付通-上海全家便利店")
        assertTrue(tokens.any { it.startsWith("M:") })
    }

    @Test
    fun truncatedParenTrimmed() {
        // journal 里被截断的商户名："美团App龙诚健康旗舰店（快递电商"
        assertEquals("美团APP龙诚健康旗舰店", Tokenizer.normalize("美团App龙诚健康旗舰店（快递电商"))
    }

    @Test
    fun balancedParenKept() {
        assertEquals("京东支付-网银在线(北京)科技有限公司", Tokenizer.normalize("京东支付-网银在线（北京）科技有限公司"))
    }
}
