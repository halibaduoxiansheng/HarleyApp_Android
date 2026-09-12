package com.example.harleyapp

import com.example.harleyapp.model.LiveTranslationCaptureStatus
import com.example.harleyapp.model.LiveTranslationModelSnapshot
import com.example.harleyapp.model.LiveTranslationModelStage
import com.example.harleyapp.model.LiveTranslationSessionStatus
import com.example.harleyapp.model.LiveTranslationSettings
import com.example.harleyapp.model.LiveTranslationSnapshot
import com.example.harleyapp.model.LiveTranslationSourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证实时翻译设置边界和界面按钮依赖的纯状态派生属性。
 *
 * 使用方法：
 * 在项目根目录执行 `gradlew testDebugUnitTest`；测试不加载模型、音频设备或Android服务。
 */
class LiveTranslationModelsTest {

    /**
     * 验证默认显示设置符合UI契约，合法输入规范化后保持不变。
     *
     * @return 无返回值；默认值或透明度换算变化时由JUnit报告失败。
     */
    @Test
    fun defaultSettingsRemainStableAfterNormalization() {
        val settings = LiveTranslationSettings()

        assertEquals(true, settings.showSourceText)
        assertEquals(22, settings.textSizeSp)
        assertEquals(72, settings.backgroundOpacityPercent)
        assertEquals(LiveTranslationSourceLanguage.ENGLISH, settings.sourceLanguage)
        assertEquals(settings, settings.normalized())
        assertEquals(0.72f, settings.backgroundOpacityFraction, 0.0001f)
    }

    /**
     * 验证英语和日语两种源语言都能进入设置，并且数值规范化不会误改用户选择。
     *
     * @return 无返回值；任一语言无法保存或规范化后发生变化时由JUnit报告失败。
     */
    @Test
    fun settingsSupportEnglishAndJapaneseSourceLanguages() {
        val english = LiveTranslationSettings(
            sourceLanguage = LiveTranslationSourceLanguage.ENGLISH
        ).normalized()
        val japanese = LiveTranslationSettings(
            sourceLanguage = LiveTranslationSourceLanguage.JAPANESE,
            textSizeSp = 100,
            backgroundOpacityPercent = 0
        ).normalized()

        assertEquals(LiveTranslationSourceLanguage.ENGLISH, english.sourceLanguage)
        assertEquals(LiveTranslationSourceLanguage.JAPANESE, japanese.sourceLanguage)
        assertEquals(36, japanese.textSizeSp)
        assertEquals(35, japanese.backgroundOpacityPercent)
    }

    /**
     * 验证旧配置中的过小和过大数值会被限制到当前界面支持范围。
     *
     * @return 无返回值；任一边界没有限制到16..36或35..95时断言失败。
     */
    @Test
    fun settingsNormalizationClampsBothBoundaries() {
        val belowMinimum = LiveTranslationSettings(
            textSizeSp = -20,
            backgroundOpacityPercent = 0
        ).normalized()
        val aboveMaximum = LiveTranslationSettings(
            textSizeSp = 200,
            backgroundOpacityPercent = 150
        ).normalized()

        assertEquals(16, belowMinimum.textSizeSp)
        assertEquals(35, belowMinimum.backgroundOpacityPercent)
        assertEquals(36, aboveMaximum.textSizeSp)
        assertEquals(95, aboveMaximum.backgroundOpacityPercent)
    }

    /**
     * 验证模型阶段和会话阶段为UI提供一致的就绪、开始及停止判断。
     *
     * @return 无返回值；按钮判定或运行状态含义发生回归时断言失败。
     */
    @Test
    fun snapshotsExposeStableUiDerivedState() {
        val preparingModel = LiveTranslationModelSnapshot(
            stage = LiveTranslationModelStage.DOWNLOADING,
            progressPercent = 25,
            message = "正在下载"
        )
        val running = LiveTranslationSnapshot(
            sessionStatus = LiveTranslationSessionStatus.RUNNING,
            captureStatus = LiveTranslationCaptureStatus.CAPTURING,
            sourceText = "hello",
            translatedText = "你好",
            audioLevelDb = -18f
        )

        assertTrue(preparingModel.isPreparing)
        assertFalse(preparingModel.isReady)
        assertTrue(running.isRunning)
        assertTrue(running.isSessionActive)
        assertTrue(running.canStop)
        assertFalse(running.canStart)
        assertTrue(running.isCapturing)
        assertTrue(running.hasCaptionText)

        val stopped = LiveTranslationSnapshot()
        assertTrue(stopped.canStart)
        assertFalse(stopped.canStop)
        assertFalse(stopped.isSessionActive)
    }
}
