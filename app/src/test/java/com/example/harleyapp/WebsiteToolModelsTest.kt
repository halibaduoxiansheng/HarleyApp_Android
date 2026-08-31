package com.example.harleyapp

import com.example.harleyapp.model.MAX_NIGHT_OVERLAY_ALPHA
import com.example.harleyapp.model.MAX_TEXT_ZOOM_PERCENT
import com.example.harleyapp.model.MAX_WEBSITE_PLAYBACK_RATE
import com.example.harleyapp.model.MIN_NIGHT_OVERLAY_ALPHA
import com.example.harleyapp.model.MIN_TEXT_ZOOM_PERCENT
import com.example.harleyapp.model.MIN_WEBSITE_PLAYBACK_RATE
import com.example.harleyapp.model.WebsiteToolSettings
import com.example.harleyapp.ui.screens.calculateFullscreenBallOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证网页工具设置的默认行为和数值边界，不依赖Android WebView或真实视频网站。
 *
 * 使用方法：
 * 在项目根目录运行gradlew testDebugUnitTest，由JUnit自动执行本测试类。
 */
class WebsiteToolModelsTest {

    /**
     * 验证首次进入网站时不会意外静音、循环、阻止播放或改变网页显示。
     *
     * @return 无返回值；默认值不符合保守策略时由JUnit报告失败。
     */
    @Test
    fun defaultToolsUseConservativeInitialValues() {
        val settings = WebsiteToolSettings()

        assertEquals(1f, settings.playbackRate)
        assertEquals(100, settings.textZoomPercent)
        assertFalse(settings.blockAutoplay)
        assertFalse(settings.videoMuted)
        assertFalse(settings.videoLoopEnabled)
        assertFalse(settings.textSelectionEnabled)
        assertFalse(settings.nightModeEnabled)
        assertFalse(settings.keepScreenOn)
        assertFalse(settings.desktopModeEnabled)
    }

    /**
     * 验证超过上限的旧数据会被限制，防止WebView接收异常倍速、遮罩和文字缩放值。
     *
     * @return 无返回值；任一上限未生效时由JUnit报告失败。
     */
    @Test
    fun normalizationClampsValuesToUpperBounds() {
        val normalized = WebsiteToolSettings(
            playbackRate = 99f,
            nightOverlayAlpha = 5f,
            textZoomPercent = 1_000
        ).normalized()

        assertEquals(MAX_WEBSITE_PLAYBACK_RATE, normalized.playbackRate)
        assertEquals(MAX_NIGHT_OVERLAY_ALPHA, normalized.nightOverlayAlpha)
        assertEquals(MAX_TEXT_ZOOM_PERCENT, normalized.textZoomPercent)
    }

    /**
     * 验证低于下限的旧数据会被限制，同时不改变独立的布尔工具状态。
     *
     * @return 无返回值；下限或布尔值被错误处理时由JUnit报告失败。
     */
    @Test
    fun normalizationClampsLowerBoundsAndKeepsToggles() {
        val normalized = WebsiteToolSettings(
            playbackRate = -1f,
            nightOverlayAlpha = -2f,
            textZoomPercent = 10,
            blockAutoplay = true,
            keepScreenOn = true
        ).normalized()

        assertEquals(MIN_WEBSITE_PLAYBACK_RATE, normalized.playbackRate)
        assertEquals(MIN_NIGHT_OVERLAY_ALPHA, normalized.nightOverlayAlpha)
        assertEquals(MIN_TEXT_ZOOM_PERCENT, normalized.textZoomPercent)
        assertTrue(normalized.blockAutoplay)
        assertTrue(normalized.keepScreenOn)
    }

    /**
     * 验证全屏脚本悬浮球只能在屏幕中部安全范围移动，不会被拖到底部遮挡播放器进度条。
     *
     * @return 无返回值；正常拖动、上下边界或非法负上限处理错误时由JUnit报告失败。
     */
    @Test
    fun fullscreenScriptBallDragStaysInsideSafeRange() {
        assertEquals(35f, calculateFullscreenBallOffset(20f, 15f, 120f), 0.001f)
        assertEquals(120f, calculateFullscreenBallOffset(80f, 90f, 120f), 0.001f)
        assertEquals(-120f, calculateFullscreenBallOffset(-80f, -90f, 120f), 0.001f)
        assertEquals(0f, calculateFullscreenBallOffset(20f, 10f, -1f), 0.001f)
    }
}
