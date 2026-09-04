package com.example.harleyapp.model

/**
 * 小学一至六年级的稳定标识。
 *
 * 使用方法：
 * 英语分册选择和语文成长页面共用本枚举。持久化时保存枚举名称，展示时读取[displayName]，
 * 避免界面文字调整破坏旧版本数据。
 *
 * @param gradeNumber 用于排序、解析在线阅读适龄范围的年级数字。
 * @param displayName 页面向用户展示的中文名称。
 */
enum class PrimarySchoolGrade(
    val gradeNumber: Int,
    val displayName: String
) {
    GRADE_ONE(1, "一年级"),
    GRADE_TWO(2, "二年级"),
    GRADE_THREE(3, "三年级"),
    GRADE_FOUR(4, "四年级"),
    GRADE_FIVE(5, "五年级"),
    GRADE_SIX(6, "六年级")
}

/**
 * 小学教材上下册标识。
 *
 * @param termNumber 资源文件使用的册次数字，1表示上册，2表示下册。
 * @param displayName 页面展示名称。
 */
enum class SchoolTerm(
    val termNumber: Int,
    val displayName: String
) {
    FIRST(1, "上册"),
    SECOND(2, "下册")
}

/**
 * 英语单词页面的词库范围。
 *
 * @param displayName 筛选按钮展示名称。
 */
enum class EnglishWordLibraryMode(val displayName: String) {
    GRADE_RECOMMENDED("年级推荐"),
    FULL_LIBRARY("全部词库")
}

/**
 * 用户当前选择的小学英语学习范围。
 *
 * @param grade 当前年级。
 * @param term 当前上册或下册。
 * @param mode 查看年级推荐还是完整离线词库。
 */
data class PrimaryEnglishSelection(
    val grade: PrimarySchoolGrade = PrimarySchoolGrade.GRADE_THREE,
    val term: SchoolTerm = SchoolTerm.FIRST,
    val mode: EnglishWordLibraryMode = EnglishWordLibraryMode.GRADE_RECOMMENDED
)

/**
 * 一个英语词条在小学推荐分册中的位置。
 *
 * @param grade 所属年级。
 * @param term 所属册次。
 * @param orderInBook 在当前分册中的稳定推荐顺序，从0开始。
 * @param isOfficialPepSeries true表示三至六年级PEP（三年级起点）整理词；false表示一二年级启蒙词。
 */
data class PrimaryEnglishPlacement(
    val grade: PrimarySchoolGrade,
    val term: SchoolTerm,
    val orderInBook: Int,
    val isOfficialPepSeries: Boolean
)

/**
 * 按用户选择取得当前小学英语分册，并恢复资源中定义的推荐顺序。
 *
 * 使用方法：
 * 首页推荐和英语单词列表均调用本函数，确保两处看到的是同一年级、同一册词汇。函数只筛选
 * 内存对象，不修改学习次数；一个词同时出现在不同册时分别使用对应册次中的顺序。
 *
 * @param words 已合并学习进度和小学分册位置的完整词库。
 * @param selection 用户当前年级、册次与词库范围选择；本函数只使用年级和册次。
 * @return 当前分册的词条，按教材整理顺序排列；没有匹配位置时返回空列表。
 */
fun primaryEnglishWordsForSelection(
    words: List<EnglishWord>,
    selection: PrimaryEnglishSelection
): List<EnglishWord> {
    return words.mapNotNull { word ->
        val placement = word.primaryPlacements.firstOrNull { candidate ->
            candidate.grade == selection.grade && candidate.term == selection.term
        } ?: return@mapNotNull null
        word to placement.orderInBook
    }.sortedBy { (_, orderInBook) ->
        orderInBook
    }.map { (word, _) ->
        word
    }
}
