package com.example.harleyapp

import com.example.harleyapp.model.AppUsageEventSnapshot
import com.example.harleyapp.model.BreathHoldRecord
import com.example.harleyapp.model.StorageFileCategory
import com.example.harleyapp.model.aggregateAppUsageEvents
import com.example.harleyapp.model.calculateBreathHoldSummary
import com.example.harleyapp.model.classifyStorageFile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 验证应用使用、文件分类和憋气历史三个新模块的纯计算边界。 */
class PersonalPhoneToolsModelsTest {

    /**
     * 验证同一应用两个Activity重叠时只计算一段前台区间和一次打开。
     *
     * @return 无返回值；时长或打开次数重复计算时由JUnit报告失败。
     */
    @Test
    fun appUsageOverlappingActivitiesCountOnce() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val rangeStart = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = today.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()
        val events = listOf(
            usageEvent("demo.app", "First", today, 10, 0, true, zoneId),
            usageEvent("demo.app", "First", today, 10, 5, false, zoneId),
            usageEvent("demo.app", "Second", today, 10, 5, true, zoneId),
            usageEvent("demo.app", "Second", today, 10, 10, false, zoneId)
        )

        val result = aggregateAppUsageEvents(
            events = events,
            rangeStartMillis = rangeStart,
            nowMillis = now,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )

        assertEquals(1, result.packages.size)
        assertEquals(10L * 60_000L, result.packages.first().todayForegroundMillis)
        assertEquals(1, result.packages.first().todayLaunchCount)
        assertEquals(10L * 60_000L, result.sevenDayTrend.last().foregroundMillis)
    }

    /**
     * 验证跨午夜前台区间会拆分到两天，今天00:00至06:00部分计入夜间。
     *
     * @return 无返回值；跨天趋势或夜间边界错误时由JUnit报告失败。
     */
    @Test
    fun appUsageCrossMidnightSplitsTrendAndNightTime() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val yesterday = today.minusDays(1)
        val rangeStart = today.minusDays(6).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val now = today.atTime(1, 0).atZone(zoneId).toInstant().toEpochMilli()
        val events = listOf(
            usageEvent("night.app", "Main", yesterday, 22, 30, true, zoneId),
            usageEvent("night.app", "Main", today, 0, 30, false, zoneId)
        )

        val result = aggregateAppUsageEvents(
            events = events,
            rangeStartMillis = rangeStart,
            nowMillis = now,
            zoneId = zoneId,
            allowedPackages = setOf("night.app")
        )
        val packageUsage = result.packages.first()

        assertEquals(30L * 60_000L, packageUsage.todayForegroundMillis)
        assertEquals(30L * 60_000L, packageUsage.todayNightMillis)
        assertEquals(0, packageUsage.todayLaunchCount)
        assertEquals(90L * 60_000L, result.sevenDayTrend[5].foregroundMillis)
        assertEquals(30L * 60_000L, result.sevenDayTrend[6].foregroundMillis)
    }

    /**
     * 验证常用扩展名大小写无关地进入正确分类，未知扩展名安全归入其他。
     *
     * @return 无返回值；分类表退化时由JUnit报告失败。
     */
    @Test
    fun storageClassificationRecognizesCommonFileTypes() {
        assertEquals(StorageFileCategory.IMAGE, classifyStorageFile("Camera/IMG_1.HEIC"))
        assertEquals(StorageFileCategory.VIDEO, classifyStorageFile("movie.mkv"))
        assertEquals(StorageFileCategory.AUDIO, classifyStorageFile("voice.opus"))
        assertEquals(StorageFileCategory.DOCUMENT, classifyStorageFile("report.xlsx"))
        assertEquals(StorageFileCategory.ARCHIVE, classifyStorageFile("backup.7z"))
        assertEquals(StorageFileCategory.INSTALLER, classifyStorageFile("release.xapk"))
        assertEquals(StorageFileCategory.OTHER, classifyStorageFile("no_extension"))
    }

    /**
     * 验证憋气历史以完成时间识别最近两次，并正确计算最佳、平均和本次变化。
     *
     * @return 无返回值；个人统计错误时由JUnit报告失败。
     */
    @Test
    fun breathHoldSummaryCalculatesBestAverageAndLatestChange() {
        val summary = calculateBreathHoldSummary(
            listOf(
                BreathHoldRecord("older", 100L, 40_000L),
                BreathHoldRecord("latest", 300L, 55_000L),
                BreathHoldRecord("middle", 200L, 65_000L)
            )
        )

        assertEquals(3, summary.attemptCount)
        assertEquals(65_000L, summary.bestDurationMillis)
        assertEquals(53_333L, summary.averageDurationMillis)
        assertEquals(55_000L, summary.latestDurationMillis)
        assertEquals(-10_000L, summary.latestChangeMillis)
    }

    /**
     * 创建指定本地日期和分钟的应用使用事件，减少时间边界测试的重复代码。
     *
     * @param packageName 应用包名。
     * @param componentName Activity名称。
     * @param date 本地日期。
     * @param hour 小时。
     * @param minute 分钟。
     * @param resumed 是否进入前台。
     * @param zoneId 测试时区。
     * @return 对应Unix毫秒时间的稳定事件快照。
     */
    private fun usageEvent(
        packageName: String,
        componentName: String,
        date: LocalDate,
        hour: Int,
        minute: Int,
        resumed: Boolean,
        zoneId: ZoneId
    ): AppUsageEventSnapshot {
        return AppUsageEventSnapshot(
            packageName = packageName,
            componentName = componentName,
            timestampMillis = date.atTime(hour, minute)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
            resumed = resumed
        )
    }
}
