package com.example.harleyapp

import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionInteraction
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.CompanionShopItem
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.afterClaimingDailyCoin
import com.example.harleyapp.model.afterClaimingTask
import com.example.harleyapp.model.afterCompletingInteraction
import com.example.harleyapp.model.afterDeveloperAddingCoins
import com.example.harleyapp.model.afterDeveloperAddingLevels
import com.example.harleyapp.model.afterPurchasingItem
import com.example.harleyapp.model.afterRecordingReading
import com.example.harleyapp.model.afterUsingItem
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

    /**
     * 验证每日登录金币同一天只发放一次，日期回退也不能再次领取。
     *
     * @return 无返回值；金币重复发放或日期保护失效时由JUnit报告失败。
     */
    @Test
    fun dailyCoinIsGrantedOnceAndRejectsDateRollback() {
        val firstClaim = CompanionProgress().afterClaimingDailyCoin(300L)
        val duplicateClaim = firstClaim.afterClaimingDailyCoin(300L)
        val rollbackClaim = duplicateClaim.afterClaimingDailyCoin(299L)
        val nextDayClaim = rollbackClaim.afterClaimingDailyCoin(301L)

        assertEquals(1, firstClaim.coins)
        assertEquals(1, duplicateClaim.coins)
        assertEquals(1, rollbackClaim.coins)
        assertEquals(2, nextDayClaim.coins)
        assertEquals(301L, nextDayClaim.lastDailyCoinEpochDay)
    }

    /**
     * 验证电子书阅读时长可以分段累计，并只在首次跨过5、15、30分钟时领取经验。
     *
     * @return 无返回值；累计时长、经验档位或每日任务去重错误时由JUnit报告失败。
     */
    @Test
    fun readingDurationGrantsAllMilestonesOnce() {
        val fourMinutes = CompanionProgress().afterRecordingReading(
            elapsedMillis = 4L * 60L * 1_000L,
            currentEpochDay = 400L
        )
        val sixMinutes = fourMinutes.afterRecordingReading(
            elapsedMillis = 2L * 60L * 1_000L,
            currentEpochDay = 400L
        )
        val fifteenMinutes = sixMinutes.afterRecordingReading(
            elapsedMillis = 9L * 60L * 1_000L,
            currentEpochDay = 400L
        )
        val thirtyMinutes = fifteenMinutes.afterRecordingReading(
            elapsedMillis = 15L * 60L * 1_000L,
            currentEpochDay = 400L
        )
        val repeated = thirtyMinutes.afterRecordingReading(
            elapsedMillis = 5L * 60L * 1_000L,
            currentEpochDay = 400L
        )

        assertEquals(0, fourMinutes.totalExperience)
        assertEquals(5, sixMinutes.totalExperience)
        assertEquals(15, fifteenMinutes.totalExperience)
        assertEquals(30, thirtyMinutes.totalExperience)
        assertEquals(30, repeated.totalExperience)
        assertTrue(CompanionTask.EBOOK_READ_30_MINUTES in repeated.completedTasks)
    }

    /**
     * 验证消耗品必须先购买，购买扣金币，使用扣库存并增加对应经验。
     *
     * @return 无返回值；金币、库存或经验计算错误时由JUnit报告失败。
     */
    @Test
    fun consumablePurchaseAndUseUpdateEconomy() {
        val emptyUse = CompanionProgress(coins = 3).afterUsingItem(
            item = CompanionShopItem.BISCUIT,
            currentEpochDay = 500L
        )
        val purchased = emptyUse.progress.afterPurchasingItem(
            item = CompanionShopItem.BISCUIT,
            currentEpochDay = 500L
        )
        val used = purchased.progress.afterUsingItem(
            item = CompanionShopItem.BISCUIT,
            currentEpochDay = 500L
        )

        assertFalse(emptyUse.success)
        assertTrue(purchased.success)
        assertEquals(2, purchased.progress.coins)
        assertEquals(1, purchased.progress.consumableInventory[CompanionShopItem.BISCUIT])
        assertTrue(used.success)
        assertEquals(10, used.progress.totalExperience)
        assertEquals(null, used.progress.consumableInventory[CompanionShopItem.BISCUIT])
    }

    /**
     * 验证永久道具只购买一次、可反复互动，并且每天只有第一次使用获得经验。
     *
     * @return 无返回值；永久拥有或每日经验重置错误时由JUnit报告失败。
     */
    @Test
    fun permanentItemRewardsExperienceOncePerDay() {
        val progress = CompanionProgress(totalExperience = 900, coins = 10)
        val purchased = progress.afterPurchasingItem(
            item = CompanionShopItem.COLOR_BALL,
            currentEpochDay = 600L
        )
        val firstUse = purchased.progress.afterUsingItem(
            item = CompanionShopItem.COLOR_BALL,
            currentEpochDay = 600L
        )
        val secondUse = firstUse.progress.afterUsingItem(
            item = CompanionShopItem.COLOR_BALL,
            currentEpochDay = 600L
        )
        val nextDayUse = secondUse.progress.afterUsingItem(
            item = CompanionShopItem.COLOR_BALL,
            currentEpochDay = 601L
        )

        assertTrue(purchased.success)
        assertEquals(5, purchased.progress.coins)
        assertEquals(905, firstUse.progress.totalExperience)
        assertEquals(905, secondUse.progress.totalExperience)
        assertEquals(910, nextDayUse.progress.totalExperience)
    }

    /**
     * 验证互动入口随等级开放，并且开发者调整始终遵守Lv.100与金币安全上限。
     *
     * @return 无返回值；等级解锁或上限计算错误时由JUnit报告失败。
     */
    @Test
    fun interactionsAndDeveloperAdjustmentsRespectCaps() {
        val levelThirtyFive = CompanionProgress(totalExperience = 3_400)
        val adjusted = CompanionProgress(totalExperience = 50, coins = 5)
            .afterDeveloperAddingLevels(2)
            .afterDeveloperAddingCoins(10)
        val capped = adjusted
            .afterDeveloperAddingLevels(1_000)
            .afterDeveloperAddingCoins(Int.MAX_VALUE)

        assertTrue(CompanionInteraction.MEMORY_MATCH in levelThirtyFive.unlockedInteractions)
        assertFalse(CompanionInteraction.SPECIAL_ACTION in levelThirtyFive.unlockedInteractions)
        assertEquals(3, adjusted.level)
        assertEquals(15, adjusted.coins)
        assertEquals(CompanionProgress.MAX_LEVEL, capped.level)
        assertEquals(CompanionProgress.MAX_TOTAL_EXPERIENCE, capped.totalExperience)
        assertEquals(CompanionProgress.MAX_COINS, capped.coins)
    }

    /**
     * 验证每种互动独立累计每日次数，达到上限后拒绝继续，并在跨天后重新开放。
     *
     * @return 无返回值；等级校验、次数提示、首次经验去重或跨日重置错误时由JUnit报告失败。
     */
    @Test
    fun interactionCountsAreIndependentAndResetNextDay() {
        val lockedResult = CompanionProgress().afterCompletingInteraction(
            interaction = CompanionInteraction.ROCK_PAPER_SCISSORS,
            currentEpochDay = 700L
        )
        val initialProgress = CompanionProgress(totalExperience = 900)
        val first = initialProgress.afterCompletingInteraction(
            interaction = CompanionInteraction.ROCK_PAPER_SCISSORS,
            currentEpochDay = 700L
        )
        val second = first.progress.afterCompletingInteraction(
            interaction = CompanionInteraction.ROCK_PAPER_SCISSORS,
            currentEpochDay = 700L
        )
        val third = second.progress.afterCompletingInteraction(
            interaction = CompanionInteraction.ROCK_PAPER_SCISSORS,
            currentEpochDay = 700L
        )
        val overLimit = third.progress.afterCompletingInteraction(
            interaction = CompanionInteraction.ROCK_PAPER_SCISSORS,
            currentEpochDay = 700L
        )
        val independentPet = third.progress.afterCompletingInteraction(
            interaction = CompanionInteraction.PET,
            currentEpochDay = 700L
        )
        val nextDay = third.progress.afterCompletingInteraction(
            interaction = CompanionInteraction.ROCK_PAPER_SCISSORS,
            currentEpochDay = 701L
        )

        assertFalse(lockedResult.success)
        assertTrue(first.success)
        assertEquals(905, first.progress.totalExperience)
        assertEquals(3, third.progress.interactionCountsToday[CompanionInteraction.ROCK_PAPER_SCISSORS])
        assertFalse(overLimit.success)
        assertTrue(independentPet.success)
        assertEquals(1, independentPet.progress.interactionCountsToday[CompanionInteraction.PET])
        assertTrue(nextDay.success)
        assertEquals(1, nextDay.progress.interactionCountsToday[CompanionInteraction.ROCK_PAPER_SCISSORS])
        assertEquals(910, nextDay.progress.totalExperience)
    }
}
