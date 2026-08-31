package com.example.harleyapp

import com.example.harleyapp.model.ReminderRepeatUnit
import com.example.harleyapp.model.calculateNextReminderAt
import com.example.harleyapp.model.isReminderTriggerInFuture
import com.example.harleyapp.model.resolveInitialReminderTriggerAt
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证通用通知的首次时间解析和多单位重复计算，不依赖Android设备或系统Alarm。
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
     * 验证等于当前时刻或已经过去的提醒仍会被基础未来时间判断拒绝。
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
     * 验证表单默认显示当前分钟时，立即保存会转换为一秒后的有效触发时间。
     *
     * @return 无返回值；当前分钟仍被误判为过期时由JUnit报告失败。
     */
    @Test
    fun currentMinuteReminderBecomesImmediateFutureTrigger() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val selectedMillis = timestamp(2026, 8, 31, 18, 20, zoneId)
        val nowMillis = selectedMillis + 42_000L

        assertEquals(
            nowMillis + 1_000L,
            resolveInitialReminderTriggerAt(selectedMillis, nowMillis, zoneId)
        )
    }

    /**
     * 验证早于当前分钟的选择不会被当成立即提醒。
     *
     * @return 无返回值；真正过期的提醒被接受时由JUnit报告失败。
     */
    @Test
    fun pastMinuteReminderIsRejected() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val selectedMillis = timestamp(2026, 8, 31, 18, 19, zoneId)
        val nowMillis = timestamp(2026, 8, 31, 18, 20, zoneId) + 10_000L

        assertNull(resolveInitialReminderTriggerAt(selectedMillis, nowMillis, zoneId))
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
            repeatIntervalValue = 0,
            repeatIntervalUnit = ReminderRepeatUnit.DAY,
            nowMillis = triggerAtMillis,
            zoneId = zoneId
        )

        assertNull(result)
    }

    /**
     * 验证秒级重复会跳过已经错过的周期并返回最近的未来时刻。
     *
     * @return 无返回值；秒级换算或补偿错误时由JUnit报告失败。
     */
    @Test
    fun secondIntervalAdvancesToNearestFutureTrigger() {
        val triggerAtMillis = 1_000_000L

        val result = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalValue = 2,
            repeatIntervalUnit = ReminderRepeatUnit.SECOND,
            nowMillis = triggerAtMillis + 3_500L
        )

        assertEquals(triggerAtMillis + 4_000L, result)
    }

    /**
     * 验证分钟和小时均按照固定时长推进，不受本地日期边界干扰。
     *
     * @return 无返回值；分钟或小时换算错误时由JUnit报告失败。
     */
    @Test
    fun minuteAndHourIntervalsUseFixedDuration() {
        val triggerAtMillis = 1_000_000L

        val nextMinuteTrigger = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalValue = 5,
            repeatIntervalUnit = ReminderRepeatUnit.MINUTE,
            nowMillis = triggerAtMillis + 12 * 60_000L
        )
        val nextHourTrigger = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalValue = 2,
            repeatIntervalUnit = ReminderRepeatUnit.HOUR,
            nowMillis = triggerAtMillis + 5 * 3_600_000L
        )

        assertEquals(triggerAtMillis + 15 * 60_000L, nextMinuteTrigger)
        assertEquals(triggerAtMillis + 6 * 3_600_000L, nextHourTrigger)
    }

    /**
     * 验证每隔三天重复时，下一次日期增加三天且保持原时分。
     *
     * @return 无返回值；日期间隔或时分变化时由JUnit报告失败。
     */
    @Test
    fun recurringDayReminderKeepsSelectedLocalTime() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val triggerAtMillis = timestamp(2026, 8, 29, 10, 30, zoneId)
        val expectedMillis = timestamp(2026, 9, 1, 10, 30, zoneId)

        val result = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalValue = 3,
            repeatIntervalUnit = ReminderRepeatUnit.DAY,
            nowMillis = triggerAtMillis + 1_000L,
            zoneId = zoneId
        )

        assertEquals(expectedMillis, result)
    }

    /**
     * 验证两星期重复会按十四个当地日历日推进。
     *
     * @return 无返回值；星期没有正确换算成日历周期时由JUnit报告失败。
     */
    @Test
    fun weekIntervalUsesSevenCalendarDays() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val triggerAtMillis = timestamp(2026, 8, 31, 9, 0, zoneId)
        val expectedMillis = timestamp(2026, 9, 14, 9, 0, zoneId)

        val result = calculateNextReminderAt(
            currentTriggerAtMillis = triggerAtMillis,
            repeatIntervalValue = 2,
            repeatIntervalUnit = ReminderRepeatUnit.WEEK,
            nowMillis = triggerAtMillis + 1_000L,
            zoneId = zoneId
        )

        assertEquals(expectedMillis, result)
    }

    /**
     * 验证手机关机跨过多次日周期后，直接跳到当前时刻之后最近的一次而不连续补发。
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
            repeatIntervalValue = 3,
            repeatIntervalUnit = ReminderRepeatUnit.DAY,
            nowMillis = nowMillis,
            zoneId = zoneId
        )

        assertEquals(expectedMillis, result)
    }

    /**
     * 验证存储单位能够恢复，未知旧值安全回退为天。
     *
     * @return 无返回值；本地单位迁移不稳定时由JUnit报告失败。
     */
    @Test
    fun repeatUnitStorageValueIsStable() {
        assertEquals(ReminderRepeatUnit.SECOND, ReminderRepeatUnit.fromStorageValue("second"))
        assertEquals(ReminderRepeatUnit.WEEK, ReminderRepeatUnit.fromStorageValue("WEEK"))
        assertEquals(ReminderRepeatUnit.DAY, ReminderRepeatUnit.fromStorageValue("unknown"))
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
