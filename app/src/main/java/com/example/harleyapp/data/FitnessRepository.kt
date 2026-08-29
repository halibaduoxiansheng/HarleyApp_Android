package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.FitnessExercise
import com.example.harleyapp.model.FitnessGoals
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 管理健身目标、每日训练记录和计步传感器基准值。
 *
 * 使用方法：
 * 使用Application Context创建实例。通过getGoals和getTodayRecord读取页面初始状态；
 * 手动训练使用updateExercise，步数校准使用setSteps，传感器数据使用syncSensorSteps。
 * 所有数据仅写入本应用私有SharedPreferences，不需要账号或网络。
 *
 * @param context Android上下文，用于打开当前存储区域下的应用私有SharedPreferences。
 */
class FitnessRepository(context: Context) {

    // 仅保存SharedPreferences实例，不持有页面Context；直接使用传入Context可保留其存储区域属性。
    private val preferences = context.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取当前每日目标，并修正旧版本或异常数据中的越界值。
     *
     * @return 可直接用于页面显示和保存的安全目标。
     */
    fun getGoals(): FitnessGoals {
        return FitnessGoals(
            pushUpGoal = preferences.getInt(
                KEY_PUSH_UP_GOAL,
                FitnessGoals.DEFAULT_PUSH_UP_GOAL
            ).coerceIn(MIN_EXERCISE_GOAL, MAX_EXERCISE_GOAL),
            sitUpGoal = preferences.getInt(
                KEY_SIT_UP_GOAL,
                FitnessGoals.DEFAULT_SIT_UP_GOAL
            ).coerceIn(MIN_EXERCISE_GOAL, MAX_EXERCISE_GOAL),
            stepGoal = preferences.getInt(
                KEY_STEP_GOAL,
                FitnessGoals.DEFAULT_STEP_GOAL
            ).coerceIn(MIN_STEP_GOAL, MAX_STEP_GOAL)
        )
    }

    /**
     * 保存新的每日目标，并同步更新今天的目标快照。
     *
     * 使用方法：
     * 目标编辑对话框校验成功后调用。历史日期保留原目标，只有今天和未来新记录使用新目标。
     *
     * @param goals 用户确认的新目标。
     * @param todayEpochDay 今天的日期序号，默认使用系统本地日期。
     *
     * @return 全部字段同步写入成功返回true，否则返回false。
     */
    @Synchronized
    fun saveGoals(
        goals: FitnessGoals,
        todayEpochDay: Long = LocalDate.now().toEpochDay()
    ): Boolean {
        val safeGoals = sanitizeGoals(goals)
        val records = readStoredRecords().toMutableList()
        val todayRecord = findRecord(records, todayEpochDay)
            .withGoals(safeGoals)
        replaceRecord(records, todayRecord)

        val success = preferences.edit()
            .putInt(KEY_PUSH_UP_GOAL, safeGoals.pushUpGoal)
            .putInt(KEY_SIT_UP_GOAL, safeGoals.sitUpGoal)
            .putInt(KEY_STEP_GOAL, safeGoals.stepGoal)
            .putString(KEY_RECORDS, recordsToJson(records))
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist fitness goals")
        }

        return success
    }

    /**
     * 读取今天的运动记录；尚无记录时返回只包含当前目标的空记录。
     *
     * @param epochDay 要读取的日期序号，默认使用今天。
     *
     * @return 指定日期的完整记录，不会返回null。
     */
    fun getTodayRecord(
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord {
        return getRecord(epochDay)
    }

    /**
     * 读取任意指定日期的运动记录，供历史补记和编辑对话框使用。
     *
     * @param epochDay 要读取的日期序号。
     *
     * @return 已保存记录；尚未保存时返回带当前目标快照的空记录。
     */
    fun getRecord(epochDay: Long): DailyFitnessRecord {
        return findRecord(readStoredRecords(), epochDay)
    }

    /**
     * 按从今天到更早日期的顺序返回连续历史记录。
     *
     * 使用方法：
     * 近7天列表可直接调用getRecentRecords()。缺失日期会返回零完成量记录，确保历史列表连续。
     *
     * @param days 需要展示的天数，内部限制为1到31天。
     * @param endEpochDay 最后一天的日期序号，默认使用今天。
     *
     * @return 日期连续、按时间倒序排列的记录列表。
     */
    fun getRecentRecords(
        days: Int = DEFAULT_HISTORY_DAYS,
        endEpochDay: Long = LocalDate.now().toEpochDay()
    ): List<DailyFitnessRecord> {
        val safeDays = days.coerceIn(1, MAX_VISIBLE_HISTORY_DAYS)
        val recordsByDate = readStoredRecords().associateBy { it.dateEpochDay }
        val currentGoals = getGoals()

        return (0 until safeDays).map { offset ->
            val epochDay = endEpochDay - offset
            recordsByDate[epochDay] ?: emptyRecord(epochDay, currentGoals)
        }
    }

    /**
     * 新增或覆盖保存某一天的完整运动记录。
     *
     * 使用方法：
     * 新增补记和历史编辑共用本接口。dateEpochDay作为唯一键；日期已存在时执行更新，
     * 不存在时创建新记录。调用前页面应要求至少一项运动量大于0。
     *
     * @param record 需要新增或覆盖的完整记录，包含当天目标快照。
     *
     * @return 写入成功时返回经过范围校验的记录，失败时返回null。
     */
    @Synchronized
    fun upsertRecord(record: DailyFitnessRecord): DailyFitnessRecord? {
        val records = readStoredRecords().toMutableList()
        val safeRecord = sanitizeRecord(record)
        return persistUpdatedRecord(records, safeRecord)
    }

    /**
     * 删除指定日期的完整运动记录。
     *
     * 使用方法：
     * 用户在历史列表确认删除后调用。接口采用幂等语义，目标日期本来就没有记录时也视为成功。
     * 删除今天记录后，页面重新读取会显示目标不变、完成量归零的空记录。
     *
     * @param epochDay 需要删除的日期序号。
     *
     * @return 删除后本地状态写入成功返回true，否则返回false。
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
     * 增减当天某项徒手训练次数。
     *
     * 使用方法：
     * 快速打卡按钮传入正数，误点撤销传入负数。结果最低为0，最高受安全上限保护。
     *
     * @param exercise 俯卧撑或仰卧起坐项目。
     * @param delta 本次需要增加或减少的次数。
     * @param epochDay 记录日期，默认使用今天。
     *
     * @return 写入成功时返回更新后的记录，失败时返回null。
     */
    @Synchronized
    fun updateExercise(
        exercise: FitnessExercise,
        delta: Int,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord? {
        val records = readStoredRecords().toMutableList()
        val current = findRecord(records, epochDay)
        val updated = when (exercise) {
            FitnessExercise.PUSH_UP -> current.copy(
                pushUps = safeCount(current.pushUps.toLong() + delta)
            )

            FitnessExercise.SIT_UP -> current.copy(
                sitUps = safeCount(current.sitUps.toLong() + delta)
            )
        }

        return persistUpdatedRecord(records, updated)
    }

    /**
     * 将某项徒手训练直接设置为目标值，便于用户一键标记达标。
     *
     * @param exercise 俯卧撑或仰卧起坐项目。
     * @param epochDay 记录日期，默认使用今天。
     *
     * @return 写入成功时返回更新后的记录，失败时返回null。
     */
    @Synchronized
    fun completeExercise(
        exercise: FitnessExercise,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord? {
        val records = readStoredRecords().toMutableList()
        val current = findRecord(records, epochDay)
        val updated = when (exercise) {
            FitnessExercise.PUSH_UP -> current.copy(
                pushUps = maxOf(current.pushUps, current.pushUpGoal)
            )

            FitnessExercise.SIT_UP -> current.copy(
                sitUps = maxOf(current.sitUps, current.sitUpGoal)
            )
        }

        return persistUpdatedRecord(records, updated)
    }

    /**
     * 手动校准今天的步数，用于无传感器、未授权或系统计步存在遗漏的情况。
     *
     * @param steps 用户确认的当天总步数。
     * @param epochDay 记录日期，默认使用今天。
     *
     * @return 写入成功时返回更新后的记录，失败时返回null。
     */
    @Synchronized
    fun setSteps(
        steps: Int,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): DailyFitnessRecord? {
        val records = readStoredRecords().toMutableList()
        val updated = findRecord(records, epochDay).copy(
            steps = steps.coerceIn(0, MAX_DAILY_COUNT)
        )

        return persistUpdatedRecord(records, updated)
    }

    /**
     * 将系统累计计步值转换为本应用当天的增量步数。
     *
     * 使用方法：
     * StepCounterMonitor每次收到TYPE_STEP_COUNTER事件后调用。第一次读取、跨天或手机重启后只建立新基准，
     * 后续读取才把安全增量加入当天记录，避免把开机以来的全部步数错误算到今天。
     *
     * @param sensorTotal 系统计步传感器自开机以来的累计值。
     * @param epochDay 当前本地日期序号，默认使用今天。
     *
     * @return 写入成功时返回同步结果，失败时返回null。
     */
    @Synchronized
    fun syncSensorSteps(
        sensorTotal: Long,
        epochDay: Long = LocalDate.now().toEpochDay()
    ): StepSyncResult? {
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
        val current = findRecord(records, epochDay)
        val updated = current.copy(
            steps = safeCount(current.steps.toLong() + addedSteps)
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
     * 把单条更新写回记录集合。
     *
     * @param records 当前可变记录集合。
     * @param updated 更新后的记录。
     *
     * @return 写入成功时返回updated，失败时返回null。
     */
    private fun persistUpdatedRecord(
        records: MutableList<DailyFitnessRecord>,
        updated: DailyFitnessRecord
    ): DailyFitnessRecord? {
        replaceRecord(records, updated)
        val success = preferences.edit()
            .putString(KEY_RECORDS, recordsToJson(records))
            .commit()

        if (!success) {
            Log.e(TAG, "Failed to persist fitness record")
            return null
        }

        return updated
    }

    /**
     * 从本地JSON读取记录，并跳过单条损坏数据，避免整个运动页面无法打开。
     *
     * @return 解析成功的全部记录；本地无数据或整体解析失败时返回空列表。
     */
    private fun readStoredRecords(): List<DailyFitnessRecord> {
        val rawRecords = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(rawRecords)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    runCatching {
                        jsonArray.getJSONObject(index).toFitnessRecord()
                    }.onSuccess(::add).onFailure {
                        Log.w(TAG, "Skipped malformed fitness record", it)
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to parse fitness records", it)
        }.getOrDefault(emptyList())
    }

    /**
     * 查找指定日期记录；没有记录时使用当前目标创建空记录。
     *
     * @param records 已读取的记录列表。
     * @param epochDay 日期序号。
     *
     * @return 已有记录或只包含目标快照的空记录。
     */
    private fun findRecord(
        records: List<DailyFitnessRecord>,
        epochDay: Long
    ): DailyFitnessRecord {
        return records.firstOrNull { it.dateEpochDay == epochDay }
            ?: emptyRecord(epochDay, getGoals())
    }

    /**
     * 创建指定日期的零完成量记录。
     *
     * @param epochDay 日期序号。
     * @param goals 该日期采用的目标快照。
     *
     * @return 新的空记录。
     */
    private fun emptyRecord(
        epochDay: Long,
        goals: FitnessGoals
    ): DailyFitnessRecord {
        return DailyFitnessRecord(
            dateEpochDay = epochDay,
            pushUpGoal = goals.pushUpGoal,
            sitUpGoal = goals.sitUpGoal,
            stepGoal = goals.stepGoal
        )
    }

    /**
     * 以日期为唯一键替换记录，并限制本地历史数量。
     *
     * @param records 需要原地修改的记录集合。
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
     * 将记录列表序列化为SharedPreferences可保存的JSON文本。
     *
     * @param records 待保存记录。
     *
     * @return 包含完整字段的JSON数组文本。
     */
    private fun recordsToJson(records: List<DailyFitnessRecord>): String {
        val jsonArray = JSONArray()
        records.forEach { record ->
            jsonArray.put(record.toJsonObject())
        }
        return jsonArray.toString()
    }

    /**
     * 将用户输入目标限制到页面和存储可承受的范围。
     *
     * @param goals 原始目标。
     *
     * @return 修正后的安全目标。
     */
    private fun sanitizeGoals(goals: FitnessGoals): FitnessGoals {
        return FitnessGoals(
            pushUpGoal = goals.pushUpGoal.coerceIn(MIN_EXERCISE_GOAL, MAX_EXERCISE_GOAL),
            sitUpGoal = goals.sitUpGoal.coerceIn(MIN_EXERCISE_GOAL, MAX_EXERCISE_GOAL),
            stepGoal = goals.stepGoal.coerceIn(MIN_STEP_GOAL, MAX_STEP_GOAL)
        )
    }

    /**
     * 校验补记或编辑记录的完成量和目标快照，防止异常输入进入本地历史。
     *
     * @param record 页面提交的原始记录。
     *
     * @return 所有数值均限制在安全范围内的记录。
     */
    private fun sanitizeRecord(record: DailyFitnessRecord): DailyFitnessRecord {
        val safeGoals = sanitizeGoals(
            FitnessGoals(
                pushUpGoal = record.pushUpGoal,
                sitUpGoal = record.sitUpGoal,
                stepGoal = record.stepGoal
            )
        )
        return record.copy(
            pushUps = record.pushUps.coerceIn(0, MAX_DAILY_COUNT),
            sitUps = record.sitUps.coerceIn(0, MAX_DAILY_COUNT),
            steps = record.steps.coerceIn(0, MAX_DAILY_COUNT),
            pushUpGoal = safeGoals.pushUpGoal,
            sitUpGoal = safeGoals.sitUpGoal,
            stepGoal = safeGoals.stepGoal
        )
    }

    /**
     * 将Long计数安全转换为页面使用的Int范围。
     *
     * @param value 原始计数。
     *
     * @return 0到MAX_DAILY_COUNT之间的计数。
     */
    private fun safeCount(value: Long): Int {
        return value.coerceIn(0L, MAX_DAILY_COUNT.toLong()).toInt()
    }

    /**
     * 将单条JSON记录转换为健身模型，并兼容缺少目标快照的早期数据。
     *
     * @return 可直接用于页面的DailyFitnessRecord。
     */
    private fun JSONObject.toFitnessRecord(): DailyFitnessRecord {
        val currentGoals = getGoals()
        return DailyFitnessRecord(
            dateEpochDay = getLong(JSON_DATE_EPOCH_DAY),
            pushUps = optInt(JSON_PUSH_UPS, 0).coerceIn(0, MAX_DAILY_COUNT),
            sitUps = optInt(JSON_SIT_UPS, 0).coerceIn(0, MAX_DAILY_COUNT),
            steps = optInt(JSON_STEPS, 0).coerceIn(0, MAX_DAILY_COUNT),
            pushUpGoal = optInt(JSON_PUSH_UP_GOAL, currentGoals.pushUpGoal)
                .coerceIn(MIN_EXERCISE_GOAL, MAX_EXERCISE_GOAL),
            sitUpGoal = optInt(JSON_SIT_UP_GOAL, currentGoals.sitUpGoal)
                .coerceIn(MIN_EXERCISE_GOAL, MAX_EXERCISE_GOAL),
            stepGoal = optInt(JSON_STEP_GOAL, currentGoals.stepGoal)
                .coerceIn(MIN_STEP_GOAL, MAX_STEP_GOAL)
        )
    }

    /**
     * 将健身模型转换为包含目标快照的JSON对象。
     *
     * @return 可保存到本地的JSONObject。
     */
    private fun DailyFitnessRecord.toJsonObject(): JSONObject {
        return JSONObject()
            .put(JSON_DATE_EPOCH_DAY, dateEpochDay)
            .put(JSON_PUSH_UPS, pushUps)
            .put(JSON_SIT_UPS, sitUps)
            .put(JSON_STEPS, steps)
            .put(JSON_PUSH_UP_GOAL, pushUpGoal)
            .put(JSON_SIT_UP_GOAL, sitUpGoal)
            .put(JSON_STEP_GOAL, stepGoal)
    }

    private companion object {
        const val TAG = "FitnessRepository"
        const val PREFERENCE_NAME = "harley_fitness"
        const val KEY_PUSH_UP_GOAL = "push_up_goal"
        const val KEY_SIT_UP_GOAL = "sit_up_goal"
        const val KEY_STEP_GOAL = "step_goal"
        const val KEY_RECORDS = "records"
        const val KEY_LAST_SENSOR_TOTAL = "last_sensor_total"
        const val KEY_LAST_SENSOR_DAY = "last_sensor_day"
        const val JSON_DATE_EPOCH_DAY = "date_epoch_day"
        const val JSON_PUSH_UPS = "push_ups"
        const val JSON_SIT_UPS = "sit_ups"
        const val JSON_STEPS = "steps"
        const val JSON_PUSH_UP_GOAL = "push_up_goal"
        const val JSON_SIT_UP_GOAL = "sit_up_goal"
        const val JSON_STEP_GOAL = "step_goal"
        const val DEFAULT_HISTORY_DAYS = 7
        const val MAX_VISIBLE_HISTORY_DAYS = 31
        const val MAX_STORED_DAYS = 370
        const val MIN_EXERCISE_GOAL = 1
        const val MAX_EXERCISE_GOAL = 1_000
        const val MIN_STEP_GOAL = 100
        const val MAX_STEP_GOAL = 100_000
        const val MAX_DAILY_COUNT = 1_000_000
        const val MAX_SENSOR_DELTA = 100_000
        const val NO_SENSOR_BASELINE = -1L
        const val NO_SENSOR_DAY = Long.MIN_VALUE
    }
}

/**
 * 一次系统计步同步的结果。
 *
 * @param record 同步后的当天记录。
 * @param addedSteps 本次实际新增到当天记录的步数。
 * @param baselineEstablished 是否因首次读取、跨天或重启而只建立了新基准。
 */
data class StepSyncResult(
    val record: DailyFitnessRecord,
    val addedSteps: Int,
    val baselineEstablished: Boolean
)
