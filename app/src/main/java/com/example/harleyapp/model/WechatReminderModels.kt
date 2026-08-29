package com.example.harleyapp.model

/**
 * 微信未查看消息重复提醒配置。
 *
 * 使用方法：
 * 设置页面通过WechatReminderRepository读取和保存本配置；通知监听服务在收到普通微信消息时
 * 读取最新配置，并在原微信通知仍保留于通知栏时按intervalMinutes安排下一次提醒。
 *
 * @param enabled 是否启用微信未查看消息重复提醒。
 * @param intervalMinutes 两次检查和提醒之间的分钟数，允许范围为5到1440分钟。
 */
data class WechatReminderSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_WECHAT_REMINDER_INTERVAL_MINUTES
)

/**
 * 微信未查看消息提醒的本地运行状态。
 *
 * 本状态只包含通知数量和时间戳，不保存联系人、群名或聊天正文。
 *
 * @param pendingNotificationCount 当前仍保留于通知栏并等待查看的普通微信通知数量。
 * @param lastMessageAtMillis 最近一次记录普通微信通知的Unix毫秒时间戳；没有记录时为0。
 * @param lastReminderAtMillis 最近一次成功展示重复提醒的Unix毫秒时间戳；尚未提醒时为0。
 */
data class WechatReminderStatus(
    val pendingNotificationCount: Int = 0,
    val lastMessageAtMillis: Long = 0L,
    val lastReminderAtMillis: Long = 0L
)

/** 用户可设置的最短提醒间隔，避免过于频繁地打扰或消耗电量。 */
const val MIN_WECHAT_REMINDER_INTERVAL_MINUTES = 5

/** 用户可设置的最长提醒间隔，对应一天。 */
const val MAX_WECHAT_REMINDER_INTERVAL_MINUTES = 24 * 60

/** 首次启用时使用的默认提醒间隔。 */
const val DEFAULT_WECHAT_REMINDER_INTERVAL_MINUTES = 10
