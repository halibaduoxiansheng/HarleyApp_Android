package com.example.harleyapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.harleyapp.model.ScheduledWechatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在本机保存微信图文定时提醒计划及其提醒、打开状态。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面调用upsertMessage和deleteMessage维护计划，
 * Alarm接收器调用markReminderShown，分享Activity调用markShareOpened。本类不连接微信服务器。
 *
 * @param context Android上下文，内部自动转换为Application Context。
 */
class ScheduledMessageRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取全部图文定时提醒，并按计划时间从近到远排列。
     *
     * @return 可安全展示的计划列表；数据损坏或尚未添加时返回空列表。
     */
    @Synchronized
    fun getMessages(): List<ScheduledWechatMessage> {
        val content = preferences.getString(KEY_MESSAGES, null) ?: return emptyList()

        return runCatching {
            val jsonArray = JSONArray(content)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    add(jsonArray.getJSONObject(index).toScheduledMessage())
                }
            }.sortedBy { it.scheduledAtMillis }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read scheduled WeChat messages", error)
            emptyList()
        }
    }

    /**
     * 按编号读取单条图文定时提醒。
     *
     * @param messageId 本地计划唯一编号。
     *
     * @return 找到时返回ScheduledWechatMessage，否则返回null。
     */
    @Synchronized
    fun getMessage(messageId: Long): ScheduledWechatMessage? {
        return getMessages().firstOrNull { it.id == messageId }
    }

    /**
     * 新增或更新一条图文定时提醒。
     *
     * 使用方法：
     * 新增时传入id为0；编辑时保留原id。联系人备注不能为空，文字和图片至少提供一个。
     * 保存成功后调用ScheduledMessageScheduler.schedule设置系统Alarm。
     *
     * @param message 待保存计划。
     *
     * @return 保存后的规范化计划；字段无效或持久化失败时返回null。
     */
    @Synchronized
    fun upsertMessage(message: ScheduledWechatMessage): ScheduledWechatMessage? {
        val normalizedContact = message.contactNote.trim().take(MAX_CONTACT_LENGTH)
        val normalizedText = message.messageText.trim().take(MAX_MESSAGE_LENGTH)
        val normalizedImageUri = message.imageUri.trim().take(MAX_URI_LENGTH)
        if (normalizedContact.isBlank() ||
            message.scheduledAtMillis <= 0L ||
            (normalizedText.isBlank() && normalizedImageUri.isBlank())
        ) {
            return null
        }

        val messages = getMessages().toMutableList()
        val existingIndex = messages.indexOfFirst { it.id == message.id && message.id > 0L }
        val normalizedMessage = message.copy(
            id = if (existingIndex >= 0) message.id else nextId(messages),
            contactNote = normalizedContact,
            messageText = normalizedText,
            imageUri = normalizedImageUri,
            createdAtMillis = if (message.createdAtMillis > 0L) {
                message.createdAtMillis
            } else {
                System.currentTimeMillis()
            }
        )

        if (existingIndex >= 0) {
            messages[existingIndex] = normalizedMessage
        } else {
            messages.add(normalizedMessage)
        }

        return if (persistMessages(messages)) normalizedMessage else null
    }

    /**
     * 删除一条图文定时提醒。
     *
     * @param messageId 要删除的计划编号。
     *
     * @return 删除并保存成功返回true；计划不存在时也返回true以支持重复取消。
     */
    @Synchronized
    fun deleteMessage(messageId: Long): Boolean {
        val messages = getMessages().toMutableList()
        val removed = messages.removeAll { it.id == messageId }

        return if (removed) persistMessages(messages) else true
    }

    /**
     * 标记系统已经展示到点提醒。
     *
     * @param messageId 计划编号。
     * @param shownAtMillis 通知成功展示时间戳。
     *
     * @return 计划存在且更新成功返回true，否则返回false。
     */
    @Synchronized
    fun markReminderShown(messageId: Long, shownAtMillis: Long): Boolean {
        return updateMessage(messageId) { message ->
            message.copy(reminderShownAtMillis = shownAtMillis.coerceAtLeast(0L))
        }
    }

    /**
     * 标记用户已经点击提醒并打开微信分享流程。
     *
     * @param messageId 计划编号。
     * @param openedAtMillis 分享流程打开时间戳。
     *
     * @return 计划存在且更新成功返回true，否则返回false。
     */
    @Synchronized
    fun markShareOpened(messageId: Long, openedAtMillis: Long): Boolean {
        return updateMessage(messageId) { message ->
            message.copy(shareOpenedAtMillis = openedAtMillis.coerceAtLeast(0L))
        }
    }

    /**
     * 注册计划列表变化监听器。
     *
     * @param listener SharedPreferences监听器，页面销毁时必须对应注销。
     *
     * @return 无返回值。
     */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 注销先前注册的计划列表变化监听器。
     *
     * @param listener 与registerChangeListener传入的同一监听器实例。
     *
     * @return 无返回值。
     */
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 以转换函数更新单条计划并持久化完整列表。
     *
     * @param messageId 计划唯一编号。
     * @param transform 接收旧计划并返回新计划的转换函数。
     *
     * @return 计划存在且持久化成功返回true，否则返回false。
     */
    private fun updateMessage(
        messageId: Long,
        transform: (ScheduledWechatMessage) -> ScheduledWechatMessage
    ): Boolean {
        val messages = getMessages().toMutableList()
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) {
            return false
        }

        messages[index] = transform(messages[index])
        return persistMessages(messages)
    }

    /**
     * 生成下一个本机唯一计划编号。
     *
     * @param messages 已有全部计划。
     *
     * @return 大于当前时间戳和已有最大编号的正整数。
     */
    private fun nextId(messages: List<ScheduledWechatMessage>): Long {
        val largestId = messages.maxOfOrNull { it.id } ?: 0L
        return maxOf(System.currentTimeMillis(), largestId + 1L)
    }

    /**
     * 把完整计划列表同步写入SharedPreferences。
     *
     * @param messages 要保存的全部图文提醒计划。
     *
     * @return 写入成功返回true，否则返回false。
     */
    private fun persistMessages(messages: List<ScheduledWechatMessage>): Boolean {
        val jsonArray = JSONArray()
        messages.forEach { message ->
            jsonArray.put(message.toJsonObject())
        }
        val success = preferences.edit()
            .putString(KEY_MESSAGES, jsonArray.toString())
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist scheduled WeChat messages")
        }
        return success
    }

    /**
     * 将JSON对象转换为图文定时提醒模型。
     *
     * @return 包含时间、文字、图片URI和状态时间戳的ScheduledWechatMessage。
     */
    private fun JSONObject.toScheduledMessage(): ScheduledWechatMessage {
        return ScheduledWechatMessage(
            id = getLong(JSON_ID),
            contactNote = optString(JSON_CONTACT_NOTE, "未命名联系人"),
            messageText = optString(JSON_MESSAGE_TEXT, ""),
            scheduledAtMillis = getLong(JSON_SCHEDULED_AT),
            imageUri = optString(JSON_IMAGE_URI, ""),
            createdAtMillis = optLong(JSON_CREATED_AT, 0L),
            reminderShownAtMillis = optLong(JSON_REMINDER_SHOWN_AT, 0L),
            shareOpenedAtMillis = optLong(JSON_SHARE_OPENED_AT, 0L)
        )
    }

    /**
     * 将图文定时提醒模型转换为JSON对象。
     *
     * @return 包含全部可恢复字段的JSONObject。
     */
    private fun ScheduledWechatMessage.toJsonObject(): JSONObject {
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_CONTACT_NOTE, contactNote)
            .put(JSON_MESSAGE_TEXT, messageText)
            .put(JSON_SCHEDULED_AT, scheduledAtMillis)
            .put(JSON_IMAGE_URI, imageUri)
            .put(JSON_CREATED_AT, createdAtMillis)
            .put(JSON_REMINDER_SHOWN_AT, reminderShownAtMillis)
            .put(JSON_SHARE_OPENED_AT, shareOpenedAtMillis)
    }

    private companion object {
        const val TAG = "ScheduledMessageRepo"
        const val PREFERENCE_NAME = "harley_scheduled_wechat_messages"
        const val KEY_MESSAGES = "messages"
        const val JSON_ID = "id"
        const val JSON_CONTACT_NOTE = "contact_note"
        const val JSON_MESSAGE_TEXT = "message_text"
        const val JSON_SCHEDULED_AT = "scheduled_at"
        const val JSON_IMAGE_URI = "image_uri"
        const val JSON_CREATED_AT = "created_at"
        const val JSON_REMINDER_SHOWN_AT = "reminder_shown_at"
        const val JSON_SHARE_OPENED_AT = "share_opened_at"
        const val MAX_CONTACT_LENGTH = 60
        const val MAX_MESSAGE_LENGTH = 1_000
        const val MAX_URI_LENGTH = 2_000
    }
}
