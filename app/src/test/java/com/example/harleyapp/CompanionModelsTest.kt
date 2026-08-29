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
     * 验证每100经验提升一级、原有Lv.10形态保持不变，并能继续成长到Lv.20。
     *
     * @return 无返回值；等级或形态门槛错误时由JUnit报告失败。
     */
    @Test
    fun experienceUnlocksExpectedLevelsAndForms() {
        val startingProgress = CompanionProgress(totalExperience = 0)
        val evolvedProgress = CompanionProgress(totalExperience = 500)
        val guardianProgress = CompanionProgress(totalExperience = 900)
        val finalProgress = CompanionProgress(totalExperience = 1_900)
        val overflowProgress = CompanionProgress(totalExperience = 99_999)

        assertEquals(1, startingProgress.level)
        assertEquals("幼年星尾狐", CompanionCategory.FOREST.formNameFor(startingProgress.level))
        assertEquals(6, evolvedProgress.level)
        assertEquals("星辉灵狐", CompanionCategory.FOREST.formNameFor(evolvedProgress.level))
        assertEquals(10, guardianProgress.level)
        assertEquals("森林守护者", CompanionCategory.FOREST.formNameFor(guardianProgress.level))
        assertEquals(4, guardianProgress.unlockedSkills.size)
        assertEquals(20, finalProgress.level)
        assertEquals("万象森之灵", CompanionCategory.FOREST.formNameFor(finalProgress.level))
        assertEquals(6, finalProgress.unlockedSkills.size)
        assertEquals(CompanionProgress.MAX_LEVEL, overflowProgress.level)
        assertEquals(CompanionProgress.EXPERIENCE_PER_LEVEL, overflowProgress.experienceInLevel)
    }

    /**
     * 验证六类伙伴都拥有完整且按等级递增的六段形态和六项技能。
     *
     * @return 无返回值；伙伴数量、成长门槛或终极形态配置不完整时由JUnit报告失败。
     */
    @Test
    fun everyCompanionHasCompleteTwentyLevelCollection() {
        val expectedUnlockLevels = listOf(1, 3, 6, 10, 15, 20)
        val expectedSkillLevels = listOf(2, 4, 7, 10, 15, 20)

        assertEquals(6, CompanionCategory.entries.size)
        CompanionCategory.entries.forEach { category ->
            assertEquals(expectedUnlockLevels, category.forms.map { form -> form.unlockLevel })
            assertEquals(expectedSkillLevels, category.skills.map { skill -> skill.unlockLevel })
            assertEquals(category.forms.last().name, category.formNameFor(20))
            assertEquals(5, category.formIndexFor(20))
        }
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
