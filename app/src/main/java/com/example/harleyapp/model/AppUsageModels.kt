package com.example.harleyapp.model

import android.graphics.Bitmap
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 应用使用页面固定展示最近七个自然日。 */
const val APP_USAGE_TREND_DAY_COUNT = 7

/** 夜间统计起点，默认从当地时间23:00开始。 */
const val APP_USAGE_NIGHT_START_HOUR = 23

/** 夜间统计终点，默认到当地时间06:00结束。 */
const val APP_USAGE_NIGHT_END_HOUR = 6

/** 同一应用两段前台区间的空档小于五秒时，打开次数仍按同一次计算。 */
const val APP_USAGE_LAUNCH_MERGE_GAP_MILLIS = 5_000L

/**
 * Android使用情况事件在纯计算层中的稳定类型。
 *
 * Activity、屏幕、锁屏和设备启停事件统一进入同一条时间线，避免某个Activity漏掉暂停事件后
 * 一直累计到查询时刻。枚举不直接暴露Android版本相关常量，便于在本地单元测试中构造边界。
 */
enum class AppUsageEventType {
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
    ACTIVITY_STOPPED,
    USER_INTERACTION,
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN,
    KEYGUARD_HIDDEN,
    DEVICE_SHUTDOWN,
    DEVICE_STARTUP
}

/**
 * 表示一次从Android UsageEvents转换出的状态变化。
 *
 * @param packageName 事件所属应用包名；屏幕、锁屏和设备事件允许为空。
 * @param componentName Activity类名；系统未提供时可为空。
 * @param timestampMillis 事件发生的Unix毫秒时间。
 * @param type 已归一化的事件类型。
 * @param sequenceIndex 系统游标中的原始顺序；相同时间戳时用它保持事件先后关系。
 */
data class AppUsageEventSnapshot(
    val packageName: String,
    val componentName: String,
    val timestampMillis: Long,
    val type: AppUsageEventType,
    val sequenceIndex: Long = 0L
)

/**
 * 单个应用在某一个自然日内的使用统计。
 *
 * @param packageName 应用包名。
 * @param label 用户可见应用名称。
 * @param icon 应用图标位图；系统禁止读取或加载失败时为空。
 * @param foregroundMillis 当日前台交互毫秒数。
 * @param launchCount 当日新前台会话次数；五秒内短暂切换会合并。
 * @param nightMillis 当日落在00:00—06:00或23:00—24:00内的前台交互毫秒数。
 * @param lastUsedAtMillis 当日最后一次保持前台交互的时间；没有有效区间时为0。
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
 * @param foregroundMillis 当天全部可展示应用的前台交互毫秒数。
 */
data class AppUsageDayTrend(
    val date: LocalDate,
    val foregroundMillis: Long
)

/**
 * 页面可直接展示的单日完整明细。
 *
 * @param date 手机当前时区下的自然日期。
 * @param appEntries 当日应用统计，按时长、次数和包名稳定排序。
 */
data class AppUsageDayDetail(
    val date: LocalDate,
    val appEntries: List<AppUsageEntry>
) {
    /** @return 当天所有应用前台交互时长之和。 */
    val foregroundMillis: Long
        get() = appEntries.sumOf(AppUsageEntry::foregroundMillis)

    /** @return 当天所有应用打开次数之和。 */
    val launchCount: Int
        get() = appEntries.sumOf(AppUsageEntry::launchCount)

    /** @return 当天所有应用夜间使用时长之和。 */
    val nightMillis: Long
        get() = appEntries.sumOf(AppUsageEntry::nightMillis)
}

/**
 * 应用使用统计页面一次查询的完整快照。
 *
 * @param generatedAtMillis 快照生成时间。
 * @param dayDetails 最近七个自然日的完整明细，按日期升序排列并包含零数据日期。
 */
data class AppUsageDashboard(
    val generatedAtMillis: Long,
    val dayDetails: List<AppUsageDayDetail>
) {
    /** @return 从逐日明细派生的七天趋势，避免趋势和应用列表出现两套不一致的数据。 */
    val sevenDayTrend: List<AppUsageDayTrend>
        get() = dayDetails.map { detail ->
            AppUsageDayTrend(
                date = detail.date,
                foregroundMillis = detail.foregroundMillis
            )
        }
}

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
 * 纯计算阶段产生的单包单日统计，控制器随后补充应用名称和图标。
 *
 * @param packageName 应用包名。
 * @param foregroundMillis 当日前台交互时长。
 * @param launchCount 当日打开次数。
 * @param nightMillis 当日夜间使用时长。
 * @param lastUsedAtMillis 当日最后前台交互时间。
 */
data class AggregatedPackageUsage(
    val packageName: String,
    val foregroundMillis: Long,
    val launchCount: Int,
    val nightMillis: Long,
    val lastUsedAtMillis: Long
)

/**
 * 纯计算阶段产生的一个自然日结果。
 *
 * @param date 手机当前时区下的自然日期。
 * @param packages 当日各应用明细，按时长、次数和包名稳定排序。
 */
data class AggregatedAppUsageDay(
    val date: LocalDate,
    val packages: List<AggregatedPackageUsage>
) {
    /** @return 当天全部应用前台交互时长。 */
    val foregroundMillis: Long
        get() = packages.sumOf(AggregatedPackageUsage::foregroundMillis)
}

/**
 * Android事件聚合后的纯数据结果。
 *
 * @param days 最近七个自然日的结果，按日期升序排列并包含零数据日期。
 */
data class AggregatedAppUsage(
    val days: List<AggregatedAppUsageDay>
)

/**
 * 把Android使用情况事件聚合为最近七个自然日的前台交互明细。
 *
 * 使用方法：
 * 控制器应额外读取展示范围前一个自然日的事件作为预滚数据，然后把真正的七天展示起点传给
 * [rangeStartMillis]。函数会先让全部应用（包括桌面和系统界面）参与唯一前台归属判断，最后才按
 * [allowedPackages]过滤输出，因此某个可展示应用不会在用户已经切到系统界面后继续虚假累计。
 *
 * 统计定义：
 * 每一毫秒最多归属一个处于前台、屏幕可交互且未锁屏的应用。Activity暂停、停止、切换到其他
 * 应用、息屏、锁屏和关机都会结束当前区间；重新亮屏或解锁只会从仍处于恢复状态的候选Activity
 * 中恢复最近一个。跨午夜区间按当地自然日拆分，不用固定24小时，因此兼容夏令时日期。
 *
 * @param events 按任意顺序提供的Android状态事件；相同时间戳使用sequenceIndex保持原顺序。
 * @param rangeStartMillis 七天展示范围的起始Unix毫秒时间。
 * @param nowMillis 查询截止Unix毫秒时间。
 * @param zoneId 手机当前时区。
 * @param allowedPackages 允许出现在结果中的桌面应用包名；空集合表示不过滤输出。
 * @return 日期连续、稳定排序且包含七个自然日零值的聚合结果。
 */
fun aggregateAppUsageEvents(
    events: List<AppUsageEventSnapshot>,
    rangeStartMillis: Long,
    nowMillis: Long,
    zoneId: ZoneId,
    allowedPackages: Set<String> = emptySet()
): AggregatedAppUsage {
    if (nowMillis <= rangeStartMillis) {
        return AggregatedAppUsage(days = emptyList())
    }

    val firstDate = Instant.ofEpochMilli(rangeStartMillis).atZone(zoneId).toLocalDate()
    val displayDates = (0 until APP_USAGE_TREND_DAY_COUNT).map { dayIndex ->
        firstDate.plusDays(dayIndex.toLong())
    }
    val activeComponents = mutableMapOf<String, MutableSet<String>>()
    val componentActivationOrder = mutableMapOf<Pair<String, String>, ActivationOrder>()
    val pendingPauseAtMillis = mutableMapOf<Pair<String, String>, Long>()
    val packageActivationOrder = mutableMapOf<String, ActivationOrder>()
    val foregroundIntervals = mutableListOf<ForegroundInterval>()
    var deviceInteractive: Boolean? = null
    var keyguardVisible: Boolean? = null
    var ownerPackage: String? = null
    var ownerStartedAtMillis: Long? = null

    /** @return 当前已知状态是否允许用户与前台Activity交互。 */
    fun isDeviceUsable(): Boolean {
        return deviceInteractive != false && keyguardVisible != true
    }

    /**
     * 在指定时间结束唯一前台归属；重复关闭或零长度区间不会产生结果。
     *
     * @param endMillis 当前归属结束的Unix毫秒时间。
     */
    fun closeOwner(endMillis: Long) {
        val packageName = ownerPackage
        val startMillis = ownerStartedAtMillis
        if (packageName != null && startMillis != null && endMillis > startMillis) {
            foregroundIntervals += ForegroundInterval(
                packageName = packageName,
                startMillis = startMillis,
                endMillis = endMillis
            )
        }
        ownerPackage = null
        ownerStartedAtMillis = null
    }

    /**
     * 把前台归属切换到指定应用，先结束旧应用，确保所有应用区间永不重叠。
     *
     * @param packageName 新的前台应用包名。
     * @param timestampMillis 切换发生的Unix毫秒时间。
     */
    fun switchOwner(packageName: String, timestampMillis: Long) {
        if (!isDeviceUsable() || packageName.isBlank() || ownerPackage == packageName) {
            return
        }
        closeOwner(timestampMillis)
        ownerPackage = packageName
        ownerStartedAtMillis = timestampMillis
    }

    /**
     * 从仍处于恢复状态的Activity中选出最近激活的应用，供亮屏、解锁或当前应用退出时恢复。
     *
     * @param timestampMillis 恢复前台归属的Unix毫秒时间。
     */
    fun restoreMostRecentOwner(timestampMillis: Long) {
        if (!isDeviceUsable() || ownerPackage != null) {
            return
        }
        val fallbackPackage = packageActivationOrder.maxWithOrNull(
            compareBy<Map.Entry<String, ActivationOrder>> { entry -> entry.value.timestampMillis }
                .thenBy { entry -> entry.value.sequenceIndex }
        )?.key
        if (fallbackPackage != null) {
            switchOwner(fallbackPackage, timestampMillis)
        }
    }

    /**
     * 某个Activity离开后，用该包仍处于恢复状态的组件重新计算包级激活顺序。
     *
     * @param packageName 需要更新候选顺序的应用包名。
     */
    fun refreshPackageActivationOrder(packageName: String) {
        val newestRemainingOrder = activeComponents[packageName]
            .orEmpty()
            .mapNotNull { componentName ->
                componentActivationOrder[packageName to componentName]
            }
            .maxWithOrNull(
                compareBy<ActivationOrder> { order -> order.timestampMillis }
                    .thenBy { order -> order.sequenceIndex }
            )
        if (newestRemainingOrder == null) {
            packageActivationOrder.remove(packageName)
        } else {
            packageActivationOrder[packageName] = newestRemainingOrder
        }
    }

    events.withIndex()
        .asSequence()
        .filter { indexedEvent -> indexedEvent.value.timestampMillis <= nowMillis }
        .sortedWith(
            compareBy<IndexedValue<AppUsageEventSnapshot>> { indexedEvent ->
                indexedEvent.value.timestampMillis
            }.thenBy { indexedEvent -> indexedEvent.value.sequenceIndex }
                .thenBy { indexedEvent -> indexedEvent.index }
        )
        .forEach { indexedEvent ->
            val event = indexedEvent.value
            val eventOrder = ActivationOrder(
                timestampMillis = event.timestampMillis,
                sequenceIndex = event.sequenceIndex
            )
            when (event.type) {
                AppUsageEventType.ACTIVITY_RESUMED -> {
                    if (event.packageName.isBlank()) return@forEach

                    val componentKey = event.componentName.ifBlank { DEFAULT_COMPONENT_KEY }
                    activeComponents.getOrPut(event.packageName, ::linkedSetOf).add(componentKey)
                    componentActivationOrder[event.packageName to componentKey] = eventOrder
                    packageActivationOrder[event.packageName] = eventOrder
                    switchOwner(event.packageName, event.timestampMillis)
                }

                AppUsageEventType.USER_INTERACTION -> {
                    val hasResumedActivity = activeComponents[event.packageName]
                        ?.isNotEmpty() == true
                    if (event.packageName.isNotBlank() && hasResumedActivity) {
                        packageActivationOrder[event.packageName] = eventOrder
                        switchOwner(event.packageName, event.timestampMillis)
                    }
                }

                AppUsageEventType.ACTIVITY_PAUSED -> {
                    if (event.packageName.isBlank()) return@forEach

                    val packageComponents = activeComponents[event.packageName]
                        ?: return@forEach
                    if (event.componentName.isBlank()) {
                        // 旧版系统可能只提供包级后台事件，此时该事件代表整个应用离开前台。
                        packageComponents.forEach { componentKey ->
                            pendingPauseAtMillis[event.packageName to componentKey] =
                                event.timestampMillis
                            componentActivationOrder.remove(event.packageName to componentKey)
                        }
                        packageComponents.clear()
                    } else {
                        val componentKey = event.componentName
                        if (packageComponents.remove(componentKey)) {
                            pendingPauseAtMillis[event.packageName to componentKey] =
                                event.timestampMillis
                            componentActivationOrder.remove(event.packageName to componentKey)
                        }
                    }
                    if (packageComponents.isEmpty()) {
                        activeComponents.remove(event.packageName)
                        refreshPackageActivationOrder(event.packageName)
                        if (ownerPackage == event.packageName) {
                            closeOwner(event.timestampMillis)
                            restoreMostRecentOwner(event.timestampMillis)
                        }
                    } else {
                        refreshPackageActivationOrder(event.packageName)
                    }
                }

                AppUsageEventType.ACTIVITY_STOPPED -> {
                    if (event.packageName.isBlank()) return@forEach

                    val packageComponents = activeComponents[event.packageName]
                        ?: return@forEach
                    if (event.componentName.isBlank()) {
                        packageComponents.forEach { componentKey ->
                            componentActivationOrder.remove(event.packageName to componentKey)
                            pendingPauseAtMillis.remove(event.packageName to componentKey)
                        }
                        packageComponents.clear()
                    } else {
                        val componentKey = event.componentName
                        val componentIdentity = event.packageName to componentKey
                        val pendingPauseAt = pendingPauseAtMillis[componentIdentity]
                        val activeResume = componentActivationOrder[componentIdentity]
                        val belongsToOlderGeneration = pendingPauseAt != null &&
                            activeResume != null &&
                            activeResume.timestampMillis >= pendingPauseAt &&
                            event.timestampMillis - pendingPauseAt <=
                            APP_USAGE_STALE_STOP_GRACE_MILLIS
                        pendingPauseAtMillis.remove(componentIdentity)
                        if (belongsToOlderGeneration) {
                            // 同类旧Activity常在新实例恢复后补发STOPPED，不能误关正在前台的新实例。
                            return@forEach
                        }
                        if (packageComponents.remove(componentKey)) {
                            componentActivationOrder.remove(componentIdentity)
                        }
                    }
                    if (packageComponents.isEmpty()) {
                        activeComponents.remove(event.packageName)
                        refreshPackageActivationOrder(event.packageName)
                        if (ownerPackage == event.packageName) {
                            closeOwner(event.timestampMillis)
                            restoreMostRecentOwner(event.timestampMillis)
                        }
                    } else {
                        refreshPackageActivationOrder(event.packageName)
                    }
                }

                AppUsageEventType.SCREEN_NON_INTERACTIVE -> {
                    closeOwner(event.timestampMillis)
                    deviceInteractive = false
                }

                AppUsageEventType.SCREEN_INTERACTIVE -> {
                    deviceInteractive = true
                    restoreMostRecentOwner(event.timestampMillis)
                }

                AppUsageEventType.KEYGUARD_SHOWN -> {
                    closeOwner(event.timestampMillis)
                    keyguardVisible = true
                }

                AppUsageEventType.KEYGUARD_HIDDEN -> {
                    keyguardVisible = false
                    restoreMostRecentOwner(event.timestampMillis)
                }

                AppUsageEventType.DEVICE_SHUTDOWN -> {
                    closeOwner(event.timestampMillis)
                    activeComponents.clear()
                    componentActivationOrder.clear()
                    pendingPauseAtMillis.clear()
                    packageActivationOrder.clear()
                    deviceInteractive = false
                    keyguardVisible = true
                }

                AppUsageEventType.DEVICE_STARTUP -> {
                    // 启动后不能沿用关机前Activity；等待系统发送新的恢复事件再开始统计。
                    closeOwner(event.timestampMillis)
                    activeComponents.clear()
                    componentActivationOrder.clear()
                    pendingPauseAtMillis.clear()
                    packageActivationOrder.clear()
                    deviceInteractive = false
                    keyguardVisible = true
                }
            }
        }

    if (isDeviceUsable()) {
        closeOwner(nowMillis)
    } else {
        ownerPackage = null
        ownerStartedAtMillis = null
    }

    val visibleIntervals = foregroundIntervals.filter { interval ->
        interval.packageName.isNotBlank() &&
            (allowedPackages.isEmpty() || interval.packageName in allowedPackages)
    }
    val mutableDailyPackages = displayDates.associateWith {
        mutableMapOf<String, MutablePackageUsage>()
    }

    // 时长、夜间时长和最后使用时间都从同一批不重叠区间派生。
    visibleIntervals.forEach { interval ->
        splitIntervalByLocalDay(interval, zoneId).forEach segmentLoop@ { segment ->
            val dayPackages = mutableDailyPackages[segment.date] ?: return@segmentLoop
            val packageUsage = dayPackages.getOrPut(interval.packageName) {
                MutablePackageUsage()
            }
            packageUsage.foregroundMillis += segment.durationMillis
            packageUsage.nightMillis += calculateNightOverlapMillis(segment, zoneId)
            packageUsage.lastUsedAtMillis = maxOf(
                packageUsage.lastUsedAtMillis,
                // 闭开区间在午夜结束时属于前一天，减一毫秒避免详情错误显示成次日00:00。
                segment.endMillis - 1L
            )
        }
    }

    // 打开次数只看前台会话起点；跨午夜连续区间不会在次日凭空多一次打开。
    val lastIntervalEndByPackage = mutableMapOf<String, Long>()
    visibleIntervals.sortedBy(ForegroundInterval::startMillis).forEach { interval ->
        val previousEndMillis = lastIntervalEndByPackage[interval.packageName]
        val isNewLaunch = previousEndMillis == null ||
            interval.startMillis - previousEndMillis >= APP_USAGE_LAUNCH_MERGE_GAP_MILLIS
        if (isNewLaunch) {
            val launchDate = Instant.ofEpochMilli(interval.startMillis)
                .atZone(zoneId)
                .toLocalDate()
            mutableDailyPackages[launchDate]
                ?.getOrPut(interval.packageName, ::MutablePackageUsage)
                ?.let { usage -> usage.launchCount += 1 }
        }
        lastIntervalEndByPackage[interval.packageName] = maxOf(
            previousEndMillis ?: Long.MIN_VALUE,
            interval.endMillis
        )
    }

    val days = displayDates.map { date ->
        val packages = mutableDailyPackages.getValue(date).map { (packageName, usage) ->
            AggregatedPackageUsage(
                packageName = packageName,
                foregroundMillis = usage.foregroundMillis,
                launchCount = usage.launchCount,
                nightMillis = usage.nightMillis,
                lastUsedAtMillis = usage.lastUsedAtMillis
            )
        }.filter { usage ->
            usage.foregroundMillis > 0L || usage.launchCount > 0
        }.sortedWith(
            compareByDescending<AggregatedPackageUsage> { usage -> usage.foregroundMillis }
                .thenByDescending { usage -> usage.launchCount }
                .thenBy { usage -> usage.packageName }
        )
        AggregatedAppUsageDay(date = date, packages = packages)
    }

    return AggregatedAppUsage(days = days)
}

/** Activity最近一次恢复或接收用户交互的稳定排序信息。 */
private data class ActivationOrder(
    val timestampMillis: Long,
    val sequenceIndex: Long
)

/** 应用连续拥有唯一前台归属的一段闭开区间。 */
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
    /** @return 当前闭开区间的非负毫秒数。 */
    val durationMillis: Long
        get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/** 单包单日聚合过程中的可变累加器。 */
private data class MutablePackageUsage(
    var foregroundMillis: Long = 0L,
    var launchCount: Int = 0,
    var nightMillis: Long = 0L,
    var lastUsedAtMillis: Long = 0L
)

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
 * 计算单日区间与00:00—06:00、23:00—24:00两个夜间窗口的重叠毫秒数。
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

/**
 * 计算两个闭开时间区间的非负重叠毫秒数。
 *
 * @param firstStart 第一个区间起点。
 * @param firstEnd 第一个区间终点。
 * @param secondStart 第二个区间起点。
 * @param secondEnd 第二个区间终点。
 * @return 两区间重叠的毫秒数，没有重叠时返回0。
 */
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

/** 新旧同类Activity切换后，旧实例STOPPED事件允许迟到的最大保护窗口。 */
private const val APP_USAGE_STALE_STOP_GRACE_MILLIS = 5L * 60_000L
