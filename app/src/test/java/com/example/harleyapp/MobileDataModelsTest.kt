package com.example.harleyapp

import com.example.harleyapp.model.MobileDataPeriod
import com.example.harleyapp.model.calculateMobileDataPeriodStart
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证手机流量“今日、本周、本月”的自然日期边界计算。
 *
 * 使用方法：
 * 在项目根目录执行 `gradlew testDebugUnitTest`，JUnit会自动运行本类全部测试。
 * 测试使用固定的上海时区，不依赖执行测试电脑的当前时间和默认时区。
 */
class MobileDataModelsTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val nowMillis = timestamp(2026, 8, 29, 14, 20)

    /**
     * 验证“今日”从当前本地日期的00:00开始。
     *
     * @return 无返回值；边界不是当天零点时由JUnit报告失败。
     */
    @Test
    fun todayStartsAtLocalMidnight() {
        val actual = calculateMobileDataPeriodStart(
            period = MobileDataPeriod.TODAY,
            nowMillis = nowMillis,
            zoneId = zoneId
        )

        assertEquals(timestamp(2026, 8, 29, 0, 0), actual)
    }

    /**
     * 验证“本周”按中文日历习惯从周一00:00开始。
     *
     * @return 无返回值；未定位到同一自然周周一时由JUnit报告失败。
     */
    @Test
    fun weekStartsOnMonday() {
        val actual = calculateMobileDataPeriodStart(
            period = MobileDataPeriod.WEEK,
            nowMillis = nowMillis,
            zoneId = zoneId
        )

        assertEquals(timestamp(2026, 8, 24, 0, 0), actual)
    }

    /**
     * 验证“本月”从当月1日00:00开始。
     *
     * @return 无返回值；未定位到当月首日时由JUnit报告失败。
     */
    @Test
    fun monthStartsOnFirstDay() {
        val actual = calculateMobileDataPeriodStart(
            period = MobileDataPeriod.MONTH,
            nowMillis = nowMillis,
            zoneId = zoneId
        )

        assertEquals(timestamp(2026, 8, 1, 0, 0), actual)
    }

    /**
     * 把固定的本地日期时间转换成测试断言使用的Unix毫秒时间戳。
     *
     * @param year 年。
     * @param month 月，范围1到12。
     * @param day 月内日期。
     * @param hour 24小时制小时。
     * @param minute 分钟。
     *
     * @return 上海时区下对应时刻的Unix毫秒时间戳。
     */
    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Long {
        return LocalDateTime.of(year, month, day, hour, minute)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
