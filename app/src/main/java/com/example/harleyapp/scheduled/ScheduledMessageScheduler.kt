package com.example.harleyapp.scheduled

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.harleyapp.model.ScheduledWechatMessage

/**
 * 使用Android AlarmManager安排或取消微信图文发送提醒。
 *
 * 使用方法：
 * 使用Application Context创建实例。计划保存成功后调用schedule；删除前调用cancel；
 * 手机重启完成后由ScheduledMessageBootReceiver调用reschedule恢复未来计划。
 * 本实现使用setAndAllowWhileIdle，不申请精确闹钟特殊权限，因此省电模式下可能延迟几分钟。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class ScheduledMessageScheduler(context: Context) {

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)

    /**
     * 为一条未来计划设置系统提醒。
     *
     * @param message 已经持久化且具有非零id的图文提醒计划。
     *
     * @return AlarmManager可用、计划时间仍在未来且设置完成时返回true，否则返回false。
     */
    fun schedule(message: ScheduledWechatMessage): Boolean {
        if (message.id <= 0L || message.scheduledAtMillis <= System.currentTimeMillis()) {
            return false
        }

        return try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                message.scheduledAtMillis,
                createAlarmPendingIntent(message.id)
            )
            Log.i(TAG, "Scheduled WeChat share reminder")
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "System rejected scheduled WeChat reminder", error)
            false
        }
    }

    /**
     * 取消指定计划对应的系统Alarm。
     *
     * @param messageId 本地计划唯一编号。
     *
     * @return 无返回值；不存在的Alarm会被安全忽略。
     */
    fun cancel(messageId: Long) {
        alarmManager.cancel(createAlarmPendingIntent(messageId))
        Log.i(TAG, "Canceled WeChat share reminder")
    }

    /**
     * 手机重启后恢复所有未来且尚未展示过提醒的计划。
     *
     * @param messages 本机持久化的全部图文提醒计划。
     *
     * @return 成功重新提交给AlarmManager的计划数量。
     */
    fun reschedule(messages: List<ScheduledWechatMessage>): Int {
        val nowMillis = System.currentTimeMillis()
        return messages.count { message ->
            message.scheduledAtMillis > nowMillis &&
                message.reminderShownAtMillis <= 0L &&
                schedule(message)
        }
    }

    /**
     * 创建可唯一识别计划编号的广播PendingIntent。
     *
     * @param messageId 计划唯一编号。
     *
     * @return 不可变且可重复定位同一计划的PendingIntent。
     */
    private fun createAlarmPendingIntent(messageId: Long): PendingIntent {
        val intent = Intent(applicationContext, ScheduledMessageReceiver::class.java)
            .setAction(ACTION_SHOW_SCHEDULED_MESSAGE)
            .putExtra(EXTRA_MESSAGE_ID, messageId)

        return PendingIntent.getBroadcast(
            applicationContext,
            messageId.toRequestCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 将64位计划编号折叠为PendingIntent使用的32位请求码。
     *
     * @return 同一计划稳定一致的请求码。
     */
    private fun Long.toRequestCode(): Int {
        return (this xor (this ushr 32)).toInt()
    }

    companion object {
        const val ACTION_SHOW_SCHEDULED_MESSAGE =
            "com.example.harleyapp.action.SHOW_SCHEDULED_MESSAGE"
        const val EXTRA_MESSAGE_ID = "scheduled_message_id"
        private const val TAG = "ScheduledMessage"
    }
}
