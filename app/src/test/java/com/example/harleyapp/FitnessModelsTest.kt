package com.example.harleyapp

import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.FitnessExerciseDefinition
import com.example.harleyapp.model.FitnessRecordItem
import com.example.harleyapp.model.FitnessTrackingType
import com.example.harleyapp.model.calculateFitnessRangeSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证动态运动项目、记录快照和区间汇总的纯计算逻辑。
 *
 * 使用方法：
 * 在工程根目录执行gradlew testDebugUnitTest，由JUnit自动运行；测试不依赖Android设备，
 * 也不会读写用户手机上的真实运动数据。
 */
class FitnessModelsTest {

    /**
     * 验证动态项目进度会限制在0到1之间，超额完成不会让进度条越界。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun exerciseProgressIsClampedForDisplay() {
        val unfinished = FitnessRecordItem(
            exerciseId = "rope",
            name = "跳绳",
            unit = "次",
            count = 0,
            goal = 100
        )
        val exceeded = unfinished.copy(count = 150)

        assertEquals(0f, unfinished.progress(), 0f)
        assertEquals(1f, exceeded.progress(), 0f)
    }

    /**
     * 验证完成状态会随项目数量动态变化，不再固定要求三项。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun dailyCompletionUsesDynamicExerciseCount() {
        val record = DailyFitnessRecord(
            dateEpochDay = 1L,
            items = listOf(
                recordItem("push", "俯卧撑", 30, 30),
                recordItem("plank", "平板支撑", 2, 3)
            )
        )

        assertEquals(1, record.completedTaskCount())
        assertFalse(record.isComplete())
        assertTrue(record.withCount("plank", 3).isComplete())
    }

    /**
     * 验证新增项目会补入旧记录，而默认模式下不会覆盖历史名称和目标快照。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun mergingDefinitionsPreservesHistoricalSnapshot() {
        val historicalRecord = DailyFitnessRecord(
            dateEpochDay = 1L,
            items = listOf(recordItem("push", "旧名称", 20, 30))
        )
        val definitions = listOf(
            definition("push", "俯卧撑", 40),
            definition("rope", "跳绳", 100)
        )

        val preserved = historicalRecord.withDefinitions(
            definitions = definitions,
            refreshExisting = false
        )
        val refreshed = historicalRecord.withDefinitions(
            definitions = definitions,
            refreshExisting = true
        )

        assertEquals("旧名称", preserved.itemFor("push")?.name)
        assertEquals(30, preserved.itemFor("push")?.goal)
        assertEquals(0, preserved.itemFor("rope")?.count)
        assertEquals("俯卧撑", refreshed.itemFor("push")?.name)
        assertEquals(40, refreshed.itemFor("push")?.goal)
    }

    /**
     * 验证区间汇总会统计自然日、记录日、全部达标日和每个项目总量。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun rangeSummaryAggregatesCustomExercises() {
        val records = listOf(
            DailyFitnessRecord(
                dateEpochDay = 10L,
                items = listOf(
                    recordItem("rope", "跳绳", 120, 100),
                    recordItem("run", "跑步", 20, 30, unit = "分钟")
                )
            ),
            DailyFitnessRecord(
                dateEpochDay = 9L,
                items = listOf(
                    recordItem("rope", "跳绳", 80, 100),
                    recordItem("run", "跑步", 30, 30, unit = "分钟")
                )
            )
        )

        val summary = calculateFitnessRangeSummary(
            records = records,
            startEpochDay = 8L,
            endEpochDay = 10L
        )

        assertEquals(3, summary.totalDays)
        assertEquals(2, summary.recordedDays)
        assertEquals(0, summary.fullyCompletedDays)
        assertEquals(200L, summary.itemSummaries.first { it.exerciseId == "rope" }.totalCount)
        assertEquals(50L, summary.itemSummaries.first { it.exerciseId == "run" }.totalCount)
        assertEquals(1, summary.itemSummaries.first { it.exerciseId == "rope" }.goalReachedDays)
    }

    /**
     * 验证默认迁移项目包含可删除的手动项目和唯一自动计步项目。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun legacyDefaultsBecomeDynamicDefinitions() {
        val definitions = FitnessExerciseDefinition.defaultDefinitions(
            pushUpGoal = 12,
            sitUpGoal = 24,
            stepGoal = 6_000
        )

        assertEquals(3, definitions.size)
        assertEquals(12, definitions[0].dailyGoal)
        assertEquals(24, definitions[1].dailyGoal)
        assertEquals(6_000, definitions[2].dailyGoal)
        assertEquals(
            1,
            definitions.count { it.trackingType == FitnessTrackingType.STEP_COUNTER }
        )
    }

    /**
     * 创建测试使用的手动项目定义。
     *
     * @param id 项目标识。
     * @param name 项目名称。
     * @param goal 每日目标。
     *
     * @return 可用于记录合并测试的定义。
     */
    private fun definition(
        id: String,
        name: String,
        goal: Int
    ): FitnessExerciseDefinition {
        return FitnessExerciseDefinition(
            id = id,
            name = name,
            unit = "次",
            dailyGoal = goal,
            quickIncrement = 5
        )
    }

    /**
     * 创建测试使用的项目记录快照。
     *
     * @param id 项目标识。
     * @param name 项目名称。
     * @param count 完成量。
     * @param goal 目标量。
     * @param unit 数量单位，默认“次”。
     *
     * @return 指定字段的FitnessRecordItem。
     */
    private fun recordItem(
        id: String,
        name: String,
        count: Int,
        goal: Int,
        unit: String = "次"
    ): FitnessRecordItem {
        return FitnessRecordItem(
            exerciseId = id,
            name = name,
            unit = unit,
            count = count,
            goal = goal
        )
    }
}
