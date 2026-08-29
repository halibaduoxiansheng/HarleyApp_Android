package com.example.harleyapp.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.system.ExactAlarmAccessController

/**
 * 使用AlarmManager安排下一次微信未查看消息提醒。
 *
 * 使用方法：
 * 通知监听服务记录普通微信消息后调用schedule；提醒广播每次展示完成后再次调用schedule形成
 * 可变间隔的单次循环。原微信通知消失或用户关闭功能时调用cancel，同时移除当前本地提醒。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class WechatReminderScheduler(context: Context) {

    private val applicationContext = context.applicationContext
    private val alarmManager = applicationContext.getSystemService(AlarmManager::class.java)
    private val notificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)
    private val repository = WechatReminderRepository(applicationContext)
    private val exactAlarmAccessController = ExactAlarmAccessController(applicationContext)

    /**
     * 从当前时刻起按指定分钟数安排一次允许待机执行的提醒广播，并持久化准确倒计时时间。
     *
     * 已获得“闹钟和提醒”特殊权限时使用精确Alarm；尚未授权时仍提交非精确Alarm作为兜底，
     * 页面会明确提示用户授权，避免计划静默丢失。
     *
     * @param intervalMinutes 用户设置的提醒间隔，函数内部限制为1到1440分钟。
     *
     * @return 系统接受调度返回true；安全策略或系统异常导致失败时返回false。
     */
    fun schedule(intervalMinutes: Int): Boolean {
        val safeIntervalMinutes = intervalMinutes.coerceIn(
            MIN_WECHAT_REMINDER_INTERVAL_MINUTES,
            MAX_WECHAT_REMINDER_INTERVAL_MINUTES
        )
        val triggerAtMillis = System.currentTimeMillis() +
            safeIntervalMinutes * MILLIS_PER_MINUTE

        return scheduleAt(triggerAtMillis)
    }

    /**
     * 恢复页面或进程先前保存的微信等待提醒倒计时。
     *
     * 使用方法：
     * App启动、通知监听服务重连或精确闹钟权限返回后调用。未来时间保持不变；如果原时间已经
     * 过去，则在两秒后尽快补发一次，避免用户永远停留在“等待提醒”。
     *
     * @param intervalMinutes 当前配置的提醒间隔；没有已保存时间时用它建立新倒计时。
     * @param savedTriggerAtMillis 仓库保存的下一次提醒Unix毫秒时间戳；0表示没有保存。
     *
     * @return 系统接受Alarm且倒计时时间保存成功返回true，否则返回false。
     */
    fun restore(intervalMinutes: Int, savedTriggerAtMillis: Long): Boolean {
        val fallbackTriggerAtMillis = System.currentTimeMillis() +
            intervalMinutes.coerceIn(
                MIN_WECHAT_REMINDER_INTERVAL_MINUTES,
                MAX_WECHAT_REMINDER_INTERVAL_MINUTES
            ) * MILLIS_PER_MINUTE
        val triggerAtMillis = when {
            savedTriggerAtMillis <= 0L -> fallbackTriggerAtMillis
            savedTriggerAtMillis <= System.currentTimeMillis() ->
                System.currentTimeMillis() + MISSED_REMINDER_DELAY_MILLIS
            else -> savedTriggerAtMillis
        }

        return scheduleAt(triggerAtMillis)
    }

    /**
     * 在指定绝对时间提交微信等待提醒，并把该时间同步写入仓库。
     *
     * @param triggerAtMillis 下一次提醒的Unix毫秒时间戳。
     *
     * @return Alarm提交和状态保存都成功返回true，否则返回false。
     */
    private fun scheduleAt(triggerAtMillis: Long): Boolean {
        if (triggerAtMillis <= System.currentTimeMillis()) {
            return false
        }

        return try {
            val pendingIntent = createReminderPendingIntent()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                exactAlarmAccessController.isGranted()
            ) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i(TAG, "Exact WeChat reminder scheduled")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.w(TAG, "Inexact WeChat reminder scheduled because exact access is missing")
            }

            repository.recordNextReminderAt(triggerAtMillis)
        } catch (error: SecurityException) {
            Log.e(TAG, "System rejected WeChat reminder alarm", error)
            repository.clearNextReminderAt()
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to schedule WeChat reminder alarm", error)
            repository.clearNextReminderAt()
            false
        }
    }

    /**
     * 取消尚未触发的提醒Alarm并移除本应用当前显示的微信未查看提醒。
     *
     * @return 无返回值。
     */
    fun cancel() {
        cancelPendingAlarm()
        notificationManager.cancel(NOTIFICATION_ID)
        Log.i(TAG, "WeChat unread reminder canceled")
    }

    /**
     * 只取消尚未触发的Alarm和倒计时时间，保留已经成功展示在通知栏中的本应用提醒。
     *
     * 使用方法：
     * 过期Alarm、空待提醒集合或通知监听服务重新连接时调用。用户主动关闭整个功能时应调用
     * [cancel]，同时移除已经展示的通知。
     *
     * @return 无返回值。
     */
    fun cancelPendingAlarm() {
        alarmManager.cancel(createReminderPendingIntent())
        repository.clearNextReminderAt()
        Log.i(TAG, "Pending WeChat reminder alarm canceled")
    }

    /**
     * 创建固定身份的显式广播PendingIntent，保证重复安排会替换旧Alarm且取消时能够准确匹配。
     *
     * @return 指向WechatReminderReceiver的不可变PendingIntent。
     */
    private fun createReminderPendingIntent(): PendingIntent {
        val intent = Intent(applicationContext, WechatReminderReceiver::class.java)
            .setAction(ACTION_SHOW_WECHAT_REMINDER)
        return PendingIntent.getBroadcast(
            applicationContext,
            REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_SHOW_WECHAT_REMINDER =
            "com.example.harleyapp.action.SHOW_WECHAT_UNREAD_REMINDER"
        const val CHANNEL_ID = "wechat_unread_reminder"
        const val NOTIFICATION_ID = 41_082
        private const val TAG = "WechatReminder"
        private const val REMINDER_REQUEST_CODE = 41_081
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MISSED_REMINDER_DELAY_MILLIS = 2_000L
    }
}
