package com.example.harleyapp

import com.example.harleyapp.model.FLASHLIGHT_MAX_FREQUENCY_HZ
import com.example.harleyapp.model.FlashlightSettingsIssue
import com.example.harleyapp.model.FlashlightStrobeSettings
import com.example.harleyapp.model.FlashlightTiming
import com.example.harleyapp.model.calculateFlashlightFrequencyHz
import com.example.harleyapp.model.scaleFlashlightTimingForFrequency
import com.example.harleyapp.model.validateFlashlightStrobeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证爆闪频率、明暗占比缩放和启动参数边界，不依赖Android设备或真实闪光灯。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew testDebugUnitTest`，JUnit会自动运行本类全部测试。
 */
class FlashlightModelsTest {

    /**
     * 验证100ms亮灯加100ms灭灯等于每秒五个完整周期。
     *
     * @return 无返回值；频率公式错误时由JUnit报告失败。
     */
    @Test
    fun frequencyUsesCompleteOnAndOffPeriod() {
        assertEquals(5f, calculateFlashlightFrequencyHz(100, 100), 0.0001f)
    }

    /**
     * 验证调整频率时保持原有25%的点亮占比。
     *
     * @return 无返回值；缩放后的明暗时长不正确时由JUnit报告失败。
     */
    @Test
    fun frequencyAdjustmentPreservesDutyRatio() {
        val adjusted = scaleFlashlightTimingForFrequency(
            currentTiming = FlashlightTiming(100, 300),
            targetFrequencyHz = 2f
        )

        assertEquals(FlashlightTiming(125, 375), adjusted)
        assertEquals(2f, adjusted.frequencyHz, 0.0001f)
    }

    /**
     * 验证10Hz边界仍保证亮灯和灭灯各自不少于50ms。
     *
     * @return 无返回值；边界阶段低于安全下限时由JUnit报告失败。
     */
    @Test
    fun maximumFrequencyKeepsBothPhasesAtMinimumDuration() {
        val adjusted = scaleFlashlightTimingForFrequency(
            currentTiming = FlashlightTiming(50, 1_950),
            targetFrequencyHz = FLASHLIGHT_MAX_FREQUENCY_HZ
        )

        assertEquals(FlashlightTiming(50, 50), adjusted)
    }

    /**
     * 验证默认合法参数可以直接开始爆闪。
     *
     * @return 无返回值；合法参数被错误拒绝时由JUnit报告失败。
     */
    @Test
    fun validSettingsHaveNoValidationIssue() {
        val settings = FlashlightStrobeSettings(
            totalDurationSeconds = 10,
            timing = FlashlightTiming(100, 100),
            strengthLevel = 1
        )

        assertNull(validateFlashlightStrobeSettings(settings))
    }

    /**
     * 验证超过300秒的总运行时长会被拒绝。
     *
     * @return 无返回值；越界总时长未被识别时由JUnit报告失败。
     */
    @Test
    fun totalDurationAboveLimitIsRejected() {
        val issue = validateFlashlightStrobeSettings(
            FlashlightStrobeSettings(
                totalDurationSeconds = 301,
                timing = FlashlightTiming(100, 100),
                strengthLevel = 1
            )
        )

        assertEquals(FlashlightSettingsIssue.TOTAL_DURATION_OUT_OF_RANGE, issue)
    }

    /**
     * 验证过长的完整周期会被拒绝，避免低于界面承诺的0.5Hz。
     *
     * @return 无返回值；过长周期未被识别时由JUnit报告失败。
     */
    @Test
    fun periodAboveLimitIsRejected() {
        val issue = validateFlashlightStrobeSettings(
            FlashlightStrobeSettings(
                totalDurationSeconds = 10,
                timing = FlashlightTiming(1_100, 1_000),
                strengthLevel = 1
            )
        )

        assertEquals(FlashlightSettingsIssue.PERIOD_TOO_LONG, issue)
    }
}
