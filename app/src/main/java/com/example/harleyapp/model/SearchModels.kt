package com.example.harleyapp.model

/**
 * 全局本地搜索结果所属功能。
 *
 * @property LEDGER 账目。
 * @property REMINDER 本地通知提醒。
 * @property FITNESS 运动记录。
 * @property WEBSITE 网站快捷入口。
 * @property WECHAT_CAPTURE 待确认微信支付通知。
 * @property ENGLISH_WORD 离线英语单词。
 * @property NOTEBOOK 本地记事本文章。
 * @property EBOOK 本地电子书。
 * @property APP 手机中可启动的应用。
 * @property FEATURE Harley App内部功能入口。
 * @property COOK 随App发布的离线HowToCook菜谱。
 */
enum class LocalSearchType(val title: String, val symbol: String) {
    LEDGER("账目", "¥"),
    REMINDER("提醒", "铃"),
    FITNESS("运动", "动"),
    WEBSITE("网站", "网"),
    WECHAT_CAPTURE("待确认", "微"),
    ENGLISH_WORD("单词", "英"),
    NOTEBOOK("记事本", "记"),
    EBOOK("电子书", "书"),
    APP("应用", "启"),
    FEATURE("功能", "功"),
    COOK("菜谱", "厨")
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
