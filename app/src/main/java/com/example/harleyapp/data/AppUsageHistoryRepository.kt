package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.AggregatedAppUsageDay
import com.example.harleyapp.model.AggregatedPackageUsage
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 在应用私有SharedPreferences中保存已经计算出的逐日应用使用明细。
 *
 * 使用方法：
 * [com.example.harleyapp.system.AppUsageController]每次完成七天事件聚合后调用[mergeWithHistory]。
 * 仓库按自然日保存不含应用图标的数值结果；以后Android清理较早的UsageEvents时，最近七天页面
 * 仍可回用先前确认过的日数据。仓库没有删除、清空或重置接口，也不会生成月度报告。
 *
 * 今天始终展示并写入实时重算结果，允许更准确的结果纠正旧虚高；对于已经结束的日期，新查询
 * 总时长意外变少通常表示系统清理了部分原始事件，此时保留旧值，新结果相同或更完整时整体覆盖。
 * 按“整天”选择而不是逐字段取最大值，可避免把两次不同时间线拼成互相重叠的虚假结果。时区或
 * 算法版本变化时旧记录不会参与本次合并。
 *
 * @param context Android上下文，内部只保存Application Context创建的私有SharedPreferences。
 */
class AppUsageHistoryRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 合并实时计算结果与本机逐日历史，并把选中的完整结果写回本机。
     *
     * @param liveDays 本次从Android事件完整重算出的连续逐日结果。
     * @param zoneId 本次统计使用的手机时区；不同的时区记录不会混用。
     * @param allowedPackages 当前仍具有桌面启动入口的包名；空集合表示不过滤。
     * @param currentDate 本次查询所在自然日；该日始终采用实时结果，不参与历史竞高。
     * @return 与[liveDays]日期顺序一致的逐日结果；新查询异常变少时可能回用本机旧日数据。
     */
    @Synchronized
    fun mergeWithHistory(
        liveDays: List<AggregatedAppUsageDay>,
        zoneId: ZoneId,
        allowedPackages: Set<String>,
        currentDate: LocalDate
    ): List<AggregatedAppUsageDay> {
        if (liveDays.isEmpty()) return emptyList()

        val mergedDays = liveDays.map { liveDay ->
            val cachedDay = readDay(
                date = liveDay.date,
                zoneId = zoneId,
                allowedPackages = allowedPackages
            )
            chooseMoreCompleteDay(
                liveDay = liveDay,
                cachedDay = cachedDay,
                isCurrentDay = liveDay.date == currentDate
            )
        }
        val editor = preferences.edit()
        mergedDays.forEach { day ->
            editor.putString(day.date.toString(), encodeDay(day, zoneId))
        }
        if (!editor.commit()) {
            Log.w(TAG, "Failed to persist app usage daily history")
        }
        return mergedDays
    }

    /**
     * 读取并校验指定日期的缓存；损坏、旧版本、时区不同或字段无效时安全忽略。
     *
     * @param date 要读取的自然日期。
     * @param zoneId 当前统计时区。
     * @param allowedPackages 当前可展示包名；空集合表示不过滤。
     * @return 可参与合并的单日结果，不可用时返回null。
     */
    private fun readDay(
        date: LocalDate,
        zoneId: ZoneId,
        allowedPackages: Set<String>
    ): AggregatedAppUsageDay? {
        val storedValue = preferences.getString(date.toString(), null) ?: return null
        return runCatching {
            val root = JSONObject(storedValue)
            if (root.optInt(JSON_SCHEMA_VERSION) != SCHEMA_VERSION ||
                root.optString(JSON_DATE) != date.toString() ||
                root.optString(JSON_ZONE_ID) != zoneId.id
            ) {
                return@runCatching null
            }

            val packagesByName = linkedMapOf<String, AggregatedPackageUsage>()
            val packageArray = root.optJSONArray(JSON_PACKAGES) ?: JSONArray()
            for (index in 0 until packageArray.length()) {
                val item = packageArray.optJSONObject(index) ?: continue
                val packageName = item.optString(JSON_PACKAGE_NAME).trim()
                if (packageName.isBlank() ||
                    (allowedPackages.isNotEmpty() && packageName !in allowedPackages)
                ) {
                    continue
                }

                val foregroundMillis = item.optLong(JSON_FOREGROUND_MILLIS)
                    .coerceAtLeast(0L)
                val launchCount = item.optInt(JSON_LAUNCH_COUNT).coerceAtLeast(0)
                val nightMillis = item.optLong(JSON_NIGHT_MILLIS)
                    .coerceIn(0L, foregroundMillis)
                val lastUsedAtMillis = item.optLong(JSON_LAST_USED_AT_MILLIS)
                    .coerceAtLeast(0L)
                if (foregroundMillis <= 0L && launchCount <= 0) continue

                packagesByName[packageName] = AggregatedPackageUsage(
                    packageName = packageName,
                    foregroundMillis = foregroundMillis,
                    launchCount = launchCount,
                    nightMillis = nightMillis,
                    lastUsedAtMillis = lastUsedAtMillis
                )
            }
            AggregatedAppUsageDay(
                date = date,
                packages = packagesByName.values.sortedWith(APP_USAGE_PACKAGE_COMPARATOR)
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to read app usage daily history", error)
        }.getOrNull()
    }

    /**
     * 把一个自然日结果编码成带算法版本和时区的JSON。
     *
     * @param day 要持久化的单日聚合结果。
     * @param zoneId 生成该结果时采用的手机时区。
     * @return 可直接写入SharedPreferences的JSON文本。
     */
    private fun encodeDay(day: AggregatedAppUsageDay, zoneId: ZoneId): String {
        val packageArray = JSONArray()
        day.packages.forEach { usage ->
            packageArray.put(
                JSONObject().apply {
                    put(JSON_PACKAGE_NAME, usage.packageName)
                    put(JSON_FOREGROUND_MILLIS, usage.foregroundMillis)
                    put(JSON_LAUNCH_COUNT, usage.launchCount)
                    put(JSON_NIGHT_MILLIS, usage.nightMillis)
                    put(JSON_LAST_USED_AT_MILLIS, usage.lastUsedAtMillis)
                }
            )
        }
        return JSONObject().apply {
            put(JSON_SCHEMA_VERSION, SCHEMA_VERSION)
            put(JSON_DATE, day.date.toString())
            put(JSON_ZONE_ID, zoneId.id)
            put(JSON_PACKAGES, packageArray)
        }.toString()
    }

    private companion object {
        const val TAG = "AppUsageHistory"
        const val PREFERENCE_NAME = "app_usage_daily_history_v2"
        const val SCHEMA_VERSION = 2
        const val JSON_SCHEMA_VERSION = "schemaVersion"
        const val JSON_DATE = "date"
        const val JSON_ZONE_ID = "zoneId"
        const val JSON_PACKAGES = "packages"
        const val JSON_PACKAGE_NAME = "packageName"
        const val JSON_FOREGROUND_MILLIS = "foregroundMillis"
        const val JSON_LAUNCH_COUNT = "launchCount"
        const val JSON_NIGHT_MILLIS = "nightMillis"
        const val JSON_LAST_USED_AT_MILLIS = "lastUsedAtMillis"
    }
}

/**
 * 在本次完整重算和本机已有日数据之间选择更完整的一项。
 *
 * 使用方法：
 * 仓库读取同一天缓存后调用。总时长是单一全局时间线的直接完整度信号；新结果不短于旧结果时
 * 使用新结果，以便接纳系统延迟写入的事件，否则整天保留旧结果，不能逐应用拼接两套时间线。
 *
 * @param liveDay 本次事件查询重新计算的单日结果。
 * @param cachedDay 同日期、同版本、同时区的旧结果；没有可用缓存时为null。
 * @param isCurrentDay true表示仍在变化的今天，必须使用实时结果，不能被旧高值锁住。
 * @return 应写回并展示的完整单日结果。
 */
internal fun chooseMoreCompleteDay(
    liveDay: AggregatedAppUsageDay,
    cachedDay: AggregatedAppUsageDay?,
    isCurrentDay: Boolean = false
): AggregatedAppUsageDay {
    if (isCurrentDay) return liveDay
    if (cachedDay == null || cachedDay.date != liveDay.date) return liveDay
    return if (liveDay.foregroundMillis >= cachedDay.foregroundMillis) {
        liveDay
    } else {
        cachedDay
    }
}

/** 单日应用明细使用的统一稳定排序。 */
private val APP_USAGE_PACKAGE_COMPARATOR =
    compareByDescending<AggregatedPackageUsage> { usage -> usage.foregroundMillis }
        .thenByDescending { usage -> usage.launchCount }
        .thenBy { usage -> usage.packageName }
