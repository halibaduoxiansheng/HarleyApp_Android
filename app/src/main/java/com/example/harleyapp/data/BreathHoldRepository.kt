package com.example.harleyapp.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.harleyapp.model.BREATH_HOLD_HISTORY_LIMIT
import com.example.harleyapp.model.BREATH_HOLD_MAX_VALID_DURATION_MILLIS
import com.example.harleyapp.model.BreathHoldRecord
import com.example.harleyapp.model.removeBreathHoldRecords
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 使用SharedPreferences在本机保存最近30次憋气计时记录。
 *
 * 使用方法：
 * 页面创建Repository后调用[loadRecords]取得历史；一次计时结束后调用[addRecord]；用户在历史管理
 * 界面勾选记录后调用[deleteRecords]。两个写操作都会返回最新完整列表，页面据此重新计算统计。
 * 记录只包含随机ID、结束时间和持续毫秒数，不包含账号、健康诊断或云端同步。
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
     * 删除用户明确选中的本机憋气记录。
     *
     * 使用方法：
     * 页面先让用户勾选一项或多项并完成二次确认，再传入这些记录的稳定ID。函数从当前落盘历史
     * 重新读取和过滤，避免使用过期页面列表覆盖刚保存的计时结果；空选择、空白ID和未知ID均按
     * 无变化处理。删除后的完整列表可直接替换页面状态，次数、最佳和平均值会据此重新计算。
     *
     * @param selectedRecordIds 用户确认删除的记录ID集合。
     * @return 写入成功后的完整倒序历史；没有有效选择或写入失败时返回删除前历史。
     */
    fun deleteRecords(selectedRecordIds: Set<String>): List<BreathHoldRecord> {
        val existing = loadRecords()
        val updated = removeBreathHoldRecords(existing, selectedRecordIds)
        if (updated.size == existing.size) return existing

        return if (writeRecords(updated)) updated else existing
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
