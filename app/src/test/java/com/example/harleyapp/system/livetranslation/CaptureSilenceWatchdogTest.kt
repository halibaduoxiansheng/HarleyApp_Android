package com.example.harleyapp.system.livetranslation

import com.example.harleyapp.model.LiveTranslationCaptureStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证实时翻译声音采集的静音进入、迟滞恢复和PCM音量计算。
 *
 * 使用方法：
 * 由app:testDebugUnitTest自动执行；测试使用内存中的ShortArray，不访问麦克风或MediaProjection。
 */
class CaptureSilenceWatchdogTest {

    /**
     * 验证连续静音只有达到完整超时才进入SILENT，并准确报告状态变化。
     *
     * @return 无返回值；静音过早或过晚触发时由JUnit报告失败。
     */
    @Test
    fun continuousSilenceEntersSilentStateAtTimeout() {
        val watchdog = CaptureSilenceWatchdog(
            CaptureSilencePolicy(
                silenceThresholdDb = -50f,
                enterSilenceAfterMillis = 1_000L,
                recoverAfterMillis = 200L
            )
        )

        val beforeTimeout = watchdog.observe(-60f, elapsedMillis = 999L)
        val atTimeout = watchdog.observe(-60f, elapsedMillis = 1L)

        assertEquals(LiveTranslationCaptureStatus.CAPTURING, beforeTimeout.captureStatus)
        assertFalse(beforeTimeout.statusChanged)
        assertEquals(LiveTranslationCaptureStatus.SILENT, atTimeout.captureStatus)
        assertTrue(atTimeout.statusChanged)
        assertEquals(1_000L, atTimeout.silentDurationMillis)
    }

    /**
     * 验证SILENT状态需要连续有效声音满足恢复迟滞，期间再次静音会重新累计恢复时间。
     *
     * @return 无返回值；短促噪声导致抖动或稳定声音不能恢复时断言失败。
     */
    @Test
    fun audibleSignalRecoversOnlyAfterContinuousHysteresis() {
        val watchdog = CaptureSilenceWatchdog(
            CaptureSilencePolicy(
                silenceThresholdDb = -50f,
                enterSilenceAfterMillis = 300L,
                recoverAfterMillis = 200L
            )
        )
        watchdog.observe(-80f, elapsedMillis = 300L)

        val firstSignal = watchdog.observe(-20f, elapsedMillis = 150L)
        val interrupted = watchdog.observe(-80f, elapsedMillis = 20L)
        val secondSignal = watchdog.observe(-20f, elapsedMillis = 200L)

        assertEquals(LiveTranslationCaptureStatus.SILENT, firstSignal.captureStatus)
        assertEquals(150L, firstSignal.recoveryDurationMillis)
        assertEquals(LiveTranslationCaptureStatus.SILENT, interrupted.captureStatus)
        assertEquals(0L, interrupted.recoveryDurationMillis)
        assertEquals(LiveTranslationCaptureStatus.CAPTURING, secondSignal.captureStatus)
        assertTrue(secondSignal.statusChanged)
    }

    /**
     * 验证全零PCM返回负无穷，明显非零PCM能够被判定为有效采集信号。
     *
     * @return 无返回值；音量单位或零能量语义变化时由JUnit报告失败。
     */
    @Test
    fun pcmLevelDistinguishesZeroAndAudibleSamples() {
        val silentLevel = calculatePcm16RmsDb(shortArrayOf(0, 0, 0, 0))
        val audibleLevel = calculatePcm16RmsDb(
            shortArrayOf(16_384, -16_384, 16_384, -16_384)
        )

        assertEquals(Float.NEGATIVE_INFINITY, silentLevel)
        assertEquals(-6.02f, audibleLevel, 0.05f)
    }
}
