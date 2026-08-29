package com.example.harleyapp

import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.FitnessExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证健身记录的纯计算逻辑，不依赖Android设备和本地数据。
 *
 * 使用方法：
 * 在工程根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class FitnessModelsTest {

    /**
     * 验证单项进度会限制在0到1之间，超额完成不会导致进度条越界。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun exerciseProgressIsClampedForDisplay() {
        val record = DailyFitnessRecord(
            dateEpochDay = 1L,
            pushUps = 45,
            pushUpGoal = 30
        )

        assertEquals(1f, record.progressFor(FitnessExercise.PUSH_UP), 0f)
        assertEquals(0f, record.progressFor(FitnessExercise.SIT_UP), 0f)
    }

    /**
     * 验证只有俯卧撑、仰卧起坐和步数三项全部达标时，整日才算完成。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun dailyCompletionRequiresAllThreeGoals() {
        val incompleteRecord = DailyFitnessRecord(
            dateEpochDay = 1L,
            pushUps = 30,
            sitUps = 50,
            steps = 7_999
        )
        val completedRecord = incompleteRecord.copy(steps = 8_000)

        assertEquals(2, incompleteRecord.completedTaskCount())
        assertFalse(incompleteRecord.isComplete())
        assertEquals(3, completedRecord.completedTaskCount())
        assertTrue(completedRecord.isComplete())
    }

    /**
     * 验证历史空占位记录不会被当作真实数据，任一项目有完成量后才允许管理和删除。
     *
     * @return 无返回值；结果不符时由JUnit报告失败。
     */
    @Test
    fun recordedActivityRequiresAtLeastOnePositiveCount() {
        val emptyRecord = DailyFitnessRecord(dateEpochDay = 1L)

        assertFalse(emptyRecord.hasRecordedActivity())
        assertTrue(emptyRecord.copy(pushUps = 1).hasRecordedActivity())
        assertTrue(emptyRecord.copy(sitUps = 1).hasRecordedActivity())
        assertTrue(emptyRecord.copy(steps = 1).hasRecordedActivity())
    }
}
