package com.example.harleyapp

import com.example.harleyapp.model.AdultBmiCategory
import com.example.harleyapp.model.FitnessGoalPeriod
import com.example.harleyapp.model.UserGender
import com.example.harleyapp.model.UserProfile
import com.example.harleyapp.model.buildAdultHealthRecommendation
import com.example.harleyapp.model.calculateAdultBmiCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证用户资料BMI边界、成年人建议项目和老年人平衡训练分支。 */
class UserProfileModelsTest {

    /** 验证成年人BMI常用分类边界没有遗漏等号或交叉区间。 */
    @Test
    fun calculateAdultBmiCategory_usesExpectedBoundaries() {
        assertEquals(AdultBmiCategory.UNDERWEIGHT, calculateAdultBmiCategory(18.49))
        assertEquals(AdultBmiCategory.HEALTHY, calculateAdultBmiCategory(18.5))
        assertEquals(AdultBmiCategory.OVERWEIGHT, calculateAdultBmiCategory(25.0))
        assertEquals(AdultBmiCategory.OBESITY, calculateAdultBmiCategory(30.0))
    }

    /** 验证普通成年人计划包含可按自然周累计的有氧和力量训练。 */
    @Test
    fun buildAdultHealthRecommendation_createsEditableWeeklyGoals() {
        val plan = buildAdultHealthRecommendation(
            profile = validProfile(ageYears = 35),
            currentDefinitions = emptyList()
        )

        assertNotNull(plan)
        val definitions = plan!!.exercises.map { recommendation -> recommendation.definition }
        assertEquals(2, definitions.size)
        assertTrue(definitions.all { definition ->
            definition.goalPeriod == FitnessGoalPeriod.WEEKLY
        })
        assertEquals(150, definitions.first { it.id == "health_aerobic" }.dailyGoal)
        assertEquals(2, definitions.first { it.id == "health_strength" }.dailyGoal)
    }

    /** 验证65岁起会追加平衡训练，且不删除基础有氧和力量项目。 */
    @Test
    fun buildAdultHealthRecommendation_addsBalanceGoalForOlderAdult() {
        val plan = buildAdultHealthRecommendation(
            profile = validProfile(ageYears = 65),
            currentDefinitions = emptyList()
        )

        assertNotNull(plan)
        assertEquals(3, plan!!.exercises.size)
        val balance = plan.exercises.first { recommendation ->
            recommendation.definition.id == "health_balance"
        }.definition
        assertEquals(FitnessGoalPeriod.WEEKLY, balance.goalPeriod)
        assertEquals(3, balance.dailyGoal)
    }

    /**
     * 创建满足健康建议计算范围的测试资料。
     *
     * @param ageYears 测试年龄。
     * @return 姓名、身高和体重均有效的用户资料。
     */
    private fun validProfile(ageYears: Int): UserProfile {
        return UserProfile(
            name = "测试用户",
            gender = UserGender.UNSPECIFIED,
            ageYears = ageYears,
            heightCm = 170,
            weightKg = 65.0
        )
    }
}
