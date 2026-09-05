package com.example.harleyapp.model

import android.graphics.Bitmap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 应用使用趋势固定展示最近七个自然日。 */
const val APP_USAGE_TREND_DAY_COUNT = 7

/** 夜间统计起点，默认从当地时间23:00开始。 */
const val APP_USAGE_NIGHT_START_HOUR = 23

/** 夜间统计终点，默认到当地时间06:00结束。 */
const val APP_USAGE_NIGHT_END_HOUR = 6

/** 同一应用Activity切换产生的短暂空档小于五秒时合并为同一次打开。 */
const val APP_USAGE_LAUNCH_MERGE_GAP_MILLIS = 5_000L

/**
 * 表示一次从Android UsageEvents转换出的前台状态变化。
 *
 * @param packageName 发生状态变化的应用包名。
 * @param componentName Activity类名；缺失时可为空，用于区分同一应用并行Activity。
 * @param timestampMillis 事件发生的Unix毫秒时间。
 * @param resumed true表示Activity进入前台，false表示离开前台。
 */
data class AppUsageEventSnapshot(
    val packageName: String,
    val componentName: String,
    val timestampMillis: Long,
    val resumed: Boolean
)

/**
 * 单个应用今天的使用统计。
 *
 * @param packageName 应用包名。
 * @param label 用户可见应用名称。
 * @param icon 应用图标位图；系统禁止读取或加载失败时为空。
 * @param foregroundMillis 今天推算出的前台使用毫秒数。
 * @param launchCount 今天从完全不活跃变为前台的次数；五秒内Activity切换会合并。
 * @param nightMillis 今天落在夜间时段内的前台使用毫秒数。
 * @param lastUsedAtMillis 最近一次进入前台的时间。
 */
data class AppUsageEntry(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    val foregroundMillis: Long,
    val launchCount: Int,
    val nightMillis: Long,
    val lastUsedAtMillis: Long
)

/**
 * 最近七天中一个自然日的总使用时间。
 *
 * @param date 手机当前时区下的自然日期。
 * @param foregroundMillis 当天全部可识别应用的前台毫秒数。
 */
data class AppUsageDayTrend(
    val date: LocalDate,
    val foregroundMillis: Long
)

/**
 * 应用使用统计页面一次查询的完整快照。
 *
 * @param generatedAtMillis 快照生成时间。
 * @param todayForegroundMillis 今日全部应用前台时长。
 * @param todayLaunchCount 今日全部应用打开次数。
 * @param todayNightMillis 今日夜间使用时长。
 * @param appEntries 按今日使用时长降序排列的应用列表。
 * @param sevenDayTrend 最近七个自然日趋势，包含无数据日期的零值。
 */
data class AppUsageDashboard(
    val generatedAtMillis: Long,
    val todayForegroundMillis: Long,
    val todayLaunchCount: Int,
    val todayNightMillis: Long,
    val appEntries: List<AppUsageEntry>,
    val sevenDayTrend: List<AppUsageDayTrend>
)

/** 应用使用统计查询失败时可展示给用户的稳定原因。 */
enum class AppUsageQueryError {
    USAGE_ACCESS_REQUIRED,
    SERVICE_UNAVAILABLE,
    QUERY_FAILED
}

/**
 * 应用使用统计查询结果。
 *
 * @param dashboard 成功时的统计快照。
 * @param error 失败原因；成功时为空。
 */
data class AppUsageQueryResult(
    val dashboard: AppUsageDashboard? = null,
    val error: AppUsageQueryError? = null
)

/**
 * 纯计算阶段产生的单包统计，控制器随后补充应用名称和图标。
 *
 * @param packageName 应用包名。
 * @param todayForegroundMillis 今日前台时长。
 * @param todayLaunchCount 今日打开次数。
 * @param todayNightMillis 今日夜间使用时长。
 * @param lastUsedAtMillis 最近进入前台时间。
 */
data class AggregatedPackageUsage(
    val packageName: String,
    val todayForegroundMillis: Long,
    val todayLaunchCount: Int,
    val todayNightMillis: Long,
    val lastUsedAtMillis: Long
)

/**
 * Android事件聚合后的纯数据结果。
 *
 * @param packages 单包今日统计。
 * @param sevenDayTrend 最近七天总时长。
 */
data class AggregatedAppUsage(
    val packages: List<AggregatedPackageUsage>,
    val sevenDayTrend: List<AppUsageDayTrend>
)

/**
 * 把前台恢复/暂停事件聚合为今日应用排行、打开次数、夜间时长和最近七天趋势。
 *
 * 使用方法：
 * 系统控制器先查询最近七天UsageEvents并转换为[AppUsageEventSnapshot]，再把系统当前时区、
 * 查询起点和当前时间传入。本函数只做时间区间计算，单元测试无需Android设备即可覆盖跨天、
 * 多Activity重叠和夜间边界。
 *
 * @param events 按任意顺序提供的应用前台状态事件。
 * @param rangeStartMillis 七天查询范围的起始毫秒。
 * @param nowMillis 查询截止毫秒。
 * @param zoneId 手机当前时区。
 * @param allowedPackages 允许进入统计的可启动应用包；空集合表示不额外过滤。
 *
 * @return 稳定排序且包含七个自然日零值的聚合结果。
 */
fun aggregateAppUsageEvents(
    events: List<AppUsageEventSnapshot>,
    rangeStartMillis: Long,
    nowMillis: Long,
    zoneId: ZoneId,
    allowedPackages: Set<String> = emptySet()
): AggregatedAppUsage {
    if (nowMillis <= rangeStartMillis) {
        return AggregatedAppUsage(
            packages = emptyList(),
            sevenDayTrend = emptyList()
        )
    }

    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val todayStartMillis = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val firstTrendDate = today.minusDays((APP_USAGE_TREND_DAY_COUNT - 1).toLong())
    val activeComponents = mutableMapOf<String, MutableSet<String>>()
    val activePackageStarts = mutableMapOf<String, Long>()
    val lastPackageInactiveAt = mutableMapOf<String, Long>()
    val intervals = mutableListOf<ForegroundInterval>()
    val launchCounts = mutableMapOf<String, Int>()
    val lastUsedTimes = mutableMapOf<String, Long>()

    events.asSequence()
        .filter { event ->
            event.packageName.isNotBlank() &&
                event.timestampMillis in rangeStartMillis..nowMillis &&
                (allowedPackages.isEmpty() || event.packageName in allowedPackages)
        }
        .sortedBy(AppUsageEventSnapshot::timestampMillis)
        .forEach { event ->
            val componentKey = event.componentName.ifBlank { DEFAULT_COMPONENT_KEY }
            val packageComponents = activeComponents.getOrPut(event.packageName) {
                linkedSetOf()
            }
            if (event.resumed) {
                val wasInactive = packageComponents.isEmpty()
                if (packageComponents.add(componentKey) && wasInactive) {
                    activePackageStarts[event.packageName] = event.timestampMillis
                    val previousInactiveAt = lastPackageInactiveAt[event.packageName]
                    val isNewLaunch = previousInactiveAt == null ||
                        event.timestampMillis - previousInactiveAt >=
                        APP_USAGE_LAUNCH_MERGE_GAP_MILLIS
                    if (event.timestampMillis >= todayStartMillis && isNewLaunch) {
                        launchCounts[event.packageName] =
                            launchCounts.getOrDefault(event.packageName, 0) + 1
                    }
                }
                lastUsedTimes[event.packageName] = maxOf(
                    lastUsedTimes.getOrDefault(event.packageName, 0L),
                    event.timestampMillis
                )
            } else if (packageComponents.remove(componentKey) && packageComponents.isEmpty()) {
                lastPackageInactiveAt[event.packageName] = event.timestampMillis
                val startMillis = activePackageStarts.remove(event.packageName)
                if (startMillis != null && event.timestampMillis > startMillis) {
                    intervals += ForegroundInterval(
                        packageName = event.packageName,
                        startMillis = startMillis,
                        endMillis = event.timestampMillis
                    )
                }
            }
        }

    activePackageStarts.forEach { (packageName, startMillis) ->
        if (nowMillis > startMillis) {
            intervals += ForegroundInterval(packageName, startMillis, nowMillis)
        }
    }

    val todayDurations = mutableMapOf<String, Long>()
    val todayNightDurations = mutableMapOf<String, Long>()
    val dailyDurations = mutableMapOf<LocalDate, Long>()
    intervals.forEach { interval ->
        splitIntervalByLocalDay(interval, zoneId).forEach { segment ->
            if (segment.date >= firstTrendDate && segment.date <= today) {
                dailyDurations[segment.date] = dailyDurations.getOrDefault(segment.date, 0L) +
                    segment.durationMillis
            }
            if (segment.date == today) {
                todayDurations[interval.packageName] =
                    todayDurations.getOrDefault(interval.packageName, 0L) +
                    segment.durationMillis
                todayNightDurations[interval.packageName] =
                    todayNightDurations.getOrDefault(interval.packageName, 0L) +
                    calculateNightOverlapMillis(segment, zoneId)
            }
        }
    }

    val packageNames = (
        todayDurations.keys + launchCounts.keys + lastUsedTimes.keys
        ).distinct()
    val packageResults = packageNames.map { packageName ->
        AggregatedPackageUsage(
            packageName = packageName,
            todayForegroundMillis = todayDurations.getOrDefault(packageName, 0L),
            todayLaunchCount = launchCounts.getOrDefault(packageName, 0),
            todayNightMillis = todayNightDurations.getOrDefault(packageName, 0L),
            lastUsedAtMillis = lastUsedTimes.getOrDefault(packageName, 0L)
        )
    }.filter { usage ->
        usage.todayForegroundMillis > 0L || usage.todayLaunchCount > 0
    }.sortedWith(
        compareByDescending<AggregatedPackageUsage> { usage -> usage.todayForegroundMillis }
            .thenByDescending { usage -> usage.todayLaunchCount }
            .thenBy { usage -> usage.packageName }
    )
    val trends = (0 until APP_USAGE_TREND_DAY_COUNT).map { dayIndex ->
        val date = firstTrendDate.plusDays(dayIndex.toLong())
        AppUsageDayTrend(
            date = date,
            foregroundMillis = dailyDurations.getOrDefault(date, 0L)
        )
    }

    return AggregatedAppUsage(
        packages = packageResults,
        sevenDayTrend = trends
    )
}

/** 应用连续位于前台的一段闭开区间。 */
private data class ForegroundInterval(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long
)

/** 跨天区间拆分后位于一个自然日内的部分。 */
private data class LocalDayInterval(
    val date: LocalDate,
    val startMillis: Long,
    val endMillis: Long
) {
    val durationMillis: Long
        get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/**
 * 按当前时区的自然日边界拆分前台区间。
 *
 * @param interval 单个应用连续前台区间。
 * @param zoneId 手机当前时区。
 * @return 每项都不跨越当地午夜的区间列表。
 */
private fun splitIntervalByLocalDay(
    interval: ForegroundInterval,
    zoneId: ZoneId
): List<LocalDayInterval> {
    val segments = mutableListOf<LocalDayInterval>()
    var cursor = interval.startMillis
    while (cursor < interval.endMillis) {
        val date = Instant.ofEpochMilli(cursor).atZone(zoneId).toLocalDate()
        val nextDayMillis = date.plusDays(1L).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val segmentEnd = minOf(interval.endMillis, nextDayMillis)
        segments += LocalDayInterval(date, cursor, segmentEnd)
        cursor = segmentEnd
    }
    return segments
}

/**
 * 计算单日区间与00:00-06:00、23:00-24:00两个夜间窗口的重叠毫秒数。
 *
 * @param interval 已保证不跨当地午夜的区间。
 * @param zoneId 手机当前时区。
 * @return 两段夜间窗口重叠时间之和。
 */
private fun calculateNightOverlapMillis(
    interval: LocalDayInterval,
    zoneId: ZoneId
): Long {
    val dayStart = interval.date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val morningEnd = interval.date.atTime(APP_USAGE_NIGHT_END_HOUR, 0)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
    val eveningStart = interval.date.atTime(APP_USAGE_NIGHT_START_HOUR, 0)
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
    val nextDayStart = interval.date.plusDays(1L).atStartOfDay(zoneId).toInstant().toEpochMilli()

    return overlapMillis(interval.startMillis, interval.endMillis, dayStart, morningEnd) +
        overlapMillis(interval.startMillis, interval.endMillis, eveningStart, nextDayStart)
}

/** 计算两个闭开时间区间的非负重叠毫秒数。 */
private fun overlapMillis(
    firstStart: Long,
    firstEnd: Long,
    secondStart: Long,
    secondEnd: Long
): Long {
    return (minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)).coerceAtLeast(0L)
}

/** UsageEvents没有Activity类名时使用的稳定占位键。 */
private const val DEFAULT_COMPONENT_KEY = "__default_activity__"
