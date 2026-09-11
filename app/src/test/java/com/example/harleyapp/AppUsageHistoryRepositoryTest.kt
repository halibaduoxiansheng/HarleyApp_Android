package com.example.harleyapp

import com.example.harleyapp.data.chooseMoreCompleteDay
import com.example.harleyapp.model.AggregatedAppUsageDay
import com.example.harleyapp.model.AggregatedPackageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/** 验证逐日应用使用留存不会被系统清理后的残缺查询覆盖。 */
class AppUsageHistoryRepositoryTest {

    /**
     * 验证新查询总时长变少时整天保留旧记录，不能逐字段拼出互相重叠的数据。
     *
     * @return 无返回值；残缺查询覆盖较完整旧结果时由JUnit报告失败。
     */
    @Test
    fun shorterLiveDayKeepsCachedWholeDay() {
        val date = LocalDate.of(2026, 9, 10)
        val cachedDay = usageDay(date, packageName = "cached.app", minutes = 80L)
        val liveDay = usageDay(date, packageName = "live.app", minutes = 20L)

        val selectedDay = chooseMoreCompleteDay(liveDay, cachedDay)

        assertSame(cachedDay, selectedDay)
        assertEquals(listOf("cached.app"), selectedDay.packages.map { usage -> usage.packageName })
    }

    /**
     * 验证系统补充了更多事件后采用整天新结果，让逐日缓存可以随真实使用单调更新。
     *
     * @return 无返回值；较完整新结果没有替换旧结果时由JUnit报告失败。
     */
    @Test
    fun longerLiveDayReplacesCachedWholeDay() {
        val date = LocalDate.of(2026, 9, 10)
        val cachedDay = usageDay(date, packageName = "cached.app", minutes = 20L)
        val liveDay = usageDay(date, packageName = "live.app", minutes = 80L)

        val selectedDay = chooseMoreCompleteDay(liveDay, cachedDay)

        assertSame(liveDay, selectedDay)
        assertEquals(listOf("live.app"), selectedDay.packages.map { usage -> usage.packageName })
    }

    /**
     * 验证今天永远使用实时重算结果，即使实时值更小也能纠正本轮之前出现的虚高缓存。
     *
     * @return 无返回值；当天仍被旧缓存竞高规则锁住时由JUnit报告失败。
     */
    @Test
    fun currentDayAlwaysUsesLiveResult() {
        val date = LocalDate.of(2026, 9, 10)
        val cachedDay = usageDay(date, packageName = "cached.app", minutes = 80L)
        val liveDay = usageDay(date, packageName = "live.app", minutes = 20L)

        val selectedDay = chooseMoreCompleteDay(
            liveDay = liveDay,
            cachedDay = cachedDay,
            isCurrentDay = true
        )

        assertSame(liveDay, selectedDay)
    }

    /**
     * 创建只包含一个应用的单日聚合结果，供缓存完整度规则测试复用。
     *
     * @param date 结果所属自然日期。
     * @param packageName 唯一应用包名。
     * @param minutes 应用前台交互分钟数。
     * @return 可直接交给缓存选择函数的单日结果。
     */
    private fun usageDay(
        date: LocalDate,
        packageName: String,
        minutes: Long
    ): AggregatedAppUsageDay {
        return AggregatedAppUsageDay(
            date = date,
            packages = listOf(
                AggregatedPackageUsage(
                    packageName = packageName,
                    foregroundMillis = minutes * 60_000L,
                    launchCount = 1,
                    nightMillis = 0L,
                    lastUsedAtMillis = 1L
                )
            )
        )
    }
}
