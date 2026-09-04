package com.example.harleyapp.model

/**
 * 语文成长页面的首页和三个学习方向。
 *
 * @param displayName 页面分栏名称。
 */
enum class ChineseGrowthSection(val displayName: String) {
    OVERVIEW("今日成长"),
    WRITING("写作训练"),
    READING("阅读见识"),
    CLASSICS("诗词积累")
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
 * @param offlineGuide 网络不可用时仍可阅读的原创导读。
 * @param observationQuestion 引导孩子提取文章信息的问题。
 * @param writingChallenge 阅读后立即完成的小练笔。
 * @param extensionSourceName 国内延伸资料站点名称，只作用户主动打开的来源提示。
 * @param extensionSourceUrl 内置固定的国内HTTPS资料地址，不接受用户输入或动态拼接。
 */
data class ChineseReadingTopic(
    val id: String,
    val minGrade: Int,
    val maxGrade: Int,
    val category: String,
    val title: String,
    val offlineGuide: String,
    val observationQuestion: String,
    val writingChallenge: String,
    val extensionSourceName: String,
    val extensionSourceUrl: String
)

/**
 * 一篇按小学年级组织、可完全离线学习的古诗文内容。
 *
 * @param id 跨版本稳定标识，用于完成进度持久化。
 * @param grade 适用年级。
 * @param title 诗文标题。
 * @param author 朝代和作者信息。
 * @param text 已进入公版范围的原文。
 * @param appreciation 面向当前年级的原创赏析提示。
 * @param recitationTip 朗读或背诵时可立即使用的方法。
 * @param practiceQuestion 检查理解而非只机械背诵的问题。
 */
data class ChineseClassicLesson(
    val id: String,
    val grade: PrimarySchoolGrade,
    val title: String,
    val author: String,
    val text: String,
    val appreciation: String,
    val recitationTip: String,
    val practiceQuestion: String
)

/**
 * 语文成长的本机学习进度摘要。
 *
 * @param completedContentIds 已完成的写作、阅读和诗词稳定标识集合。
 * @param totalCompletedCount 历史累计完成内容数。
 * @param weeklyCompletedCount 最近七个自然日内完成内容数。
 * @param streakDays 截至今天或昨天仍连续的学习天数。
 */
data class ChineseGrowthProgress(
    val completedContentIds: Set<String>,
    val totalCompletedCount: Int,
    val weeklyCompletedCount: Int,
    val streakDays: Int
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

/**
 * 计算最近若干自然日内实际记录的学习事件数量。
 *
 * 使用方法：
 * 仓库把每次完成事件的自然日传入，并使用默认七天窗口生成成长首页数据。同一内容在不同日期重复
 * 学习会分别计数，同一天重复点击同一内容应由仓库先去重。
 *
 * @param studyEpochDays 已保存学习事件对应的自然日列表。
 * @param currentEpochDay 当前设备本地自然日。
 * @param dayCount 统计窗口包含的自然日数量，必须大于零。
 * @return 位于闭区间`今天-(dayCount-1)`到今天内的事件数量；窗口无效时返回0。
 */
fun countRecentChineseGrowthEvents(
    studyEpochDays: Collection<Long>,
    currentEpochDay: Long,
    dayCount: Int = 7
): Int {
    if (dayCount <= 0) return 0
    val startEpochDay = currentEpochDay - (dayCount - 1L)
    return studyEpochDays.count { epochDay -> epochDay in startEpochDay..currentEpochDay }
}

/**
 * 计算截至今天或昨天仍连续的语文学习自然日数量。
 *
 * @param studyEpochDays 学习事件自然日，可包含同一天的多个学习项目。
 * @param currentEpochDay 当前设备本地自然日。
 * @return 连续学习天数；今天和昨天均无记录时返回0。
 */
fun calculateChineseGrowthStreakDays(
    studyEpochDays: Collection<Long>,
    currentEpochDay: Long
): Int {
    val uniqueDays = studyEpochDays.toSet()
    var expectedDay = when {
        currentEpochDay in uniqueDays -> currentEpochDay
        currentEpochDay - 1L in uniqueDays -> currentEpochDay - 1L
        else -> return 0
    }
    var streakDays = 0
    while (expectedDay in uniqueDays) {
        streakDays += 1
        expectedDay -= 1L
    }
    return streakDays
}
