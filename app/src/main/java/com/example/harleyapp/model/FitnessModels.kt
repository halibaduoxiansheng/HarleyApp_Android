package com.example.harleyapp.model

/**
 * 运动项目的记录方式。
 *
 * 使用方法：
 * 手动增加次数、分钟或组数的项目使用MANUAL；需要连接手机TYPE_STEP_COUNTER传感器的项目
 * 使用STEP_COUNTER。同一时间仓库只允许存在一个STEP_COUNTER项目。
 */
enum class FitnessTrackingType {
    MANUAL,
    STEP_COUNTER
}

/**
 * 用户可以增删改查的运动项目定义。
 *
 * 使用方法：
 * 页面通过FitnessRepository.getExerciseDefinitions读取列表；新增或编辑后调用
 * FitnessRepository.upsertExerciseDefinition保存。id是稳定主键，修改名称时不得重新生成，
 * 这样历史记录和区间汇总仍能识别为同一个项目。
 *
 * @param id 项目稳定标识；新建项目可传空字符串，由仓库生成唯一标识。
 * @param name 用户看到的项目名称，例如“俯卧撑”“跳绳”。
 * @param unit 数量单位，例如“次”“分钟”“公里”。当前版本使用非负整数记录数量。
 * @param dailyGoal 每日目标数量。
 * @param quickIncrement 今日卡片每次快速增加的数量。
 * @param trackingType 手动记录或手机自动计步类型。
 */
data class FitnessExerciseDefinition(
    val id: String,
    val name: String,
    val unit: String,
    val dailyGoal: Int,
    val quickIncrement: Int,
    val trackingType: FitnessTrackingType = FitnessTrackingType.MANUAL
) {

    /**
     * 创建当天记录使用的项目快照。
     *
     * @param count 初始完成量，默认0。
     *
     * @return 包含当前名称、单位、目标和记录方式的快照。
     */
    fun toRecordItem(count: Int = 0): FitnessRecordItem {
        return FitnessRecordItem(
            exerciseId = id,
            name = name,
            unit = unit,
            count = count,
            goal = dailyGoal,
            trackingType = trackingType
        )
    }

    companion object {
        const val DEFAULT_PUSH_UP_ID = "push_up"
        const val DEFAULT_SIT_UP_ID = "sit_up"
        const val DEFAULT_STEP_ID = "steps"
        const val DEFAULT_PUSH_UP_GOAL = 30
        const val DEFAULT_SIT_UP_GOAL = 50
        const val DEFAULT_STEP_GOAL = 8_000

        /**
         * 创建首次安装或旧版本迁移时使用的三个默认项目。
         *
         * @param pushUpGoal 旧版本俯卧撑目标。
         * @param sitUpGoal 旧版本仰卧起坐目标。
         * @param stepGoal 旧版本步数目标。
         *
         * @return 顺序固定为俯卧撑、仰卧起坐、步行的动态项目列表。
         */
        fun defaultDefinitions(
            pushUpGoal: Int = DEFAULT_PUSH_UP_GOAL,
            sitUpGoal: Int = DEFAULT_SIT_UP_GOAL,
            stepGoal: Int = DEFAULT_STEP_GOAL
        ): List<FitnessExerciseDefinition> {
            return listOf(
                FitnessExerciseDefinition(
                    id = DEFAULT_PUSH_UP_ID,
                    name = "俯卧撑",
                    unit = "次",
                    dailyGoal = pushUpGoal,
                    quickIncrement = 5
                ),
                FitnessExerciseDefinition(
                    id = DEFAULT_SIT_UP_ID,
                    name = "仰卧起坐",
                    unit = "次",
                    dailyGoal = sitUpGoal,
                    quickIncrement = 5
                ),
                FitnessExerciseDefinition(
                    id = DEFAULT_STEP_ID,
                    name = "步行",
                    unit = "步",
                    dailyGoal = stepGoal,
                    quickIncrement = 1_000,
                    trackingType = FitnessTrackingType.STEP_COUNTER
                )
            )
        }
    }
}

/**
 * 某一天内单个运动项目的完成量和目标快照。
 *
 * 使用方法：
 * DailyFitnessRecord以列表保存这些快照。项目之后被改名、修改目标或删除时，已经保存的历史记录
 * 仍保留当天真实采用的名称、单位和目标，避免历史达标结果被新设置反向改变。
 *
 * @param exerciseId 对应FitnessExerciseDefinition.id。
 * @param name 当天项目名称快照。
 * @param unit 当天单位快照。
 * @param count 当天实际完成量。
 * @param goal 当天目标快照。
 * @param trackingType 当天采用的记录方式快照。
 */
data class FitnessRecordItem(
    val exerciseId: String,
    val name: String,
    val unit: String,
    val count: Int = 0,
    val goal: Int,
    val trackingType: FitnessTrackingType = FitnessTrackingType.MANUAL
) {

    /**
     * 计算该项目的显示进度。
     *
     * @return 0到1之间的进度；超额完成时固定返回1。
     */
    fun progress(): Float {
        return (count.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /**
     * 判断该项目当天是否达到目标。
     *
     * @return 完成量大于等于正数目标时返回true。
     */
    fun isComplete(): Boolean {
        return goal > 0 && count >= goal
    }
}

/**
 * 某一天的动态运动记录。
 *
 * 使用方法：
 * 通过itemFor读取单项，通过withCount生成修改后的不可变副本；页面使用completedTaskCount、
 * isComplete和hasRecordedActivity展示完成状态。items保存当天项目快照，因此支持任意项目数量。
 *
 * @param dateEpochDay LocalDate转换得到的日期序号。
 * @param items 当天所有运动项目的完成量和目标快照。
 */
data class DailyFitnessRecord(
    val dateEpochDay: Long,
    val items: List<FitnessRecordItem> = emptyList()
) {

    /**
     * 查找指定项目的当天快照。
     *
     * @param exerciseId 项目稳定标识。
     *
     * @return 找到时返回快照，否则返回null。
     */
    fun itemFor(exerciseId: String): FitnessRecordItem? {
        return items.firstOrNull { it.exerciseId == exerciseId }
    }

    /**
     * 读取指定项目的完成量。
     *
     * @param exerciseId 项目稳定标识。
     *
     * @return 对应完成量；记录中没有该项目时返回0。
     */
    fun countFor(exerciseId: String): Int {
        return itemFor(exerciseId)?.count ?: 0
    }

    /**
     * 更新一个项目的完成量，并保留其他项目顺序与快照。
     *
     * @param exerciseId 需要修改的项目标识。
     * @param count 新完成量；仓库保存前还会执行范围校验。
     *
     * @return 找到项目时返回更新后的副本；项目不存在时返回原记录。
     */
    fun withCount(exerciseId: String, count: Int): DailyFitnessRecord {
        return copy(
            items = items.map { item ->
                if (item.exerciseId == exerciseId) {
                    item.copy(count = count)
                } else {
                    item
                }
            }
        )
    }

    /**
     * 把当前项目定义补入记录。
     *
     * 使用方法：
     * 用户新增运动项目后，编辑旧日期时调用本函数可出现新的输入框。历史中已有项目默认保留原快照；
     * 只有refreshExisting为true时才用最新名称、单位、目标刷新已有项目，通常仅用于今天。
     *
     * @param definitions 当前启用的项目定义。
     * @param refreshExisting 是否刷新已有项目快照。
     *
     * @return 合并项目后的记录副本；已经删除的历史项目仍会保留。
     */
    fun withDefinitions(
        definitions: List<FitnessExerciseDefinition>,
        refreshExisting: Boolean
    ): DailyFitnessRecord {
        val mergedItems = items.toMutableList()
        definitions.forEach { definition ->
            val existingIndex = mergedItems.indexOfFirst {
                it.exerciseId == definition.id
            }
            if (existingIndex < 0) {
                mergedItems.add(definition.toRecordItem())
            } else if (refreshExisting) {
                val existing = mergedItems[existingIndex]
                mergedItems[existingIndex] = definition.toRecordItem(existing.count)
            }
        }
        return copy(items = mergedItems)
    }

    /**
     * 计算当天快照中已经达标的项目数量。
     *
     * @return 0到items.size之间的达标项数。
     */
    fun completedTaskCount(): Int {
        return items.count(FitnessRecordItem::isComplete)
    }

    /**
     * 按当前启用项目计算达标数量，忽略已经删除但仍保留在当天历史里的项目。
     *
     * @param definitions 当前启用项目定义。
     *
     * @return 当前启用项目中的达标数量。
     */
    fun completedTaskCount(
        definitions: List<FitnessExerciseDefinition>
    ): Int {
        val activeIds = definitions.mapTo(hashSetOf()) { it.id }
        return items.count { item ->
            item.exerciseId in activeIds && item.isComplete()
        }
    }

    /**
     * 判断历史当天的全部项目是否达标。
     *
     * @return 当天至少有一个项目且全部达标时返回true。
     */
    fun isComplete(): Boolean {
        return items.isNotEmpty() && completedTaskCount() == items.size
    }

    /**
     * 按当前启用项目判断今天是否全部达标。
     *
     * @param definitions 当前启用项目定义。
     *
     * @return 至少启用一个项目且全部达到各自目标时返回true。
     */
    fun isComplete(
        definitions: List<FitnessExerciseDefinition>
    ): Boolean {
        return definitions.isNotEmpty() &&
            completedTaskCount(definitions) == definitions.size
    }

    /**
     * 判断当天是否至少记录过一项实际运动量。
     *
     * @return 任一项目完成量大于0时返回true，否则返回false。
     */
    fun hasRecordedActivity(): Boolean {
        return items.any { it.count > 0 }
    }
}

/**
 * 日期区间内一个运动项目的汇总结果。
 *
 * @param exerciseId 项目稳定标识。
 * @param name 区间内最近一条记录保存的项目名称。
 * @param unit 区间内最近一条记录保存的单位。
 * @param totalCount 区间总完成量。
 * @param activeDays 完成量大于0的天数。
 * @param goalReachedDays 达到当天目标的天数。
 */
data class FitnessRangeItemSummary(
    val exerciseId: String,
    val name: String,
    val unit: String,
    val totalCount: Long,
    val activeDays: Int,
    val goalReachedDays: Int
)

/**
 * 用户选择的日期区间汇总。
 *
 * @param startEpochDay 区间开始日期，包含当天。
 * @param endEpochDay 区间结束日期，包含当天。
 * @param totalDays 区间自然日数量。
 * @param recordedDays 至少有一项完成量的天数。
 * @param fullyCompletedDays 当天全部快照项目达标的天数。
 * @param itemSummaries 各项目累计结果。
 */
data class FitnessRangeSummary(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val totalDays: Int,
    val recordedDays: Int,
    val fullyCompletedDays: Int,
    val itemSummaries: List<FitnessRangeItemSummary>
)

/**
 * 计算一段日期内的运动汇总。
 *
 * 使用方法：
 * 把FitnessRepository.getRecordsInRange返回的真实记录与查询起止日期传入。函数不会修改数据，
 * 可直接用于Compose页面和本地单元测试。
 *
 * @param records 区间内已保存的记录，可为任意顺序。
 * @param startEpochDay 查询开始日期。
 * @param endEpochDay 查询结束日期。
 *
 * @return 日期经过纠正、按项目累计后的FitnessRangeSummary。
 */
fun calculateFitnessRangeSummary(
    records: List<DailyFitnessRecord>,
    startEpochDay: Long,
    endEpochDay: Long
): FitnessRangeSummary {
    val safeStart = minOf(startEpochDay, endEpochDay)
    val safeEnd = maxOf(startEpochDay, endEpochDay)
    val rangeRecords = records
        .filter { it.dateEpochDay in safeStart..safeEnd && it.hasRecordedActivity() }
        .sortedByDescending { it.dateEpochDay }
    val summaryById = linkedMapOf<String, MutableRangeItemSummary>()

    rangeRecords.forEach { record ->
        record.items.forEach { item ->
            val summary = summaryById.getOrPut(item.exerciseId) {
                MutableRangeItemSummary(
                    exerciseId = item.exerciseId,
                    name = item.name,
                    unit = item.unit
                )
            }
            summary.totalCount += item.count.toLong()
            if (item.count > 0) {
                summary.activeDays += 1
            }
            if (item.isComplete()) {
                summary.goalReachedDays += 1
            }
        }
    }

    return FitnessRangeSummary(
        startEpochDay = safeStart,
        endEpochDay = safeEnd,
        totalDays = (safeEnd - safeStart + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        recordedDays = rangeRecords.size,
        fullyCompletedDays = rangeRecords.count(DailyFitnessRecord::isComplete),
        itemSummaries = summaryById.values
            .filter { it.totalCount > 0L }
            .map { summary ->
                FitnessRangeItemSummary(
                    exerciseId = summary.exerciseId,
                    name = summary.name,
                    unit = summary.unit,
                    totalCount = summary.totalCount,
                    activeDays = summary.activeDays,
                    goalReachedDays = summary.goalReachedDays
                )
            }
    )
}

/**
 * 区间汇总计算期间使用的可变累加器，只在calculateFitnessRangeSummary内部创建。
 */
private data class MutableRangeItemSummary(
    val exerciseId: String,
    val name: String,
    val unit: String,
    var totalCount: Long = 0L,
    var activeDays: Int = 0,
    var goalReachedDays: Int = 0
)
