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
import com.example.harleyapp.MainActivity
import com.example.harleyapp.R
import com.example.harleyapp.data.WechatReminderRepository

/**
 * 接收微信未查看消息Alarm并展示通用本地提醒。
 *
 * 使用方法：
 * 只由WechatReminderScheduler创建的显式PendingIntent触发。接收器每次先确认功能仍启用且存在
 * 待查看原微信通知，再展示不含联系人和正文的提醒，并为下一间隔重新安排单次Alarm。
 */
class WechatReminderReceiver : BroadcastReceiver() {

    /**
     * 校验提醒状态、发布本地通知并续约下一次检查。
     *
     * @param context 广播上下文。
     * @param intent AlarmManager传入的显式广播Intent。
     *
     * @return 无返回值；功能关闭或原微信通知已消失时立即取消整个提醒循环。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WechatReminderScheduler.ACTION_SHOW_WECHAT_REMINDER) {
            return
        }

        val applicationContext = context.applicationContext
        val repository = WechatReminderRepository(applicationContext)
        val scheduler = WechatReminderScheduler(applicationContext)
        val settings = repository.getSettings()
        val status = repository.getStatus()
        if (!settings.enabled || status.pendingNotificationCount <= 0) {
            scheduler.cancel()
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
        val notification = Notification.Builder(
            applicationContext,
            WechatReminderScheduler.CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("微信消息还没查看")
            .setContentText("你有${pendingCount}条微信通知仍在通知栏，点击打开微信。")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "你有${pendingCount}条微信通知仍在通知栏。查看或清除原微信通知后，重复提醒会自动停止。"
                )
            )
            .setContentIntent(createOpenWechatPendingIntent(applicationContext))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setNumber(pendingCount)
            .build()

        // 先移除同编号旧提醒再重新发布，确保每个用户设定的间隔都能重新触发提示音或振动。
        notificationManager.cancel(WechatReminderScheduler.NOTIFICATION_ID)
        notificationManager.notify(WechatReminderScheduler.NOTIFICATION_ID, notification)
        repository.recordReminderShown(System.currentTimeMillis())
        scheduler.schedule(settings.intervalMinutes)
        Log.i(TAG, "WeChat unread reminder displayed")
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
        val openIntent = context.packageManager
            .getLaunchIntentForPackage(WECHAT_PACKAGE_NAME)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ?: Intent(context, MainActivity::class.java)

        return PendingIntent.getActivity(
            context,
            OPEN_WECHAT_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val TAG = "WechatReminder"
        const val WECHAT_PACKAGE_NAME = "com.tencent.mm"
        const val OPEN_WECHAT_REQUEST_CODE = 41_083
    }
}
