package com.example.harleyapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.harleyapp.model.BREATH_HOLD_HISTORY_LIMIT
import com.example.harleyapp.model.BREATH_HOLD_MAX_VALID_DURATION_MILLIS
import com.example.harleyapp.model.BreathHoldRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 使用SharedPreferences在本机保存最近30次憋气计时记录。
 *
 * 使用方法：
 * 页面创建Repository后调用[loadRecords]取得历史；一次计时结束后调用[addRecord]，再使用其返回列表
 * 更新页面。记录只包含随机ID、结束时间和持续毫秒数，不包含账号、健康诊断或云端同步。
 *
 * @param context Android上下文，内部统一保存Application Context。
 */
class BreathHoldRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取、校验并按完成时间倒序返回本机历史。
     *
     * @return 最多30条有效记录；偏好不存在或内容损坏时返回空列表，并记录英文错误日志。
     */
    fun loadRecords(): List<BreathHoldRecord> {
        val rawJson = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString(KEY_ID)
                    val completedAtMillis = item.optLong(KEY_COMPLETED_AT, 0L)
                    val durationMillis = item.optLong(KEY_DURATION, 0L)
                    if (
                        id.isNotBlank() &&
                        completedAtMillis > 0L &&
                        durationMillis in 1..BREATH_HOLD_MAX_VALID_DURATION_MILLIS
                    ) {
                        add(
                            BreathHoldRecord(
                                id = id,
                                completedAtMillis = completedAtMillis,
                                durationMillis = durationMillis
                            )
                        )
                    }
                }
            }.sortedByDescending(BreathHoldRecord::completedAtMillis)
                .take(BREATH_HOLD_HISTORY_LIMIT)
        }.onFailure { error ->
            Log.e(TAG, "Failed to read breath hold history", error)
        }.getOrDefault(emptyList())
    }

    /**
     * 把一次有效计时加入本机历史并裁剪为最近30条。
     *
     * @param durationMillis 从Android单调时钟计算出的本次持续毫秒数。
     * @param completedAtMillis 本次结束的Unix毫秒时间，默认使用当前系统时间。
     * @return 保存后的完整倒序历史；时长无效或写入失败时返回原历史，不抛出异常。
     */
    fun addRecord(
        durationMillis: Long,
        completedAtMillis: Long = System.currentTimeMillis()
    ): List<BreathHoldRecord> {
        val existing = loadRecords()
        if (
            durationMillis !in 1..BREATH_HOLD_MAX_VALID_DURATION_MILLIS ||
            completedAtMillis <= 0L
        ) {
            return existing
        }
        val updated = (
            listOf(
                BreathHoldRecord(
                    id = UUID.randomUUID().toString(),
                    completedAtMillis = completedAtMillis,
                    durationMillis = durationMillis
                )
            ) + existing
            ).sortedByDescending(BreathHoldRecord::completedAtMillis)
            .take(BREATH_HOLD_HISTORY_LIMIT)
        return if (writeRecords(updated)) updated else existing
    }

    /**
     * 清空全部本机憋气记录。
     *
     * 页面必须先取得用户确认才可调用；本函数不显示对话框。
     *
     * @return SharedPreferences同步提交成功时返回true，否则返回false并记录英文日志。
     */
    @SuppressLint("UseKtx")
    fun clearRecords(): Boolean {
        return runCatching {
            preferences.edit().remove(KEY_RECORDS).commit()
        }.onFailure { error ->
            Log.e(TAG, "Failed to clear breath hold history", error)
        }.getOrDefault(false)
    }

    /**
     * 把已校验记录序列化并同步提交，确保页面收到成功结果时数据已经落盘。
     *
     * @param records 已按完成时间倒序排列的有效记录。
     * @return 成功写入返回true，失败返回false。
     */
    @SuppressLint("UseKtx")
    private fun writeRecords(records: List<BreathHoldRecord>): Boolean {
        return runCatching {
            val array = JSONArray()
            records.forEach { record ->
                array.put(
                    JSONObject()
                        .put(KEY_ID, record.id)
                        .put(KEY_COMPLETED_AT, record.completedAtMillis)
                        .put(KEY_DURATION, record.durationMillis)
                )
            }
            preferences.edit().putString(KEY_RECORDS, array.toString()).commit()
        }.onFailure { error ->
            Log.e(TAG, "Failed to write breath hold history", error)
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "BreathHoldRepository"
        const val PREFERENCES_NAME = "breath_hold_history"
        const val KEY_RECORDS = "records"
        const val KEY_ID = "id"
        const val KEY_COMPLETED_AT = "completed_at"
        const val KEY_DURATION = "duration"
    }
}
