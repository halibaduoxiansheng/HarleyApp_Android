package com.example.harleyapp.model

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 一条由本应用在指定日期和时间展示的本地通知计划。
 *
 * 使用方法：
 * 新建计划时将[id]设为0，[ReminderRepository][com.example.harleyapp.data.ReminderRepository]
 * 会生成本机唯一编号；编辑计划时保留原编号。将[repeatIntervalDays]设为0表示只提醒一次，
 * 设为1到365表示每隔对应天数重复提醒，直到用户删除计划。
 *
 * @param id 本机唯一计划编号，0表示尚未保存的新计划。
 * @param content 用户希望在通知栏看到的提醒内容。
 * @param nextTriggerAtMillis 下一次计划提醒的Unix毫秒时间戳。
 * @param repeatIntervalDays 重复间隔天数；0表示不重复，1到365表示按天重复。
 * @param createdAtMillis 计划首次创建的Unix毫秒时间戳，编辑时保持不变。
 * @param lastTriggeredAtMillis 最近一次Alarm实际触发的Unix毫秒时间戳；尚未触发时为0。
 */
data class ScheduledReminder(
    val id: Long,
    val content: String,
    val nextTriggerAtMillis: Long,
    val repeatIntervalDays: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastTriggeredAtMillis: Long = 0L
)

/**
 * 计算重复通知在当前时刻之后的下一次提醒时间。
 *
 * 使用方法：
 * Alarm触发后，把当前计划时间、重复间隔和当前时间传入。函数按照设备当前时区执行
 * “当地日期加N天”，从而尽量保持用户选择的时分；如果手机关机跨过多次提醒，
 * 会直接跳到当前时刻之后最近的一次，不会连续补发多条过期通知。
 *
 * @param currentTriggerAtMillis 本次计划提醒的Unix毫秒时间戳。
 * @param repeatIntervalDays 重复间隔天数；必须为正整数才会计算下一次。
 * @param nowMillis 用于判断是否已经过期的当前Unix毫秒时间戳。
 * @param zoneId 计算当地日期使用的时区，默认使用设备当前时区；测试时可传入固定时区。
 *
 * @return 重复计划在[nowMillis]之后的下一次Unix毫秒时间戳；不重复或输入无效时返回null。
 */
fun calculateNextReminderAt(
    currentTriggerAtMillis: Long,
    repeatIntervalDays: Int,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long? {
    if (currentTriggerAtMillis <= 0L || repeatIntervalDays <= 0 || nowMillis < 0L) {
        return null
    }

    val currentTrigger = Instant.ofEpochMilli(currentTriggerAtMillis).atZone(zoneId)
    val nowInstant = Instant.ofEpochMilli(nowMillis)
    val nowDate = nowInstant.atZone(zoneId).toLocalDate()
    val elapsedCalendarDays = ChronoUnit.DAYS.between(currentTrigger.toLocalDate(), nowDate)
        .coerceAtLeast(0L)
    var elapsedIntervals = (elapsedCalendarDays / repeatIntervalDays).coerceAtLeast(1L)
    var nextTrigger = currentTrigger.plusDays(elapsedIntervals * repeatIntervalDays.toLong())

    if (!nextTrigger.toInstant().isAfter(nowInstant)) {
        elapsedIntervals += 1L
        nextTrigger = currentTrigger.plusDays(elapsedIntervals * repeatIntervalDays.toLong())
    }

    return nextTrigger.toInstant().toEpochMilli()
}
