package com.example.harleyapp.system.livetranslation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证流式识别partial修订、final确认和重复回调过滤的纯字幕逻辑。
 *
 * 使用方法：
 * 由app:testDebugUnitTest自动执行；测试不创建SenseVoice模型或Android浮层。
 */
class CaptionCueAssemblerTest {

    /**
     * 验证partial可以逐步修订显示原文，而相同partial不会触发重复界面刷新。
     *
     * @return 无返回值；空白规范化或partial去重失效时由JUnit报告失败。
     */
    @Test
    fun partialRevisionsUpdateOnlyWhenNormalizedTextChanges() {
        val assembler = CaptionCueAssembler()

        val first = assembler.acceptPartial("  hello   wor  ")
        val duplicate = assembler.acceptPartial("hello wor")
        val revised = assembler.acceptPartial("hello world")

        assertEquals("hello wor", first.displayText)
        assertTrue(first.accepted)
        assertFalse(first.shouldTranslate)
        assertFalse(duplicate.accepted)
        assertFalse(duplicate.displayTextChanged)
        assertEquals("hello world", revised.displayText)
        assertTrue(revised.displayTextChanged)
    }

    /**
     * 验证final即使和最后partial显示相同，也会提交该独立语音片段的翻译。
     *
     * @return 无返回值；final没有触发翻译时断言失败。
     */
    @Test
    fun finalMatchingLastPartialIsTranslated() {
        val assembler = CaptionCueAssembler()
        assembler.acceptPartial("good morning")

        val firstFinal = assembler.acceptFinal("good morning")

        assertTrue(firstFinal.accepted)
        assertTrue(firstFinal.shouldTranslate)
        assertEquals("good morning", firstFinal.finalTextForTranslation)
        assertFalse(firstFinal.displayTextChanged)
    }

    /**
     * 验证两个没有partial的相同短句仍属于两个独立VAD片段，不能按正文相等误删第二句。
     *
     * @return 无返回值；第二个相同final被去重时断言失败。
     */
    @Test
    fun consecutiveIdenticalShortFinalsAreBothAccepted() {
        val assembler = CaptionCueAssembler()

        val firstFinal = assembler.acceptFinal("yes")
        val secondFinal = assembler.acceptFinal("yes")

        assertTrue(firstFinal.accepted)
        assertTrue(secondFinal.accepted)
        assertEquals("yes", firstFinal.finalTextForTranslation)
        assertEquals("yes", secondFinal.finalTextForTranslation)
    }

    /**
     * 验证相同台词之间出现新partial后可再次确认，避免把真实重复对白误判为重复回调。
     *
     * @return 无返回值；第二次真实台词被错误丢弃时由JUnit报告失败。
     */
    @Test
    fun newPartialAllowsSameFinalToBeEmittedAgain() {
        val assembler = CaptionCueAssembler()
        assembler.acceptFinal("yes")
        assembler.acceptPartial("yes")

        val repeatedDialogue = assembler.acceptFinal("yes")

        assertTrue(repeatedDialogue.accepted)
        assertEquals("yes", repeatedDialogue.finalTextForTranslation)
    }

    /**
     * 验证重置会清除上一会话字幕和去重标记。
     *
     * @return 无返回值；新会话仍残留旧字幕或旧去重状态时由JUnit报告失败。
     */
    @Test
    fun resetClearsDisplayAndDeduplicationState() {
        val assembler = CaptionCueAssembler()
        assembler.acceptFinal("session one")

        assembler.reset()
        val newSessionFinal = assembler.acceptFinal("session one")

        assertEquals("session one", newSessionFinal.finalTextForTranslation)
        assertTrue(newSessionFinal.accepted)
    }

    /**
     * 验证短英语、日语假名、数字、日文长音和常见混合标点不会被噪声门禁误删。
     *
     * @return 无返回值；任一正常电影对白被清空或错误改写时由JUnit报告失败。
     */
    @Test
    fun captionNormalizationKeepsShortEnglishAndJapaneseDialogue() {
        assertEquals("I!", normalizeCaptionText("I!"))
        assertEquals("えっ！？", normalizeCaptionText("えっ！？"))
        assertEquals("待って……", normalizeCaptionText("待って……"))
        assertEquals("スーパー", normalizeCaptionText("スーパー"))
        assertEquals("don't", normalizeCaptionText("don't"))
        assertEquals("3.14", normalizeCaptionText("3.14"))
    }

    /**
     * 验证SenseVoice元标签、控制字符和异常标点会在进入浮层及翻译队列前被处理。
     *
     * @return 无返回值；元标签泄漏、标点上限失效或纯噪声被接纳时由JUnit报告失败。
     */
    @Test
    fun captionNormalizationRemovesMetadataAndRejectsNonLexicalNoise() {
        assertEquals(
            "はい。",
            normalizeCaptionText("<|ja|><|NEUTRAL|><|Speech|>\u200Fはい。。")
        )
        assertEquals("No!!", normalizeCaptionText("No!!!!!"))
        assertEquals("Wait...", normalizeCaptionText("Wait...."))
        assertEquals("What?!", normalizeCaptionText("What?!"))
        assertEquals("什么！？", normalizeCaptionText("什么！？"))
        assertEquals("", normalizeCaptionText("...！？♪♪"))
        assertEquals("", normalizeCaptionText("ー"))
        assertEquals("", normalizeCaptionText("bad\uFFFDtext"))
        assertEquals("", normalizeCaptionText("<unk>"))
        assertEquals("", normalizeCaptionText("bad\uD800text"))
    }

    /**
     * 验证空白折叠保持词边界，并且相同清洗结果仍沿用既有partial去重契约。
     *
     * @return 无返回值；Unicode空白产生粘连或重复partial被错误接纳时由JUnit报告失败。
     */
    @Test
    fun captionNormalizationCollapsesUnicodeWhitespaceBeforeDeduplication() {
        val assembler = CaptionCueAssembler()

        val first = assembler.acceptPartial("hello\u00A0\u3000world")
        val duplicate = assembler.acceptPartial("hello world")

        assertEquals("hello world", first.displayText)
        assertTrue(first.accepted)
        assertFalse(duplicate.accepted)
    }
}
