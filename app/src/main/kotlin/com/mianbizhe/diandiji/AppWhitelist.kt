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

    /**
     * 银行家族：同一家银行在各平台/各产品线的 App（安卓/iOS × 银行主 App/信用卡 App）。
     * 同一笔消费可能在家族内多个 App 各发一条通知（取决于用户在哪台设备登录了哪个 App，
     * 2×2 共 4 种组合），消费管线按家族去重。
     *
     * iOS bundle id 来源：iTunes Search API（2026-07-25 批量核查，开发者名验证防山寨），
     * 三个已知 id（MPBBank/cmblife/iphoneclient）均复现通过。
     * 注意部分银行两个平台用同一个 id（如 com.icbc.elife、com.cib.cibmb），不冲突。
     *
     * 本表是去重包名的**单一来源**：运行态判断（familyOf）和清理 SQL
     * （SpendPipeline.hideFamilyDuplicatesQuery，运行时从本表生成）都读这里，加银行只改这一处。
     */
    val BANK_FAMILIES: List<Set<String>> = listOf(
        // 工商银行（工银e生活两平台同 id）
        setOf("com.icbc", "com.icbc.elife", "com.icbc.iphoneclient"),
        // 建设银行（建行生活两平台同 id；iOS 主 App 沿用历史 id ccbDemo）
        setOf("com.chinamworld.main", "com.ccb.ccbDemo", "com.ccb.longjiLife"),
        // 农业银行
        setOf("com.android.bankabc", "com.bankabc.iphonerelease"),
        // 中国银行（缤纷生活 iOS 版未查到，发现后补）
        setOf("com.boc.bocsoft.bocmbovsa.buss", "com.boc.BOCMBCI", "com.boc.boccard"),
        // 招商银行
        setOf(
            "cmb.pb",                             // 安卓 招商银行
            "com.cmbchina.ccd.pluto.cmbActivity", // 安卓 掌上生活
            "com.cmbchina.MPBBank",               // iPhone 招商银行
            "com.cmbchina.cmblife",               // iPhone 掌上生活
        ),
        // 交通银行（主 App 两平台同 id）
        setOf("com.bankcomm.Bankcomm", "com.bankcomm.ccard", "com.bankcomm.maidanba"),
        // 浦发银行（iOS 浦大喜奔的 id 是反向域名 spdbccc.com.cn，非笔误）
        setOf("com.spdb.mobilebank", "com.spdb.retail.bank", "com.spdbccc.app", "spdbccc.com.cn"),
        // 中信银行
        setOf("com.citicbank.mobilebank", "com.citic.mobile", "com.citiccard.mobilebank", "com.citicbank.creditspace"),
        // 光大银行
        setOf("com.cebbank.mobilebank", "com.cebbank.ebank", "com.ebank.creditcard", "com.cebbank.xyk"),
        // 民生银行
        setOf("com.cmbc.mbank", "com.cmbc.cn.iphone", "com.cmbc.cc.mbank", "com.cmbc.mobilePhone"),
        // 兴业银行（两个 App 均两平台同 id）
        setOf("com.cib.cibmb", "com.cib.xyk"),
        // 平安银行（只有口袋银行一个 App）
        setOf("com.pingan.paces.ccms", "com.pingan.creditcard"),
        // 邮储银行
        setOf("com.yitong.mbank.psbc", "com.psbc.mobilebank"),
        // 广发银行
        setOf("com.cgbchina.xpt", "com.gdb.mobilegdb", "com.cs_credit_bank", "com.cgb.creditcard.iphone"),
        // 华夏银行（华彩生活两平台同 id）
        setOf("com.hxb.mobilebank", "com.hxb.mobile.client", "com.HuaXiaBank.HuaCard"),
    )

    /** 全部家族成员的并集 */
    val FAMILY_PACKAGES: Set<String> = BANK_FAMILIES.flatten().toSet()

    /** 包名 → 家族索引（不属于任何家族 → null）。同家族索引相同 = 互为去重候选 */
    fun familyOf(pkg: String): Int? =
        BANK_FAMILIES.indexOfFirst { pkg in it }.takeIf { it >= 0 }
}
