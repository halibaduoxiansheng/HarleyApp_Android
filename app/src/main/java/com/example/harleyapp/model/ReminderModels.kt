package com.example.harleyapp.model

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 通用通知支持的重复时间单位。
 *
 * 使用方法：
 * 页面把用户选择的枚举值与重复数值一起保存到[ScheduledReminder]；仓库通过[storageValue]
 * 持久化，并用[fromStorageValue]兼容无法识别或旧版本没有单位字段的数据。
 *
 * @param storageValue 写入本地JSON的稳定英文值，不能随界面文案变化。
 */
enum class ReminderRepeatUnit(val storageValue: String) {
    SECOND("second"),
    MINUTE("minute"),
    HOUR("hour"),
    DAY("day"),
    WEEK("week");

    companion object {

        /**
         * 从本地存储值恢复重复单位。
         *
         * @param value JSON中保存的英文单位；允许为null或未知值。
         *
         * @return 匹配的重复单位；旧数据或未知值回退为[DAY]。
         */
        fun fromStorageValue(value: String?): ReminderRepeatUnit {
            return entries.firstOrNull { unit ->
                unit.storageValue.equals(value, ignoreCase = true)
            } ?: DAY
        }
    }
}

/**
 * 一条由本应用在指定日期和时间展示的本地通知计划。
 *
 * 使用方法：
 * 新建计划时将[id]设为0，[ReminderRepository][com.example.harleyapp.data.ReminderRepository]
 * 会生成本机唯一编号；编辑计划时保留原编号。将[repeatIntervalValue]设为0表示只提醒一次，
 * 设为正数时按照[repeatIntervalUnit]对应的秒、分钟、小时、天或星期持续重复，直到用户删除。
 *
 * @param id 本机唯一计划编号，0表示尚未保存的新计划。
 * @param content 用户希望在通知栏看到的提醒内容。
 * @param nextTriggerAtMillis 下一次计划提醒的Unix毫秒时间戳。
 * @param repeatIntervalValue 重复间隔数值；0表示不重复，正数表示按所选单位重复。
 * @param repeatIntervalUnit 重复间隔单位；一次性计划也保留该值，便于编辑表单稳定回显。
 * @param createdAtMillis 计划首次创建的Unix毫秒时间戳，编辑时保持不变。
 * @param lastTriggeredAtMillis 最近一次Alarm实际触发的Unix毫秒时间戳；尚未触发时为0。
 */
data class ScheduledReminder(
    val id: Long,
    val content: String,
    val nextTriggerAtMillis: Long,
    val repeatIntervalValue: Int = 0,
    val repeatIntervalUnit: ReminderRepeatUnit = ReminderRepeatUnit.DAY,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastTriggeredAtMillis: Long = 0L
) {

    /** @return 重复间隔为正数时返回true，0表示一次性计划并返回false。 */
    val isRecurring: Boolean
        get() = repeatIntervalValue > 0
}

/**
 * 判断用户选择的首次提醒时间是否仍位于当前时刻之后。
 *
 * 使用方法：
 * 用户完成日期和分钟选择后，把转换得到的时间戳与一次读取的当前时间传入。本函数不再额外
 * 强制预留一分钟，因此当前8:00时选择8:01可以保存；已经到达或早于当前时刻时仍会拒绝。
 *
 * @param triggerAtMillis 用户选择的首次提醒Unix毫秒时间戳。
 * @param nowMillis 执行保存校验时读取的当前Unix毫秒时间戳。
 *
 * @return 提醒时刻严格晚于当前时刻返回true，否则返回false。
 */
fun isReminderTriggerInFuture(triggerAtMillis: Long, nowMillis: Long): Boolean {
    return triggerAtMillis > nowMillis
}

/**
 * 把只精确到分钟的表单时间解析为可以交给AlarmManager的首次触发时间。
 *
 * 使用方法：
 * 页面默认显示当前日期和当前时分。因为时间选择器不包含秒，用户立即保存时，所选分钟的
 * 整分时间通常已经比当前时刻早几十秒；本函数会把“仍处于当前分钟”的选择转换为一秒后
 * 触发。真正早于当前分钟的时间仍返回null，避免意外创建过期计划。
 *
 * @param selectedTriggerAtMillis 日期和分钟选择器转换出的Unix毫秒时间戳。
 * @param nowMillis 点击保存时的当前Unix毫秒时间戳。
 * @param zoneId 比较本地分钟使用的时区，默认使用设备当前时区。
 *
 * @return 可安全调度的未来时间；所选时间已经早于当前分钟时返回null。
 */
fun resolveInitialReminderTriggerAt(
    selectedTriggerAtMillis: Long,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long? {
    if (selectedTriggerAtMillis < 0L || nowMillis < 0L) {
        return null
    }
    if (selectedTriggerAtMillis > nowMillis) {
        return selectedTriggerAtMillis
    }

    val selectedMinute = Instant.ofEpochMilli(selectedTriggerAtMillis)
        .atZone(zoneId)
        .truncatedTo(ChronoUnit.MINUTES)
    val currentMinute = Instant.ofEpochMilli(nowMillis)
        .atZone(zoneId)
        .truncatedTo(ChronoUnit.MINUTES)
    return if (selectedMinute == currentMinute) {
        nowMillis + IMMEDIATE_REMINDER_DELAY_MILLIS
    } else {
        null
    }
}

/**
 * 计算重复通知在当前时刻之后的下一次提醒时间。
 *
 * 使用方法：
 * Alarm触发后，把当前计划时间、重复数值、单位和当前时间传入。秒、分钟、小时使用固定时长；
 * 天和星期按照设备当前时区增加当地日历日期，从而在夏令时变化时仍尽量保持用户选择的时分。
 * 如果手机关机或系统省电跨过多次提醒，会直接跳到当前时刻之后最近的一次，不连续补发。
 *
 * @param currentTriggerAtMillis 本次计划提醒的Unix毫秒时间戳。
 * @param repeatIntervalValue 重复间隔数值；必须为正整数才会计算下一次。
 * @param repeatIntervalUnit 重复间隔使用的秒、分钟、小时、天或星期单位。
 * @param nowMillis 用于判断是否已经过期的当前Unix毫秒时间戳。
 * @param zoneId 日历天和星期计算使用的时区，默认使用设备当前时区；测试时可传入固定时区。
 *
 * @return 重复计划在[nowMillis]之后的下一次Unix毫秒时间戳；不重复或输入无效时返回null。
 */
fun calculateNextReminderAt(
    currentTriggerAtMillis: Long,
    repeatIntervalValue: Int,
    repeatIntervalUnit: ReminderRepeatUnit,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): Long? {
    if (currentTriggerAtMillis <= 0L || repeatIntervalValue <= 0 || nowMillis < 0L) {
        return null
    }

    return when (repeatIntervalUnit) {
        ReminderRepeatUnit.SECOND,
        ReminderRepeatUnit.MINUTE,
        ReminderRepeatUnit.HOUR -> calculateFixedDurationNextTrigger(
            currentTriggerAtMillis = currentTriggerAtMillis,
            repeatIntervalValue = repeatIntervalValue,
            repeatIntervalUnit = repeatIntervalUnit,
            nowMillis = nowMillis
        )

        ReminderRepeatUnit.DAY,
        ReminderRepeatUnit.WEEK -> calculateCalendarNextTrigger(
            currentTriggerAtMillis = currentTriggerAtMillis,
            repeatIntervalValue = repeatIntervalValue,
            repeatIntervalUnit = repeatIntervalUnit,
            nowMillis = nowMillis,
            zoneId = zoneId
        )
    }
}

/**
 * 按固定毫秒周期推进秒、分钟或小时重复计划。
 *
 * @return 当前时刻之后最近的一次触发时间；数值溢出时返回null。
 */
private fun calculateFixedDurationNextTrigger(
    currentTriggerAtMillis: Long,
    repeatIntervalValue: Int,
    repeatIntervalUnit: ReminderRepeatUnit,
    nowMillis: Long
): Long? {
    val unitMillis = when (repeatIntervalUnit) {
        ReminderRepeatUnit.SECOND -> 1_000L
        ReminderRepeatUnit.MINUTE -> 60_000L
        ReminderRepeatUnit.HOUR -> 3_600_000L
        else -> return null
    }
    val intervalMillis = runCatching {
        Math.multiplyExact(unitMillis, repeatIntervalValue.toLong())
    }.getOrNull() ?: return null
    val elapsedMillis = (nowMillis - currentTriggerAtMillis).coerceAtLeast(0L)
    val elapsedIntervals = (elapsedMillis / intervalMillis) + 1L

    return runCatching {
        Math.addExact(
            currentTriggerAtMillis,
            Math.multiplyExact(intervalMillis, elapsedIntervals)
        )
    }.getOrNull()
}

/**
 * 按当地日历日期推进天或星期重复计划，避免夏令时切换造成固定小时偏移。
 *
 * @return 当前时刻之后最近的一次触发时间；输入单位不是天或星期时返回null。
 */
private fun calculateCalendarNextTrigger(
    currentTriggerAtMillis: Long,
    repeatIntervalValue: Int,
    repeatIntervalUnit: ReminderRepeatUnit,
    nowMillis: Long,
    zoneId: ZoneId
): Long? {
    val intervalDays = when (repeatIntervalUnit) {
        ReminderRepeatUnit.DAY -> repeatIntervalValue.toLong()
        ReminderRepeatUnit.WEEK -> repeatIntervalValue.toLong() * DAYS_PER_WEEK
        else -> return null
    }
    val currentTrigger = Instant.ofEpochMilli(currentTriggerAtMillis).atZone(zoneId)
    val nowInstant = Instant.ofEpochMilli(nowMillis)
    val nowDate = nowInstant.atZone(zoneId).toLocalDate()
    val elapsedCalendarDays = ChronoUnit.DAYS.between(currentTrigger.toLocalDate(), nowDate)
        .coerceAtLeast(0L)
    var elapsedIntervals = (elapsedCalendarDays / intervalDays).coerceAtLeast(1L)
    var nextTrigger = currentTrigger.plusDays(elapsedIntervals * intervalDays)

    if (!nextTrigger.toInstant().isAfter(nowInstant)) {
        elapsedIntervals += 1L
        nextTrigger = currentTrigger.plusDays(elapsedIntervals * intervalDays)
    }

    return nextTrigger.toInstant().toEpochMilli()
}

private const val IMMEDIATE_REMINDER_DELAY_MILLIS = 1_000L
private const val DAYS_PER_WEEK = 7L
