package com.example.harleyapp.model

import android.graphics.Bitmap
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 手机蜂窝流量页面支持的统计周期。
 *
 * @param title 页面筛选按钮显示的中文名称。
 */
enum class MobileDataPeriod(val title: String) {
    TODAY("今日"),
    WEEK("本周"),
    MONTH("本月")
}

/**
 * 一个Android UID在指定时间范围内产生的蜂窝流量汇总。
 *
 * 使用方法：
 * MobileDataUsageController把NetworkStats中同一UID的多条状态Bucket合并后创建本模型。
 * 多个应用共享同一UID时Android无法继续拆分，因此[packageNames]和[label]会同时说明共享关系。
 *
 * @param uid Android为应用或系统组件分配的UID。
 * @param packageNames 当前系统允许查询到的包名；系统或已卸载应用可能为空。
 * @param label 用户可识别的应用名称或系统流量名称。
 * @param icon 可显示的应用图标；系统、共享或已卸载UID无法解析时为null。
 * @param receivedBytes 该UID通过蜂窝网络下载的字节数。
 * @param transmittedBytes 该UID通过蜂窝网络上传的字节数。
 */
data class MobileDataAppUsage(
    val uid: Int,
    val packageNames: List<String>,
    val label: String,
    val icon: Bitmap?,
    val receivedBytes: Long,
    val transmittedBytes: Long
) {
    /** 当前UID上传和下载之和，供页面排序和占比条使用。 */
    val totalBytes: Long
        get() = receivedBytes + transmittedBytes
}

/**
 * 一次手机蜂窝流量查询的完整页面快照。
 *
 * @param period 查询周期。
 * @param startTimeMillis 查询开始Unix毫秒时间戳。
 * @param endTimeMillis 查询结束Unix毫秒时间戳。
 * @param receivedBytes 当前用户所有可查询UID的总下载字节数。
 * @param transmittedBytes 当前用户所有可查询UID的总上传字节数。
 * @param apps 按总流量从高到低排列的UID使用明细。
 * @param wifiConnected 查询完成时设备是否正通过Wi-Fi连接；不影响蜂窝统计范围。
 */
data class MobileDataUsageSnapshot(
    val period: MobileDataPeriod,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val apps: List<MobileDataAppUsage>,
    val wifiConnected: Boolean
) {
    /** 当前查询范围内蜂窝上传与下载总和。 */
    val totalBytes: Long
        get() = receivedBytes + transmittedBytes
}

/**
 * 手机流量查询失败时可由页面转换为用户提示的稳定错误类型。
 */
enum class MobileDataQueryError {
    USAGE_ACCESS_REQUIRED,
    SERVICE_UNAVAILABLE,
    QUERY_FAILED
}

/**
 * 手机蜂窝流量查询结果，确保页面能够区分空数据与系统错误。
 *
 * @param snapshot 查询成功时的完整快照，失败时为null。
 * @param error 查询失败类型，成功时为null。
 */
data class MobileDataQueryResult(
    val snapshot: MobileDataUsageSnapshot? = null,
    val error: MobileDataQueryError? = null
)

/**
 * 计算今日、本周或本月统计范围的本地开始时间。
 *
 * 使用方法：
 * Controller在后台查询NetworkStats前传入当前时间。今日从当地00:00开始；本周从当地周一
 * 00:00开始；本月从当地1号00:00开始，符合中文日历习惯且不会用固定毫秒数近似自然日。
 *
 * @param period 需要统计的今日、本周或本月周期。
 * @param nowMillis 当前Unix毫秒时间戳。
 * @param zoneId 计算自然日期使用的时区，默认使用设备当前时区。
 *
 * @return 对应周期开始时刻的Unix毫秒时间戳。
 */
fun calculateMobileDataPeriodStart(
    period: MobileDataPeriod,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val startDate = when (period) {
        MobileDataPeriod.TODAY -> now.toLocalDate()
        MobileDataPeriod.WEEK -> now.toLocalDate().with(
            TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        )
        MobileDataPeriod.MONTH -> now.toLocalDate().withDayOfMonth(1)
    }

    return startDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
}
