package com.example.harleyapp.model

/** 本机最多保留最近30次有效憋气记录。 */
const val BREATH_HOLD_HISTORY_LIMIT = 30

/** 防止偏好文件损坏造成异常展示，单次记录最大接受24小时。 */
const val BREATH_HOLD_MAX_VALID_DURATION_MILLIS = 24L * 60L * 60L * 1000L

/**
 * 一次完成的憋气计时记录。
 *
 * @param id 本机生成的稳定唯一标识，用于Compose列表键值。
 * @param completedAtMillis 用户结束本次计时时的Unix毫秒时间。
 * @param durationMillis 本次从“开始憋气”到点击结束的单调时钟时长。
 */
data class BreathHoldRecord(
    val id: String,
    val completedAtMillis: Long,
    val durationMillis: Long
)

/**
 * 历史记录计算出的概览指标。
 *
 * @param attemptCount 有效记录次数。
 * @param bestDurationMillis 最长一次时长。
 * @param averageDurationMillis 全部保留记录的平均时长。
 * @param latestDurationMillis 最近一次时长。
 * @param latestChangeMillis 最近一次相对上一次的变化；首次记录时为0。
 */
data class BreathHoldSummary(
    val attemptCount: Int,
    val bestDurationMillis: Long,
    val averageDurationMillis: Long,
    val latestDurationMillis: Long,
    val latestChangeMillis: Long
)

/**
 * 从按完成时间倒序排列的历史中计算次数、最佳、平均和最近变化。
 *
 * 使用方法：Repository加载或保存记录后，将完整列表传入本函数；函数会自行过滤非正数时长，
 * 即使调用方顺序不稳定也会按完成时间找到最近两次。
 *
 * @param records 本机憋气历史记录。
 * @return 无有效记录时所有值为0；有记录时返回稳定概览。
 */
fun calculateBreathHoldSummary(records: List<BreathHoldRecord>): BreathHoldSummary {
    val validRecords = records
        .filter { record ->
            record.durationMillis in 1..BREATH_HOLD_MAX_VALID_DURATION_MILLIS
        }
        .sortedByDescending(BreathHoldRecord::completedAtMillis)
    if (validRecords.isEmpty()) {
        return BreathHoldSummary(
            attemptCount = 0,
            bestDurationMillis = 0L,
            averageDurationMillis = 0L,
            latestDurationMillis = 0L,
            latestChangeMillis = 0L
        )
    }

    val latestDuration = validRecords.first().durationMillis
    val previousDuration = validRecords.getOrNull(1)?.durationMillis
    return BreathHoldSummary(
        attemptCount = validRecords.size,
        bestDurationMillis = validRecords.maxOf(BreathHoldRecord::durationMillis),
        averageDurationMillis = validRecords.sumOf(BreathHoldRecord::durationMillis) /
            validRecords.size,
        latestDurationMillis = latestDuration,
        latestChangeMillis = previousDuration?.let { previous ->
            latestDuration - previous
        } ?: 0L
    )
}

/**
 * 从憋气历史中移除用户明确选中的记录，并保持其余记录的原始顺序。
 *
 * 使用方法：
 * 历史管理界面把勾选记录的稳定ID集合与当前完整历史传入本函数，再由Repository持久化返回列表。
 * 空白ID、未知ID和空选择都不会误删数据；若历史中意外存在相同ID，则对应记录会一并删除，避免
 * 用户取消选择模式后仍残留无法单独识别的重复项。
 *
 * @param records 当前完整憋气历史。
 * @param selectedRecordIds 用户明确选择删除的记录ID集合。
 * @return 删除选中项后的新列表；没有有效选择时返回内容相同的新列表。
 */
fun removeBreathHoldRecords(
    records: List<BreathHoldRecord>,
    selectedRecordIds: Set<String>
): List<BreathHoldRecord> {
    val safeSelectedIds = selectedRecordIds
        .filterTo(mutableSetOf()) { recordId -> recordId.isNotBlank() }
    if (safeSelectedIds.isEmpty()) return records.toList()

    return records.filterNot { record -> record.id in safeSelectedIds }
}
