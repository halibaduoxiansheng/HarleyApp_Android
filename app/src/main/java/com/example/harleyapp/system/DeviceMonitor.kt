package com.example.harleyapp.system

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.example.harleyapp.model.DeviceSnapshot
import java.io.File
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
 * 读取Android系统公开的RAM、内部存储和网络累计流量信息。
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
                availableSwapBytes = swapSnapshot.availableBytes
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

    private companion object {
        const val TAG = "DeviceMonitor"
        const val PROC_MEMINFO_PATH = "/proc/meminfo"
        const val SWAP_TOTAL_PREFIX = "SwapTotal:"
        const val SWAP_FREE_PREFIX = "SwapFree:"
    }
}
