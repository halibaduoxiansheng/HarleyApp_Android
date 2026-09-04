package com.example.harleyapp.model

/**
 * 语文成长页面的两个学习方向。
 *
 * @param displayName 页面分栏名称。
 */
enum class ChineseGrowthSection(val displayName: String) {
    WRITING("写作训练"),
    READING("阅读见识")
}

/**
 * 一项可实际完成的小学写作训练。
 *
 * @param id 跨版本稳定标识。
 * @param grade 适用年级。
 * @param title 训练标题。
 * @param focus 本次只需掌握的核心能力。
 * @param methodSteps 写作前依次执行的方法步骤。
 * @param prompt 可以直接开始写的题目或观察任务。
 * @param outline 不提供整篇范文，而是给出可自行填充的结构提示。
 * @param checklist 写完后由孩子或家长逐项检查的标准。
 */
data class ChineseWritingMission(
    val id: String,
    val grade: PrimarySchoolGrade,
    val title: String,
    val focus: String,
    val methodSteps: List<String>,
    val prompt: String,
    val outline: List<String>,
    val checklist: List<String>
)

/**
 * 经过白名单筛选、允许联网读取的百科主题。
 *
 * @param id 跨版本稳定标识，也是本地缓存键的一部分。
 * @param minGrade 建议阅读的最低年级。
 * @param maxGrade 建议阅读的最高年级。
 * @param category 页面显示的知识类别。
 * @param title 面向孩子的阅读标题。
 * @param wikipediaTitle 中文维基百科实际条目标题，只允许使用内置固定值，不接受用户拼接。
 * @param offlineGuide 网络不可用时仍可阅读的原创导读。
 * @param observationQuestion 引导孩子提取文章信息的问题。
 * @param writingChallenge 阅读后立即完成的小练笔。
 */
data class ChineseReadingTopic(
    val id: String,
    val minGrade: Int,
    val maxGrade: Int,
    val category: String,
    val title: String,
    val wikipediaTitle: String,
    val offlineGuide: String,
    val observationQuestion: String,
    val writingChallenge: String
)

/**
 * App内最终展示的一篇阅读内容。
 *
 * @param topicId 对应白名单主题标识。
 * @param title 在线条目或离线导读标题。
 * @param description 可选的一句话类别说明。
 * @param body 直接在App内展示的正文摘要。
 * @param sourceName 内容来源名称。
 * @param sourceUrl 来源页面完整地址，用于署名和追溯，不自动跳转浏览器。
 * @param licenseLabel 内容许可；原创离线导读使用“HarleyApp原创导读”。
 * @param fetchedAtMillis 在线内容取得时间；离线导读为0。
 * @param isOnlineContent true表示正文来自在线公开接口，false表示内置原创导读。
 * @param isFromCache true表示本次直接显示此前成功保存的在线内容。
 */
data class ChineseReadingArticle(
    val topicId: String,
    val title: String,
    val description: String,
    val body: String,
    val sourceName: String,
    val sourceUrl: String,
    val licenseLabel: String,
    val fetchedAtMillis: Long,
    val isOnlineContent: Boolean,
    val isFromCache: Boolean
)

/**
 * 阅读仓库返回给页面的文章和非阻断提示。
 *
 * @param article 始终可展示的在线文章、缓存或离线导读。
 * @param notice 网络失败、缓存回退等需要解释给用户的中文提示；正常联网成功时为空。
 */
data class ChineseReadingLoadResult(
    val article: ChineseReadingArticle,
    val notice: String? = null
)

/**
 * 判断一个阅读主题是否适合当前年级。
 *
 * @param topic 待判断的白名单阅读主题。
 * @param grade 用户选择的年级。
 * @return 年级数字处于主题建议范围内时返回true。
 */
fun isChineseReadingTopicSuitable(
    topic: ChineseReadingTopic,
    grade: PrimarySchoolGrade
): Boolean {
    return grade.gradeNumber in topic.minGrade..topic.maxGrade
}
