package com.example.harleyapp.model

import kotlin.random.Random

/** 单词达到三次“学会”后视为完成本轮学习。 */
const val ENGLISH_WORD_MASTERY_COUNT = 3

/**
 * 英语单词的一条完整离线学习内容及当前进度。
 *
 * 使用方法：
 * 数据仓库把内置词库内容与本机学习次数合并后创建本对象；首页和英语学习页只读取本对象，
 * 不直接解析词库或访问数据库。
 *
 * @param id 单词在内置词库中的稳定标识，用于持久化学习进度。
 * @param word 英文单词或短语。
 * @param phonetic 英语音标；词库没有提供时为空字符串。
 * @param definitionEn 英文释义；词库没有提供时为空字符串。
 * @param meaningZh 中文释义。
 * @param exampleEn 可选英文例句，也是例句发音时交给TTS的文本。
 * @param exampleZh 可选英文例句中文翻译。
 * @param tags 中考、高考、四六级、牛津核心等词库标签。
 * @param learnedCount 用户已点击“学会”的次数，范围固定为0至3。
 */
data class EnglishWord(
    val id: String,
    val word: String,
    val phonetic: String = "",
    val definitionEn: String = "",
    val meaningZh: String,
    val exampleEn: String = "",
    val exampleZh: String = "",
    val tags: List<String> = emptyList(),
    val learnedCount: Int
)

/**
 * 单词详情页最终用于展示和朗读的例句。
 *
 * @param english 可直接提交给离线TTS的英文例句。
 * @param chinese 可选的中文翻译。
 * @param isGenerated true表示原词库没有专属例句，本例句由App在本机按固定模板补齐。
 */
data class EnglishLearningExample(
    val english: String,
    val chinese: String,
    val isGenerated: Boolean
)

/**
 * 取得任何单词都可展示和朗读的离线学习例句。
 *
 * 使用方法：
 * 单词详情页把当前[EnglishWord]传入本函数。词库已有英文例句时完整保留原例句和翻译；
 * 没有专属例句时，使用只包含当前单词名称的通用学习句，保证“朗读例句”始终有实际文本。
 * 本函数不访问网络、不调用生成式服务，也不会修改原始词库。
 *
 * @param word 当前需要显示独立学习页的单词。
 * @return 已有例句或本机补齐的通用例句，以及是否为补齐内容的标记。
 */
fun resolveEnglishLearningExample(word: EnglishWord): EnglishLearningExample {
    if (word.exampleEn.isNotBlank()) {
        return EnglishLearningExample(
            english = word.exampleEn.trim(),
            chinese = word.exampleZh.trim(),
            isGenerated = false
        )
    }

    val normalizedWord = word.word.trim().ifBlank { "word" }
    return EnglishLearningExample(
        english = "I am learning how to use the word \"$normalizedWord\".",
        chinese = "我正在学习如何使用单词“$normalizedWord”。",
        isGenerated = true
    )
}

/** “全部”分栏使用的特殊筛选值，不与0至3次学习进度冲突。 */
const val ENGLISH_WORD_ALL_STAGES = -1

/**
 * 英语单词学习页使用的四个进度分栏。
 *
 * @param learnedCount 该分栏对应的已学会次数。
 * @param title 页面显示的中文标题。
 */
enum class EnglishLearningStage(
    val learnedCount: Int,
    val title: String
) {
    NOT_LEARNED(0, "未学会"),
    LEARNED_ONCE(1, "学会1次"),
    LEARNED_TWICE(2, "学会2次"),
    LEARNED_THREE_TIMES(3, "学会3次");

    companion object {

        /**
         * 根据任意整数取得安全的学习阶段。
         *
         * 使用方法：
         * 读取旧备份或异常数据后调用本函数，负数按0处理，大于3的值按3处理，避免页面出现
         * 不存在的第五阶段。
         *
         * @param learnedCount 待转换的学习次数。
         * @return 与规范化次数对应的四阶段枚举。
         */
        fun fromLearnedCount(learnedCount: Int): EnglishLearningStage {
            val normalizedCount = learnedCount.coerceIn(0, ENGLISH_WORD_MASTERY_COUNT)
            return entries.first { stage -> stage.learnedCount == normalizedCount }
        }
    }
}

/**
 * 从尚未完成三次学习的单词中随机选择下一项。
 *
 * 使用方法：
 * 首页首次显示或用户点击“学会”后调用本函数。只要候选池中还有其他单词，就会排除上一词，
 * 从而避免用户连续两次看到同一个词；候选池只剩一个词时允许继续返回它，保证仍可完成三次。
 *
 * @param words 当前完整单词及进度列表。
 * @param previousWordId 上一张卡片的单词标识；首次选择时传null。
 * @param randomIndexProvider 根据候选数量返回随机索引；默认使用系统随机数，测试可传固定实现。
 * @return 随机候选；所有单词均达到三次时返回null。
 */
fun chooseNextEnglishWord(
    words: List<EnglishWord>,
    previousWordId: String? = null,
    randomIndexProvider: (Int) -> Int = { bound -> Random.nextInt(bound) }
): EnglishWord? {
    val learningWords = words.filter { word ->
        word.learnedCount < ENGLISH_WORD_MASTERY_COUNT
    }
    if (learningWords.isEmpty()) return null

    val preferredWords = learningWords
        .filterNot { word -> word.id == previousWordId }
        .ifEmpty { learningWords }
    val requestedIndex = randomIndexProvider(preferredWords.size)
    val safeIndex = requestedIndex.coerceIn(preferredWords.indices)
    return preferredWords[safeIndex]
}

/**
 * 按学习阶段和用户输入搜索英语单词，并把更接近输入内容的结果排在前面。
 *
 * 使用方法：
 * 英语学习列表在搜索文字或切换“全部、未学会、学会1至3次”分栏后调用本函数。英文搜索
 * 不区分大小写，并依次按完全匹配、前缀匹配和包含匹配排序；中文输入会匹配中文释义，
 * 因此输入“goo”可以优先得到“good”，输入“美好”也可以找到含该释义或例句翻译的单词。
 *
 * @param words 当前完整单词列表，列表原始顺序代表词库推荐顺序。
 * @param query 用户输入的英文拼写或中文释义；空白表示不限制关键词。
 * @param learnedCount 学习次数筛选；传[ENGLISH_WORD_ALL_STAGES]表示显示全部单词。
 * @return 符合阶段和关键词条件的单词列表；相同匹配级别保持词库原始顺序。
 */
fun searchEnglishWords(
    words: List<EnglishWord>,
    query: String,
    learnedCount: Int = ENGLISH_WORD_ALL_STAGES
): List<EnglishWord> {
    val stageFilteredWords = if (learnedCount == ENGLISH_WORD_ALL_STAGES) {
        words
    } else {
        val normalizedCount = learnedCount.coerceIn(0, ENGLISH_WORD_MASTERY_COUNT)
        words.filter { word -> word.learnedCount == normalizedCount }
    }
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return stageFilteredWords

    return stageFilteredWords.mapIndexedNotNull { index, word ->
        val spelling = word.word.lowercase()
        val rank = when {
            spelling == normalizedQuery -> 0
            spelling.startsWith(normalizedQuery) -> 1
            spelling.contains(normalizedQuery) -> 2
            word.meaningZh.contains(normalizedQuery, ignoreCase = true) -> 3
            word.definitionEn.contains(normalizedQuery, ignoreCase = true) -> 4
            word.exampleEn.contains(normalizedQuery, ignoreCase = true) -> 5
            word.exampleZh.contains(normalizedQuery, ignoreCase = true) -> 6
            word.tags.any { tag -> tag.contains(normalizedQuery, ignoreCase = true) } -> 7
            else -> return@mapIndexedNotNull null
        }
        RankedEnglishWord(
            word = word,
            rank = rank,
            lengthDifference = kotlin.math.abs(spelling.length - normalizedQuery.length),
            originalIndex = index
        )
    }.sortedWith(
        compareBy<RankedEnglishWord> { result -> result.rank }
            .thenBy { result -> result.lengthDifference }
            .thenBy { result -> result.originalIndex }
    ).map { result -> result.word }
}

/**
 * 保存一次搜索匹配的内部排序信息。
 *
 * @param word 匹配到的单词。
 * @param rank 匹配等级，数值越小越接近用户输入。
 * @param lengthDifference 单词长度与查询长度的差值，用于让简短的前缀结果优先。
 * @param originalIndex 单词在当前词库中的原始位置，用于稳定排序。
 */
private data class RankedEnglishWord(
    val word: EnglishWord,
    val rank: Int,
    val lengthDifference: Int,
    val originalIndex: Int
)
