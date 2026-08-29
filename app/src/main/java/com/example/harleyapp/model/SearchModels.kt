package com.example.harleyapp.model

/**
 * 全局本地搜索结果所属功能。
 *
 * @property LEDGER 账目。
 * @property REMINDER 本地通知提醒。
 * @property FITNESS 运动记录。
 * @property WEBSITE 网站快捷入口。
 * @property WECHAT_CAPTURE 待确认微信支付通知。
 */
enum class LocalSearchType(val title: String, val symbol: String) {
    LEDGER("账目", "¥"),
    REMINDER("提醒", "铃"),
    FITNESS("运动", "动"),
    WEBSITE("网站", "网"),
    WECHAT_CAPTURE("待确认", "微")
}

/**
 * 一条可以跳转到原功能的本地搜索结果。
 *
 * @param stableId 类型内稳定标识，用于Compose列表和点击定位。
 * @param type 结果所属功能。
 * @param title 主要匹配内容。
 * @param subtitle 日期、金额或网址等辅助信息。
 * @param sortTimeMillis 统一倒序排序时间；没有时间概念时为0。
 * @param targetValue 点击后原功能需要的账目编号、网站id等值。
 */
data class LocalSearchResult(
    val stableId: String,
    val type: LocalSearchType,
    val title: String,
    val subtitle: String,
    val sortTimeMillis: Long,
    val targetValue: String
)
