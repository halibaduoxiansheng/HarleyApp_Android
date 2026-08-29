package com.example.harleyapp.model

/**
 * 微信未查看消息重复提醒配置。
 *
 * 使用方法：
 * 设置页面通过WechatReminderRepository读取和保存本配置；通知监听服务在收到普通微信消息时
 * 读取最新配置，并在原微信通知仍保留于通知栏时按intervalMinutes安排下一次提醒。
 *
 * @param enabled 是否启用微信未查看消息重复提醒。
 * @param intervalMinutes 两次检查和提醒之间的分钟数，允许范围为1到1440分钟。
 * @param notificationCount 每轮最多展示的本App通知次数；1到20为固定次数，0表示持续重复。
 */
data class WechatReminderSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_WECHAT_REMINDER_INTERVAL_MINUTES,
    val notificationCount: Int = DEFAULT_WECHAT_REMINDER_NOTIFICATION_COUNT
)

/**
 * 微信未查看消息提醒的本地运行状态。
 *
 * 本状态只包含通知数量和时间戳，不保存联系人、群名或聊天正文。
 *
 * @param pendingNotificationCount 当前仍保留于通知栏并等待查看的普通微信通知数量。
 * @param lastMessageAtMillis 最近一次记录普通微信通知的Unix毫秒时间戳；没有记录时为0。
 * @param lastReminderAtMillis 最近一次成功展示重复提醒的Unix毫秒时间戳；尚未提醒时为0。
 * @param nextReminderAtMillis 下一次由本应用展示提醒的Unix毫秒时间戳；当前没有倒计时时为0。
 * @param notificationsShownInCycle 当前这轮等待中已经成功展示的本App通知次数。
 */
data class WechatReminderStatus(
    val pendingNotificationCount: Int = 0,
    val lastMessageAtMillis: Long = 0L,
    val lastReminderAtMillis: Long = 0L,
    val nextReminderAtMillis: Long = 0L,
    val notificationsShownInCycle: Int = 0
)

/** 用户可设置的最短提醒间隔；允许一分钟以便用户进行短时间提醒和实机验证。 */
const val MIN_WECHAT_REMINDER_INTERVAL_MINUTES = 1

/** 用户可设置的最长提醒间隔，对应一天。 */
const val MAX_WECHAT_REMINDER_INTERVAL_MINUTES = 24 * 60

/** 首次启用时使用的默认提醒间隔。 */
const val DEFAULT_WECHAT_REMINDER_INTERVAL_MINUTES = 10

/** 单轮允许用户设置的最少通知次数；0单独表示持续重复。 */
const val MIN_WECHAT_REMINDER_NOTIFICATION_COUNT = 0

/** 单轮允许用户设置的最多固定通知次数，避免误操作造成过度打扰。 */
const val MAX_WECHAT_REMINDER_NOTIFICATION_COUNT = 20

/** 首次启用时默认只通知一次，不擅自开启无限重复。 */
const val DEFAULT_WECHAT_REMINDER_NOTIFICATION_COUNT = 1
