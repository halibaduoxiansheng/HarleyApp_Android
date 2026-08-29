package com.example.harleyapp

import com.example.harleyapp.model.calculateNextReminderAt
import com.example.harleyapp.model.isReminderTriggerInFuture
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证通用通知按天重复的纯时间计算，不依赖Android设备或系统Alarm。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行本文件全部测试。
 */
class ReminderModelsTest {

    /**
     * 验证当前分钟内选择紧邻的下一分钟时，即使不足完整60秒也允许保存。
     *
     * @return 无返回值；下一分钟被错误阻止时由JUnit报告失败。
     */
    @Test
    fun nextMinuteReminderIsAccepted() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val nowMillis = timestamp(2026, 8, 29, 8, 0, zoneId) + 45_000L
        val triggerAtMillis = timestamp(2026, 8, 29, 8, 1, zoneId)

        assertEquals(true, isReminderTriggerInFuture(triggerAtMillis, nowMillis))
    }

    /**
     * 验证等于当前时刻或已经过去的提醒仍会被拒绝。
     *
     * @return 无返回值；非未来时间被错误接受时由JUnit报告失败。
     */
    @Test
    fun currentOrPastReminderIsRejected() {
        val nowMillis = 1_000_000L

        assertEquals(false, isReminderTriggerInFuture(nowMillis, nowMillis))
        assertEquals(false, isReminderTriggerInFuture(nowMillis - 1L, nowMillis))
    }

    /**
     * 验证重复间隔为0的一次性通知不会生成下一次时间。
     *
     * @return 无返回值；错误生成下一次时间时由JUnit报告失败。
     */
    @Test
    fun oneTimeReminderHasNoNextTrigger() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val triggerAtMillis = timestamp(2026, 8, 29, 10, 0, zoneId)

        val result = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalDays = 0,
            nowMillis = triggerAtMillis,
            zoneId = zoneId
        )

        assertNull(result)
    }

    /**
     * 验证每隔三天重复时，下一次日期增加三天且保持原时分。
     *
     * @return 无返回值；日期间隔或时分变化时由JUnit报告失败。
     */
    @Test
    fun recurringReminderKeepsSelectedLocalTime() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val triggerAtMillis = timestamp(2026, 8, 29, 10, 30, zoneId)
        val expectedMillis = timestamp(2026, 9, 1, 10, 30, zoneId)

        val result = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalDays = 3,
            nowMillis = triggerAtMillis + 1_000L,
            zoneId = zoneId
        )

        assertEquals(expectedMillis, result)
    }

    /**
     * 验证手机关机跨过多次周期后，直接跳到当前时刻之后最近的一次而不连续补发。
     *
     * @return 无返回值；结果仍过期或没有跳过旧周期时由JUnit报告失败。
     */
    @Test
    fun missedIntervalsAdvanceToNearestFutureTrigger() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val triggerAtMillis = timestamp(2026, 8, 29, 10, 0, zoneId)
        val nowMillis = timestamp(2026, 9, 8, 12, 0, zoneId)
        val expectedMillis = timestamp(2026, 9, 10, 10, 0, zoneId)

        val result = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalDays = 3,
            nowMillis = nowMillis,
            zoneId = zoneId
        )

        assertEquals(expectedMillis, result)
    }

    /**
     * 把固定本地日期时间转换为测试使用的Unix毫秒时间戳。
     *
     * @param year 年。
     * @param month 月，范围1到12。
     * @param day 月内日期。
     * @param hour 24小时制小时。
     * @param minute 分钟。
     * @param zoneId 测试固定时区。
     *
     * @return 对应日期时间的Unix毫秒时间戳。
     */
    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zoneId: ZoneId
    ): Long {
        return LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
