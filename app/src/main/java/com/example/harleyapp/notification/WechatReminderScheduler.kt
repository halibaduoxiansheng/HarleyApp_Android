package com.example.harleyapp.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_INTERVAL_MINUTES

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

    /**
     * 从当前时刻起按指定分钟数安排一次允许待机执行的提醒广播。
     *
     * 本函数使用非精确Alarm，不申请精确闹钟权限；锁屏、省电或系统繁忙时实际时间可能稍晚。
     *
     * @param intervalMinutes 用户设置的提醒间隔，函数内部限制为5到1440分钟。
     *
     * @return 系统接受调度返回true；安全策略或系统异常导致失败时返回false。
     */
    fun schedule(intervalMinutes: Int): Boolean {
        val safeIntervalMinutes = intervalMinutes.coerceIn(
            MIN_WECHAT_REMINDER_INTERVAL_MINUTES,
            MAX_WECHAT_REMINDER_INTERVAL_MINUTES
        )
        val triggerAtElapsedMillis = SystemClock.elapsedRealtime() +
            safeIntervalMinutes * MILLIS_PER_MINUTE

        return try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedMillis,
                createReminderPendingIntent()
            )
            Log.i(TAG, "Next WeChat unread reminder scheduled")
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "System rejected WeChat reminder alarm", error)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to schedule WeChat reminder alarm", error)
            false
        }
    }

    /**
     * 取消尚未触发的提醒Alarm并移除本应用当前显示的微信未查看提醒。
     *
     * @return 无返回值。
     */
    fun cancel() {
        alarmManager.cancel(createReminderPendingIntent())
        notificationManager.cancel(NOTIFICATION_ID)
        Log.i(TAG, "WeChat unread reminder canceled")
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
    }
}
