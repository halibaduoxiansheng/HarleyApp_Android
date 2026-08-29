package com.example.harleyapp.system

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.example.harleyapp.model.MobileDataAppUsage
import com.example.harleyapp.model.MobileDataPeriod
import com.example.harleyapp.model.MobileDataQueryError
import com.example.harleyapp.model.MobileDataQueryResult
import com.example.harleyapp.model.MobileDataUsageSnapshot
import com.example.harleyapp.model.calculateMobileDataPeriodStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 查询系统记录的蜂窝移动数据使用量，并管理“使用情况访问”特殊授权入口。
 *
 * 使用方法：
 * 页面先调用[hasUsageAccess]判断用户是否已授权；未授权时通过[createUsageAccessIntent]打开
 * 系统设置。授权后在协程中调用[query]读取今日、本周或本月统计。NetworkStats查询可能耗时
 * 数秒，[query]内部固定切换到IO线程，不会阻塞Compose主线程。
 *
 * 本类只查询ConnectivityManager.TYPE_MOBILE，subscriberId传null表示汇总所有SIM的蜂窝流量，
 * 不读取手机号、IMSI、网页内容或Wi-Fi流量。
 *
 * @param context Android上下文，内部转换为Application Context。
 */
class MobileDataUsageController(context: Context) {

    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val appOpsManager = applicationContext.getSystemService(AppOpsManager::class.java)
    private val networkStatsManager = applicationContext.getSystemService(
        NetworkStatsManager::class.java
    )
    private val connectivityManager = applicationContext.getSystemService(
        ConnectivityManager::class.java
    )

    /**
     * 判断用户是否在系统设置中允许本应用读取其他应用的使用情况统计。
     *
     * @return AppOps模式为MODE_ALLOWED时返回true，其余情况返回false。
     */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                applicationContext.packageName
            )
        } else {
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                applicationContext.packageName
            )
        }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 创建打开Android“使用情况访问”授权列表的Intent。
     *
     * @return 可交给ActivityResultLauncher启动的系统设置Intent。
     */
    fun createUsageAccessIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }

    /**
     * 查询指定自然周期内所有可见UID的蜂窝上传和下载量。
     *
     * @param period 今日、本周或本月周期。
     *
     * @return 成功时包含总量和按UID排名；未授权、系统服务不可用或查询异常时返回明确错误类型。
     */
    suspend fun query(period: MobileDataPeriod): MobileDataQueryResult =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess()) {
                return@withContext MobileDataQueryResult(
                    error = MobileDataQueryError.USAGE_ACCESS_REQUIRED
                )
            }

            val nowMillis = System.currentTimeMillis()
            val startMillis = calculateMobileDataPeriodStart(period, nowMillis)
            val networkStats = runCatching {
                networkStatsManager.querySummary(
                    ConnectivityManager.TYPE_MOBILE,
                    null,
                    startMillis,
                    nowMillis
                )
            }.getOrElse { error ->
                Log.e(TAG, "Failed to query mobile network statistics", error)
                return@withContext MobileDataQueryResult(
                    error = MobileDataQueryError.QUERY_FAILED
                )
            } ?: return@withContext MobileDataQueryResult(
                error = MobileDataQueryError.SERVICE_UNAVAILABLE
            )

            val usageByUid = mutableMapOf<Int, MutableUidUsage>()
            try {
                val bucket = NetworkStats.Bucket()
                while (networkStats.hasNextBucket()) {
                    networkStats.getNextBucket(bucket)
                    if (bucket.uid == NetworkStats.Bucket.UID_ALL) {
                        continue
                    }

                    val usage = usageByUid.getOrPut(bucket.uid) {
                        MutableUidUsage()
                    }
                    usage.receivedBytes += bucket.rxBytes.coerceAtLeast(0L)
                    usage.transmittedBytes += bucket.txBytes.coerceAtLeast(0L)
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Failed while reading mobile data buckets", error)
                return@withContext MobileDataQueryResult(
                    error = MobileDataQueryError.QUERY_FAILED
                )
            } finally {
                networkStats.close()
            }

            val appUsage = usageByUid
                .mapNotNull { (uid, usage) ->
                    if (usage.receivedBytes <= 0L && usage.transmittedBytes <= 0L) {
                        null
                    } else {
                        resolveAppUsage(uid, usage)
                    }
                }
                .sortedByDescending { usage ->
                    usage.totalBytes
                }
            val totalReceivedBytes = appUsage.sumOf { usage ->
                usage.receivedBytes
            }
            val totalTransmittedBytes = appUsage.sumOf { usage ->
                usage.transmittedBytes
            }

            MobileDataQueryResult(
                snapshot = MobileDataUsageSnapshot(
                    period = period,
                    startTimeMillis = startMillis,
                    endTimeMillis = nowMillis,
                    receivedBytes = totalReceivedBytes,
                    transmittedBytes = totalTransmittedBytes,
                    apps = appUsage,
                    wifiConnected = isWifiConnected()
                )
            )
        }

    /**
     * 判断当前默认活动网络是否通过Wi-Fi传输。
     *
     * @return 当前连接Wi-Fi返回true；未连接、无网络或系统服务未提供能力时返回false。
     */
    fun isWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 把一个UID的累计字节数转换为用户可识别的应用名称、包名和图标。
     *
     * @param uid Android UID。
     * @param usage 已聚合的上传和下载字节数。
     *
     * @return 可直接展示的MobileDataAppUsage；共享UID会合并显示多个应用名称。
     */
    private fun resolveAppUsage(uid: Int, usage: MutableUidUsage): MobileDataAppUsage {
        val packageNames = packageManager.getPackagesForUid(uid)
            ?.distinct()
            ?.sorted()
            .orEmpty()
        val applicationInfos = packageNames.mapNotNull(::getApplicationInfo)
        val labels = applicationInfos
            .map { applicationInfo ->
                packageManager.getApplicationLabel(applicationInfo).toString()
            }
            .distinct()
        val label = when {
            labels.size > 1 -> labels.joinToString(separator = " / ")
            labels.size == 1 -> labels.first()
            uid == NetworkStats.Bucket.UID_REMOVED -> "已卸载应用"
            uid == NetworkStats.Bucket.UID_TETHERING -> "热点共享"
            else -> "Android系统（UID $uid）"
        }
        val icon = if (applicationInfos.size == 1) {
            loadIcon(applicationInfos.first())
        } else {
            null
        }

        return MobileDataAppUsage(
            uid = uid,
            packageNames = packageNames,
            label = label,
            icon = icon,
            receivedBytes = usage.receivedBytes,
            transmittedBytes = usage.transmittedBytes
        )
    }

    /**
     * 按当前Android版本安全查询ApplicationInfo。
     *
     * @param packageName 要解析的包名。
     *
     * @return 当前应用可见且仍安装时返回ApplicationInfo，否则返回null。
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
     * 把应用Drawable缩放为列表使用的小尺寸Bitmap。
     *
     * @param applicationInfo 已解析应用信息。
     *
     * @return 成功加载时返回ARGB图标，加载异常时返回null。
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

    /** 同一UID多条NetworkStats Bucket的可变聚合容器。 */
    private data class MutableUidUsage(
        var receivedBytes: Long = 0L,
        var transmittedBytes: Long = 0L
    )

    private companion object {
        const val TAG = "MobileDataUsage"
        const val ICON_SIZE_PX = 120
    }
}
