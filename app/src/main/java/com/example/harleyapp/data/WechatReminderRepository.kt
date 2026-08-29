package com.example.harleyapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.harleyapp.model.DEFAULT_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.DEFAULT_WECHAT_REMINDER_NOTIFICATION_COUNT
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_NOTIFICATION_COUNT
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_NOTIFICATION_COUNT
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus

/**
 * 在本机保存微信未查看消息提醒配置和匿名通知状态。
 *
 * 使用方法：
 * 使用Application Context创建实例。设置页面调用getSettings和saveSettings；通知监听服务调用
 * trackNotification、removeNotification与syncActiveNotifications；提醒接收器展示成功后调用
 * recordReminderShown。待处理集合只保存Android通知键，不保存联系人或聊天正文。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 * @param preferenceName 保存本功能的SharedPreferences名称；正式代码使用默认值，测试可传隔离名称。
 */
class WechatReminderRepository(
    context: Context,
    preferenceName: String = PREFERENCE_NAME
) {

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        preferenceName,
        Context.MODE_PRIVATE
    )

    init {
        // 新功能不再读取或发送自动回复文本，升级后同步删除旧自动回复配置文件。
        applicationContext.deleteSharedPreferences(LEGACY_AUTO_REPLY_PREFERENCE_NAME)
    }

    /**
     * 读取并限制当前微信未查看消息提醒配置。
     *
     * @return 可直接供页面、通知服务和Alarm调度器使用的配置。
     */
    @Synchronized
    fun getSettings(): WechatReminderSettings {
        return WechatReminderSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            intervalMinutes = preferences.getInt(
                KEY_INTERVAL_MINUTES,
                DEFAULT_WECHAT_REMINDER_INTERVAL_MINUTES
            ).coerceIn(
                MIN_WECHAT_REMINDER_INTERVAL_MINUTES,
                MAX_WECHAT_REMINDER_INTERVAL_MINUTES
            ),
            notificationCount = preferences.getInt(
                KEY_NOTIFICATION_COUNT,
                DEFAULT_WECHAT_REMINDER_NOTIFICATION_COUNT
            ).coerceIn(
                MIN_WECHAT_REMINDER_NOTIFICATION_COUNT,
                MAX_WECHAT_REMINDER_NOTIFICATION_COUNT
            )
        )
    }

    /**
     * 保存完整提醒配置；关闭功能时同时清空待提醒通知，避免旧Alarm继续打扰。
     *
     * @param settings 页面提交的新配置，间隔会被限制在1到1440分钟。
     *
     * @return 同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun saveSettings(settings: WechatReminderSettings): Boolean {
        val editor = preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(
                KEY_INTERVAL_MINUTES,
                settings.intervalMinutes.coerceIn(
                    MIN_WECHAT_REMINDER_INTERVAL_MINUTES,
                    MAX_WECHAT_REMINDER_INTERVAL_MINUTES
                )
            )
            .putInt(
                KEY_NOTIFICATION_COUNT,
                settings.notificationCount.coerceIn(
                    MIN_WECHAT_REMINDER_NOTIFICATION_COUNT,
                    MAX_WECHAT_REMINDER_NOTIFICATION_COUNT
                )
            )

        if (!settings.enabled) {
            editor.remove(KEY_PENDING_NOTIFICATION_KEYS)
            editor.remove(KEY_NEXT_REMINDER_AT)
            editor.remove(KEY_NOTIFICATIONS_SHOWN_IN_CYCLE)
        }

        val success = editor.commit()
        if (!success) {
            Log.e(TAG, "Failed to persist WeChat reminder settings")
        }
        return success
    }

    /**
     * 读取当前匿名提醒状态。
     *
     * @return 待查看通知数量、最近消息时间和最近提醒时间，不包含微信消息明文。
     */
    @Synchronized
    fun getStatus(): WechatReminderStatus {
        return WechatReminderStatus(
            pendingNotificationCount = readPendingNotificationKeys().size,
            lastMessageAtMillis = preferences.getLong(KEY_LAST_MESSAGE_AT, 0L),
            lastReminderAtMillis = preferences.getLong(KEY_LAST_REMINDER_AT, 0L),
            nextReminderAtMillis = preferences.getLong(KEY_NEXT_REMINDER_AT, 0L),
            notificationsShownInCycle = preferences.getInt(
                KEY_NOTIFICATIONS_SHOWN_IN_CYCLE,
                0
            ).coerceAtLeast(0)
        )
    }

    /**
     * 记录一条当前仍在通知栏中的普通微信消息通知。
     *
     * 同一个Android通知键更新时不会增加数量，但会刷新最近消息时间，让提醒间隔从最新消息重新计算。
     *
     * @param notificationKey Android提供的通知唯一键，不得包含自行拼接的消息正文。
     * @param receivedAtMillis 通知发布或更新的Unix毫秒时间戳。
     *
     * @return 写入后的提醒状态；通知键为空时保持原状态。
     */
    @Synchronized
    fun trackNotification(
        notificationKey: String,
        receivedAtMillis: Long
    ): WechatReminderStatus {
        if (notificationKey.isBlank()) {
            return getStatus()
        }

        val pendingKeys = readPendingNotificationKeys().apply {
            add(notificationKey)
        }
        val success = preferences.edit()
            .putStringSet(KEY_PENDING_NOTIFICATION_KEYS, pendingKeys)
            .putLong(KEY_LAST_MESSAGE_AT, receivedAtMillis.coerceAtLeast(0L))
            .putInt(KEY_NOTIFICATIONS_SHOWN_IN_CYCLE, 0)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to track pending WeChat notification")
        }
        return getStatus()
    }

    /**
     * 在微信原通知被打开、清除或由微信撤销时移除对应待提醒项。
     *
     * @param notificationKey Android提供的原通知唯一键。
     *
     * @return 移除后的提醒状态；键不存在时返回未修改状态。
     */
    @Synchronized
    fun removeNotification(notificationKey: String): WechatReminderStatus {
        val pendingKeys = readPendingNotificationKeys()
        if (!pendingKeys.remove(notificationKey)) {
            return getStatus()
        }

        val success = preferences.edit()
            .putStringSet(KEY_PENDING_NOTIFICATION_KEYS, pendingKeys)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to remove pending WeChat notification")
        }
        return getStatus()
    }

    /**
     * 用通知监听服务当前可见的微信通知重新校准待提醒集合。
     *
     * 使用方法：
     * 通知服务每次重新连接时调用，清除进程退出期间已经消失的通知，避免旧状态产生误提醒。
     *
     * @param notificationKeys 当前仍活跃且符合提醒策略的Android通知键集合。
     * @param latestMessageAtMillis 当前活跃集合中的最近发布时间；集合为空时不会覆盖历史时间。
     *
     * @return 校准后的提醒状态。
     */
    @Synchronized
    fun syncActiveNotifications(
        notificationKeys: Set<String>,
        latestMessageAtMillis: Long
    ): WechatReminderStatus {
        // 微信会在合并会话、角标变化时主动撤换旧通知。这里使用并集合并，而不是用当前通知栏
        // 覆盖本应用已经保存的待提醒项，确保页面退出、服务重连或微信更新通知后倒计时仍保留。
        val mergedNotificationKeys = readPendingNotificationKeys().apply {
            addAll(notificationKeys.filter(String::isNotBlank))
        }
        val editor = preferences.edit()
            .putStringSet(KEY_PENDING_NOTIFICATION_KEYS, mergedNotificationKeys)
        if (mergedNotificationKeys.isNotEmpty() && latestMessageAtMillis > 0L) {
            editor.putLong(
                KEY_LAST_MESSAGE_AT,
                maxOf(
                    preferences.getLong(KEY_LAST_MESSAGE_AT, 0L),
                    latestMessageAtMillis
                )
            )
        }

        val success = editor.commit()
        if (!success) {
            Log.e(TAG, "Failed to synchronize active WeChat notifications")
        }
        return getStatus()
    }

    /**
     * 清空全部待提醒通知键。
     *
     * @return 同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun clearPendingNotifications(): Boolean {
        val success = preferences.edit()
            .remove(KEY_PENDING_NOTIFICATION_KEYS)
            .remove(KEY_NEXT_REMINDER_AT)
            .remove(KEY_NOTIFICATIONS_SHOWN_IN_CYCLE)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to clear pending WeChat notifications")
        }
        return success
    }

    /**
     * 记录本应用成功展示微信等待提醒的时间，并结束本轮倒计时。
     *
     * 使用方法：
     * WechatReminderReceiver确认系统通知发布成功后调用。本轮已经完成提醒，因此同步清空匿名
     * 通知键和下一次时间；后续收到新的普通微信消息时会重新建立一轮独立倒计时。
     *
     * @param shownAtMillis 提醒实际发布的Unix毫秒时间戳。
     *
     * @return 更新后的等待状态；固定次数已经完成时待提醒数量会变为0，持续重复时继续保留。
     */
    @Synchronized
    fun recordReminderShown(shownAtMillis: Long): WechatReminderStatus {
        val settings = getSettings()
        val shownCount = preferences.getInt(KEY_NOTIFICATIONS_SHOWN_IN_CYCLE, 0)
            .coerceAtLeast(0) + 1
        val shouldContinue = settings.notificationCount == REPEAT_CONTINUOUSLY_COUNT ||
            shownCount < settings.notificationCount
        val editor = preferences.edit()
            .putLong(KEY_LAST_REMINDER_AT, shownAtMillis.coerceAtLeast(0L))
            .remove(KEY_NEXT_REMINDER_AT)
            .putInt(KEY_NOTIFICATIONS_SHOWN_IN_CYCLE, shownCount)
        if (!shouldContinue) {
            editor.remove(KEY_PENDING_NOTIFICATION_KEYS)
        }

        val success = editor.commit()
        if (!success) {
            Log.e(TAG, "Failed to persist WeChat reminder timestamp")
        }
        return getStatus()
    }

    /**
     * 保存系统已经接受的下一次微信等待提醒时间，供页面倒计时和进程重建后恢复Alarm使用。
     *
     * @param nextReminderAtMillis 下一次提醒的Unix毫秒时间戳；必须大于0。
     *
     * @return 参数有效且同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun recordNextReminderAt(nextReminderAtMillis: Long): Boolean {
        if (nextReminderAtMillis <= 0L) {
            return false
        }

        val success = preferences.edit()
            .putLong(KEY_NEXT_REMINDER_AT, nextReminderAtMillis)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist next WeChat reminder timestamp")
        }
        return success
    }

    /**
     * 清除尚未触发的倒计时时间，但保留已经捕获的待提醒消息状态。
     *
     * 使用方法：
     * Alarm提交失败或仅取消旧Alarm准备重新安排时调用。不要在成功展示通知后单独调用，
     * 成功路径应使用[recordReminderShown]一次完成状态更新。
     *
     * @return 同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun clearNextReminderAt(): Boolean {
        val success = preferences.edit()
            .remove(KEY_NEXT_REMINDER_AT)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to clear next WeChat reminder timestamp")
        }
        return success
    }

    /**
     * 写入通知监听服务最近一次连接状态变化，用于唤醒已打开设置页的状态监听器。
     *
     * 页面显示的实时在线结果仍由进程内服务标记提供；这里的布尔值和时间戳只负责产生可靠的
     * SharedPreferences变更通知，并为现场诊断保留最后一次连接事件，不涉及微信消息数据。
     *
     * @param connected 本次事件是成功连接为true，断开或销毁为false。
     * @param changedAtMillis 连接状态发生变化的Unix毫秒时间戳。
     *
     * @return 同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun recordListenerConnectionChange(
        connected: Boolean,
        changedAtMillis: Long
    ): Boolean {
        val success = preferences.edit()
            .putBoolean(KEY_LISTENER_CONNECTED, connected)
            .putLong(KEY_LISTENER_CHANGED_AT, changedAtMillis.coerceAtLeast(0L))
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist notification listener connection event")
        }
        return success
    }

    /**
     * 注册配置或状态变化监听器。
     *
     * @param listener SharedPreferences监听器，页面销毁时必须使用同一实例注销。
     *
     * @return 无返回值。
     */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 注销先前注册的配置或状态变化监听器。
     *
     * @param listener 先前传入registerChangeListener的监听器实例。
     *
     * @return 无返回值。
     */
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 复制读取SharedPreferences中的待提醒通知键，避免修改框架返回的共享集合。
     *
     * @return 可安全修改的通知键集合。
     */
    private fun readPendingNotificationKeys(): MutableSet<String> {
        return preferences.getStringSet(KEY_PENDING_NOTIFICATION_KEYS, emptySet())
            .orEmpty()
            .filter(String::isNotBlank)
            .toMutableSet()
    }

    companion object {
        const val TAG = "WechatReminderRepo"
        const val PREFERENCE_NAME = "harley_wechat_message_reminder"
        const val LEGACY_AUTO_REPLY_PREFERENCE_NAME = "harley_wechat_auto_reply"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL_MINUTES = "interval_minutes"
        const val KEY_NOTIFICATION_COUNT = "notification_count"
        const val KEY_PENDING_NOTIFICATION_KEYS = "pending_notification_keys"
        const val KEY_LAST_MESSAGE_AT = "last_message_at"
        const val KEY_LAST_REMINDER_AT = "last_reminder_at"
        const val KEY_NEXT_REMINDER_AT = "next_reminder_at"
        const val KEY_NOTIFICATIONS_SHOWN_IN_CYCLE = "notifications_shown_in_cycle"
        const val KEY_LISTENER_CONNECTED = "listener_connected"
        const val KEY_LISTENER_CHANGED_AT = "listener_changed_at"
        const val REPEAT_CONTINUOUSLY_COUNT = 0
    }
}
