package com.mianbizhe.diandiji

/**
 * 应用筛选组 — 国内常见银行/信用卡 App 白名单。
 *
 * 数据来源：小米应用商店、APKPure、各银行官网，2026-07-06 收集。
 * 每家银行通常有两个 App：综合银行 App + 信用卡/生活 App。
 *
 * 采集层照旧全量落库（本地优先），此处只影响查询/消费管线。
 *
 * 新增银行：在此文件加包名即可，API 和页面自动生效。
 */
object AppWhitelist {

    /** 全部财务 App（银行 + 信用卡） */
    val finance: Set<String> = setOf(
        // ---- 工商银行 ----
        "com.icbc",            // 中国工商银行
        "com.icbc.elife",      // 工银e生活（信用卡）

        // ---- 建设银行 ----
        "com.chinamworld.main", // 中国建设银行
        "com.ccb.longjiLife",   // 建行生活（信用卡/生活）

        // ---- 农业银行 ----
        "com.android.bankabc",  // 农行掌上银行

        // ---- 中国银行 ----
        "com.boc.bocsoft.bocmbovsa.buss", // 中国银行
        "com.boc.boccard",                 // 缤纷生活（信用卡）

        // ---- 招商银行 ----
        "cmb.pb",                              // 招商银行
        "com.cmbchina.ccd.pluto.cmbActivity",  // 掌上生活（信用卡）

        // ---- 交通银行 ----
        "com.bankcomm.Bankcomm", // 交通银行
        "com.bankcomm.ccard",    // 买单吧（信用卡）

        // ---- 浦发银行 ----
        "com.spdb.mobilebank",  // 浦发银行
        "com.spdbccc.app",      // 浦大喜奔（信用卡）

        // ---- 中信银行 ----
        "com.citicbank.mobilebank", // 中信银行
        "com.citiccard.mobilebank", // 动卡空间（信用卡）

        // ---- 光大银行 ----
        "com.cebbank.mobilebank", // 光大银行
        "com.ebank.creditcard",   // 阳光惠生活（信用卡）

        // ---- 民生银行 ----
        "com.cmbc.mbank",      // 民生银行
        "com.cmbc.cc.mbank",   // 全民生活（信用卡）

        // ---- 兴业银行 ----
        "com.cib.cibmb",       // 兴业银行
        "com.cib.xyk",         // 兴业生活（信用卡）

        // ---- 平安银行 ----
        "com.pingan.paces.ccms", // 平安口袋银行

        // ---- 邮储银行 ----
        "com.yitong.mbank.psbc", // 邮储银行

        // ---- 广发银行 ----
        "com.cgbchina.xpt",     // 广发银行
        "com.cs_credit_bank",   // 发现精彩（信用卡）

        // ---- 华夏银行 ----
        "com.hxb.mobilebank",      // 华夏银行
        "com.HuaXiaBank.HuaCard",  // 华彩生活（信用卡）
    )

    /** 仅银行主 App（不含信用卡 App），用于一些只想看主银行的场景 */
    val mainBanks: Set<String> = setOf(
        "com.icbc",
        "com.chinamworld.main",
        "com.android.bankabc",
        "com.boc.bocsoft.bocmbovsa.buss",
        "cmb.pb",
        "com.bankcomm.Bankcomm",
        "com.spdb.mobilebank",
        "com.citicbank.mobilebank",
        "com.cebbank.mobilebank",
        "com.cmbc.mbank",
        "com.cib.cibmb",
        "com.pingan.paces.ccms",
        "com.yitong.mbank.psbc",
        "com.cgbchina.xpt",
        "com.hxb.mobilebank",
    )
}
