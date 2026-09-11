package com.example.harleyapp

import com.example.harleyapp.model.MORSE_DEFAULT_UNIT_DURATION_MILLIS
import com.example.harleyapp.model.MORSE_MAX_INPUT_LENGTH
import com.example.harleyapp.model.createMorseFlashPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证摩斯编码、国际标准时序、输入归一化和启动边界，不依赖Android设备或真实手电筒。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew :app:testDebugUnitTest`，JUnit会自动运行本类全部测试。测试通过只能
 * 证明纯Kotlin计划生成正确，真实手电筒切换精度和随时停止行为仍需由设备执行层另行验证。
 */
class MorseFlashModelsTest {

    /**
     * 验证SOS由27个标准时间单位组成，默认200毫秒单位时预计总时长为5400毫秒。
     *
     * @return 无返回值；编码、单位数、总时长或启动条件错误时由JUnit报告失败。
     */
    @Test
    fun sosUsesTwentySevenUnitsAndDefaultDuration() {
        val plan = createMorseFlashPlan("SOS")

        assertEquals("... --- ...", plan.encodedText)
        assertEquals(27L, plan.totalDurationMillis / MORSE_DEFAULT_UNIT_DURATION_MILLIS)
        assertEquals(5_400L, plan.totalDurationMillis)
        assertTrue(plan.canStart)
    }

    /**
     * 验证A与B之间的空白使用7单位单词间隔，因此整段编码共21个时间单位。
     *
     * @return 无返回值；点划时长或单词间隔错误时由JUnit报告失败。
     */
    @Test
    fun aAndBSeparatedByWordGapUseTwentyOneUnits() {
        val plan = createMorseFlashPlan(text = "A B", unitDurationMillis = 1L)

        assertEquals(".- / -...", plan.encodedText)
        assertEquals(21L, plan.totalDurationMillis)
    }

    /**
     * 验证英文小写会归一为大写编码，多个不同空白只保留一个单词间隔。
     *
     * @return 无返回值；大小写或连续空白改变编码和时序时由JUnit报告失败。
     */
    @Test
    fun lowercaseAndConsecutiveWhitespaceAreNormalized() {
        val irregularPlan = createMorseFlashPlan("  sOs\t \n 42  ")
        val normalizedPlan = createMorseFlashPlan("SOS 42")

        assertEquals("... --- ... / ....- ..---", irregularPlan.encodedText)
        assertEquals(normalizedPlan, irregularPlan)
    }

    /**
     * 验证NFKC归一化可把全角英数、空格和感叹号转换为已有国际摩斯编码。
     *
     * @return 无返回值；全角字符被误报为不支持或编码不一致时由JUnit报告失败。
     */
    @Test
    fun fullWidthLettersDigitsAndPunctuationAreSupported() {
        val fullWidthPlan = createMorseFlashPlan("ｓＯＳ　１２３！")
        val asciiPlan = createMorseFlashPlan("SOS 123!")

        assertEquals(asciiPlan, fullWidthPlan)
        assertTrue(fullWidthPlan.unsupportedCharacters.isEmpty())
    }

    /**
     * 验证不支持字符按出现顺序完整收集，同时保留其余可编码内容供界面预览。
     *
     * @return 无返回值；字符被遗漏、错误参与闪光或计划仍可启动时由JUnit报告失败。
     */
    @Test
    fun unsupportedCharactersAreCollectedAndDisableStart() {
        val plan = createMorseFlashPlan("A#中B")

        assertEquals(listOf("#", "中"), plan.unsupportedCharacters)
        assertEquals(".- -...", plan.encodedText)
        assertFalse(plan.canStart)
    }

    /**
     * 验证一个由UTF-16代理对组成的Emoji只会形成一个完整的不支持字符提示。
     *
     * @return 无返回值；Emoji被拆成两个乱码提示项时由JUnit报告失败。
     */
    @Test
    fun emojiIsReportedAsOneUnsupportedCharacter() {
        val plan = createMorseFlashPlan("SOS🙂")

        assertEquals(listOf("🙂"), plan.unsupportedCharacters)
        assertFalse(plan.canStart)
    }

    /**
     * 验证纯空白输入不会生成首尾灭灯阶段、预计时长或可启动计划。
     *
     * @return 无返回值；空输入生成任何执行阶段或被允许启动时由JUnit报告失败。
     */
    @Test
    fun blankInputCreatesEmptyPlan() {
        val plan = createMorseFlashPlan(" \t\n ")

        assertTrue(plan.encodedText.isEmpty())
        assertTrue(plan.steps.isEmpty())
        assertTrue(plan.unsupportedCharacters.isEmpty())
        assertEquals(0L, plan.totalDurationMillis)
        assertFalse(plan.canStart)
    }

    /**
     * 验证预计总时长严格等于全部亮灭阶段之和，且计划没有开头或结尾的空白阶段。
     *
     * @return 无返回值；阶段持续时间、累计结果或首尾状态错误时由JUnit报告失败。
     */
    @Test
    fun totalDurationMatchesStepsWithoutLeadingOrTrailingGap() {
        val plan = createMorseFlashPlan(text = "CQ? TEST", unitDurationMillis = 137L)

        assertTrue(plan.steps.first().isLightOn)
        assertTrue(plan.steps.last().isLightOn)
        assertTrue(plan.steps.all { step -> step.durationMillis > 0L })
        assertTrue(
            plan.steps.zipWithNext().all { (current, next) ->
                current.isLightOn != next.isLightOn
            }
        )
        assertEquals(plan.steps.sumOf { step -> step.durationMillis }, plan.totalDurationMillis)
    }

    /**
     * 验证模型最多处理160个原始输入字符，避免界面遗漏限制后产生超长计划。
     *
     * @return 无返回值；超出限制的尾部仍参与编码时由JUnit报告失败。
     */
    @Test
    fun inputIsBoundedToMaximumLength() {
        val plan = createMorseFlashPlan("E".repeat(MORSE_MAX_INPUT_LENGTH + 1))

        assertEquals(MORSE_MAX_INPUT_LENGTH, plan.encodedText.count { character -> character == '.' })
        assertTrue(plan.inputWasTruncated)
        assertFalse(plan.canStart)
    }
}
