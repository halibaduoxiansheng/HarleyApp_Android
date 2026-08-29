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
 * @param meaningZh 中文释义。
 * @param exampleEn 英文例句，也是例句发音时交给TTS的文本。
 * @param exampleZh 英文例句对应的中文翻译。
 * @param learnedCount 用户已点击“学会”的次数，范围固定为0至3。
 */
data class EnglishWord(
    val id: String,
    val word: String,
    val meaningZh: String,
    val exampleEn: String,
    val exampleZh: String,
    val learnedCount: Int
)

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
