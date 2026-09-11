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
import com.example.harleyapp.data.AppUsageHistoryRepository
import com.example.harleyapp.model.APP_USAGE_TREND_DAY_COUNT
import com.example.harleyapp.model.AppUsageDashboard
import com.example.harleyapp.model.AppUsageDayDetail
import com.example.harleyapp.model.AppUsageEntry
import com.example.harleyapp.model.AppUsageEventSnapshot
import com.example.harleyapp.model.AppUsageEventType
import com.example.harleyapp.model.AppUsageQueryError
import com.example.harleyapp.model.AppUsageQueryResult
import com.example.harleyapp.model.aggregateAppUsageEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * 读取Android“使用情况访问”提供的系统事件，并转换为最近七天逐日应用使用明细。
 *
 * 使用方法：
 * 页面先调用[hasUsageAccess]判断用户是否已经授权。未授权时，通过[createUsageAccessIntent]
 * 打开系统设置；用户返回页面后再次检查权限。已授权时，在协程内调用[query]即可读取最近七天
 * 每一天的前台交互时长、打开次数、夜间使用和应用排行。查询、图标解码和历史合并固定在IO线程。
 *
 * 时长统计的是“用户可交互时的唯一前台Activity”，不是后台进程存在时间。控制器同时读取Activity、
 * 息屏、锁屏和设备启停事件；全部包先参与前台归属切换，只有最终结果才过滤为桌面应用，从而避免
 * 漏暂停、系统界面切换或多应用事件重叠造成长时间虚高。Android可能清理较早的原始事件，因此
 * 已经成功算出的日明细会自动保存在本机；不生成月报，也不提供删除、清空或重置入口。
 *
 * @param context Android上下文，内部统一保存Application Context，避免持有页面Activity。
 */
class AppUsageController(context: Context) {

    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val appOpsManager = applicationContext.getSystemService(AppOpsManager::class.java)
    private val usageStatsManager = applicationContext.getSystemService(UsageStatsManager::class.java)
    private val historyRepository = AppUsageHistoryRepository(applicationContext)

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
     * 查询最近七个自然日的逐日应用前台交互统计。
     *
     * 调用方式：必须在用户完成“使用情况访问”授权后调用。函数内部仍会二次检查授权，防止用户
     * 从系统设置撤销权限后页面继续读取。查询会额外向前读取一个自然日作为状态预滚，但只返回
     * 今天和前六天；应用名称与图标按包名只解析一次，再复用到七天明细中。
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
        val firstDisplayDate = today.minusDays((APP_USAGE_TREND_DAY_COUNT - 1).toLong())
        val rangeStartMillis = firstDisplayDate
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val eventQueryStartMillis = firstDisplayDate
            .minusDays(APP_USAGE_EVENT_PREROLL_DAY_COUNT)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        val launcherPackages = queryLauncherPackages()
            ?: return@withContext AppUsageQueryResult(error = AppUsageQueryError.QUERY_FAILED)
        val eventSnapshots = runCatching {
            readEventSnapshots(
                service = service,
                queryStartMillis = eventQueryStartMillis,
                nowMillis = nowMillis
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read app usage events", error)
            return@withContext AppUsageQueryResult(error = AppUsageQueryError.QUERY_FAILED)
        }
        val liveAggregation = runCatching {
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
        val mergedDays = runCatching {
            historyRepository.mergeWithHistory(
                liveDays = liveAggregation.days,
                zoneId = zoneId,
                allowedPackages = launcherPackages,
                currentDate = today
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to merge app usage history", error)
        }.getOrDefault(liveAggregation.days)

        // 名称和图标只按包名解析一次，避免同一个应用在七个日期里重复解码位图。
        val applicationPresentation = mergedDays.asSequence()
            .flatMap { day -> day.packages.asSequence() }
            .map { usage -> usage.packageName }
            .distinct()
            .associateWith(::loadApplicationPresentation)
        val dayDetails = mergedDays.map { day ->
            AppUsageDayDetail(
                date = day.date,
                appEntries = day.packages.map { usage ->
                    val presentation = applicationPresentation.getValue(usage.packageName)
                    AppUsageEntry(
                        packageName = usage.packageName,
                        label = presentation.label,
                        icon = presentation.icon,
                        foregroundMillis = usage.foregroundMillis,
                        launchCount = usage.launchCount,
                        nightMillis = usage.nightMillis,
                        lastUsedAtMillis = usage.lastUsedAtMillis
                    )
                }
            )
        }

        AppUsageQueryResult(
            dashboard = AppUsageDashboard(
                generatedAtMillis = nowMillis,
                dayDetails = dayDetails
            )
        )
    }

    /**
     * 顺序读取UsageEvents游标，保留前台、屏幕、锁屏、交互和设备启停事件。
     *
     * Android 8和9的MOVE_TO_FOREGROUND/BACKGROUND与新版ACTIVITY_RESUMED/PAUSED使用相同
     * 事件编号，因此统一映射为Activity恢复和暂停。原始游标顺序写入sequenceIndex，相同毫秒内
     * 不会因为再次排序而颠倒暂停、恢复或锁屏的先后关系。
     *
     * @param service Android使用情况统计服务。
     * @param queryStartMillis 含一个预滚日的查询起点Unix毫秒时间。
     * @param nowMillis 查询截止Unix毫秒时间。
     * @return 可交给纯计算层聚合的稳定事件快照列表。
     */
    private fun readEventSnapshots(
        service: UsageStatsManager,
        queryStartMillis: Long,
        nowMillis: Long
    ): List<AppUsageEventSnapshot> {
        val endExclusiveMillis = if (nowMillis == Long.MAX_VALUE) {
            nowMillis
        } else {
            nowMillis + 1L
        }
        val usageEvents = service.queryEvents(queryStartMillis, endExclusiveMillis)
        val currentEvent = UsageEvents.Event()
        val snapshots = mutableListOf<AppUsageEventSnapshot>()
        var sequenceIndex = 0L
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(currentEvent)
            val type = when (currentEvent.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> AppUsageEventType.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> AppUsageEventType.ACTIVITY_PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> AppUsageEventType.ACTIVITY_STOPPED
                UsageEvents.Event.USER_INTERACTION -> AppUsageEventType.USER_INTERACTION
                UsageEvents.Event.SCREEN_INTERACTIVE -> AppUsageEventType.SCREEN_INTERACTIVE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    AppUsageEventType.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.KEYGUARD_SHOWN -> AppUsageEventType.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN -> AppUsageEventType.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> AppUsageEventType.DEVICE_SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> AppUsageEventType.DEVICE_STARTUP
                else -> {
                    sequenceIndex += 1L
                    continue
                }
            }
            snapshots += AppUsageEventSnapshot(
                packageName = currentEvent.packageName.orEmpty(),
                componentName = currentEvent.className.orEmpty(),
                timestampMillis = currentEvent.timeStamp,
                type = type,
                sequenceIndex = sequenceIndex
            )
            sequenceIndex += 1L
        }
        return snapshots
    }

    /**
     * 查询具有桌面启动入口的应用包名，过滤系统内部Activity和不可直接打开的组件。
     *
     * 该集合只用于最终输出过滤，所有系统包事件仍会先进入状态机并结束旧应用的前台归属。
     *
     * @return 当前用户下所有可见桌面应用包名；查询失败或结果异常为空时返回null，调用方停止统计，
     * 避免把空白名单误解释为“允许所有系统组件”。
     */
    @Suppress("DEPRECATION")
    private fun queryLauncherPackages(): Set<String>? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = runCatching {
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
        }.getOrNull()
        if (packages.isNullOrEmpty()) {
            Log.w(TAG, "Launcher application query returned no packages")
            return null
        }
        return packages
    }

    /**
     * 读取一个包的用户可见名称和缩放图标。
     *
     * @param packageName 要解析的应用包名。
     * @return 始终可用的展示信息；系统读取失败时使用包名并返回空图标。
     */
    private fun loadApplicationPresentation(packageName: String): ApplicationPresentation {
        val applicationInfo = getApplicationInfo(packageName)
        return ApplicationPresentation(
            label = applicationInfo?.let(packageManager::getApplicationLabel)?.toString()
                ?: packageName,
            icon = applicationInfo?.let(::loadIcon)
        )
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

    /** 一个包在页面中复用的名称和图标。 */
    private data class ApplicationPresentation(
        val label: String,
        val icon: Bitmap?
    )

    private companion object {
        const val TAG = "AppUsageController"
        const val ICON_SIZE_PX = 120
        const val APP_USAGE_EVENT_PREROLL_DAY_COUNT = 1L
    }
}
