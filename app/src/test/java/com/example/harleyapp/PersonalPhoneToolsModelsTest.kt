package com.example.harleyapp

import com.example.harleyapp.model.AggregatedAppUsage
import com.example.harleyapp.model.AggregatedPackageUsage
import com.example.harleyapp.model.AppUsageEventSnapshot
import com.example.harleyapp.model.AppUsageEventType
import com.example.harleyapp.model.BreathHoldRecord
import com.example.harleyapp.model.aggregateAppUsageEvents
import com.example.harleyapp.model.calculateBreathHoldSummary
import com.example.harleyapp.model.removeBreathHoldRecords
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 验证应用使用和憋气历史模块的纯计算边界。 */
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
        val events = listOf(
            usageEvent(
                "demo.app",
                "First",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "Second",
                today,
                10,
                4,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "First",
                today,
                10,
                5,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "Second",
                today,
                10,
                10,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 12,
            nowMinute = 0,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val packageUsage = requirePackageUsage(result, today, "demo.app")

        assertEquals(10L * 60_000L, packageUsage.foregroundMillis)
        assertEquals(1, packageUsage.launchCount)
        assertEquals(10L * 60_000L, result.days.last().foregroundMillis)
    }

    /**
     * 验证前一个应用漏发暂停事件时，切换到另一个应用仍会立即结束旧归属，两个应用不会重叠累计。
     *
     * @return 无返回值；旧应用延长到查询时刻或总时长发生重叠时由JUnit报告失败。
     */
    @Test
    fun appUsageSwitchingPackageClosesMissingPauseInterval() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent(
                "first.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "second.app",
                "Main",
                today,
                10,
                10,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 10,
            nowMinute = 20,
            zoneId = zoneId,
            allowedPackages = setOf("first.app", "second.app")
        )
        val firstUsage = requirePackageUsage(result, today, "first.app")
        val secondUsage = requirePackageUsage(result, today, "second.app")

        assertEquals(10L * 60_000L, firstUsage.foregroundMillis)
        assertEquals(10L * 60_000L, secondUsage.foregroundMillis)
        assertEquals(20L * 60_000L, result.days.last().foregroundMillis)
    }

    /**
     * 验证息屏和锁屏都会立即截断前台区间，重新亮屏或解锁后才从对应时刻恢复统计。
     *
     * @return 无返回值；不可交互期间被计入应用前台时间时由JUnit报告失败。
     */
    @Test
    fun appUsageScreenAndKeyguardEventsCutAndRestoreForeground() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent(
                "demo.app",
                "Main",
                today,
                9,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent("", "", today, 9, 10, AppUsageEventType.SCREEN_NON_INTERACTIVE, zoneId),
            usageEvent("", "", today, 9, 20, AppUsageEventType.SCREEN_INTERACTIVE, zoneId),
            usageEvent(
                "demo.app",
                "Main",
                today,
                9,
                30,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent("", "", today, 10, 10, AppUsageEventType.KEYGUARD_SHOWN, zoneId),
            usageEvent("", "", today, 10, 20, AppUsageEventType.KEYGUARD_HIDDEN, zoneId),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                30,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 12,
            nowMinute = 0,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val packageUsage = requirePackageUsage(result, today, "demo.app")

        assertEquals(40L * 60_000L, packageUsage.foregroundMillis)
        assertEquals(0L, packageUsage.nightMillis)
    }

    /**
     * 验证关机事件会清除旧Activity状态，开机后必须收到新的恢复事件才能重新开始统计。
     *
     * @return 无返回值；关机区间被继承或开机后的新会话丢失时由JUnit报告失败。
     */
    @Test
    fun appUsageShutdownAndStartupNeverBridgeForegroundInterval() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent(
                "demo.app",
                "Main",
                today,
                8,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent("", "", today, 8, 10, AppUsageEventType.DEVICE_SHUTDOWN, zoneId),
            usageEvent("", "", today, 8, 20, AppUsageEventType.DEVICE_STARTUP, zoneId),
            usageEvent("", "", today, 8, 21, AppUsageEventType.SCREEN_INTERACTIVE, zoneId),
            usageEvent("", "", today, 8, 25, AppUsageEventType.KEYGUARD_HIDDEN, zoneId),
            usageEvent(
                "demo.app",
                "Main",
                today,
                8,
                30,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                8,
                40,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 9,
            nowMinute = 0,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val packageUsage = requirePackageUsage(result, today, "demo.app")

        assertEquals(20L * 60_000L, packageUsage.foregroundMillis)
        assertEquals(2, packageUsage.launchCount)
    }

    /**
     * 验证跨午夜区间会进入两天的独立明细，分别计算夜间时长，并且次日不会凭拆分边界新增打开次数。
     *
     * @return 无返回值；逐日时长、夜间时长或跨日打开次数错误时由JUnit报告失败。
     */
    @Test
    fun appUsageCrossMidnightSplitsDailyDetailNightAndLaunchCount() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val yesterday = today.minusDays(1)
        val events = listOf(
            usageEvent(
                "night.app",
                "Main",
                yesterday,
                22,
                30,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "night.app",
                "Main",
                today,
                0,
                30,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 1,
            nowMinute = 0,
            zoneId = zoneId,
            allowedPackages = setOf("night.app")
        )
        val yesterdayUsage = requirePackageUsage(result, yesterday, "night.app")
        val todayUsage = requirePackageUsage(result, today, "night.app")

        assertEquals(90L * 60_000L, yesterdayUsage.foregroundMillis)
        assertEquals(60L * 60_000L, yesterdayUsage.nightMillis)
        assertEquals(1, yesterdayUsage.launchCount)
        assertEquals(30L * 60_000L, todayUsage.foregroundMillis)
        assertEquals(30L * 60_000L, todayUsage.nightMillis)
        assertEquals(0, todayUsage.launchCount)
    }

    /**
     * 验证桌面或系统界面即使不属于允许输出的应用，也会参与全局前台归属并截断旧应用。
     *
     * @return 无返回值；系统包被输出或允许应用继续虚假累计时由JUnit报告失败。
     */
    @Test
    fun appUsageHiddenSystemPackageStillCutsVisibleApplication() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "system.launcher",
                "Home",
                today,
                10,
                15,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 10,
            nowMinute = 30,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val todayDetail = result.days.single { day -> day.date == today }

        assertEquals(listOf("demo.app"), todayDetail.packages.map { usage -> usage.packageName })
        assertEquals(15L * 60_000L, todayDetail.foregroundMillis)
    }

    /**
     * 验证结果始终提供连续七个自然日，完全没有事件的日期也保留为空明细和零时长。
     *
     * @return 无返回值；日期缺失、顺序错误或零值日被省略时由JUnit报告失败。
     */
    @Test
    fun appUsageAlwaysReturnsSevenContinuousDatesIncludingZeroDays() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)

        val result = aggregateUsageForDay(
            events = emptyList(),
            today = today,
            nowHour = 12,
            nowMinute = 0,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )

        assertEquals(
            (6L downTo 0L).map { daysAgo -> today.minusDays(daysAgo) },
            result.days.map { day -> day.date }
        )
        assertEquals(List(7) { 0L }, result.days.map { day -> day.foregroundMillis })
        assertEquals(List(7) { 0 }, result.days.map { day -> day.packages.size })
    }

    /**
     * 验证相同时间戳事件优先按sequenceIndex还原系统原始顺序，而不是依赖调用方列表顺序。
     *
     * @return 无返回值；同毫秒的暂停和恢复顺序不稳定并产生虚假时长时由JUnit报告失败。
     */
    @Test
    fun appUsageSameTimestampUsesSequenceIndexForStableOrdering() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            // 故意把较晚的暂停事件放在列表前面，确认聚合器不会退化为输入列表顺序。
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId,
                sequenceIndex = 2L
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId,
                sequenceIndex = 1L
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 10,
            nowMinute = 10,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val todayDetail = result.days.single { day -> day.date == today }

        assertEquals(0L, todayDetail.foregroundMillis)
        assertEquals(emptyList<AggregatedPackageUsage>(), todayDetail.packages)
    }

    /**
     * 验证同类旧Activity在新实例恢复后补发STOPPED时，不会误关正在前台的新实例。
     *
     * @return 无返回值；旧实例停止事件把新实例截短时由JUnit报告失败。
     */
    @Test
    fun appUsageDelayedStopFromOldSameClassKeepsNewInstanceActive() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                5,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId,
                sequenceIndex = 1L
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                5,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId,
                sequenceIndex = 2L
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                6,
                AppUsageEventType.ACTIVITY_STOPPED,
                zoneId
            ),
            usageEvent(
                "demo.app",
                "Main",
                today,
                10,
                15,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 10,
            nowMinute = 20,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val packageUsage = requirePackageUsage(result, today, "demo.app")

        assertEquals(15L * 60_000L, packageUsage.foregroundMillis)
        assertEquals(1, packageUsage.launchCount)
    }

    /**
     * 验证设备启动后即使先收到Activity恢复，也必须等到亮屏且明确解锁才开始累计。
     *
     * @return 无返回值；开机锁屏阶段被错误计入前台交互时间时由JUnit报告失败。
     */
    @Test
    fun appUsageStartupWaitsForExplicitUnlock() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent("", "", today, 8, 0, AppUsageEventType.DEVICE_STARTUP, zoneId),
            usageEvent(
                "demo.app",
                "Main",
                today,
                8,
                1,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent("", "", today, 8, 2, AppUsageEventType.SCREEN_INTERACTIVE, zoneId),
            usageEvent("", "", today, 8, 10, AppUsageEventType.KEYGUARD_HIDDEN, zoneId),
            usageEvent(
                "demo.app",
                "Main",
                today,
                8,
                20,
                AppUsageEventType.ACTIVITY_PAUSED,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 8,
            nowMinute = 30,
            zoneId = zoneId,
            allowedPackages = setOf("demo.app")
        )
        val packageUsage = requirePackageUsage(result, today, "demo.app")

        assertEquals(10L * 60_000L, packageUsage.foregroundMillis)
    }

    /**
     * 验证分屏候选同时保持恢复状态时，用户交互事件会把唯一前台归属切到实际操作的应用。
     *
     * @return 无返回值；交互事件未切换归属或两应用发生重叠累计时由JUnit报告失败。
     */
    @Test
    fun appUsageUserInteractionSwitchesSplitScreenOwner() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 9, 5)
        val events = listOf(
            usageEvent(
                "first.app",
                "Main",
                today,
                10,
                0,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "second.app",
                "Main",
                today,
                10,
                5,
                AppUsageEventType.ACTIVITY_RESUMED,
                zoneId
            ),
            usageEvent(
                "first.app",
                "",
                today,
                10,
                10,
                AppUsageEventType.USER_INTERACTION,
                zoneId
            )
        )

        val result = aggregateUsageForDay(
            events = events,
            today = today,
            nowHour = 10,
            nowMinute = 15,
            zoneId = zoneId,
            allowedPackages = setOf("first.app", "second.app")
        )

        assertEquals(
            10L * 60_000L,
            requirePackageUsage(result, today, "first.app").foregroundMillis
        )
        assertEquals(
            5L * 60_000L,
            requirePackageUsage(result, today, "second.app").foregroundMillis
        )
        assertEquals(15L * 60_000L, result.days.last().foregroundMillis)
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
     * 验证删除唯一最佳记录后，次数、最佳、平均和最近变化都按剩余记录重新计算。
     *
     * @return 无返回值；指定记录未删除、顺序变化或统计仍引用已删记录时测试失败。
     */
    @Test
    fun breathHoldSelectedDeletionRecalculatesAllSummaryMetrics() {
        val records = listOf(
            BreathHoldRecord("latest", 300L, 55_000L),
            BreathHoldRecord("best", 200L, 65_000L),
            BreathHoldRecord("older", 100L, 40_000L)
        )

        val remaining = removeBreathHoldRecords(records, setOf("best"))
        val summary = calculateBreathHoldSummary(remaining)

        assertEquals(listOf("latest", "older"), remaining.map(BreathHoldRecord::id))
        assertEquals(2, summary.attemptCount)
        assertEquals(55_000L, summary.bestDurationMillis)
        assertEquals(47_500L, summary.averageDurationMillis)
        assertEquals(55_000L, summary.latestDurationMillis)
        assertEquals(15_000L, summary.latestChangeMillis)
    }

    /**
     * 验证删除最近记录后，最近成绩和变化改用剩余历史中时间最新的两次。
     *
     * @return 无返回值；最近记录识别仍引用已删除项目时测试失败。
     */
    @Test
    fun breathHoldDeletingLatestRecordRebuildsLatestComparison() {
        val records = listOf(
            BreathHoldRecord("latest", 300L, 55_000L),
            BreathHoldRecord("middle", 200L, 65_000L),
            BreathHoldRecord("older", 100L, 40_000L)
        )

        val summary = calculateBreathHoldSummary(
            removeBreathHoldRecords(records, setOf("latest"))
        )

        assertEquals(2, summary.attemptCount)
        assertEquals(65_000L, summary.bestDurationMillis)
        assertEquals(52_500L, summary.averageDurationMillis)
        assertEquals(65_000L, summary.latestDurationMillis)
        assertEquals(25_000L, summary.latestChangeMillis)
    }

    /**
     * 验证空选择和未知ID不会误删；全选删除后所有统计安全归零。
     *
     * @return 无返回值；无效选择改变历史或空历史统计残留旧值时测试失败。
     */
    @Test
    fun breathHoldDeletionHandlesNoOpAndSelectAllBoundaries() {
        val records = listOf(
            BreathHoldRecord("first", 200L, 60_000L),
            BreathHoldRecord("second", 100L, 45_000L)
        )

        assertEquals(records, removeBreathHoldRecords(records, emptySet()))
        assertEquals(records, removeBreathHoldRecords(records, setOf("unknown", "")))

        val remaining = removeBreathHoldRecords(
            records = records,
            selectedRecordIds = records.mapTo(linkedSetOf(), BreathHoldRecord::id)
        )
        val summary = calculateBreathHoldSummary(remaining)

        assertEquals(emptyList<BreathHoldRecord>(), remaining)
        assertEquals(0, summary.attemptCount)
        assertEquals(0L, summary.bestDurationMillis)
        assertEquals(0L, summary.averageDurationMillis)
        assertEquals(0L, summary.latestDurationMillis)
        assertEquals(0L, summary.latestChangeMillis)
    }

    /**
     * 按指定“今天”和查询时刻执行七日聚合，供各测试以相同范围调用核心纯计算函数。
     *
     * 使用方法：传入待验证事件、页面所处日期、查询时分和允许展示的包名集合；函数会自动计算七日范围起点。
     *
     * @param events 待聚合的UsageEvents稳定快照。
     * @param today 查询时刻所在的本地自然日。
     * @param nowHour 查询时刻的本地小时。
     * @param nowMinute 查询时刻的本地分钟。
     * @param zoneId 用于自然日和Unix毫秒互转的测试时区。
     * @param allowedPackages 允许出现在结果中的应用包名集合。
     * @return 包含连续七个自然日明细的聚合结果。
     */
    private fun aggregateUsageForDay(
        events: List<AppUsageEventSnapshot>,
        today: LocalDate,
        nowHour: Int,
        nowMinute: Int,
        zoneId: ZoneId,
        allowedPackages: Set<String>
    ): AggregatedAppUsage {
        val rangeStart = today.minusDays(6L)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val now = today.atTime(nowHour, nowMinute)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        return aggregateAppUsageEvents(
            events = events,
            rangeStartMillis = rangeStart,
            nowMillis = now,
            zoneId = zoneId,
            allowedPackages = allowedPackages
        )
    }

    /**
     * 从聚合结果中读取指定日期和包名的唯一明细，避免测试重复编写查找表达式。
     *
     * 使用方法：仅在测试预期该应用当天确实存在有效时长或打开次数时调用；缺失或重复会直接让测试失败。
     *
     * @param result 七日应用使用聚合结果。
     * @param date 需要读取的本地自然日。
     * @param packageName 需要读取的应用包名。
     * @return 与日期、包名同时匹配的唯一应用统计。
     */
    private fun requirePackageUsage(
        result: AggregatedAppUsage,
        date: LocalDate,
        packageName: String
    ): AggregatedPackageUsage {
        return result.days
            .single { day -> day.date == date }
            .packages
            .single { usage -> usage.packageName == packageName }
    }

    /**
     * 创建指定本地日期和分钟的应用使用事件，减少时间边界测试的重复代码。
     *
     * 使用方法：Activity事件填写包名和组件名；屏幕、锁屏及设备事件可把前两个参数传为空字符串。
     *
     * @param packageName 应用包名；设备级事件允许为空。
     * @param componentName Activity名称；设备级事件允许为空。
     * @param date 本地日期。
     * @param hour 小时。
     * @param minute 分钟。
     * @param type 已归一化的应用使用事件类型。
     * @param zoneId 测试时区。
     * @param sequenceIndex 同一时间戳下用于保持系统原始先后关系的序号。
     * @return 对应Unix毫秒时间的稳定事件快照。
     */
    private fun usageEvent(
        packageName: String,
        componentName: String,
        date: LocalDate,
        hour: Int,
        minute: Int,
        type: AppUsageEventType,
        zoneId: ZoneId,
        sequenceIndex: Long = 0L
    ): AppUsageEventSnapshot {
        return AppUsageEventSnapshot(
            packageName = packageName,
            componentName = componentName,
            timestampMillis = date.atTime(hour, minute)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
            type = type,
            sequenceIndex = sequenceIndex
        )
    }
}
