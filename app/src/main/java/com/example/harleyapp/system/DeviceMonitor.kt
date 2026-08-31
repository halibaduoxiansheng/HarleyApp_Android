package com.example.harleyapp.system

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.HardwarePropertiesManager
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.example.harleyapp.model.DeviceSnapshot
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * 网络累计字节数的内部采样点。
 *
 * @param elapsedRealtimeMillis 设备启动后的单调时钟毫秒数。
 * @param transmittedBytes 设备累计发送字节数。
 * @param receivedBytes 设备累计接收字节数。
 */
data class NetworkSample(
    val elapsedRealtimeMillis: Long,
    val transmittedBytes: Long,
    val receivedBytes: Long
)

/**
 * 一次完整读取的手机状态及下一次计算所需采样点。
 *
 * @param snapshot 可直接显示的设备状态。
 * @param networkSample 本次网络累计量采样点。
 */
data class DeviceReading(
    val snapshot: DeviceSnapshot,
    val networkSample: NetworkSample
)

/**
 * 内核公开的交换或内存扩展空间状态。
 *
 * @param totalBytes 交换空间总量。
 * @param availableBytes 当前未使用的交换空间。
 */
private data class SwapSnapshot(
    val totalBytes: Long,
    val availableBytes: Long
)

/**
 * 读取Android系统公开的RAM、内部存储、CPU温度和网络累计流量信息。
 *
 * 使用方法：
 * 首次调用read(null)建立网络基线，随后每隔约1秒把上次返回的networkSample传回read。
 * 计算结果是设备当前网络活动的估算值，不是会主动下载数据的带宽测速。
 *
 * @param context Android上下文，内部使用Application Context避免持有页面实例。
 */
class DeviceMonitor(context: Context) {

    private val applicationContext = context.applicationContext

    /**
     * 读取一次设备状态。
     *
     * @param previousSample 上一次采样；首次读取时传null，此时上下行速率显示为0。
     *
     * @return 当前设备状态，以及供下一次读取使用的新采样点。
     */
    fun read(previousSample: NetworkSample?): DeviceReading {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
            as ActivityManager
        activityManager.getMemoryInfo(memoryInfo)

        val storageStats = StatFs(Environment.getDataDirectory().absolutePath)
        val swapSnapshot = readSwapSnapshot()
        val currentSample = NetworkSample(
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            transmittedBytes = normalizeTrafficValue(TrafficStats.getTotalTxBytes()),
            receivedBytes = normalizeTrafficValue(TrafficStats.getTotalRxBytes())
        )
        val elapsedMillis = previousSample?.let {
            max(1L, currentSample.elapsedRealtimeMillis - it.elapsedRealtimeMillis)
        } ?: 1L
        val uploadRate = previousSample?.let {
            bytesPerSecond(
                currentBytes = currentSample.transmittedBytes,
                previousBytes = it.transmittedBytes,
                elapsedMillis = elapsedMillis
            )
        } ?: 0L
        val downloadRate = previousSample?.let {
            bytesPerSecond(
                currentBytes = currentSample.receivedBytes,
                previousBytes = it.receivedBytes,
                elapsedMillis = elapsedMillis
            )
        } ?: 0L

        return DeviceReading(
            snapshot = DeviceSnapshot(
                uploadBytesPerSecond = uploadRate,
                downloadBytesPerSecond = downloadRate,
                totalMemoryBytes = memoryInfo.totalMem,
                availableMemoryBytes = memoryInfo.availMem,
                totalStorageBytes = storageStats.totalBytes,
                availableStorageBytes = storageStats.availableBytes,
                totalSwapBytes = swapSnapshot.totalBytes,
                availableSwapBytes = swapSnapshot.availableBytes,
                cpuTemperatureCelsius = readCpuTemperatureCelsius()
            ),
            networkSample = currentSample
        )
    }

    /**
     * 把系统不支持时返回的负数流量值转换为安全的0。
     *
     * @param value TrafficStats返回的累计字节数。
     *
     * @return 不小于0的累计字节数。
     */
    private fun normalizeTrafficValue(value: Long): Long {
        return if (value == TrafficStats.UNSUPPORTED.toLong() || value < 0L) 0L else value
    }

    /**
     * 根据两次累计量计算每秒速率，并处理系统重启或计数器重置。
     *
     * @param currentBytes 当前累计字节数。
     * @param previousBytes 上一次累计字节数。
     * @param elapsedMillis 两次采样之间的毫秒数。
     *
     * @return 非负的字节每秒速率。
     */
    private fun bytesPerSecond(
        currentBytes: Long,
        previousBytes: Long,
        elapsedMillis: Long
    ): Long {
        val difference = max(0L, currentBytes - previousBytes)
        return difference * 1_000L / max(1L, elapsedMillis)
    }

    /**
     * 从/proc/meminfo读取Android内核公开的SwapTotal和SwapFree。
     *
     * 使用方法：
     * 由read自动调用。小米等厂商的内存扩展通常体现在交换空间中，
     * 该数值与物理RAM分开显示，不能理解为同等性能的物理内存。
     *
     * @return 读取成功返回交换空间状态；系统禁止读取或字段缺失时返回全0。
     */
    private fun readSwapSnapshot(): SwapSnapshot {
        return runCatching {
            var totalBytes = 0L
            var availableBytes = 0L

            File(PROC_MEMINFO_PATH).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith(SWAP_TOTAL_PREFIX) -> {
                            totalBytes = parseMeminfoKilobytes(line)
                        }

                        line.startsWith(SWAP_FREE_PREFIX) -> {
                            availableBytes = parseMeminfoKilobytes(line)
                        }
                    }
                }
            }

            SwapSnapshot(
                totalBytes = totalBytes,
                availableBytes = availableBytes
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read swap memory information", error)
            SwapSnapshot(totalBytes = 0L, availableBytes = 0L)
        }
    }

    /**
     * 解析/proc/meminfo中以kB为单位的一行数值。
     *
     * @param line 例如“SwapTotal: 12582908 kB”的完整文本。
     *
     * @return 换算后的字节数；格式不合法时返回0。
     */
    private fun parseMeminfoKilobytes(line: String): Long {
        val kilobytes = line.substringAfter(':')
            .trim()
            .substringBefore(' ')
            .toLongOrNull()
            ?: return 0L

        return kilobytes * 1_024L
    }

    /**
     * 读取当前CPU或SOC热区温度。
     *
     * 使用方法：
     * [read]每次采样时自动调用。先尝试Android系统温度接口，再读取普通应用有权限访问的
     * `/sys/class/thermal/thermal_zone*`；多个CPU热区同时可用时返回最高值，更能反映当前热点。
     * Android厂商未向普通应用开放接口或节点时返回null，绝不使用电池温度冒充CPU温度。
     *
     * @return 当前可读取CPU/SOC热区中的最高摄氏温度；无权限或没有合法数据时返回null。
     */
    private fun readCpuTemperatureCelsius(): Float? {
        val frameworkTemperatures: List<Float> = runCatching {
            val manager = applicationContext.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE)
                as? HardwarePropertiesManager
                ?: return@runCatching emptyList<Float>()
            manager.getDeviceTemperatures(
                HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                HardwarePropertiesManager.TEMPERATURE_CURRENT
            ).asSequence()
                .mapNotNull(::normalizeCpuTemperature)
                .toList()
        }.getOrDefault(emptyList())
        if (frameworkTemperatures.isNotEmpty()) {
            return frameworkTemperatures.maxOrNull()
        }

        return runCatching {
            File(THERMAL_CLASS_PATH)
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { zone -> zone.isDirectory && zone.name.startsWith(THERMAL_ZONE_PREFIX) }
                .mapNotNull { zone ->
                    // 单个厂商节点拒绝访问时只跳过该节点，继续尝试后续可能开放的CPU热区。
                    runCatching {
                        val type = File(zone, THERMAL_TYPE_FILE_NAME)
                            .takeIf(File::canRead)
                            ?.readText()
                            ?.trim()
                            .orEmpty()
                        if (!isCpuThermalZoneType(type)) {
                            return@runCatching null
                        }

                        File(zone, THERMAL_TEMP_FILE_NAME)
                            .takeIf(File::canRead)
                            ?.readText()
                            ?.trim()
                            ?.toFloatOrNull()
                            ?.let(::normalizeCpuTemperature)
                    }.getOrNull()
                }
                .maxOrNull()
        }.getOrNull()
    }

    private companion object {
        const val TAG = "DeviceMonitor"
        const val PROC_MEMINFO_PATH = "/proc/meminfo"
        const val THERMAL_CLASS_PATH = "/sys/class/thermal"
        const val THERMAL_ZONE_PREFIX = "thermal_zone"
        const val THERMAL_TYPE_FILE_NAME = "type"
        const val THERMAL_TEMP_FILE_NAME = "temp"
        const val SWAP_TOTAL_PREFIX = "SwapTotal:"
        const val SWAP_FREE_PREFIX = "SwapFree:"
    }
}

/**
 * 把厂商可能使用的摄氏度或千分之一摄氏度原始值规范为摄氏度。
 *
 * 使用方法：
 * 系统接口和thermal sysfs读取后统一调用。绝对值达到1000时按毫摄氏度换算，并过滤NaN、无穷大
 * 以及超出消费电子设备合理范围的数据，避免把错误节点或状态码显示为温度。
 *
 * @param rawValue 系统返回的原始浮点温度。
 *
 * @return -40°C到150°C范围内的摄氏温度；数据无效时返回null。
 */
internal fun normalizeCpuTemperature(rawValue: Float): Float? {
    if (!rawValue.isFinite()) {
        return null
    }
    val temperatureCelsius = if (abs(rawValue) >= 1_000f) {
        rawValue / 1_000f
    } else {
        rawValue
    }
    return temperatureCelsius.takeIf { value -> value in -40f..150f }
}

/**
 * 判断thermal zone类型是否明确代表CPU或SOC，而非电池、充电器、机身表面等热区。
 *
 * 使用方法：
 * 遍历`/sys/class/thermal`时把type文件内容传入。本函数先排除常见非CPU关键词，再匹配CPU、SOC、
 * 集群和TSENS等厂商命名；未知类型宁可不展示，也不把其他温度误标为CPU温度。
 *
 * @param type thermal zone的type文本。
 *
 * @return 明确属于CPU/SOC热区返回true，否则返回false。
 */
internal fun isCpuThermalZoneType(type: String): Boolean {
    val normalizedType = type.trim().lowercase(Locale.US)
    if (normalizedType.isBlank()) {
        return false
    }
    if (NON_CPU_THERMAL_KEYWORDS.any(normalizedType::contains)) {
        return false
    }
    return CPU_THERMAL_KEYWORDS.any(normalizedType::contains)
}

/** CPU或SOC热区常见厂商命名片段。 */
private val CPU_THERMAL_KEYWORDS = listOf(
    "cpu",
    "soc",
    "cluster",
    "tsens",
    "big_core",
    "little_core",
    "ap_therm",
    "ap-therm"
)

/** 必须排除的非CPU热区命名片段，避免错误展示电池或外壳温度。 */
private val NON_CPU_THERMAL_KEYWORDS = listOf(
    "battery",
    "batt",
    "charger",
    "charge",
    "skin",
    "surface",
    "usb",
    "gpu",
    "modem",
    "wifi",
    "camera",
    "display"
)
