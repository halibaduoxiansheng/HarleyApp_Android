package com.example.harleyapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.harleyapp.data.local.RoomBackedPreferences
import com.example.harleyapp.model.ReminderRepeatUnit
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.calculateNextReminderAt
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在本机私有存储中维护通用定时通知计划。
 *
 * 使用方法：
 * 使用Application Context创建实例。页面通过[upsertReminder]、[deleteReminder]完成增删改，
 * 通过[getReminders]、[getReminder]完成查询；Alarm接收器通过[recordTriggered]记录本次触发
 * 并推进重复计划的下一次时间。本类只负责数据，不直接设置系统Alarm或展示通知。
 *
 * @param context Android上下文；正式页面应传入Application Context，测试可传入隔离存储上下文。
 */
class ReminderRepository(context: Context) {

    private val preferences = RoomBackedPreferences.create(
        context = context,
        preferenceName = PREFERENCE_NAME
    )

    /**
     * 查询本机保存的全部通知计划。
     *
     * @return 按下一次提醒时间从近到远排列的计划；没有数据或数据损坏时返回空列表。
     */
    @Synchronized
    fun getReminders(): List<ScheduledReminder> {
        val content = preferences.getString(KEY_REMINDERS, null) ?: return emptyList()

        return runCatching {
            val jsonArray = JSONArray(content)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    add(jsonArray.getJSONObject(index).toScheduledReminder())
                }
            }.sortedBy { reminder ->
                reminder.nextTriggerAtMillis
            }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read scheduled reminders", error)
            emptyList()
        }
    }

    /**
     * 按唯一编号查询一条通知计划。
     *
     * @param reminderId 本机计划唯一编号。
     *
     * @return 找到时返回完整计划，不存在时返回null。
     */
    @Synchronized
    fun getReminder(reminderId: Long): ScheduledReminder? {
        return getReminders().firstOrNull { reminder ->
            reminder.id == reminderId
        }
    }

    /**
     * 新增或修改通知计划。
     *
     * 使用方法：
     * 新增时传入id为0的计划；修改时传入原计划id。内容不能为空，时间戳必须为正数，
     * 重复间隔数值只接受0到999，单位由ReminderRepeatUnit约束。成功保存后，调用方还需要通过
     * ReminderScheduler更新系统Alarm。
     *
     * @param reminder 待保存的通知计划。
     *
     * @return 保存并规范化后的计划；字段无效或写入失败时返回null。
     */
    @Synchronized
    fun upsertReminder(reminder: ScheduledReminder): ScheduledReminder? {
        val normalizedContent = reminder.content.trim().take(MAX_CONTENT_LENGTH)
        if (normalizedContent.isBlank() ||
            reminder.nextTriggerAtMillis <= 0L ||
            reminder.repeatIntervalValue !in MIN_REPEAT_INTERVAL_VALUE..MAX_REPEAT_INTERVAL_VALUE
        ) {
            return null
        }

        val reminders = getReminders().toMutableList()
        val existingIndex = reminders.indexOfFirst { savedReminder ->
            reminder.id > 0L && savedReminder.id == reminder.id
        }
        val normalizedReminder = reminder.copy(
            id = if (existingIndex >= 0) reminder.id else nextId(reminders),
            content = normalizedContent,
            createdAtMillis = if (reminder.createdAtMillis > 0L) {
                reminder.createdAtMillis
            } else {
                System.currentTimeMillis()
            },
            lastTriggeredAtMillis = reminder.lastTriggeredAtMillis.coerceAtLeast(0L)
        )

        if (existingIndex >= 0) {
            reminders[existingIndex] = normalizedReminder
        } else {
            reminders.add(normalizedReminder)
        }

        return if (persistReminders(reminders)) normalizedReminder else null
    }

    /**
     * 删除指定通知计划。
     *
     * @param reminderId 要删除的计划唯一编号。
     *
     * @return 删除并写入成功返回true；计划本来就不存在时也返回true，便于安全重复删除。
     */
    @Synchronized
    fun deleteReminder(reminderId: Long): Boolean {
        val reminders = getReminders().toMutableList()
        val removed = reminders.removeAll { reminder ->
            reminder.id == reminderId
        }

        return if (removed) persistReminders(reminders) else true
    }

    /**
     * 原子记录一次Alarm触发，并在需要重复时推进到当前时间之后最近的一次计划。
     *
     * 使用方法：
     * 接收器先读取计划并保存其nextTriggerAtMillis，再把该值作为[expectedTriggerAtMillis]传入。
     * 如果用户恰好已编辑计划，期望值会不匹配，本函数不会覆盖用户的新时间。一次性计划只记录
     * 触发时间；重复计划会同时计算并保存下一次时间。
     *
     * @param reminderId 已触发的计划唯一编号。
     * @param expectedTriggerAtMillis 接收器实际处理的原计划时间，用于阻止过期Alarm覆盖新编辑。
     * @param triggeredAtMillis 系统实际处理广播的当前Unix毫秒时间戳。
     *
     * @return 更新后的计划；计划不存在、Alarm已失效、一次性计划已处理或写入失败时返回null。
     */
    @Synchronized
    fun recordTriggered(
        reminderId: Long,
        expectedTriggerAtMillis: Long,
        triggeredAtMillis: Long
    ): ScheduledReminder? {
        val reminders = getReminders().toMutableList()
        val index = reminders.indexOfFirst { reminder ->
            reminder.id == reminderId
        }
        if (index < 0) {
            return null
        }

        val currentReminder = reminders[index]
        if (currentReminder.nextTriggerAtMillis != expectedTriggerAtMillis ||
            (!currentReminder.isRecurring && currentReminder.lastTriggeredAtMillis > 0L)
        ) {
            return null
        }

        val nextTriggerAtMillis = calculateNextReminderAt(
            currentTriggerAtMillis = currentReminder.nextTriggerAtMillis,
            repeatIntervalValue = currentReminder.repeatIntervalValue,
            repeatIntervalUnit = currentReminder.repeatIntervalUnit,
            nowMillis = triggeredAtMillis
        )
        val updatedReminder = currentReminder.copy(
            nextTriggerAtMillis = nextTriggerAtMillis ?: currentReminder.nextTriggerAtMillis,
            lastTriggeredAtMillis = triggeredAtMillis.coerceAtLeast(0L)
        )
        reminders[index] = updatedReminder

        return if (persistReminders(reminders)) updatedReminder else null
    }

    /**
     * 注册SharedPreferences变化监听器，让已打开页面可同步响应Alarm接收器的数据更新。
     *
     * @param listener 页面持有的SharedPreferences监听器，销毁时必须对应注销。
     *
     * @return 无返回值。
     */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 注销先前注册的计划变化监听器。
     *
     * @param listener 与[registerChangeListener]传入的同一监听器实例。
     *
     * @return 无返回值。
     */
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 生成一个不小于当前时间且大于现有最大编号的本机唯一编号。
     *
     * @param reminders 当前保存的全部通知计划。
     *
     * @return 可用于新计划的正整数编号。
     */
    private fun nextId(reminders: List<ScheduledReminder>): Long {
        val largestId = reminders.maxOfOrNull { reminder ->
            reminder.id
        } ?: 0L

        return maxOf(System.currentTimeMillis(), largestId + 1L)
    }

    /**
     * 把完整通知计划列表同步写入本机私有存储。
     *
     * @param reminders 要持久化的全部通知计划。
     *
     * @return 写入成功返回true，失败返回false并记录英文错误日志。
     */
    private fun persistReminders(reminders: List<ScheduledReminder>): Boolean {
        val jsonArray = JSONArray()
        reminders.forEach { reminder ->
            jsonArray.put(reminder.toJsonObject())
        }
        val success = preferences.edit()
            .putString(KEY_REMINDERS, jsonArray.toString())
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist scheduled reminders")
        }

        return success
    }

    /**
     * 把持久化JSON恢复为通知计划模型。
     *
     * 旧版本仅保存repeatIntervalDays，本函数会把该字段迁移为数值加DAY单位，不丢失已有计划。
     *
     * @return 包含内容、时间、重复数值、单位和触发状态的ScheduledReminder。
     */
    private fun JSONObject.toScheduledReminder(): ScheduledReminder {
        val hasNewRepeatValue = has(JSON_REPEAT_INTERVAL_VALUE)
        val repeatIntervalValue = if (hasNewRepeatValue) {
            optInt(JSON_REPEAT_INTERVAL_VALUE, 0)
        } else {
            optInt(JSON_LEGACY_REPEAT_INTERVAL_DAYS, 0)
        }
        val repeatIntervalUnit = if (hasNewRepeatValue) {
            ReminderRepeatUnit.fromStorageValue(optString(JSON_REPEAT_INTERVAL_UNIT, null))
        } else {
            ReminderRepeatUnit.DAY
        }

        return ScheduledReminder(
            id = getLong(JSON_ID),
            content = optString(JSON_CONTENT, ""),
            nextTriggerAtMillis = getLong(JSON_NEXT_TRIGGER_AT),
            repeatIntervalValue = repeatIntervalValue,
            repeatIntervalUnit = repeatIntervalUnit,
            createdAtMillis = optLong(JSON_CREATED_AT, 0L),
            lastTriggeredAtMillis = optLong(JSON_LAST_TRIGGERED_AT, 0L)
        )
    }

    /**
     * 把通知计划模型转换为可持久化JSON。
     *
     * @return 包含全部可恢复字段的JSONObject。
     */
    private fun ScheduledReminder.toJsonObject(): JSONObject {
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_CONTENT, content)
            .put(JSON_NEXT_TRIGGER_AT, nextTriggerAtMillis)
            .put(JSON_REPEAT_INTERVAL_VALUE, repeatIntervalValue)
            .put(JSON_REPEAT_INTERVAL_UNIT, repeatIntervalUnit.storageValue)
            .put(JSON_CREATED_AT, createdAtMillis)
            .put(JSON_LAST_TRIGGERED_AT, lastTriggeredAtMillis)
    }

    private companion object {
        const val TAG = "ReminderRepository"
        const val PREFERENCE_NAME = "scheduled_reminders"
        const val KEY_REMINDERS = "reminders_json"
        const val JSON_ID = "id"
        const val JSON_CONTENT = "content"
        const val JSON_NEXT_TRIGGER_AT = "nextTriggerAtMillis"
        const val JSON_REPEAT_INTERVAL_VALUE = "repeatIntervalValue"
        const val JSON_REPEAT_INTERVAL_UNIT = "repeatIntervalUnit"
        const val JSON_LEGACY_REPEAT_INTERVAL_DAYS = "repeatIntervalDays"
        const val JSON_CREATED_AT = "createdAtMillis"
        const val JSON_LAST_TRIGGERED_AT = "lastTriggeredAtMillis"
        const val MAX_CONTENT_LENGTH = 1_000
        const val MIN_REPEAT_INTERVAL_VALUE = 0
        const val MAX_REPEAT_INTERVAL_VALUE = 999
    }
}
