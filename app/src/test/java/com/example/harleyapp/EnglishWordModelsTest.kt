package com.example.harleyapp

import com.example.harleyapp.data.loadBundledEnglishWords
import com.example.harleyapp.model.EnglishLearningStage
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.chooseNextEnglishWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 内置词库应保持122个稳定、完整且不重复的离线单词。 */
    @Test
    fun bundledWordsAreCompleteAndUnique() {
        val words = loadBundledEnglishWords()

        assertEquals(122, words.size)
        assertEquals(words.size, words.map { word -> word.id }.distinct().size)
        assertFalse(words.any { word ->
            word.id.isBlank() || word.word.isBlank() || word.meaningZh.isBlank() ||
                word.exampleEn.isBlank() || word.exampleZh.isBlank()
        })
        assertTrue(words.all { word -> word.exampleEn.endsWith('.') || word.exampleEn.endsWith('?') })
    }

    /**
     * 创建随机选择测试所需的最小单词对象。
     *
     * @param id 测试稳定标识。
     * @param learnedCount 测试学习次数。
     * @return 其余文本使用固定占位值的单词对象。
     */
    private fun testWord(id: String, learnedCount: Int): EnglishWord {
        return EnglishWord(
            id = id,
            word = id,
            meaningZh = "释义",
            exampleEn = "Example sentence.",
            exampleZh = "例句。",
            learnedCount = learnedCount
        )
    }
}
