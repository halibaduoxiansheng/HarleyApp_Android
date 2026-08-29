package com.example.harleyapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.harleyapp.model.BillImportResult
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerSource
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.WechatCapture
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用SharedPreferences和JSON在本机保存账目与待确认微信通知。
 *
 * 使用方法：
 * 使用Application Context创建一个实例，然后调用getEntries、upsertEntry、importEntries、
 * getPendingCaptures等接口。本类不联网，数据不会由HarleyApp上传到服务器。
 *
 * @param context Android上下文，内部会自动转换为Application Context。
 */
class LedgerRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取全部账目并按日期和创建时间倒序排列。
     *
     * @return 账目列表；数据损坏或尚无记录时返回空列表。
     */
    @Synchronized
    fun getEntries(): List<LedgerEntry> {
        val content = preferences.getString(KEY_ENTRIES, null) ?: return emptyList()

        return runCatching {
            val jsonArray = JSONArray(content)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    add(jsonArray.getJSONObject(index).toLedgerEntry())
                }
            }.sortedWith(
                compareByDescending<LedgerEntry> { it.dateEpochDay }
                    .thenByDescending { it.createdAtMillis }
            )
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read ledger entries", error)
            emptyList()
        }
    }

    /**
     * 新增或更新一条账目，并按externalKey防止微信自动记录重复。
     *
     * 使用方法：
     * 手动新增时传入id为0且externalKey为空的LedgerEntry；微信记录必须带稳定externalKey。
     *
     * @param entry 要保存的账目。
     *
     * @return 保存成功返回true，写入失败返回false。
     */
    @Synchronized
    fun upsertEntry(entry: LedgerEntry): Boolean {
        val entries = getEntries().toMutableList()
        val existingIndex = findExistingEntryIndex(entries, entry)
        val normalizedEntry = when {
            existingIndex >= 0 -> entry.copy(id = entries[existingIndex].id)
            entry.id > 0L -> entry
            else -> entry.copy(id = nextId(entries))
        }

        if (existingIndex >= 0) {
            entries[existingIndex] = normalizedEntry
        } else {
            entries.add(normalizedEntry)
        }

        return persistEntries(entries)
    }

    /**
     * 批量导入微信账单，并以账单交易号生成的externalKey去重。
     *
     * @param importedEntries 已经完成格式解析的微信账目。
     *
     * @return 新增、更新和跳过数量；持久化失败时errorMessage不为空。
     */
    @Synchronized
    fun importEntries(importedEntries: List<LedgerEntry>): BillImportResult {
        val entries = getEntries().toMutableList()
        var insertedRows = 0
        var updatedRows = 0
        var skippedRows = 0

        importedEntries.forEach { importedEntry ->
            if (importedEntry.externalKey.isBlank() || importedEntry.amountCents <= 0L) {
                skippedRows++
                return@forEach
            }

            val existingIndex = findExistingEntryIndex(entries, importedEntry)
            if (existingIndex >= 0) {
                entries[existingIndex] = importedEntry.copy(id = entries[existingIndex].id)
                updatedRows++
            } else {
                entries.add(importedEntry.copy(id = nextId(entries)))
                insertedRows++
            }
        }

        val success = persistEntries(entries)
        return BillImportResult(
            scannedRows = importedEntries.size,
            insertedRows = if (success) insertedRows else 0,
            updatedRows = if (success) updatedRows else 0,
            skippedRows = skippedRows,
            errorMessage = if (success) "" else "账单写入失败，请重试"
        )
    }

    /**
     * 按唯一编号删除账目。
     *
     * @param entryId 要删除的账目唯一编号。
     *
     * @return 删除并保存成功返回true；目标不存在或写入失败返回false。
     */
    @Synchronized
    fun deleteEntry(entryId: Long): Boolean {
        val entries = getEntries().toMutableList()
        val removed = entries.removeAll { it.id == entryId }

        return removed && persistEntries(entries)
    }

    /**
     * 读取全部待确认微信通知，最新通知排在最前面。
     *
     * @return 待确认通知列表；数据异常或没有记录时返回空列表。
     */
    @Synchronized
    fun getPendingCaptures(): List<WechatCapture> {
        val content = preferences.getString(KEY_PENDING_CAPTURES, null) ?: return emptyList()

        return runCatching {
            val jsonArray = JSONArray(content)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    add(jsonArray.getJSONObject(index).toWechatCapture())
                }
            }.sortedByDescending { it.receivedAtMillis }
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read pending WeChat captures", error)
            emptyList()
        }
    }

    /**
     * 新增或更新一条待确认微信通知。
     *
     * @param capture 待确认通知，externalKey相同的后续通知会覆盖旧内容。
     *
     * @return 保存成功返回true，否则返回false。
     */
    @Synchronized
    fun upsertPendingCapture(capture: WechatCapture): Boolean {
        val captures = getPendingCaptures().toMutableList()
        val existingIndex = captures.indexOfFirst { it.externalKey == capture.externalKey }

        if (existingIndex >= 0) {
            captures[existingIndex] = capture
        } else {
            captures.add(capture)
        }

        return persistCaptures(captures)
    }

    /**
     * 删除已经确认或由用户忽略的微信通知。
     *
     * @param externalKey 通知去重键。
     *
     * @return 删除并持久化成功返回true；目标不存在时也返回true，保证重复操作幂等。
     */
    @Synchronized
    fun deletePendingCapture(externalKey: String): Boolean {
        val captures = getPendingCaptures().toMutableList()
        val removed = captures.removeAll { it.externalKey == externalKey }

        return if (removed) persistCaptures(captures) else true
    }

    /**
     * 清理超过保留天数且仍未处理的原始微信通知。
     *
     * 使用方法：
     * App启动或用户执行本地清理时调用。该操作不会删除已经确认的账目。
     *
     * @param retentionDays 待确认通知保留天数，最小为7天。
     *
     * @return 成功删除的待确认通知数量。
     */
    @Synchronized
    fun cleanExpiredCaptures(retentionDays: Int = DEFAULT_CAPTURE_RETENTION_DAYS): Int {
        val safeRetentionDays = retentionDays.coerceAtLeast(MIN_CAPTURE_RETENTION_DAYS)
        val threshold = System.currentTimeMillis() - safeRetentionDays * MILLIS_PER_DAY
        val captures = getPendingCaptures().toMutableList()
        val originalSize = captures.size
        captures.removeAll { it.receivedAtMillis < threshold }
        val removedCount = originalSize - captures.size

        if (removedCount > 0 && !persistCaptures(captures)) {
            return 0
        }

        return removedCount
    }

    /**
     * 注册账目或待确认通知变化监听器。
     *
     * @param listener SharedPreferences变化监听器，页面销毁时必须对应注销。
     *
     * @return 无返回值。
     */
    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 注销先前注册的数据变化监听器。
     *
     * @param listener 与registerChangeListener传入的同一监听器实例。
     *
     * @return 无返回值。
     */
    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /**
     * 查找同一账目。优先使用微信externalKey，手动记录使用id。
     *
     * @param entries 当前全部账目。
     * @param candidate 待匹配账目。
     *
     * @return 已存在记录下标；没有匹配时返回-1。
     */
    private fun findExistingEntryIndex(
        entries: List<LedgerEntry>,
        candidate: LedgerEntry
    ): Int {
        if (candidate.externalKey.isNotBlank()) {
            return entries.indexOfFirst { it.externalKey == candidate.externalKey }
        }

        return if (candidate.id > 0L) {
            entries.indexOfFirst { it.id == candidate.id }
        } else {
            -1
        }
    }

    /**
     * 生成单机账本中下一个可用的账目编号。
     *
     * @param entries 当前已有账目。
     *
     * @return 大于当前时间戳和已有最大编号的唯一编号。
     */
    private fun nextId(entries: List<LedgerEntry>): Long {
        val largestId = entries.maxOfOrNull { it.id } ?: 0L
        return maxOf(System.currentTimeMillis(), largestId + 1L)
    }

    /**
     * 把完整账目列表原子性写入SharedPreferences。
     *
     * @param entries 要保存的全部账目。
     *
     * @return 同步写入成功返回true，否则返回false。
     */
    private fun persistEntries(entries: List<LedgerEntry>): Boolean {
        val jsonArray = JSONArray()
        entries.forEach { entry ->
            jsonArray.put(entry.toJsonObject())
        }

        val success = preferences.edit()
            .putString(KEY_ENTRIES, jsonArray.toString())
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist ledger entries")
        }

        return success
    }

    /**
     * 把待确认微信通知列表写入SharedPreferences。
     *
     * @param captures 要保存的全部待确认通知。
     *
     * @return 写入成功返回true，否则返回false。
     */
    private fun persistCaptures(captures: List<WechatCapture>): Boolean {
        val jsonArray = JSONArray()
        captures.forEach { capture ->
            jsonArray.put(capture.toJsonObject())
        }

        val success = preferences.edit()
            .putString(KEY_PENDING_CAPTURES, jsonArray.toString())
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist pending WeChat captures")
        }

        return success
    }

    /**
     * 将JSON对象转换为账目模型，并兼容旧版本中缺少来源字段的记录。
     *
     * @return 解析完成的LedgerEntry；字段异常时由调用方统一处理错误。
     */
    private fun JSONObject.toLedgerEntry(): LedgerEntry {
        return LedgerEntry(
            id = getLong(JSON_ID),
            type = enumValueOrDefault(
                value = optString(JSON_TYPE),
                defaultValue = LedgerType.EXPENSE
            ),
            amountCents = getLong(JSON_AMOUNT_CENTS),
            category = optString(JSON_CATEGORY, DEFAULT_CATEGORY),
            note = optString(JSON_NOTE, ""),
            dateEpochDay = getLong(JSON_DATE_EPOCH_DAY),
            createdAtMillis = optLong(JSON_CREATED_AT, 0L),
            source = enumValueOrDefault(
                value = optString(JSON_SOURCE),
                defaultValue = LedgerSource.MANUAL
            ),
            counterparty = optString(JSON_COUNTERPARTY, ""),
            rawText = optString(JSON_RAW_TEXT, ""),
            externalKey = optString(JSON_EXTERNAL_KEY, "")
        )
    }

    /**
     * 将账目模型转换为JSON对象。
     *
     * @return 包含全部可恢复字段的JSONObject。
     */
    private fun LedgerEntry.toJsonObject(): JSONObject {
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_TYPE, type.name)
            .put(JSON_AMOUNT_CENTS, amountCents)
            .put(JSON_CATEGORY, category)
            .put(JSON_NOTE, note)
            .put(JSON_DATE_EPOCH_DAY, dateEpochDay)
            .put(JSON_CREATED_AT, createdAtMillis)
            .put(JSON_SOURCE, source.name)
            .put(JSON_COUNTERPARTY, counterparty)
            .put(JSON_RAW_TEXT, rawText)
            .put(JSON_EXTERNAL_KEY, externalKey)
    }

    /**
     * 将JSON对象转换为待确认微信通知。
     *
     * @return 解析完成的WechatCapture。
     */
    private fun JSONObject.toWechatCapture(): WechatCapture {
        val amountValue = optLong(JSON_PARSED_AMOUNT_CENTS, NO_AMOUNT_SENTINEL)
        val typeValue = optString(JSON_SUGGESTED_TYPE, "")

        return WechatCapture(
            externalKey = getString(JSON_EXTERNAL_KEY),
            title = optString(JSON_TITLE, "微信支付通知"),
            content = optString(JSON_CONTENT, ""),
            parsedAmountCents = if (amountValue == NO_AMOUNT_SENTINEL) null else amountValue,
            suggestedType = typeValue.takeIf { it.isNotBlank() }?.let {
                enumValueOrDefault(it, LedgerType.EXPENSE)
            },
            suggestedCategory = optString(JSON_CATEGORY, DEFAULT_CATEGORY),
            receivedAtMillis = optLong(JSON_RECEIVED_AT, 0L)
        )
    }

    /**
     * 将待确认微信通知转换为JSON对象。
     *
     * @return 包含解析建议与原始摘要的JSONObject。
     */
    private fun WechatCapture.toJsonObject(): JSONObject {
        return JSONObject()
            .put(JSON_EXTERNAL_KEY, externalKey)
            .put(JSON_TITLE, title)
            .put(JSON_CONTENT, content)
            .put(JSON_PARSED_AMOUNT_CENTS, parsedAmountCents ?: NO_AMOUNT_SENTINEL)
            .put(JSON_SUGGESTED_TYPE, suggestedType?.name.orEmpty())
            .put(JSON_CATEGORY, suggestedCategory)
            .put(JSON_RECEIVED_AT, receivedAtMillis)
    }

    /**
     * 安全解析枚举字段，避免旧数据或未知值导致整个账本无法打开。
     *
     * @param value 待解析枚举名称。
     * @param defaultValue 解析失败后的默认值。
     *
     * @return 成功解析的枚举值或defaultValue。
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String,
        defaultValue: T
    ): T {
        return runCatching { enumValueOf<T>(value) }.getOrDefault(defaultValue)
    }

    private companion object {
        const val TAG = "LedgerRepository"
        const val PREFERENCE_NAME = "harley_ledger"
        const val KEY_ENTRIES = "entries"
        const val KEY_PENDING_CAPTURES = "pending_captures"
        const val DEFAULT_CATEGORY = "其他"
        const val DEFAULT_CAPTURE_RETENTION_DAYS = 90
        const val MIN_CAPTURE_RETENTION_DAYS = 7
        const val MILLIS_PER_DAY = 86_400_000L
        const val NO_AMOUNT_SENTINEL = -1L
        const val JSON_ID = "id"
        const val JSON_TYPE = "type"
        const val JSON_AMOUNT_CENTS = "amount_cents"
        const val JSON_CATEGORY = "category"
        const val JSON_NOTE = "note"
        const val JSON_DATE_EPOCH_DAY = "date_epoch_day"
        const val JSON_CREATED_AT = "created_at"
        const val JSON_SOURCE = "source"
        const val JSON_COUNTERPARTY = "counterparty"
        const val JSON_RAW_TEXT = "raw_text"
        const val JSON_EXTERNAL_KEY = "external_key"
        const val JSON_TITLE = "title"
        const val JSON_CONTENT = "content"
        const val JSON_PARSED_AMOUNT_CENTS = "parsed_amount_cents"
        const val JSON_SUGGESTED_TYPE = "suggested_type"
        const val JSON_RECEIVED_AT = "received_at"
    }
}
