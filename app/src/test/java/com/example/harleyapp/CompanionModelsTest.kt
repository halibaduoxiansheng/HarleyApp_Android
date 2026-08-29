package com.example.harleyapp

import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.afterClaimingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证伙伴等级、每日任务去重和全部完成奖励，不依赖Android存储。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest，由JUnit自动运行全部测试。
 */
class CompanionModelsTest {

    /**
     * 验证每100经验提升一级，并在指定等级切换到对应形态。
     *
     * @return 无返回值；等级或形态门槛错误时由JUnit报告失败。
     */
    @Test
    fun experienceUnlocksExpectedLevelsAndForms() {
        val startingProgress = CompanionProgress(totalExperience = 0)
        val evolvedProgress = CompanionProgress(totalExperience = 500)
        val finalProgress = CompanionProgress(totalExperience = 900)

        assertEquals(1, startingProgress.level)
        assertEquals("幼年星尾狐", CompanionCategory.FOREST.formNameFor(startingProgress.level))
        assertEquals(6, evolvedProgress.level)
        assertEquals("星辉灵狐", CompanionCategory.FOREST.formNameFor(evolvedProgress.level))
        assertEquals(10, finalProgress.level)
        assertEquals("森林守护者", CompanionCategory.FOREST.formNameFor(finalProgress.level))
        assertEquals(4, finalProgress.unlockedSkills.size)
    }

    /**
     * 验证同一天同一任务只奖励一次，跨天后可以再次领取。
     *
     * @return 无返回值；经验重复发放或跨天未重置时由JUnit报告失败。
     */
    @Test
    fun dailyTaskIsDeduplicatedAndResetsOnNextDay() {
        val firstClaim = CompanionProgress().afterClaimingTask(
            task = CompanionTask.DAILY_OPEN,
            currentEpochDay = 100L
        )
        val duplicateClaim = firstClaim.afterClaimingTask(
            task = CompanionTask.DAILY_OPEN,
            currentEpochDay = 100L
        )
        val nextDayClaim = duplicateClaim.afterClaimingTask(
            task = CompanionTask.DAILY_OPEN,
            currentEpochDay = 101L
        )

        assertEquals(10, firstClaim.totalExperience)
        assertEquals(firstClaim, duplicateClaim)
        assertEquals(20, nextDayClaim.totalExperience)
        assertEquals(setOf(CompanionTask.DAILY_OPEN), nextDayClaim.completedTasks)
    }

    /**
     * 验证最后一项常规任务完成时只追加一次20经验的全部完成奖励。
     *
     * @return 无返回值；奖励缺失、重复或任务状态错误时由JUnit报告失败。
     */
    @Test
    fun allDailyTasksGrantSingleCompletionBonus() {
        val requiredTasks = CompanionTask.entries.filter { task ->
            task.countsTowardDailyBonus
        }
        val completedProgress = requiredTasks.fold(CompanionProgress()) { progress, task ->
            progress.afterClaimingTask(task, currentEpochDay = 200L)
        }
        val expectedExperience = requiredTasks.sumOf { task -> task.experience } +
            CompanionTask.DAILY_BONUS.experience
        val duplicateLastTask = completedProgress.afterClaimingTask(
            task = requiredTasks.last(),
            currentEpochDay = 200L
        )

        assertEquals(expectedExperience, completedProgress.totalExperience)
        assertTrue(CompanionTask.DAILY_BONUS in completedProgress.completedTasks)
        assertFalse(completedProgress.completedTasks.isEmpty())
        assertEquals(completedProgress, duplicateLastTask)
    }
}
