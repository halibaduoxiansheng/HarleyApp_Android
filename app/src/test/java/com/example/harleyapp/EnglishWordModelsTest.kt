package com.example.harleyapp

import com.example.harleyapp.model.ENGLISH_WORD_ALL_STAGES
import com.example.harleyapp.model.EnglishLearningStage
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.PrimaryEnglishPlacement
import com.example.harleyapp.model.PrimaryEnglishSelection
import com.example.harleyapp.model.PrimarySchoolGrade
import com.example.harleyapp.model.SchoolTerm
import com.example.harleyapp.model.chooseNextEnglishWord
import com.example.harleyapp.model.primaryEnglishWordsForSelection
import com.example.harleyapp.model.resolveEnglishLearningExample
import com.example.harleyapp.model.searchEnglishWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证离线英语词库和首页随机选择规则。 */
class EnglishWordModelsTest {

    /** 学习次数越界时应被稳定映射到0至3的四个分栏。 */
    @Test
    fun learningStageClampsUnexpectedCounts() {
        assertEquals(EnglishLearningStage.NOT_LEARNED, EnglishLearningStage.fromLearnedCount(-2))
        assertEquals(EnglishLearningStage.LEARNED_ONCE, EnglishLearningStage.fromLearnedCount(1))
        assertEquals(
            EnglishLearningStage.LEARNED_THREE_TIMES,
            EnglishLearningStage.fromLearnedCount(9)
        )
    }

    /** 只要还有其他候选，随机选择就不应立即重复上一单词。 */
    @Test
    fun nextWordAvoidsImmediateRepeat() {
        val words = listOf(
            testWord(id = "first", learnedCount = 0),
            testWord(id = "second", learnedCount = 1),
            testWord(id = "finished", learnedCount = 3)
        )

        val selected = chooseNextEnglishWord(
            words = words,
            previousWordId = "first",
            randomIndexProvider = { 0 }
        )

        assertEquals("second", selected?.id)
    }

    /** 候选只剩上一单词时仍应返回它，以便用户把次数继续增加到三次。 */
    @Test
    fun nextWordKeepsOnlyRemainingCandidate() {
        val onlyLearningWord = testWord(id = "only", learnedCount = 2)
        val selected = chooseNextEnglishWord(
            words = listOf(onlyLearningWord, testWord(id = "done", learnedCount = 3)),
            previousWordId = "only",
            randomIndexProvider = { 0 }
        )

        assertEquals(onlyLearningWord, selected)
    }

    /** 所有单词达到三次后首页随机池应为空。 */
    @Test
    fun nextWordReturnsNullAfterAllWordsAreMastered() {
        val selected = chooseNextEnglishWord(
            words = listOf(
                testWord(id = "one", learnedCount = 3),
                testWord(id = "two", learnedCount = 3)
            )
        )

        assertNull(selected)
    }

    /** 输入英文前缀goo时应优先推荐拼写最接近的good。 */
    @Test
    fun searchRanksEnglishPrefixMatches() {
        val words = listOf(
            testWord(id = "book", learnedCount = 0),
            testWord(id = "goods", learnedCount = 0),
            testWord(id = "good", learnedCount = 0),
            testWord(id = "goose", learnedCount = 0)
        )

        val result = searchEnglishWords(
            words = words,
            query = "goo",
            learnedCount = ENGLISH_WORD_ALL_STAGES
        )

        assertEquals(listOf("good", "goods", "goose"), result.map { word -> word.id })
    }

    /** 中文释义和学习阶段应能与英文拼写共用同一个搜索入口。 */
    @Test
    fun searchMatchesChineseMeaningAndLearningStage() {
        val words = listOf(
            testWord(id = "good", learnedCount = 0, meaningZh = "好的；优秀的"),
            testWord(id = "excellent", learnedCount = 2, meaningZh = "优秀的；卓越的"),
            testWord(id = "book", learnedCount = 2, meaningZh = "书籍")
        )

        val result = searchEnglishWords(
            words = words,
            query = "优秀",
            learnedCount = EnglishLearningStage.LEARNED_TWICE.learnedCount
        )

        assertEquals(listOf("excellent"), result.map { word -> word.id })
    }

    /**
     * 英文例句和中文例句翻译也应进入模糊搜索范围。
     *
     * @return 无返回值；例句文字无法命中对应单词时由JUnit报告失败。
     */
    @Test
    fun searchMatchesExampleSentenceText() {
        val words = listOf(
            testWord(id = "journey", learnedCount = 0).copy(
                exampleEn = "She began a long journey.",
                exampleZh = "她开始了一段漫长旅程。"
            ),
            testWord(id = "book", learnedCount = 0).copy(
                exampleEn = "This is a useful book.",
                exampleZh = "这是一本有用的书。"
            )
        )

        val result = searchEnglishWords(
            words = words,
            query = "漫长旅程",
            learnedCount = ENGLISH_WORD_ALL_STAGES
        )

        assertEquals(listOf("journey"), result.map { word -> word.id })
    }

    /** 词库没有专属例句时应补齐可朗读的本机通用例句，并包含当前单词。 */
    @Test
    fun missingExampleUsesOfflineLearningSentence() {
        val example = resolveEnglishLearningExample(
            testWord(id = "good", learnedCount = 0).copy(
                exampleEn = "",
                exampleZh = ""
            )
        )

        assertEquals(true, example.isGenerated)
        assertEquals(true, example.english.contains("good"))
        assertEquals(true, example.chinese.contains("good"))
    }

    /** 词库已有专属例句时必须原样使用，不能被通用句覆盖。 */
    @Test
    fun bundledExampleIsPreserved() {
        val example = resolveEnglishLearningExample(
            testWord(id = "ability", learnedCount = 0).copy(
                exampleEn = "She has the ability to learn quickly.",
                exampleZh = "她有快速学习的能力。"
            )
        )

        assertEquals(false, example.isGenerated)
        assertEquals("She has the ability to learn quickly.", example.english)
        assertEquals("她有快速学习的能力。", example.chinese)
    }

    /**
     * 年级推荐必须只保留当前册词条，并按照分册位置恢复顺序。
     *
     * @return 无返回值；跨册词混入或顺序错误时由JUnit报告失败。
     */
    @Test
    fun primaryRecommendationFiltersAndSortsSelectedBook() {
        val targetSelection = PrimaryEnglishSelection(
            grade = PrimarySchoolGrade.GRADE_FOUR,
            term = SchoolTerm.SECOND
        )
        val words = listOf(
            testWord(id = "second", learnedCount = 0).copy(
                primaryPlacements = listOf(
                    PrimaryEnglishPlacement(
                        grade = PrimarySchoolGrade.GRADE_FOUR,
                        term = SchoolTerm.SECOND,
                        orderInBook = 2,
                        isOfficialPepSeries = true
                    )
                )
            ),
            testWord(id = "other", learnedCount = 0).copy(
                primaryPlacements = listOf(
                    PrimaryEnglishPlacement(
                        grade = PrimarySchoolGrade.GRADE_FIVE,
                        term = SchoolTerm.SECOND,
                        orderInBook = 0,
                        isOfficialPepSeries = true
                    )
                )
            ),
            testWord(id = "first", learnedCount = 0).copy(
                primaryPlacements = listOf(
                    PrimaryEnglishPlacement(
                        grade = PrimarySchoolGrade.GRADE_FOUR,
                        term = SchoolTerm.SECOND,
                        orderInBook = 1,
                        isOfficialPepSeries = true
                    )
                )
            )
        )

        val result = primaryEnglishWordsForSelection(words, targetSelection)

        assertEquals(listOf("first", "second"), result.map { word -> word.id })
    }

    /**
     * 创建随机选择测试所需的最小单词对象。
     *
     * @param id 测试稳定标识。
     * @param learnedCount 测试学习次数。
     * @return 其余文本使用固定占位值的单词对象。
     */
    private fun testWord(
        id: String,
        learnedCount: Int,
        meaningZh: String = "释义"
    ): EnglishWord {
        return EnglishWord(
            id = id,
            word = id,
            meaningZh = meaningZh,
            exampleEn = "Example sentence.",
            exampleZh = "例句。",
            learnedCount = learnedCount
        )
    }
}
