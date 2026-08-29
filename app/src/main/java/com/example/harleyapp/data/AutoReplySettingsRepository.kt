package com.example.harleyapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.harleyapp.model.AutoReplyCompatibility
import com.example.harleyapp.model.AutoReplySettings
import com.example.harleyapp.model.AutoReplyStatus
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 自动回复发送前的频率限制检查结果。
 *
 * @property ALLOWED 当前通知可以继续发送。
 * @property DUPLICATE_NOTIFICATION 同一条通知已经成功处理，禁止重复发送。
 * @property CONVERSATION_COOLDOWN 同一会话仍处于冷却时间。
 * @property DAILY_LIMIT 当日成功发送数量已经达到上限。
 */
enum class AutoReplyThrottleResult {
    ALLOWED,
    DUPLICATE_NOTIFICATION,
    CONVERSATION_COOLDOWN,
    DAILY_LIMIT
}

/**
 * 在本机保存微信自动回复配置、兼容状态和匿名频率限制数据。
 *
 * 使用方法：
 * 使用Application Context创建实例。设置页面调用getSettings与saveSettings；通知服务先调用
 * evaluateThrottle，系统快捷回复发送成功后再调用recordSuccessfulReply。会话只以SHA-256摘要
 * 参与冷却判断，本仓库不会保存联系人名称或聊天正文。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class AutoReplySettingsRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取并规范化当前自动回复配置。
     *
     * @return 可直接用于界面和通知服务的AutoReplySettings。
     */
    @Synchronized
    fun getSettings(): AutoReplySettings {
        return AutoReplySettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            replyText = preferences.getString(KEY_REPLY_TEXT, DEFAULT_REPLY_TEXT)
                ?.take(MAX_REPLY_TEXT_LENGTH)
                .orEmpty()
                .ifBlank { DEFAULT_REPLY_TEXT },
            startMinuteOfDay = preferences.getInt(KEY_START_MINUTE, DEFAULT_START_MINUTE)
                .coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY),
            endMinuteOfDay = preferences.getInt(KEY_END_MINUTE, DEFAULT_END_MINUTE)
                .coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY),
            cooldownMinutes = preferences.getInt(KEY_COOLDOWN_MINUTES, DEFAULT_COOLDOWN_MINUTES)
                .coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES),
            dailyLimit = preferences.getInt(KEY_DAILY_LIMIT, DEFAULT_DAILY_LIMIT)
                .coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT),
            replyToGroups = preferences.getBoolean(KEY_REPLY_TO_GROUPS, false)
        )
    }

    /**
     * 保存完整自动回复配置。
     *
     * @param settings 页面提交的新配置；回复内容为空时拒绝保存，其余数值会限制在安全范围内。
     *
     * @return 配置合法且同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun saveSettings(settings: AutoReplySettings): Boolean {
        val normalizedReplyText = settings.replyText.trim().take(MAX_REPLY_TEXT_LENGTH)
        if (normalizedReplyText.isBlank()) {
            return false
        }

        val success = preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_REPLY_TEXT, normalizedReplyText)
            .putInt(
                KEY_START_MINUTE,
                settings.startMinuteOfDay.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)
            )
            .putInt(
                KEY_END_MINUTE,
                settings.endMinuteOfDay.coerceIn(MIN_MINUTE_OF_DAY, MAX_MINUTE_OF_DAY)
            )
            .putInt(
                KEY_COOLDOWN_MINUTES,
                settings.cooldownMinutes.coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES)
            )
            .putInt(
                KEY_DAILY_LIMIT,
                settings.dailyLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)
            )
            .putBoolean(KEY_REPLY_TO_GROUPS, settings.replyToGroups)
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist auto-reply settings")
        }

        return success
    }

    /**
     * 读取最近兼容性检测和当天成功发送统计。
     *
     * @param nowMillis 当前时间戳，默认使用系统时间，测试时可以传入固定值。
     *
     * @return 不包含联系人和聊天正文的AutoReplyStatus。
     */
    @Synchronized
    fun getStatus(nowMillis: Long = System.currentTimeMillis()): AutoReplyStatus {
        val savedDate = preferences.getString(KEY_DAILY_COUNT_DATE, "").orEmpty()
        val today = localDateText(nowMillis)

        return AutoReplyStatus(
            compatibility = enumValueOrDefault(
                value = preferences.getString(KEY_COMPATIBILITY, "").orEmpty(),
                defaultValue = AutoReplyCompatibility.NOT_TESTED
            ),
            lastCheckedAtMillis = preferences.getLong(KEY_LAST_CHECKED_AT, 0L),
            lastReplyAtMillis = preferences.getLong(KEY_LAST_REPLY_AT, 0L),
            repliesToday = if (savedDate == today) {
                preferences.getInt(KEY_DAILY_COUNT, 0).coerceAtLeast(0)
            } else {
                0
            },
            detailCode = preferences.getString(KEY_DETAIL_CODE, "").orEmpty()
        )
    }

    /**
     * 检查去重、同会话冷却和每日发送上限。
     *
     * 使用方法：
     * 仅在时间段、消息类型和快捷回复入口都通过检查后调用。本函数只检查而不占用次数；
     * 系统PendingIntent发送成功后必须调用recordSuccessfulReply完成记录。
     *
     * @param conversationHash 会话名称的SHA-256摘要，不得传入明文联系人名称。
     * @param notificationFingerprint 当前消息通知的SHA-256指纹。
     * @param settings 当前自动回复配置。
     * @param nowMillis 当前通知处理时间戳。
     *
     * @return 允许发送或具体的频率限制原因。
     */
    @Synchronized
    fun evaluateThrottle(
        conversationHash: String,
        notificationFingerprint: String,
        settings: AutoReplySettings,
        nowMillis: Long
    ): AutoReplyThrottleResult {
        val handledNotifications = readTimestampMap(KEY_HANDLED_NOTIFICATIONS)
        if (notificationFingerprint in handledNotifications) {
            return AutoReplyThrottleResult.DUPLICATE_NOTIFICATION
        }

        val conversationReplies = readTimestampMap(KEY_CONVERSATION_REPLIES)
        val lastConversationReply = conversationReplies[conversationHash] ?: 0L
        val cooldownMillis = settings.cooldownMinutes
            .coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES) * MILLIS_PER_MINUTE
        if (lastConversationReply > 0L && nowMillis - lastConversationReply < cooldownMillis) {
            return AutoReplyThrottleResult.CONVERSATION_COOLDOWN
        }

        val today = localDateText(nowMillis)
        val savedDate = preferences.getString(KEY_DAILY_COUNT_DATE, "").orEmpty()
        val sentToday = if (savedDate == today) {
            preferences.getInt(KEY_DAILY_COUNT, 0).coerceAtLeast(0)
        } else {
            0
        }

        return if (sentToday >= settings.dailyLimit.coerceIn(MIN_DAILY_LIMIT, MAX_DAILY_LIMIT)) {
            AutoReplyThrottleResult.DAILY_LIMIT
        } else {
            AutoReplyThrottleResult.ALLOWED
        }
    }

    /**
     * 记录已经成功发送的自动回复，并更新匿名去重、冷却和当天计数。
     *
     * @param conversationHash 会话名称的SHA-256摘要。
     * @param notificationFingerprint 已成功回复的消息通知指纹。
     * @param nowMillis 成功调用系统快捷回复入口的时间戳。
     *
     * @return 全部状态同步持久化成功返回true，否则返回false。
     */
    @Synchronized
    fun recordSuccessfulReply(
        conversationHash: String,
        notificationFingerprint: String,
        nowMillis: Long
    ): Boolean {
        val handledNotifications = pruneTimestampMap(
            values = readTimestampMap(KEY_HANDLED_NOTIFICATIONS),
            thresholdMillis = nowMillis - RECENT_NOTIFICATION_RETENTION_MILLIS
        ).apply {
            this[notificationFingerprint] = nowMillis
        }
        val conversationReplies = pruneTimestampMap(
            values = readTimestampMap(KEY_CONVERSATION_REPLIES),
            thresholdMillis = nowMillis - CONVERSATION_RETENTION_MILLIS
        ).apply {
            this[conversationHash] = nowMillis
        }
        val today = localDateText(nowMillis)
        val oldDate = preferences.getString(KEY_DAILY_COUNT_DATE, "").orEmpty()
        val oldCount = if (oldDate == today) preferences.getInt(KEY_DAILY_COUNT, 0) else 0

        val success = preferences.edit()
            .putString(KEY_HANDLED_NOTIFICATIONS, timestampMapToJson(handledNotifications))
            .putString(KEY_CONVERSATION_REPLIES, timestampMapToJson(conversationReplies))
            .putString(KEY_DAILY_COUNT_DATE, today)
            .putInt(KEY_DAILY_COUNT, oldCount.coerceAtLeast(0) + 1)
            .putString(KEY_COMPATIBILITY, AutoReplyCompatibility.SUPPORTED.name)
            .putLong(KEY_LAST_CHECKED_AT, nowMillis)
            .putLong(KEY_LAST_REPLY_AT, nowMillis)
            .putString(KEY_DETAIL_CODE, DETAIL_SENT)
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist successful auto-reply state")
        }

        return success
    }

    /**
     * 保存快捷回复兼容性检测结果，不记录通知正文或联系人。
     *
     * @param compatibility 检测得到的兼容状态。
     * @param detailCode 供界面解释原因的英文状态代码，不得包含聊天隐私内容。
     * @param checkedAtMillis 检测发生时间戳。
     *
     * @return 同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun recordCompatibility(
        compatibility: AutoReplyCompatibility,
        detailCode: String,
        checkedAtMillis: Long
    ): Boolean {
        val success = preferences.edit()
            .putString(KEY_COMPATIBILITY, compatibility.name)
            .putLong(KEY_LAST_CHECKED_AT, checkedAtMillis)
            .putString(KEY_DETAIL_CODE, detailCode.take(MAX_DETAIL_CODE_LENGTH))
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist auto-reply compatibility")
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
     * 从JSON对象读取字符串到时间戳的匿名映射。
     *
     * @param key SharedPreferences中的字段名。
     *
     * @return 可修改映射；字段缺失或损坏时返回空映射。
     */
    private fun readTimestampMap(key: String): MutableMap<String, Long> {
        val content = preferences.getString(key, null) ?: return mutableMapOf()

        return runCatching {
            val jsonObject = JSONObject(content)
            buildMap {
                jsonObject.keys().forEach { itemKey ->
                    put(itemKey, jsonObject.optLong(itemKey, 0L))
                }
            }.toMutableMap()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read auto-reply timestamp map", error)
            mutableMapOf()
        }
    }

    /**
     * 删除早于阈值的匿名时间记录，并限制最大条目数量。
     *
     * @param values 待清理映射。
     * @param thresholdMillis 最早允许保留的时间戳。
     *
     * @return 清理后的可修改映射。
     */
    private fun pruneTimestampMap(
        values: MutableMap<String, Long>,
        thresholdMillis: Long
    ): MutableMap<String, Long> {
        return values
            .filterValues { it >= thresholdMillis }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_TRACKED_ITEMS)
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    /**
     * 把匿名时间戳映射转换为JSON文本。
     *
     * @param values 要持久化的映射。
     *
     * @return JSONObject序列化字符串。
     */
    private fun timestampMapToJson(values: Map<String, Long>): String {
        val jsonObject = JSONObject()
        values.forEach { (key, value) ->
            jsonObject.put(key, value)
        }
        return jsonObject.toString()
    }

    /**
     * 将时间戳转换为手机当前时区的自然日字符串。
     *
     * @param timestampMillis Unix毫秒时间戳。
     *
     * @return ISO-8601日期文本，例如2026-08-29。
     */
    private fun localDateText(timestampMillis: Long): String {
        return Instant.ofEpochMilli(timestampMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }

    /**
     * 安全解析枚举，避免升级前的未知值导致页面或通知服务崩溃。
     *
     * @param value 待解析枚举名称。
     * @param defaultValue 解析失败时使用的默认值。
     *
     * @return 成功解析的枚举或defaultValue。
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String,
        defaultValue: T
    ): T {
        return runCatching { enumValueOf<T>(value) }.getOrDefault(defaultValue)
    }

    private companion object {
        const val TAG = "AutoReplyRepository"
        const val PREFERENCE_NAME = "harley_wechat_auto_reply"
        const val KEY_ENABLED = "enabled"
        const val KEY_REPLY_TEXT = "reply_text"
        const val KEY_START_MINUTE = "start_minute"
        const val KEY_END_MINUTE = "end_minute"
        const val KEY_COOLDOWN_MINUTES = "cooldown_minutes"
        const val KEY_DAILY_LIMIT = "daily_limit"
        const val KEY_REPLY_TO_GROUPS = "reply_to_groups"
        const val KEY_COMPATIBILITY = "compatibility"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
        const val KEY_LAST_REPLY_AT = "last_reply_at"
        const val KEY_DETAIL_CODE = "detail_code"
        const val KEY_DAILY_COUNT_DATE = "daily_count_date"
        const val KEY_DAILY_COUNT = "daily_count"
        const val KEY_HANDLED_NOTIFICATIONS = "handled_notifications"
        const val KEY_CONVERSATION_REPLIES = "conversation_replies"
        const val DEFAULT_REPLY_TEXT = "您好，我现在不方便回复，稍后联系您。"
        const val DEFAULT_START_MINUTE = 9 * 60
        const val DEFAULT_END_MINUTE = 18 * 60
        const val DEFAULT_COOLDOWN_MINUTES = 30
        const val DEFAULT_DAILY_LIMIT = 30
        const val MIN_MINUTE_OF_DAY = 0
        const val MAX_MINUTE_OF_DAY = 23 * 60 + 59
        const val MIN_COOLDOWN_MINUTES = 5
        const val MAX_COOLDOWN_MINUTES = 24 * 60
        const val MIN_DAILY_LIMIT = 1
        const val MAX_DAILY_LIMIT = 200
        const val MAX_REPLY_TEXT_LENGTH = 200
        const val MAX_DETAIL_CODE_LENGTH = 40
        const val MAX_TRACKED_ITEMS = 200
        const val MILLIS_PER_MINUTE = 60_000L
        const val RECENT_NOTIFICATION_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L
        const val CONVERSATION_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
        const val DETAIL_SENT = "sent"
    }
}
