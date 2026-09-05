package com.example.harleyapp.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.example.harleyapp.model.AppUsageDashboard
import com.example.harleyapp.model.AppUsageEntry
import com.example.harleyapp.model.AppUsageEventSnapshot
import com.example.harleyapp.model.AppUsageQueryError
import com.example.harleyapp.model.AppUsageQueryResult
import com.example.harleyapp.model.APP_USAGE_TREND_DAY_COUNT
import com.example.harleyapp.model.aggregateAppUsageEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 读取Android“使用情况访问”提供的前台Activity事件，并转换为应用使用统计页面需要的数据。
 *
 * 使用方法：
 * 页面先调用[hasUsageAccess]判断用户是否已经授权。未授权时，通过[createUsageAccessIntent]
 * 打开系统设置；用户返回页面后再次检查权限。已授权时，在协程内调用[query]即可读取今天概览、
 * 应用排行和最近七天趋势。查询和图标解码固定在IO线程执行，不会阻塞Compose主线程。
 *
 * 系统事件只记录Android提供的Activity前台状态变化，部分厂商会延迟或提前清理历史事件，
 * 因此结果是用于自我管理的合理估算，不把它表述为精确的后台存活时长。
 *
 * @param context Android上下文，内部统一保存Application Context，避免持有页面Activity。
 */
class AppUsageController(context: Context) {

    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val appOpsManager = applicationContext.getSystemService(AppOpsManager::class.java)
    private val usageStatsManager = applicationContext.getSystemService(UsageStatsManager::class.java)

    /**
     * 判断用户是否在系统设置中允许本应用读取使用情况。
     *
     * @return AppOps模式为允许时返回true；服务不可用、尚未选择或被拒绝时返回false。
     */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val service = appOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                applicationContext.packageName
            )
        } else {
            service.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                applicationContext.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 创建Android“使用情况访问”授权列表的设置Intent。
     *
     * @return 可交给ActivityResultLauncher启动的系统设置Intent。
     */
    fun createUsageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * 查询今天和最近七个自然日的应用前台使用统计。
     *
     * 调用方式：必须在用户完成“使用情况访问”授权后调用。函数内部仍会二次检查授权，防止用户
     * 从系统设置撤销权限后页面继续读取。返回结果已按今天前台时长降序排列并补齐应用名称、图标。
     *
     * @return 成功时返回包含[AppUsageDashboard]的结果；未授权、系统服务缺失或查询异常时返回
     * 对应的[AppUsageQueryError]，不会把异常抛到Compose页面。
     */
    suspend fun query(): AppUsageQueryResult = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) {
            return@withContext AppUsageQueryResult(
                error = AppUsageQueryError.USAGE_ACCESS_REQUIRED
            )
        }

        val service = usageStatsManager ?: return@withContext AppUsageQueryResult(
            error = AppUsageQueryError.SERVICE_UNAVAILABLE
        )
        val nowMillis = System.currentTimeMillis()
        val zoneId = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val rangeStartMillis = today
            .minusDays((APP_USAGE_TREND_DAY_COUNT - 1).toLong())
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val launcherPackages = queryLauncherPackages()
        val eventSnapshots = runCatching {
            readEventSnapshots(
                service = service,
                rangeStartMillis = rangeStartMillis,
                nowMillis = nowMillis
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read app usage events", error)
            return@withContext AppUsageQueryResult(error = AppUsageQueryError.QUERY_FAILED)
        }
        val aggregated = runCatching {
            aggregateAppUsageEvents(
                events = eventSnapshots,
                rangeStartMillis = rangeStartMillis,
                nowMillis = nowMillis,
                zoneId = zoneId,
                allowedPackages = launcherPackages
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to aggregate app usage events", error)
            return@withContext AppUsageQueryResult(error = AppUsageQueryError.QUERY_FAILED)
        }
        val entries = aggregated.packages.map { usage ->
            val applicationInfo = getApplicationInfo(usage.packageName)
            AppUsageEntry(
                packageName = usage.packageName,
                label = applicationInfo?.let(packageManager::getApplicationLabel)?.toString()
                    ?: usage.packageName,
                icon = applicationInfo?.let(::loadIcon),
                foregroundMillis = usage.todayForegroundMillis,
                launchCount = usage.todayLaunchCount,
                nightMillis = usage.todayNightMillis,
                lastUsedAtMillis = usage.lastUsedAtMillis
            )
        }

        AppUsageQueryResult(
            dashboard = AppUsageDashboard(
                generatedAtMillis = nowMillis,
                todayForegroundMillis = entries.sumOf(AppUsageEntry::foregroundMillis),
                todayLaunchCount = entries.sumOf(AppUsageEntry::launchCount),
                todayNightMillis = entries.sumOf(AppUsageEntry::nightMillis),
                appEntries = entries,
                sevenDayTrend = aggregated.sevenDayTrend
            )
        )
    }

    /**
     * 顺序读取UsageEvents游标，只保留Activity进入前台和离开前台两种事件。
     *
     * @param service Android使用情况统计服务。
     * @param rangeStartMillis 查询起点Unix毫秒时间。
     * @param nowMillis 查询截止Unix毫秒时间。
     * @return 可交给纯计算层聚合的稳定事件快照列表。
     */
    private fun readEventSnapshots(
        service: UsageStatsManager,
        rangeStartMillis: Long,
        nowMillis: Long
    ): List<AppUsageEventSnapshot> {
        val usageEvents = service.queryEvents(rangeStartMillis, nowMillis)
        val currentEvent = UsageEvents.Event()
        val snapshots = mutableListOf<AppUsageEventSnapshot>()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(currentEvent)
            val resumed = when (currentEvent.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> true
                UsageEvents.Event.ACTIVITY_PAUSED -> false
                else -> continue
            }
            snapshots += AppUsageEventSnapshot(
                packageName = currentEvent.packageName.orEmpty(),
                componentName = currentEvent.className.orEmpty(),
                timestampMillis = currentEvent.timeStamp,
                resumed = resumed
            )
        }
        return snapshots
    }

    /**
     * 查询具有桌面启动入口的应用包名，过滤系统内部Activity和不可直接打开的组件。
     *
     * @return 当前用户下所有可见桌面应用包名；查询失败时返回空集合，此时计算层不过滤事件。
     */
    @Suppress("DEPRECATION")
    private fun queryLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                packageManager.queryIntentActivities(intent, 0)
            }
            activities.mapTo(linkedSetOf()) { resolveInfo ->
                resolveInfo.activityInfo.packageName
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to query launcher applications", error)
        }.getOrDefault(emptySet())
    }

    /**
     * 按当前Android版本安全读取指定包的ApplicationInfo。
     *
     * @param packageName 要解析的应用包名。
     * @return 应用仍安装且对本应用可见时返回ApplicationInfo，否则返回null。
     */
    @Suppress("DEPRECATION")
    private fun getApplicationInfo(packageName: String): ApplicationInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                packageManager.getApplicationInfo(packageName, 0)
            }
        }.getOrNull()
    }

    /**
     * 把应用Drawable缩放为列表使用的小尺寸ARGB位图。
     *
     * @param applicationInfo 已成功解析的应用信息。
     * @return 图标成功解码时返回位图；厂商资源异常时返回null，由页面显示文字占位符。
     */
    private fun loadIcon(applicationInfo: ApplicationInfo): Bitmap? {
        return runCatching {
            applicationInfo.loadIcon(packageManager).toBitmap(
                width = ICON_SIZE_PX,
                height = ICON_SIZE_PX,
                config = Bitmap.Config.ARGB_8888
            )
        }.getOrNull()
    }

    private companion object {
        const val TAG = "AppUsageController"
        const val ICON_SIZE_PX = 120
    }
}
