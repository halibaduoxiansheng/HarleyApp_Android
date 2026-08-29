package com.example.harleyapp.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.harleyapp.model.ScheduledReminder

/**
 * 使用Android AlarmManager安排、取消和恢复通用本地通知。
 *
 * 使用方法：
 * 使用Application Context创建实例。计划持久化成功后调用[schedule]；删除或修改旧计划前调用
 * [cancel]；手机重启完成后由ReminderBootReceiver调用[reschedule]恢复计划。本实现使用
 * setAndAllowWhileIdle，不申请精确闹钟特殊权限，因此部分系统的省电策略可能使通知延迟几分钟。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class ReminderScheduler(context: Context) {

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    /**
     * 为一条已保存且时间仍在未来的计划提交系统Alarm。
     *
     * @param reminder 已经持久化并具有正数id的通知计划。
     *
     * @return 参数有效且Alarm提交成功返回true，否则返回false并记录英文日志。
     */
    fun schedule(reminder: ScheduledReminder): Boolean {
        if (reminder.id <= 0L || reminder.nextTriggerAtMillis <= System.currentTimeMillis()) {
            return false
        }

        return scheduleAt(reminder.id, reminder.nextTriggerAtMillis)
    }

    /**
     * 取消指定计划当前对应的系统Alarm。
     *
     * @param reminderId 本机通知计划唯一编号。
     *
     * @return 无返回值；Alarm不存在时由系统安全忽略。
     */
    fun cancel(reminderId: Long) {
        alarmManager.cancel(createAlarmPendingIntent(reminderId))
        Log.i(TAG, "Canceled scheduled reminder")
    }

    /**
     * 手机重启后恢复所有仍应执行的计划。
     *
     * 使用方法：
     * 传入仓库的完整查询结果。未来计划按原时间恢复；手机关机期间错过的一次性或重复计划
     * 会在开机后尽快触发一次，其中重复计划随后自动推进到未来最近的一次，不连续补发。
     * 已经触发过的一次性计划不会恢复。
     *
     * @param reminders 本机持久化的全部通用通知计划。
     *
     * @return 成功重新提交给AlarmManager的计划数量。
     */
    fun reschedule(reminders: List<ScheduledReminder>): Int {
        val nowMillis = System.currentTimeMillis()
        return reminders.count { reminder ->
            when {
                reminder.repeatIntervalDays == 0 && reminder.lastTriggeredAtMillis > 0L -> false
                reminder.nextTriggerAtMillis > nowMillis -> schedule(reminder)
                else -> scheduleAt(reminder.id, nowMillis + MISSED_REMINDER_DELAY_MILLIS)
            }
        }
    }

    /**
     * 在指定绝对时间提交一个显式广播Alarm。
     *
     * @param reminderId 通知计划唯一编号。
     * @param triggerAtMillis 实际交给AlarmManager的Unix毫秒时间戳。
     *
     * @return Alarm提交成功返回true；时间无效或系统拒绝时返回false。
     */
    private fun scheduleAt(reminderId: Long, triggerAtMillis: Long): Boolean {
        if (reminderId <= 0L || triggerAtMillis <= System.currentTimeMillis()) {
            return false
        }

        return try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                createAlarmPendingIntent(reminderId)
            )
            Log.i(TAG, "Scheduled local reminder")
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "System rejected scheduled reminder", error)
            false
        }
    }

    /**
     * 创建能够按计划编号稳定定位和替换同一Alarm的不可变PendingIntent。
     *
     * @param reminderId 通知计划唯一编号。
     *
     * @return 指向ReminderReceiver的显式广播PendingIntent。
     */
    private fun createAlarmPendingIntent(reminderId: Long): PendingIntent {
        val intent = Intent(applicationContext, ReminderReceiver::class.java)
            .setAction(ACTION_SHOW_REMINDER)
            .putExtra(EXTRA_REMINDER_ID, reminderId)

        return PendingIntent.getBroadcast(
            applicationContext,
            reminderId.toRequestCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 将64位计划编号稳定折叠为带模块盐值的32位请求码。
     *
     * @return 同一通用通知计划固定一致、并与微信定时提醒区分的请求码。
     */
    private fun Long.toRequestCode(): Int {
        return (this xor (this ushr 32)).toInt() xor REQUEST_CODE_SALT
    }

    companion object {
        const val ACTION_SHOW_REMINDER = "com.example.harleyapp.action.SHOW_LOCAL_REMINDER"
        const val EXTRA_REMINDER_ID = "scheduled_reminder_id"
        const val REQUEST_CODE_SALT = 0x524D0000
        private const val TAG = "ReminderScheduler"
        private const val MISSED_REMINDER_DELAY_MILLIS = 2_000L
    }
}
