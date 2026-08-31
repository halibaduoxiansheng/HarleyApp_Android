package com.example.harleyapp.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.notification.NotificationAlertChannels
import com.example.harleyapp.notification.ReminderAlertPlaybackService

/**
 * 接收系统到点Alarm，推进重复计划并展示用户自定义内容的本地通知。
 *
 * 使用方法：
 * 由ReminderScheduler创建显式广播PendingIntent，Android系统到点自动调用。接收器不会执行
 * 网络请求；点击通知只打开本应用。重复计划每次只提交下一次Alarm，避免一次创建大量任务。
 */
class ReminderReceiver : BroadcastReceiver() {

    /**
     * 校验计划、原子推进状态，并在通知权限允许时展示高优先级提醒。
     *
     * @param context Android广播上下文。
     * @param intent ReminderScheduler创建的显式广播Intent。
     *
     * @return 无返回值；计划已删除、旧Alarm已失效或计划已经处理时安全退出。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ReminderScheduler.ACTION_SHOW_REMINDER) {
            return
        }

        val reminderId = intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, 0L)
        val applicationContext = context.applicationContext
        val repository = ReminderRepository(applicationContext)
        val reminder = repository.getReminder(reminderId) ?: return
        val nowMillis = System.currentTimeMillis()

        if (reminder.nextTriggerAtMillis > nowMillis + EARLY_ALARM_TOLERANCE_MILLIS) {
            Log.w(TAG, "Ignored stale reminder alarm")
            return
        }

        val scheduler = ReminderScheduler(applicationContext)
        if (!canPostNotifications(context)) {
            Log.w(TAG, "Notification permission missing for local reminder")
            scheduler.scheduleRetry(reminder.id, NOTIFICATION_RETRY_DELAY_MILLIS)
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        NotificationAlertChannels.createScheduledReminderChannel(notificationManager)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "App notifications disabled for local reminder")
            scheduler.scheduleRetry(reminder.id, NOTIFICATION_RETRY_DELAY_MILLIS)
            return
        }
        if (!NotificationAlertChannels.isChannelEnabled(
                notificationManager,
                NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
            )
        ) {
            Log.w(TAG, "Scheduled reminder channel is disabled")
            scheduler.scheduleRetry(reminder.id, NOTIFICATION_RETRY_DELAY_MILLIS)
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            reminder.id.toNotificationId(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(
            context,
            NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("定时提醒")
            .setContentText(reminder.content)
            .setStyle(Notification.BigTextStyle().bigText(reminder.content))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOnlyAlertOnce(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()

        val notificationDisplayed = runCatching {
            notificationManager.notify(reminder.id.toNotificationId(), notification)
            true
        }.onSuccess {
            Log.i(TAG, "Displayed scheduled local reminder")
        }.onFailure { error ->
            Log.e(TAG, "Failed to display scheduled reminder", error)
        }.getOrDefault(false)
        if (!notificationDisplayed) {
            scheduler.scheduleRetry(reminder.id, NOTIFICATION_RETRY_DELAY_MILLIS)
            return
        }
        if (!ReminderAlertPlaybackService.start(
                context = applicationContext,
                alertChannelId = NotificationAlertChannels.SCHEDULED_REMINDER_CHANNEL_ID
            )
        ) {
            Log.e(TAG, "Failed to start scheduled reminder alert playback")
        }

        // 只有系统通知成功发布后才更新“已提醒”状态，避免权限或渠道问题让计划静默丢失。
        val updatedReminder = repository.recordTriggered(
            reminderId = reminder.id,
            expectedTriggerAtMillis = reminder.nextTriggerAtMillis,
            triggeredAtMillis = nowMillis
        ) ?: return

        if (updatedReminder.isRecurring && !scheduler.schedule(updatedReminder)) {
            Log.e(TAG, "Failed to schedule next recurring reminder")
        }
    }

    /**
     * 判断当前Android版本是否允许本应用发布通知。
     *
     * @param context 用于查询POST_NOTIFICATIONS权限的Android上下文。
     *
     * @return Android 13以下固定返回true；Android 13及以上已授权时返回true。
     */
    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 将64位计划编号转换为与微信提醒区分的稳定通知编号。
     *
     * @return 同一通用通知计划固定一致的32位通知编号。
     */
    private fun Long.toNotificationId(): Int {
        return (this xor (this ushr 32)).toInt() xor ReminderScheduler.REQUEST_CODE_SALT
    }

    private companion object {
        const val TAG = "ReminderReceiver"
        const val EARLY_ALARM_TOLERANCE_MILLIS = 60_000L
        const val NOTIFICATION_RETRY_DELAY_MILLIS = 60_000L
    }
}
