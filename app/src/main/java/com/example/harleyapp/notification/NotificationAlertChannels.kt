package com.example.harleyapp.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * 统一创建本应用需要横幅和振动的高重要性静音通知渠道。
 *
 * 使用方法：
 * 在发布定时提醒或微信未查看提醒前，分别调用[createScheduledReminderChannel]或
 * [createWechatReminderChannel]。渠道本身不绑定手机来电或系统通知铃声，HarleyApp由
 * [ReminderAlertPlaybackService]播放用户在App内选择的独立提示音；渠道仍负责横幅、锁屏、角标
 * 和系统振动权限。[createAll]可在App启动时提前创建全部渠道。
 */
object NotificationAlertChannels {

    /** 通用定时提醒使用的静音强提醒渠道ID；v5由App独立合成提示音。 */
    const val SCHEDULED_REMINDER_CHANNEL_ID = "scheduled_local_reminders_alert_v5"

    /** 微信未查看消息提醒使用的静音强提醒渠道ID；v5由App独立合成提示音。 */
    const val WECHAT_REMINDER_CHANNEL_ID = "wechat_unread_reminder_alert_v5"

    /**
     * 提前创建通用提醒和微信等待提醒的全部通知渠道。
     *
     * 使用方法：
     * MainActivity创建时调用一次；各广播接收器在发布通知前仍会再次调用对应创建函数，以兼容
     * 系统清理渠道或进程直接从后台启动的情况。重复调用由Android安全去重。
     *
     * @param notificationManager Android系统通知管理器。
     *
     * @return 无返回值；创建结果由Android系统持久化。
     */
    fun createAll(notificationManager: NotificationManager) {
        createScheduledReminderChannel(notificationManager)
        createWechatReminderChannel(notificationManager)
    }

    /**
     * 判断指定提醒渠道是否仍允许展示通知。
     *
     * 使用方法：
     * 发布通知前先确保渠道已创建，再调用本函数。Android返回null或重要性为IMPORTANCE_NONE时表示
     * 渠道不可用，接收器应保留提醒状态并稍后重试，而不能静默标记为已提醒。
     *
     * @param notificationManager Android系统通知管理器。
     * @param channelId 需要检查的提醒渠道ID。
     *
     * @return 渠道存在且未被用户关闭返回true，否则返回false。
     */
    fun isChannelEnabled(
        notificationManager: NotificationManager,
        channelId: String
    ): Boolean {
        val channel = notificationManager.getNotificationChannel(channelId) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * 创建通用定时提醒渠道，由App播放独立提示音并在系统允许时振动。
     *
     * @param notificationManager Android系统通知管理器。
     *
     * @return 无返回值；渠道已存在时保留用户在系统设置中修改过的通知展示和振动选项。
     */
    fun createScheduledReminderChannel(notificationManager: NotificationManager) {
        createAlertChannel(
            notificationManager = notificationManager,
            channelId = SCHEDULED_REMINDER_CHANNEL_ID,
            channelName = "定时强提醒（HarleyApp提示音）",
            channelDescription = "按自定义日期和时间在后台展示通知，由App播放独立提示音"
        )
    }

    /**
     * 创建微信未查看消息提醒渠道，由App播放独立提示音并在系统允许时振动。
     *
     * @param notificationManager Android系统通知管理器。
     *
     * @return 无返回值；渠道已存在时保留用户在系统设置中修改过的通知展示和振动选项。
     */
    fun createWechatReminderChannel(notificationManager: NotificationManager) {
        createAlertChannel(
            notificationManager = notificationManager,
            channelId = WECHAT_REMINDER_CHANNEL_ID,
            channelName = "微信未查看强提醒（HarleyApp提示音）",
            channelDescription = "按自定义间隔在后台展示通知，由App播放独立提示音"
        )
    }

    /**
     * 按统一规则创建一个高重要性提醒渠道。
     *
     * 渠道声音固定为null，防止系统来电铃声或通知铃声与HarleyApp内置提示音重叠。振动仅声明
     * 本渠道允许振动，手机勿扰、系统关闭振动或用户手动关闭本渠道振动时，Android仍会优先
     * 尊重系统和用户设置。
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
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = channelDescription
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
            // 声音由短时前台服务实时合成，渠道必须静音以避免与手机来电铃声重复播放。
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = ALERT_VIBRATION_PATTERN
        }

        notificationManager.createNotificationChannel(channel)
    }

    /** 两段短振动组成的提醒节奏；首个0表示通知到达后立即开始振动。 */
    private val ALERT_VIBRATION_PATTERN = longArrayOf(0L, 280L, 160L, 280L)
}
