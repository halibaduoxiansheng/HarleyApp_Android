package com.example.harleyapp.system

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

/**
 * 读取Android健康数据共享中的每日步数汇总。
 *
 * 使用方法：
 * 先调用[getAvailability]判断当前设备是否可用；状态为[HealthConnectAvailability.AVAILABLE]时，
 * 通过[readStepsPermission]交给健康权限授权器申请权限。授权完成后调用[hasReadPermission]复核权限，
 * 最后调用[readDailySteps]按本地自然日读取汇总步数。
 *
 * 本类只申请和读取步数，不写入健康数据，也不读取心率、位置、睡眠等其他敏感数据。
 *
 * @param context 用于创建Health Connect客户端的上下文，内部会转换为ApplicationContext避免泄漏页面。
 */
class HealthConnectStepReader(context: Context) {

    private val applicationContext = context.applicationContext

    private val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(applicationContext)
    }

    /**
     * 检查当前设备上的健康数据共享服务状态。
     *
     * 使用方法：
     * 进入运动页面时调用一次，根据结果决定显示授权入口还是继续使用计步传感器兜底。
     *
     * @return 可直接用于页面分支判断的[HealthConnectAvailability]状态。
     */
    fun getAvailability(): HealthConnectAvailability {
        return runCatching {
            when (HealthConnectClient.getSdkStatus(applicationContext)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                    HealthConnectAvailability.UPDATE_REQUIRED
                }

                else -> HealthConnectAvailability.UNAVAILABLE
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to check Health Connect availability", error)
        }.getOrDefault(HealthConnectAvailability.UNAVAILABLE)
    }

    /**
     * 打开系统健康数据共享设置，供用户检查小米运动健康等数据提供方是否允许写入步数。
     *
     * 使用方法：
     * Health Connect已授权但当天汇总为空时，由运动页面的“健康数据设置”按钮调用。
     * Android会显示系统管理页，所有其他应用的健康权限仍必须由用户本人确认。
     *
     * @return 成功交给系统打开设置页时返回true；设备没有可处理入口或启动失败时返回false。
     */
    fun openSettings(): Boolean {
        return runCatching {
            val settingsIntent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(settingsIntent)
            true
        }.onFailure { error ->
            Log.w(TAG, "Failed to open Health Connect settings", error)
        }.getOrDefault(false)
    }

    /**
     * 查询用户是否仍允许本App读取步数。
     *
     * 使用方法：
     * 每次进入运动页面或从系统授权页返回时调用，不能只依赖上一次授权结果，因为用户可能在系统设置中撤销权限。
     *
     * @return 成功时包含是否已授权；服务异常或不可访问时返回失败结果，由页面回退到传感器计步。
     */
    suspend fun hasReadPermission(): Result<Boolean> {
        return runCatching {
            client.permissionController
                .getGrantedPermissions()
                .contains(readStepsPermission)
        }.onFailure { error ->
            Log.w(TAG, "Failed to query Health Connect permissions", error)
        }
    }

    /**
     * 读取指定本地日期内经过Health Connect聚合、去重后的步数总量。
     *
     * 使用方法：
     * 仅在[hasReadPermission]返回true后调用。页面可定时重新读取当天数据，以接收小米运动健康等来源的后续同步。
     *
     * @param date 需要查询的本地日期，查询区间为当天00:00至下一天00:00。
     * @param zoneId 日期边界使用的时区，默认跟随手机当前系统时区。
     *
     * @return 成功时返回安全范围内的当天步数以及是否存在有效步数；失败时包含原始异常供上层降级处理。
     */
    suspend fun readDailySteps(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result<HealthConnectDailySteps> {
        return runCatching {
            val startTime = date.atStartOfDay(zoneId).toInstant()
            val endTime = date.plusDays(1L).atStartOfDay(zoneId).toInstant()
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            val totalSteps = (result[StepsRecord.COUNT_TOTAL] ?: 0L)
                .coerceIn(0L, Int.MAX_VALUE.toLong())
                .toInt()

            HealthConnectDailySteps(
                steps = totalSteps,
                // Health Connect无记录时同样返回0；正数可明确证明已有可用于同步的步数来源。
                hasStepData = totalSteps > 0
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to read daily steps from Health Connect", error)
        }
    }

    companion object {
        private const val TAG = "HealthConnectSteps"

        /**
         * Health Connect读取步数所需的唯一权限。
         *
         * 使用方法：
         * 作为PermissionController授权合同的输入集合元素；清单中必须同时声明READ_STEPS。
         *
         * @return 权限字符串常量。
         */
        val readStepsPermission: String = HealthPermission.getReadPermission(StepsRecord::class)
    }
}

/**
 * 描述当前设备的健康数据共享可用状态。
 *
 * [AVAILABLE]表示可以授权并读取；[UPDATE_REQUIRED]表示服务存在但版本过旧；
 * [UNAVAILABLE]表示当前系统没有可用服务，页面应直接使用本机计步传感器。
 */
enum class HealthConnectAvailability {
    AVAILABLE,
    UPDATE_REQUIRED,
    UNAVAILABLE
}

/**
 * 保存一次Health Connect自然日步数聚合结果。
 *
 * @param steps 当天聚合步数，已限制在非负Int范围。
 * @param hasStepData 是否检测到可作为主数据源的有效步数；false时页面继续使用传感器兜底。
 */
data class HealthConnectDailySteps(
    val steps: Int,
    val hasStepData: Boolean
)
