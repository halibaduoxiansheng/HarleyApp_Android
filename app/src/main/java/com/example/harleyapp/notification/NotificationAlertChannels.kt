package com.example.harleyapp.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.provider.Settings

/**
 * 统一创建本应用需要提示音和振动的高重要性通知渠道。
 *
 * 使用方法：
 * 在发布定时提醒或微信未查看提醒前，分别调用[createScheduledReminderChannel]或
 * [createWechatReminderChannel]。Android 8.0及以上会以渠道为单位保存声音、振动和重要性；
 * 同一渠道再次创建时不会覆盖用户在系统设置中的选择。
 */
object NotificationAlertChannels {

    /** 通用定时提醒使用的有声渠道ID；v2用于避开旧渠道已经固化的静音配置。 */
    const val SCHEDULED_REMINDER_CHANNEL_ID = "scheduled_local_reminders_alert_v2"

    /** 微信未查看消息提醒使用的有声渠道ID；v2用于避开旧渠道已经固化的静音配置。 */
    const val WECHAT_REMINDER_CHANNEL_ID = "wechat_unread_reminder_alert_v2"

    /**
     * 创建通用定时提醒渠道，默认使用系统通知提示音，并在系统允许时振动。
     *
     * @param notificationManager Android系统通知管理器。
     *
     * @return 无返回值；渠道已存在时保留用户在系统设置中修改过的声音与振动选项。
     */
    fun createScheduledReminderChannel(notificationManager: NotificationManager) {
        createAlertChannel(
            notificationManager = notificationManager,
            channelId = SCHEDULED_REMINDER_CHANNEL_ID,
            channelName = "定时提醒（声音和振动）",
            channelDescription = "按自定义日期、时间和重复间隔展示本地提醒"
        )
    }

    /**
     * 创建微信未查看消息提醒渠道，默认使用系统通知提示音，并在系统允许时振动。
     *
     * @param notificationManager Android系统通知管理器。
     *
     * @return 无返回值；渠道已存在时保留用户在系统设置中修改过的声音与振动选项。
     */
    fun createWechatReminderChannel(notificationManager: NotificationManager) {
        createAlertChannel(
            notificationManager = notificationManager,
            channelId = WECHAT_REMINDER_CHANNEL_ID,
            channelName = "微信未查看提醒（声音和振动）",
            channelDescription = "按自定义间隔提醒仍保留在通知栏中的普通微信消息"
        )
    }

    /**
     * 按统一规则创建一个高重要性提醒渠道。
     *
     * 提示音使用用户当前选择的系统默认通知音；振动仅声明本渠道允许振动，手机静音、勿扰、
     * 系统关闭振动或用户手动关闭本渠道振动时，Android仍会优先尊重系统和用户设置。
     *
     * @param notificationManager Android系统通知管理器。
     * @param channelId 不可变的通知渠道ID。
     * @param channelName 展示在系统通知设置页面中的渠道名称。
     * @param channelDescription 展示在系统通知设置页面中的渠道用途说明。
     *
     * @return 无返回值；创建结果由Android系统持久化管理。
     */
    private fun createAlertChannel(
        notificationManager: NotificationManager,
        channelId: String,
        channelName: String,
        channelDescription: String
    ) {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = channelDescription
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
            enableVibration(true)
            vibrationPattern = ALERT_VIBRATION_PATTERN
        }

        notificationManager.createNotificationChannel(channel)
    }

    /** 两段短振动组成的提醒节奏；首个0表示通知到达后立即开始振动。 */
    private val ALERT_VIBRATION_PATTERN = longArrayOf(0L, 280L, 160L, 280L)
}
