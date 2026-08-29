package com.example.harleyapp.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.harleyapp.R
import com.example.harleyapp.data.WechatReminderRepository

/**
 * 接收微信未查看消息Alarm并展示通用本地提醒。
 *
 * 使用方法：
 * 只由WechatReminderScheduler创建的显式PendingIntent触发。接收器每次先确认功能仍启用且存在
 * 待提醒状态，再展示不含联系人和正文的高优先级通知。本轮提醒成功后清除倒计时；收到新的
 * 普通微信消息时，通知监听服务会重新开始下一轮倒计时。
 */
class WechatReminderReceiver : BroadcastReceiver() {

    /**
     * 校验提醒状态、发布本地通知并续约下一次检查。
     *
     * @param context 广播上下文。
     * @param intent AlarmManager传入的显式广播Intent。
     *
     * @return 无返回值；功能关闭或当前没有待提醒消息时立即取消过期Alarm。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        val applicationContext = context.applicationContext
        val repository = WechatReminderRepository(applicationContext)
        val scheduler = WechatReminderScheduler(applicationContext)
        if (intent?.action == ACTION_STOP_WECHAT_REMINDERS) {
            repository.clearPendingNotifications()
            scheduler.cancel()
            Log.i(TAG, "WeChat reminder cycle stopped by user")
            return
        }
        if (intent?.action != WechatReminderScheduler.ACTION_SHOW_WECHAT_REMINDER) {
            return
        }

        val settings = repository.getSettings()
        val status = repository.getStatus()
        if (!settings.enabled || status.pendingNotificationCount <= 0) {
            if (settings.enabled) {
                scheduler.cancelPendingAlarm()
            } else {
                scheduler.cancel()
            }
            return
        }

        val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        if (!canPostNotification) {
            Log.w(TAG, "Notification permission missing for WeChat unread reminder")
            scheduler.schedule(settings.intervalMinutes)
            return
        }

        val notificationManager =
            applicationContext.getSystemService(NotificationManager::class.java)
        createNotificationChannel(notificationManager)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w(TAG, "App notifications disabled for WeChat unread reminder")
            scheduler.schedule(settings.intervalMinutes)
            return
        }

        val pendingCount = status.pendingNotificationCount
        val currentNotificationNumber = status.notificationsShownInCycle + 1
        val countDescription = if (settings.notificationCount == REPEAT_CONTINUOUSLY_COUNT) {
            "持续提醒 · 第${currentNotificationNumber}次"
        } else {
            "第${currentNotificationNumber}/${settings.notificationCount}次提醒"
        }
        val notification = Notification.Builder(
            applicationContext,
            WechatReminderScheduler.CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle("该查看微信消息了")
            .setContentText("$countDescription，你有${pendingCount}条微信消息需要查看。")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "等待时间已到，你有${pendingCount}条微信消息需要查看。点击这条提醒可直接打开微信。"
                )
            )
            .setContentIntent(createOpenWechatPendingIntent(applicationContext))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setNumber(pendingCount)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "停止后续提醒",
                    createStopReminderPendingIntent(applicationContext)
                ).build()
            )
            .build()

        // 先移除同编号旧提醒再重新发布，确保每个用户设定的间隔都能重新触发提示音或振动。
        notificationManager.cancel(WechatReminderScheduler.NOTIFICATION_ID)
        notificationManager.notify(WechatReminderScheduler.NOTIFICATION_ID, notification)
        val updatedStatus = repository.recordReminderShown(System.currentTimeMillis())
        if (updatedStatus.pendingNotificationCount > 0) {
            scheduler.schedule(settings.intervalMinutes)
        }
        Log.i(TAG, "WeChat waiting reminder displayed")
    }

    /**
     * 创建高重要性微信未查看消息提醒渠道。
     *
     * @param notificationManager 系统NotificationManager。
     *
     * @return 无返回值；渠道已存在时系统保留用户自己的声音、振动和重要性设置。
     */
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            WechatReminderScheduler.CHANNEL_ID,
            "微信未查看消息提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "按自定义间隔提醒仍保留在通知栏中的普通微信消息"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 创建点击提醒后打开微信的PendingIntent；微信不可用时回退到本应用首页。
     *
     * @param context Android上下文。
     *
     * @return 可由系统通知安全触发的不可变Activity PendingIntent。
     */
    private fun createOpenWechatPendingIntent(context: Context): PendingIntent {
        val openIntent = Intent(context, WechatReminderOpenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        return PendingIntent.getActivity(
            context,
            OPEN_WECHAT_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 创建通知操作按钮使用的停止广播PendingIntent。
     *
     * @param context Android上下文。
     *
     * @return 点击后清除本轮待提醒状态并取消后续Alarm的不可变PendingIntent。
     */
    private fun createStopReminderPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WechatReminderReceiver::class.java)
            .setAction(ACTION_STOP_WECHAT_REMINDERS)
        return PendingIntent.getBroadcast(
            context,
            STOP_REMINDER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val TAG = "WechatReminder"
        const val OPEN_WECHAT_REQUEST_CODE = 41_083
        const val STOP_REMINDER_REQUEST_CODE = 41_084
        const val ACTION_STOP_WECHAT_REMINDERS =
            "com.example.harleyapp.action.STOP_WECHAT_REMINDERS"
        const val REPEAT_CONTINUOUSLY_COUNT = 0
    }
}
