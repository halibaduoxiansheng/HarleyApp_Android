package com.example.harleyapp.scheduled

import android.Manifest
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
import com.example.harleyapp.data.ScheduledMessageRepository

/**
 * 接收系统到点Alarm并展示微信图文发送提醒通知。
 *
 * 使用方法：
 * 由ScheduledMessageScheduler创建显式广播PendingIntent，Android系统到点自动调用。
 * 本接收器只展示提醒，不会在后台打开微信或发送内容。
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    /**
     * 读取计划编号、确认计划仍存在并展示高优先级本地通知。
     *
     * @param context 广播上下文。
     * @param intent Scheduler创建的显式广播Intent。
     *
     * @return 无返回值；计划不存在或通知权限缺失时记录英文日志并安全退出。
     */
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ScheduledMessageScheduler.ACTION_SHOW_SCHEDULED_MESSAGE) {
            return
        }

        val messageId = intent.getLongExtra(ScheduledMessageScheduler.EXTRA_MESSAGE_ID, 0L)
        val repository = ScheduledMessageRepository(context.applicationContext)
        val message = repository.getMessage(messageId) ?: return
        if (message.reminderShownAtMillis > 0L) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notification permission missing for scheduled reminder")
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createNotificationChannel(notificationManager)
        val openShareIntent = Intent(context, ScheduledShareActivity::class.java)
            .putExtra(ScheduledMessageScheduler.EXTRA_MESSAGE_ID, message.id)
        val contentIntent = PendingIntent.getActivity(
            context,
            message.id.toRequestCode(),
            openShareIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentSummary = when {
            message.imageUri.isNotBlank() && message.messageText.isNotBlank() ->
                "图文内容已准备，点击复制文字并打开微信分享"
            message.imageUri.isNotBlank() -> "图片已准备，点击打开微信分享"
            else -> "文字已准备，点击复制并打开微信分享"
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.harleyapp.R.drawable.ic_launcher_foreground)
            .setContentTitle("微信发送提醒：${message.contactNote}")
            .setContentText(contentSummary)
            .setStyle(
                android.app.Notification.BigTextStyle().bigText(
                    "$contentSummary。联系人备注仅用于核对，请在微信中重新确认接收人后发送。"
                )
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setVisibility(android.app.Notification.VISIBILITY_PRIVATE)
            .build()

        notificationManager.notify(message.id.toRequestCode(), notification)
        repository.markReminderShown(message.id, System.currentTimeMillis())
        Log.i(TAG, "Scheduled WeChat share reminder displayed")
    }

    /**
     * 创建Android 8.0及以上所需的图文提醒通知渠道。
     *
     * @param notificationManager 系统NotificationManager。
     *
     * @return 无返回值；同名渠道已存在时系统保持用户原有设置。
     */
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "微信图文发送提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "提醒用户确认并通过微信分享预先准备的文字或图片"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 将64位计划编号折叠为通知和PendingIntent使用的32位编号。
     *
     * @return 同一计划稳定一致的32位编号。
     */
    private fun Long.toRequestCode(): Int {
        return (this xor (this ushr 32)).toInt()
    }

    private companion object {
        const val TAG = "ScheduledMessage"
        const val CHANNEL_ID = "wechat_scheduled_share"
    }
}
