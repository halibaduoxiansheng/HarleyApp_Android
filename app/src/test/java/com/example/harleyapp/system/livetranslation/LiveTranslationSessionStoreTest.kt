package com.example.harleyapp.system.livetranslation

import com.example.harleyapp.model.LiveTranslationCaptureStatus
import com.example.harleyapp.model.LiveTranslationSessionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证音量、字幕与异步翻译结果通过单一快照合并时不会跨句覆盖。
 *
 * 使用方法：
 * 由app:testDebugUnitTest自动执行；每个测试结束后恢复空闲状态，不创建Android服务或本地模型。
 */
class LiveTranslationSessionStoreTest {

    /** @return 每个测试后清除进程级测试状态。 */
    @After
    fun resetStore() {
        LiveTranslationSessionStore.resetToIdle()
    }

    /**
     * 验证更新音量不会清除当前字幕，匹配原文的翻译能够正常发布。
     *
     * @return 无返回值；字段被互相覆盖时由JUnit报告失败。
     */
    @Test
    fun audioAndMatchingTranslationPreserveOneSnapshot() {
        LiveTranslationSessionStore.publishStarting()
        LiveTranslationSessionStore.publishRunning()
        LiveTranslationSessionStore.publishSource("hello", clearTranslation = true)
        LiveTranslationSessionStore.publishAudio(
            LiveTranslationCaptureStatus.CAPTURING,
            audioLevelDb = -18f
        )

        val published = LiveTranslationSessionStore.publishTranslation("hello", "你好")
        val snapshot = LiveTranslationSessionStore.snapshot.value

        assertTrue(published)
        assertEquals(LiveTranslationSessionStatus.RUNNING, snapshot.sessionStatus)
        assertEquals("hello", snapshot.sourceText)
        assertEquals("你好", snapshot.translatedText)
        assertEquals(-18f, snapshot.audioLevelDb)
    }

    /**
     * 验证上一句迟到的异步翻译不能覆盖已经显示的新原文。
     *
     * @return 无返回值；旧译文被接纳时由JUnit报告失败。
     */
    @Test
    fun staleTranslationCannotOverwriteNewCaption() {
        LiveTranslationSessionStore.publishRunning()
        LiveTranslationSessionStore.publishSource("first", clearTranslation = true)
        LiveTranslationSessionStore.publishSource("second", clearTranslation = true)

        val published = LiveTranslationSessionStore.publishTranslation("first", "第一句")

        assertFalse(published)
        assertEquals("second", LiveTranslationSessionStore.snapshot.value.sourceText)
        assertEquals("", LiveTranslationSessionStore.snapshot.value.translatedText)
    }

    /**
     * 验证新一句尚未确认的partial只更新原文，上一句中文在新final译文到达前保持可读。
     *
     * @return 无返回值；partial错误清空既有译文时由JUnit报告失败。
     */
    @Test
    fun partialSourceUpdateKeepsPreviousTranslation() {
        LiveTranslationSessionStore.publishRunning()
        LiveTranslationSessionStore.publishSource("first final", clearTranslation = true)
        LiveTranslationSessionStore.publishTranslation("first final", "第一句")

        LiveTranslationSessionStore.publishSource("second par", clearTranslation = false)
        val snapshot = LiveTranslationSessionStore.snapshot.value

        assertEquals("second par", snapshot.sourceText)
        assertEquals("第一句", snapshot.translatedText)
    }

    /**
     * 验证旧字幕到期任务不能清除新句，匹配当前句的任务只清字幕而不改变运行状态。
     *
     * @return 无返回值；比较保护失效或误改会话状态时由JUnit报告失败。
     */
    @Test
    fun captionExpiryOnlyClearsMatchingSource() {
        LiveTranslationSessionStore.publishRunning()
        LiveTranslationSessionStore.publishSource("first", clearTranslation = true)
        LiveTranslationSessionStore.publishTranslation("first", "第一句")
        LiveTranslationSessionStore.publishSource("second", clearTranslation = false)

        val staleCleared = LiveTranslationSessionStore.clearCaptionIfSourceMatches("first")
        val currentCleared = LiveTranslationSessionStore.clearCaptionIfSourceMatches("second")
        val snapshot = LiveTranslationSessionStore.snapshot.value

        assertFalse(staleCleared)
        assertTrue(currentCleared)
        assertEquals(LiveTranslationSessionStatus.RUNNING, snapshot.sessionStatus)
        assertEquals("", snapshot.sourceText)
        assertEquals("", snapshot.translatedText)
    }
}
