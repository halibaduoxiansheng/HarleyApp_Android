package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.FitnessExerciseDefinition
import com.example.harleyapp.model.FitnessRecordItem
import com.example.harleyapp.model.FitnessTrackingType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * 管理动态运动项目、每日运动记录和计步传感器基准值。
 *
 * 使用方法：
 * 使用Application Context创建实例。项目管理调用getExerciseDefinitions、
 * upsertExerciseDefinition和deleteExerciseDefinition；记录管理调用getRecordsInRange、
 * upsertRecord和deleteRecord；今日快捷打卡调用updateExercise、completeExercise或setSteps。
 * 所有数据只保存在本应用私有SharedPreferences，不需要账号和网络。
 *
 * 兼容说明：
 * 首次读取旧版本数据时，会把固定的俯卧撑、仰卧起坐和步数转换为动态项目及记录快照；
 * 旧JSON键不会被提前删除，避免迁移失败时丢失用户数据。
 *
 * @param context Android上下文，用于打开应用私有SharedPreferences。
 */
class FitnessRepository(context: Context) {

    // 只保存SharedPreferences实例，不持有Activity，避免页面销毁后发生Context泄漏。
    private val preferences = context.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取当前启用的动态运动项目。
     *
     * @return 按用户保存顺序排列的项目；首次升级时返回由旧目标迁移得到的默认三项。
     */
    fun getExerciseDefinitions(): List<FitnessExerciseDefinition> {
        val rawDefinitions = preferences.getString(KEY_EXERCISE_DEFINITIONS, null)
            ?: return legacyDefaultDefinitions()
        return parseDefinitions(rawDefinitions)
    }

    /**
     * 新增或更新一个运动项目。
     *
     * 使用方法：
     * 新建时传入空id，仓库会生成UUID；编辑时保留原id。项目名称不允许与其他项目重复，
     * 自动计步项目最多一个。保存成功后，今天已有记录的对应快照会同步新名称、单位和目标，
     * 历史日期保持原快照不变。
     *
     * @param definition 页面提交的项目定义。
     * @param todayEpochDay 今天的日期序号，默认使用系统本地日期。
     *
     * @return 保存成功时返回经过校验的项目；校验或写入失败时返回null。
     */
    @Synchronized
    fun upsertExerciseDefinition(
        definition: FitnessExerciseDefinition,
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): FitnessExerciseDefinition? {
        val definitions = getExerciseDefinitions().toMutableList()
        val generatedId = definition.id.ifBlank {
            "exercise_${UUID.randomUUID().toString().replace("-", "")}"
        }
        val safeDefinition = sanitizeDefinition(definition.copy(id = generatedId))
            ?: return null
        val existingIndex = definitions.indexOfFirst { it.id == safeDefinition.id }

        if (existingIndex < 0 && definitions.size >= MAX_EXERCISE_DEFINITIONS) {
            return null
        }
        if (definitions.any { existing ->
                existing.id != safeDefinition.id &&
                    existing.name.equals(safeDefinition.name, ignoreCase = true)
            }
        ) {
            return null
        }
        if (safeDefinition.trackingType == FitnessTrackingType.STEP_COUNTER &&
            definitions.any { existing ->
                existing.id != safeDefinition.id &&
                    existing.trackingType == FitnessTrackingType.STEP_COUNTER
            }
        ) {
            return null
        }

        if (existingIndex >= 0) {
            definitions[existingIndex] = safeDefinition
        } else {
            definitions.add(safeDefinition)
        }

        val records = readStoredRecords().toMutableList()
        val todayIndex = records.indexOfFirst { it.dateEpochDay == todayEpochDay }
        if (todayIndex >= 0) {
            records[todayIndex] = records[todayIndex].withDefinitions(
                definitions = listOf(safeDefinition),
                refreshExisting = true
            )
        }

        val editor = preferences.edit()
            .putString(KEY_EXERCISE_DEFINITIONS, definitionsToJson(definitions))
        if (todayIndex >= 0) {
            editor.putString(KEY_RECORDS, recordsToJson(records))
        }
        val success = editor.commit()
        if (!success) {
            Log.e(TAG, "Failed to persist exercise definition")
            return null
        }

        return safeDefinition
    }

    /**
     * 删除一个当前运动项目。
     *
     * 使用方法：
     * 项目管理弹窗二次确认后调用。删除只影响今天之后的启用列表；已有历史记录继续保留该项目
     * 当天的名称、单位、目标和完成量，保证区间统计和历史查询不丢数据。
     *
     * @param exerciseId 需要删除的项目稳定标识。
     *
     * @return 项目不存在或删除成功时返回true，写入失败时返回false。
     */
    @Synchronized
    fun deleteExerciseDefinition(exerciseId: String): Boolean {
        val definitions = getExerciseDefinitions().toMutableList()
        val removed = definitions.removeAll { it.id == exerciseId }
        if (!removed) {
            return true
        }

        val success = preferences.edit()
            .putString(KEY_EXERCISE_DEFINITIONS, definitionsToJson(definitions))
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to delete exercise definition")
        }
        return success
    }

    /**
     * 读取今天的运动记录。
     *
     * @param epochDay 需要读取的日期序号，默认今天。
     *
     * @return 已保存记录或包含当前项目、完成量为0的临时记录。
     */
    fun getTodayRecord(
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord {
        return getRecord(epochDay)
    }

    /**
     * 读取任意日期的可编辑记录。
     *
     * 使用方法：
     * 补记和编辑对话框调用。尚未保存的日期会使用当前全部项目；已经保存的历史日期只保留
     * 当天真实存在的项目，避免后来新增的目标反向改变旧达标结果。已删除项目也不会从历史移除。
     *
     * @param epochDay 需要读取的日期序号。
     *
     * @return 可直接交给动态记录编辑器的完整记录。
     */
    fun getRecord(epochDay: Long): DailyFitnessRecord {
        val definitions = getExerciseDefinitions()
        val storedRecord = readStoredRecords().firstOrNull {
            it.dateEpochDay == epochDay
        }
        return if (storedRecord == null) {
            emptyRecord(epochDay, definitions)
        } else if (epochDay == LocalDate.now().toEpochDay()) {
            // 今天应同步当前项目；历史日期只保留当天实际存在的项目，避免新项目反向改变旧达标结果。
            storedRecord.withDefinitions(
                definitions = definitions,
                refreshExisting = true
            )
        } else {
            storedRecord
        }
    }

    /**
     * 按连续日期读取近期记录，主要用于连续达标计算。
     *
     * @param days 查询天数，限制为1到370天。
     * @param endEpochDay 区间最后一天，默认今天。
     *
     * @return 包含无记录日期空占位的倒序连续列表。
     */
    fun getRecentRecords(
        days: Int = DEFAULT_HISTORY_DAYS,
        endEpochDay: Long = LocalDate.now().toEpochDay()
    ): List<DailyFitnessRecord> {
        val safeDays = days.coerceIn(1, MAX_STORED_DAYS)
        val recordsByDate = readStoredRecords().associateBy { it.dateEpochDay }
        val definitions = getExerciseDefinitions()
        return (0 until safeDays).map { offset ->
            val epochDay = endEpochDay - offset
            recordsByDate[epochDay] ?: emptyRecord(epochDay, definitions)
        }
    }

    /**
     * 查询自定义日期区间内真正保存过运动量的记录。
     *
     * 使用方法：
     * 区间汇总和记录明细共用本接口。空日期不会生成列表卡片，因此用户无需滚动大量零记录；
     * 区间总天数仍由汇总模型根据起止日期计算。
     *
     * @param startEpochDay 开始日期，包含当天。
     * @param endEpochDay 结束日期，包含当天。
     *
     * @return 区间内有实际完成量的记录，按日期从新到旧排列。
     */
    fun getRecordsInRange(
        startEpochDay: Long,
        endEpochDay: Long
    ): List<DailyFitnessRecord> {
        val safeStart = minOf(startEpochDay, endEpochDay)
        val safeEnd = maxOf(startEpochDay, endEpochDay)
        return readStoredRecords()
            .filter { record ->
                record.dateEpochDay in safeStart..safeEnd && record.hasRecordedActivity()
            }
            .sortedByDescending { it.dateEpochDay }
    }

    /**
     * 新增或覆盖保存某一天的完整动态记录。
     *
     * @param record 页面提交的记录；dateEpochDay是唯一键。
     *
     * @return 保存成功时返回安全记录；全部完成量为0或写入失败时返回null。
     */
    @Synchronized
    fun upsertRecord(record: DailyFitnessRecord): DailyFitnessRecord? {
        val safeRecord = sanitizeRecord(record)
        if (!safeRecord.hasRecordedActivity()) {
            return null
        }
        val records = readStoredRecords().toMutableList()
        return persistUpdatedRecord(records, safeRecord)
    }

    /**
     * 删除指定日期的整条运动记录。
     *
     * @param epochDay 需要删除的日期序号。
     *
     * @return 记录不存在或删除成功时返回true，写入失败时返回false。
     */
    @Synchronized
    fun deleteRecord(epochDay: Long): Boolean {
        val records = readStoredRecords().toMutableList()
        val removed = records.removeAll { it.dateEpochDay == epochDay }
        if (!removed) {
            return true
        }

        val success = preferences.edit()
            .putString(KEY_RECORDS, recordsToJson(records))
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to delete fitness record")
        }
        return success
    }

    /**
     * 增减今天某个动态运动项目的完成量。
     *
     * @param exerciseId 运动项目稳定标识。
     * @param delta 本次增加或减少的整数数量，可为负数。
     * @param epochDay 记录日期，默认今天。
     *
     * @return 保存成功后的记录；项目已删除或写入失败时返回null。
     */
    @Synchronized
    fun updateExercise(
        exerciseId: String,
        delta: Int,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord? {
        val definitions = getExerciseDefinitions()
        if (definitions.none { it.id == exerciseId }) {
            return null
        }
        val records = readStoredRecords().toMutableList()
        val current = findMutableRecord(records, epochDay, definitions)
        val currentCount = current.countFor(exerciseId)
        val updated = current.withCount(
            exerciseId = exerciseId,
            count = safeCount(currentCount.toLong() + delta)
        )
        return persistUpdatedRecord(records, updated)
    }

    /**
     * 将一个动态项目直接设置为当天目标值。
     *
     * @param exerciseId 运动项目稳定标识。
     * @param epochDay 记录日期，默认今天。
     *
     * @return 保存成功后的记录；项目不存在或写入失败时返回null。
     */
    @Synchronized
    fun completeExercise(
        exerciseId: String,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord? {
        val definitions = getExerciseDefinitions()
        if (definitions.none { it.id == exerciseId }) {
            return null
        }
        val records = readStoredRecords().toMutableList()
        val current = findMutableRecord(records, epochDay, definitions)
        val item = current.itemFor(exerciseId) ?: return null
        val updated = current.withCount(
            exerciseId = exerciseId,
            count = maxOf(item.count, item.goal)
        )
        return persistUpdatedRecord(records, updated)
    }

    /**
     * 手动校准当前自动步数项目的当天总数。
     *
     * @param steps 用户确认的当天总步数。
     * @param epochDay 记录日期，默认今天。
     *
     * @return 保存成功后的记录；当前没有自动步数项目或写入失败时返回null。
     */
    @Synchronized
    fun setSteps(
        steps: Int,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord? {
        val definitions = getExerciseDefinitions()
        val stepDefinition = definitions.firstOrNull {
            it.trackingType == FitnessTrackingType.STEP_COUNTER
        } ?: return null
        val records = readStoredRecords().toMutableList()
        val current = findMutableRecord(records, epochDay, definitions)
        val updated = current.withCount(
            exerciseId = stepDefinition.id,
            count = steps.coerceIn(0, MAX_DAILY_COUNT)
        )
        return persistUpdatedRecord(records, updated)
    }

    /**
     * 将系统累计计步值转换为本应用当天的增量步数。
     *
     * 使用方法：
     * StepCounterMonitor收到TYPE_STEP_COUNTER事件后调用。首次读取、跨天或重启只建立基准；
     * 后续安全增量才加入当前自动步数项目，避免把开机以来的总步数误算到今天。
     *
     * @param sensorTotal 系统计步传感器自开机以来累计值。
     * @param epochDay 当前本地日期序号，默认今天。
     *
     * @return 写入成功时返回同步结果；没有自动步数项目或写入失败时返回null。
     */
    @Synchronized
    fun syncSensorSteps(
        sensorTotal: Long,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): StepSyncResult? {
        val definitions = getExerciseDefinitions()
        val stepDefinition = definitions.firstOrNull {
            it.trackingType == FitnessTrackingType.STEP_COUNTER
        } ?: return null
        val safeSensorTotal = sensorTotal.coerceAtLeast(0L)
        val lastSensorTotal = preferences.getLong(KEY_LAST_SENSOR_TOTAL, NO_SENSOR_BASELINE)
        val lastSensorDay = preferences.getLong(KEY_LAST_SENSOR_DAY, NO_SENSOR_DAY)
        val canApplyDelta = lastSensorTotal >= 0L &&
            lastSensorDay == epochDay &&
            safeSensorTotal >= lastSensorTotal
        val addedSteps = if (canApplyDelta) {
            (safeSensorTotal - lastSensorTotal)
                .coerceAtMost(MAX_SENSOR_DELTA.toLong())
                .toInt()
        } else {
            0
        }

        val records = readStoredRecords().toMutableList()
        val current = findMutableRecord(records, epochDay, definitions)
        val updated = current.withCount(
            exerciseId = stepDefinition.id,
            count = safeCount(current.countFor(stepDefinition.id).toLong() + addedSteps)
        )
        replaceRecord(records, updated)

        val success = preferences.edit()
            .putString(KEY_RECORDS, recordsToJson(records))
            .putLong(KEY_LAST_SENSOR_TOTAL, safeSensorTotal)
            .putLong(KEY_LAST_SENSOR_DAY, epochDay)
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist sensor step update")
            return null
        }

        return StepSyncResult(
            record = updated,
            addedSteps = addedSteps,
            baselineEstablished = !canApplyDelta
        )
    }

    /**
     * 读取并修正旧版本三个固定目标，供首次动态项目迁移使用。
     *
     * @return 俯卧撑、仰卧起坐和自动步数默认项目。
     */
    private fun legacyDefaultDefinitions(): List<FitnessExerciseDefinition> {
        return FitnessExerciseDefinition.defaultDefinitions(
            pushUpGoal = preferences.getInt(
                KEY_LEGACY_PUSH_UP_GOAL,
                FitnessExerciseDefinition.DEFAULT_PUSH_UP_GOAL
            ).coerceIn(MIN_GOAL, MAX_DAILY_COUNT),
            sitUpGoal = preferences.getInt(
                KEY_LEGACY_SIT_UP_GOAL,
                FitnessExerciseDefinition.DEFAULT_SIT_UP_GOAL
            ).coerceIn(MIN_GOAL, MAX_DAILY_COUNT),
            stepGoal = preferences.getInt(
                KEY_LEGACY_STEP_GOAL,
                FitnessExerciseDefinition.DEFAULT_STEP_GOAL
            ).coerceIn(MIN_GOAL, MAX_DAILY_COUNT)
        )
    }

    /**
     * 查找用于修改的记录，并补齐当前项目、刷新今天的项目快照。
     *
     * @param records 已读取记录集合。
     * @param epochDay 目标日期。
     * @param definitions 当前启用项目。
     *
     * @return 可安全更新的完整记录。
     */
    private fun findMutableRecord(
        records: List<DailyFitnessRecord>,
        epochDay: Long,
        definitions: List<FitnessExerciseDefinition>
    ): DailyFitnessRecord {
        return records.firstOrNull { it.dateEpochDay == epochDay }
            ?.withDefinitions(definitions, refreshExisting = true)
            ?: emptyRecord(epochDay, definitions)
    }

    /**
     * 创建指定日期的零完成量动态记录。
     *
     * @param epochDay 日期序号。
     * @param definitions 当前启用项目。
     *
     * @return 包含全部项目快照、完成量为0的记录。
     */
    private fun emptyRecord(
        epochDay: Long,
        definitions: List<FitnessExerciseDefinition>
    ): DailyFitnessRecord {
        return DailyFitnessRecord(
            dateEpochDay = epochDay,
            items = definitions.map { definition ->
                definition.toRecordItem()
            }
        )
    }

    /**
     * 把单条更新写回记录集合。
     *
     * @param records 当前记录集合。
     * @param updated 页面或传感器生成的新记录。
     *
     * @return 保存成功后的安全记录，失败时返回null。
     */
    private fun persistUpdatedRecord(
        records: MutableList<DailyFitnessRecord>,
        updated: DailyFitnessRecord
    ): DailyFitnessRecord? {
        val safeRecord = sanitizeRecord(updated)
        replaceRecord(records, safeRecord)
        val success = preferences.edit()
            .putString(KEY_RECORDS, recordsToJson(records))
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist fitness record")
            return null
        }
        return safeRecord
    }

    /**
     * 从本地JSON读取所有新旧运动记录。
     *
     * @return 解析成功的记录；单条损坏会被跳过，整体损坏时返回空列表。
     */
    private fun readStoredRecords(): List<DailyFitnessRecord> {
        val rawRecords = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(rawRecords)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    runCatching {
                        jsonArray.getJSONObject(index).toFitnessRecord()
                    }.onSuccess(::add).onFailure { error ->
                        Log.w(TAG, "Skipped malformed fitness record", error)
                    }
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to parse fitness records", error)
        }.getOrDefault(emptyList())
    }

    /**
     * 解析动态项目JSON。
     *
     * @param rawDefinitions SharedPreferences保存的JSON数组文本。
     *
     * @return 有效项目列表；JSON损坏时回退到旧目标生成的默认项目。
     */
    private fun parseDefinitions(rawDefinitions: String): List<FitnessExerciseDefinition> {
        return runCatching {
            val jsonArray = JSONArray(rawDefinitions)
            val definitions = mutableListOf<FitnessExerciseDefinition>()
            for (index in 0 until jsonArray.length()) {
                val definition = jsonArray.getJSONObject(index).toExerciseDefinition()
                if (definition != null &&
                    definitions.none { it.id == definition.id } &&
                    definitions.none { it.name.equals(definition.name, ignoreCase = true) } &&
                    (definition.trackingType != FitnessTrackingType.STEP_COUNTER ||
                        definitions.none {
                            it.trackingType == FitnessTrackingType.STEP_COUNTER
                        })
                ) {
                    definitions.add(definition)
                }
            }
            definitions.take(MAX_EXERCISE_DEFINITIONS)
        }.onFailure { error ->
            Log.e(TAG, "Failed to parse exercise definitions", error)
        }.getOrElse {
            legacyDefaultDefinitions()
        }
    }

    /**
     * 校验项目定义并限制字段长度和数值范围。
     *
     * @param definition 原始项目定义。
     *
     * @return 可保存项目；id、名称或单位为空时返回null。
     */
    private fun sanitizeDefinition(
        definition: FitnessExerciseDefinition
    ): FitnessExerciseDefinition? {
        val safeId = definition.id.trim().take(MAX_ID_LENGTH)
        val safeName = definition.name.trim().take(MAX_NAME_LENGTH)
        val safeUnit = definition.unit.trim().take(MAX_UNIT_LENGTH)
        if (safeId.isBlank() || safeName.isBlank() || safeUnit.isBlank()) {
            return null
        }
        return definition.copy(
            id = safeId,
            name = safeName,
            unit = safeUnit,
            dailyGoal = definition.dailyGoal.coerceIn(MIN_GOAL, MAX_DAILY_COUNT),
            quickIncrement = definition.quickIncrement.coerceIn(
                MIN_QUICK_INCREMENT,
                MAX_QUICK_INCREMENT
            )
        )
    }

    /**
     * 校验记录中的项目快照，并按exerciseId去重。
     *
     * @param record 页面或传感器生成的记录。
     *
     * @return 数值和文本均在安全范围内的记录。
     */
    private fun sanitizeRecord(record: DailyFitnessRecord): DailyFitnessRecord {
        val uniqueItems = linkedMapOf<String, FitnessRecordItem>()
        record.items.forEach { item ->
            val safeId = item.exerciseId.trim().take(MAX_ID_LENGTH)
            val safeName = item.name.trim().take(MAX_NAME_LENGTH)
            val safeUnit = item.unit.trim().take(MAX_UNIT_LENGTH)
            if (safeId.isNotBlank() && safeName.isNotBlank() && safeUnit.isNotBlank()) {
                uniqueItems[safeId] = item.copy(
                    exerciseId = safeId,
                    name = safeName,
                    unit = safeUnit,
                    count = item.count.coerceIn(0, MAX_DAILY_COUNT),
                    goal = item.goal.coerceIn(MIN_GOAL, MAX_DAILY_COUNT)
                )
            }
        }
        return record.copy(items = uniqueItems.values.toList())
    }

    /**
     * 以日期为唯一键替换记录，并限制最多保存约一年的数据。
     *
     * @param records 需要原地修改的集合。
     * @param updated 最新记录。
     *
     * @return 无返回值。
     */
    private fun replaceRecord(
        records: MutableList<DailyFitnessRecord>,
        updated: DailyFitnessRecord
    ) {
        records.removeAll { it.dateEpochDay == updated.dateEpochDay }
        records.add(updated)
        records.sortByDescending { it.dateEpochDay }
        if (records.size > MAX_STORED_DAYS) {
            records.subList(MAX_STORED_DAYS, records.size).clear()
        }
    }

    /**
     * 把动态项目列表序列化为JSON数组。
     *
     * @param definitions 待保存项目。
     *
     * @return SharedPreferences可保存的JSON文本。
     */
    private fun definitionsToJson(
        definitions: List<FitnessExerciseDefinition>
    ): String {
        val jsonArray = JSONArray()
        definitions.forEach { definition ->
            jsonArray.put(definition.toJsonObject())
        }
        return jsonArray.toString()
    }

    /**
     * 把记录列表序列化为JSON数组。
     *
     * @param records 待保存记录。
     *
     * @return SharedPreferences可保存的JSON文本。
     */
    private fun recordsToJson(records: List<DailyFitnessRecord>): String {
        val jsonArray = JSONArray()
        records.forEach { record ->
            jsonArray.put(record.toJsonObject())
        }
        return jsonArray.toString()
    }

    /**
     * 将JSON对象转换为动态项目定义。
     *
     * @return 有效项目定义；关键字段缺失时返回null。
     */
    private fun JSONObject.toExerciseDefinition(): FitnessExerciseDefinition? {
        val trackingType = runCatching {
            FitnessTrackingType.valueOf(
                optString(JSON_TRACKING_TYPE, FitnessTrackingType.MANUAL.name)
            )
        }.getOrDefault(FitnessTrackingType.MANUAL)
        return sanitizeDefinition(
            FitnessExerciseDefinition(
                id = optString(JSON_ID),
                name = optString(JSON_NAME),
                unit = optString(JSON_UNIT),
                dailyGoal = optInt(JSON_DAILY_GOAL, MIN_GOAL),
                quickIncrement = optInt(JSON_QUICK_INCREMENT, DEFAULT_QUICK_INCREMENT),
                trackingType = trackingType
            )
        )
    }

    /**
     * 将项目定义转换为JSON对象。
     *
     * @return 包含全部定义字段的JSONObject。
     */
    private fun FitnessExerciseDefinition.toJsonObject(): JSONObject {
        return JSONObject()
            .put(JSON_ID, id)
            .put(JSON_NAME, name)
            .put(JSON_UNIT, unit)
            .put(JSON_DAILY_GOAL, dailyGoal)
            .put(JSON_QUICK_INCREMENT, quickIncrement)
            .put(JSON_TRACKING_TYPE, trackingType.name)
    }

    /**
     * 将新旧JSON记录转换为DailyFitnessRecord。
     *
     * @return 动态项目记录；旧固定字段会自动映射到三个默认项目。
     */
    private fun JSONObject.toFitnessRecord(): DailyFitnessRecord {
        val epochDay = getLong(JSON_DATE_EPOCH_DAY)
        val itemArray = optJSONArray(JSON_ITEMS)
        if (itemArray != null) {
            val items = buildList {
                for (index in 0 until itemArray.length()) {
                    val itemObject = itemArray.getJSONObject(index)
                    val trackingType = runCatching {
                        FitnessTrackingType.valueOf(
                            itemObject.optString(
                                JSON_TRACKING_TYPE,
                                FitnessTrackingType.MANUAL.name
                            )
                        )
                    }.getOrDefault(FitnessTrackingType.MANUAL)
                    add(
                        FitnessRecordItem(
                            exerciseId = itemObject.optString(JSON_EXERCISE_ID),
                            name = itemObject.optString(JSON_NAME),
                            unit = itemObject.optString(JSON_UNIT),
                            count = itemObject.optInt(JSON_COUNT, 0),
                            goal = itemObject.optInt(JSON_GOAL, MIN_GOAL),
                            trackingType = trackingType
                        )
                    )
                }
            }
            return sanitizeRecord(
                DailyFitnessRecord(
                    dateEpochDay = epochDay,
                    items = items
                )
            )
        }

        // 旧版记录只有固定字段；使用记录自己的目标快照构造动态项目，确保升级后历史达标不变。
        val legacyDefinitions = legacyDefaultDefinitions()
        val pushUpDefinition = legacyDefinitions[0].copy(
            dailyGoal = optInt(
                JSON_LEGACY_PUSH_UP_GOAL,
                legacyDefinitions[0].dailyGoal
            )
        )
        val sitUpDefinition = legacyDefinitions[1].copy(
            dailyGoal = optInt(
                JSON_LEGACY_SIT_UP_GOAL,
                legacyDefinitions[1].dailyGoal
            )
        )
        val stepDefinition = legacyDefinitions[2].copy(
            dailyGoal = optInt(
                JSON_LEGACY_STEP_GOAL,
                legacyDefinitions[2].dailyGoal
            )
        )
        return sanitizeRecord(
            DailyFitnessRecord(
                dateEpochDay = epochDay,
                items = listOf(
                    pushUpDefinition.toRecordItem(optInt(JSON_LEGACY_PUSH_UPS, 0)),
                    sitUpDefinition.toRecordItem(optInt(JSON_LEGACY_SIT_UPS, 0)),
                    stepDefinition.toRecordItem(optInt(JSON_LEGACY_STEPS, 0))
                )
            )
        )
    }

    /**
     * 将动态记录转换为包含项目快照的JSON对象。
     *
     * @return 可持久化的JSONObject。
     */
    private fun DailyFitnessRecord.toJsonObject(): JSONObject {
        val itemArray = JSONArray()
        items.forEach { item ->
            itemArray.put(
                JSONObject()
                    .put(JSON_EXERCISE_ID, item.exerciseId)
                    .put(JSON_NAME, item.name)
                    .put(JSON_UNIT, item.unit)
                    .put(JSON_COUNT, item.count)
                    .put(JSON_GOAL, item.goal)
                    .put(JSON_TRACKING_TYPE, item.trackingType.name)
            )
        }
        return JSONObject()
            .put(JSON_DATE_EPOCH_DAY, dateEpochDay)
            .put(JSON_ITEMS, itemArray)
    }

    /**
     * 将Long计数限制到页面和存储可承受的Int范围。
     *
     * @param value 原始计数。
     *
     * @return 0到MAX_DAILY_COUNT之间的安全值。
     */
    private fun safeCount(value: Long): Int {
        return value.coerceIn(0L, MAX_DAILY_COUNT.toLong()).toInt()
    }

    private companion object {
        const val TAG = "FitnessRepository"
        const val PREFERENCE_NAME = "harley_fitness"
        const val KEY_EXERCISE_DEFINITIONS = "exercise_definitions_v2"
        const val KEY_RECORDS = "records"
        const val KEY_LAST_SENSOR_TOTAL = "last_sensor_total"
        const val KEY_LAST_SENSOR_DAY = "last_sensor_day"

        // 旧版键只读不删，专门用于无损迁移固定三项数据。
        const val KEY_LEGACY_PUSH_UP_GOAL = "push_up_goal"
        const val KEY_LEGACY_SIT_UP_GOAL = "sit_up_goal"
        const val KEY_LEGACY_STEP_GOAL = "step_goal"
        const val JSON_LEGACY_PUSH_UPS = "push_ups"
        const val JSON_LEGACY_SIT_UPS = "sit_ups"
        const val JSON_LEGACY_STEPS = "steps"
        const val JSON_LEGACY_PUSH_UP_GOAL = "push_up_goal"
        const val JSON_LEGACY_SIT_UP_GOAL = "sit_up_goal"
        const val JSON_LEGACY_STEP_GOAL = "step_goal"

        const val JSON_DATE_EPOCH_DAY = "date_epoch_day"
        const val JSON_ITEMS = "items"
        const val JSON_ID = "id"
        const val JSON_EXERCISE_ID = "exercise_id"
        const val JSON_NAME = "name"
        const val JSON_UNIT = "unit"
        const val JSON_DAILY_GOAL = "daily_goal"
        const val JSON_QUICK_INCREMENT = "quick_increment"
        const val JSON_TRACKING_TYPE = "tracking_type"
        const val JSON_COUNT = "count"
        const val JSON_GOAL = "goal"

        const val DEFAULT_HISTORY_DAYS = 31
        const val MAX_STORED_DAYS = 370
        const val MAX_EXERCISE_DEFINITIONS = 20
        const val MIN_GOAL = 1
        const val MAX_DAILY_COUNT = 1_000_000
        const val MIN_QUICK_INCREMENT = 1
        const val MAX_QUICK_INCREMENT = 100_000
        const val DEFAULT_QUICK_INCREMENT = 5
        const val MAX_SENSOR_DELTA = 100_000
        const val MAX_ID_LENGTH = 64
        const val MAX_NAME_LENGTH = 20
        const val MAX_UNIT_LENGTH = 8
        const val NO_SENSOR_BASELINE = -1L
        const val NO_SENSOR_DAY = Long.MIN_VALUE
    }
}

/**
 * 一次系统计步同步的结果。
 *
 * @param record 同步后的当天记录。
 * @param addedSteps 本次实际新增到自动步数项目的步数。
 * @param baselineEstablished 是否因首次读取、跨天或重启而只建立新基准。
 */
data class StepSyncResult(
    val record: DailyFitnessRecord,
    val addedSteps: Int,
    val baselineEstablished: Boolean
)
