package com.example.harleyapp.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.harleyapp.MainActivity
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.wallClockTriggerToElapsedRealtime
import com.example.harleyapp.system.ExactAlarmAccessController

/**
 * 使用Android AlarmManager安排、取消和恢复通用本地通知。
 *
 * 使用方法：
 * 使用Application Context创建实例。计划持久化成功后调用[schedule]；删除或修改旧计划前调用
 * [cancel]；手机重启完成后由ReminderBootReceiver调用[reschedule]恢复计划。获得“闹钟和提醒”
 * 特殊权限时使用系统闹钟级Alarm，确保用户明确创建的提醒不被电池策略长期推迟；尚未授权时
 * 保留非精确Alarm兜底，并由页面提示用户完成授权。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class ReminderScheduler(context: Context) {

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val exactAlarmAccessController = ExactAlarmAccessController(applicationContext)

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
     * 当通知权限暂时缺失或通知发布异常时，为同一计划安排一次短延迟重试。
     *
     * 使用方法：
     * ReminderReceiver尚未把计划标记为已触发时调用。仓库中的原计划时间保持不变，因此重试
     * 广播仍能通过计划编号读取到完整内容，并在通知真正发布成功后再更新状态。
     *
     * @param reminderId 本机通知计划唯一编号。
     * @param delayMillis 从当前时刻起延迟的毫秒数，内部至少限制为一秒。
     *
     * @return 系统接受重试Alarm返回true，否则返回false。
     */
    fun scheduleRetry(reminderId: Long, delayMillis: Long): Boolean {
        return scheduleAt(
            reminderId = reminderId,
            triggerAtMillis = System.currentTimeMillis() + delayMillis.coerceAtLeast(1_000L)
        )
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
                !reminder.isRecurring && reminder.lastTriggeredAtMillis > 0L -> false
                reminder.nextTriggerAtMillis > nowMillis -> schedule(reminder)
                else -> scheduleAt(reminder.id, nowMillis + MISSED_REMINDER_DELAY_MILLIS)
            }
        }
    }

    /**
     * 按权限状态把指定绝对时间提交为系统闹钟级Alarm或开机时钟兜底Alarm。
     *
     * 使用方法：
     * 调度、恢复和短时重试都通过本函数进入。获得精确闹钟权限时使用系统不会调整的
     * AlarmClock；尚未授权时根据当前时刻计算剩余延迟并提交开机时钟兜底Alarm。仓库始终保存
     * Unix时间戳供页面显示和系统时间变化后重新安排。
     *
     * @param reminderId 通知计划唯一编号。
     * @param triggerAtMillis 用户计划触发的Unix毫秒时间戳。
     *
     * @return Alarm提交成功返回true；时间无效或系统拒绝时返回false。
     */
    private fun scheduleAt(reminderId: Long, triggerAtMillis: Long): Boolean {
        val elapsedTriggerAtMillis = wallClockTriggerToElapsedRealtime(
            triggerAtMillis = triggerAtMillis,
            currentTimeMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime()
        )
        if (reminderId <= 0L || elapsedTriggerAtMillis == null) {
            return false
        }

        return try {
            val pendingIntent = createAlarmPendingIntent(reminderId)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                exactAlarmAccessController.isGranted()
            ) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(
                        triggerAtMillis,
                        createAlarmClockInfoPendingIntent(reminderId)
                    ),
                    pendingIntent
                )
                Log.i(TAG, "Scheduled alarm-clock local reminder")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    elapsedTriggerAtMillis,
                    pendingIntent
                )
                Log.w(TAG, "Scheduled inexact local reminder because exact access is missing")
            }
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
     * 创建系统闹钟标记被用户点击时打开HarleyApp的页面Intent。
     *
     * 使用方法：
     * 仅作为[AlarmManager.AlarmClockInfo]的展示入口，不负责触发提醒；真正的到点广播仍由
     * [createAlarmPendingIntent]提供，避免用户点击系统闹钟标记时提前发出通知。
     *
     * @param reminderId 通知计划唯一编号，用于稳定区分多个系统闹钟展示入口。
     *
     * @return 指向[MainActivity]的不可变PendingIntent。
     */
    private fun createAlarmClockInfoPendingIntent(reminderId: Long): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext,
            reminderId.toRequestCode() xor ALARM_CLOCK_INFO_REQUEST_CODE_SALT,
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
        private const val ALARM_CLOCK_INFO_REQUEST_CODE_SALT = 0x00100000
        private const val TAG = "ReminderScheduler"
        private const val MISSED_REMINDER_DELAY_MILLIS = 2_000L
    }
}
