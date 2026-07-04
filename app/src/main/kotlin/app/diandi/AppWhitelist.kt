package app.diandi

/**
 * 应用白名单（硬编码版，自用够了）。
 * 包名 = 判断 App 的唯一 ID（列表页每条通知下方灰字即是，抄进来即可）。
 *
 * 语义：空集合 = 不过滤，显示全部；非空 = 只显示这些包的通知。
 * 只影响查询/展示层（/api/notifications），**采集照旧全量落库**——
 * 本地优先原则：先存全，过滤随时可改可撤销。
 *
 * TODO: 做成「通知滤盒/SmsForwarder 式」的本机应用选择编辑界面（见 DECISIONS.md 待办）。
 */
object AppWhitelist {
    val packages: Set<String> = setOf(
        // "com.tencent.mm",        // 微信（示例，按需放开）
        // "com.android.mms",       // 短信
    )

    fun isEmpty() = packages.isEmpty()
}
