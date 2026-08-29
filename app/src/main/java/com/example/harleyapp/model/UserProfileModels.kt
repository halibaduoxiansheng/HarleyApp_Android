package com.example.harleyapp.model

import java.util.Locale

/**
 * 用户资料中的性别选项。
 *
 * 使用方法：
 * 用户资料编辑弹窗保存枚举名称，展示时读取[displayName]。当前健康运动建议不根据性别设置
 * 不同目标，避免仅凭单一性别字段做缺少身体状况依据的强度推断。
 *
 * @param displayName 页面显示的中文名称。
 */
enum class UserGender(val displayName: String) {
    UNSPECIFIED("未设置"),
    MALE("男"),
    FEMALE("女"),
    OTHER("其他")
}

/**
 * 成年人BMI筛查结果。
 *
 * 使用方法：
 * 通过[calculateAdultBmiCategory]取得分类后展示通用活动提示。BMI只是一项筛查指标，不能替代
 * 医疗诊断，也不能单独判断肌肉量、体脂分布和具体疾病风险。
 *
 * @param displayName 页面显示的分类名称。
 */
enum class AdultBmiCategory(val displayName: String) {
    UNDERWEIGHT("偏低"),
    HEALTHY("健康范围"),
    OVERWEIGHT("偏高"),
    OBESITY("较高")
}

/**
 * 保存在本机的用户基础资料。
 *
 * 使用方法：
 * 页面通过UserProfileRepository读取、保存或删除本对象。头像只保存系统文档选择器返回的Uri，
 * 不复制原图、不上传网络；年龄、身高和体重用于生成保守的成年人通用运动建议。
 *
 * @param avatarUri 用户选择的本地头像Uri；空字符串表示使用姓名首字或默认头像。
 * @param name 用户姓名或昵称。
 * @param gender 用户选择的性别。
 * @param ageYears 年龄，完整资料要求18到120岁。
 * @param heightCm 身高厘米数，完整资料要求80到250厘米。
 * @param weightKg 体重千克数，完整资料要求25到350千克。
 */
data class UserProfile(
    val avatarUri: String = "",
    val name: String = "",
    val gender: UserGender = UserGender.UNSPECIFIED,
    val ageYears: Int = 0,
    val heightCm: Int = 0,
    val weightKg: Double = 0.0
) {

    /**
     * 判断资料是否足以生成成年人通用运动建议。
     *
     * @return 姓名、年龄、身高和体重均处于可接受范围时返回true，否则返回false。
     */
    fun isReadyForHealthAdvice(): Boolean {
        return name.isNotBlank() &&
            ageYears in MIN_ADULT_AGE..MAX_PROFILE_AGE &&
            heightCm in MIN_HEIGHT_CM..MAX_HEIGHT_CM &&
            weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG
    }

    /**
     * 计算成年人BMI筛查值。
     *
     * @return 资料完整时返回“千克/米平方”的BMI；字段不完整时返回null。
     */
    fun adultBmi(): Double? {
        if (!isReadyForHealthAdvice()) {
            return null
        }
        val heightMeters = heightCm / 100.0
        return weightKg / (heightMeters * heightMeters)
    }

    companion object {
        const val MIN_ADULT_AGE = 18
        const val MAX_PROFILE_AGE = 120
        const val MIN_HEIGHT_CM = 80
        const val MAX_HEIGHT_CM = 250
        const val MIN_WEIGHT_KG = 25.0
        const val MAX_WEIGHT_KG = 350.0
    }
}

/**
 * 一条可同步到运动功能的健康建议。
 *
 * @param definition 建议使用的运动项目定义；弹窗会先创建可编辑副本，不会直接写入。
 * @param reason 该项目的通用健康依据和使用说明。
 */
data class HealthExerciseRecommendation(
    val definition: FitnessExerciseDefinition,
    val reason: String
)

/**
 * 根据用户资料生成的健康运动建议计划。
 *
 * @param bmiValue BMI筛查值，页面保留一位小数展示。
 * @param bmiCategory BMI筛查分类。
 * @param adviceTexts 资料相关的保守运动提示。
 * @param exercises 可编辑并选择性同步的运动项目。
 */
data class HealthRecommendationPlan(
    val bmiValue: Double,
    val bmiCategory: AdultBmiCategory,
    val adviceTexts: List<String>,
    val exercises: List<HealthExerciseRecommendation>
)

/**
 * 根据BMI数值返回成年人筛查分类。
 *
 * 使用方法：
 * 先调用[UserProfile.adultBmi]获得有效数值，再把结果传入本函数。边界采用成年人常用分类：
 * 18.5以下、18.5到25以下、25到30以下、30及以上。
 *
 * @param bmi 有效BMI数值。
 *
 * @return 对应的[AdultBmiCategory]。
 */
fun calculateAdultBmiCategory(bmi: Double): AdultBmiCategory {
    return when {
        bmi < 18.5 -> AdultBmiCategory.UNDERWEIGHT
        bmi < 25.0 -> AdultBmiCategory.HEALTHY
        bmi < 30.0 -> AdultBmiCategory.OVERWEIGHT
        else -> AdultBmiCategory.OBESITY
    }
}

/**
 * 生成可在同步前继续修改的成年人健康运动计划。
 *
 * 使用方法：
 * 用户保存完整资料后，把资料和当前运动项目传入。函数会优先复用同稳定id或同名项目，确保再次
 * 一键同步时更新原推荐项目，不重复新增；返回值只在内存中生成，必须由用户在预览弹窗确认后
 * 再交给FitnessRepository写入。
 *
 * 建议以每周150分钟中等强度有氧和每周2次力量训练为通用起点；65岁及以上增加每周3次
 * 平衡训练。BMI仅调整提示文字，不自动提高运动强度或生成减重处方。
 *
 * @param profile 已保存的用户资料。
 * @param currentDefinitions 运动功能当前项目，用于识别需要更新的同一项目。
 *
 * @return 资料完整时返回建议计划；资料不完整时返回null。
 */
fun buildAdultHealthRecommendation(
    profile: UserProfile,
    currentDefinitions: List<FitnessExerciseDefinition>
): HealthRecommendationPlan? {
    val bmi = profile.adultBmi() ?: return null
    val bmiCategory = calculateAdultBmiCategory(bmi)
    val advice = mutableListOf(
        "每周累计至少150分钟中等强度有氧，可拆分到多天完成。",
        "每周至少安排2次覆盖主要肌群的力量训练，并保留恢复时间。"
    )

    when (bmiCategory) {
        AdultBmiCategory.UNDERWEIGHT -> advice.add(
            "BMI筛查值偏低，不建议以快速减重为目标；运动量应逐步增加并关注营养和恢复。"
        )

        AdultBmiCategory.HEALTHY -> advice.add(
            "BMI处于常用健康筛查范围，可在没有不适的前提下逐步增加持续时间。"
        )

        AdultBmiCategory.OVERWEIGHT,
        AdultBmiCategory.OBESITY -> advice.add(
            "建议优先选择快走、骑行等较低冲击活动，从可轻松完成的时长开始逐步增加。"
        )
    }

    val exercises = mutableListOf(
        HealthExerciseRecommendation(
            definition = resolveRecommendedDefinition(
                currentDefinitions = currentDefinitions,
                stableId = HEALTH_AEROBIC_ID,
                defaultName = "中等强度有氧",
                unit = "分钟",
                goal = 150,
                quickIncrement = 10,
                goalPeriod = FitnessGoalPeriod.WEEKLY
            ),
            reason = "可选择快走、骑行或游泳等能持续进行的项目，每周累计。"
        ),
        HealthExerciseRecommendation(
            definition = resolveRecommendedDefinition(
                currentDefinitions = currentDefinitions,
                stableId = HEALTH_STRENGTH_ID,
                defaultName = "力量训练",
                unit = "次",
                goal = 2,
                quickIncrement = 1,
                goalPeriod = FitnessGoalPeriod.WEEKLY
            ),
            reason = "一次代表一次完整训练，建议覆盖主要肌群并避免连续高强度训练。"
        )
    )

    if (profile.ageYears >= OLDER_ADULT_AGE) {
        advice.add("每周至少3次平衡训练，并根据自身稳定性选择扶墙或有人陪同。")
        exercises.add(
            HealthExerciseRecommendation(
                definition = resolveRecommendedDefinition(
                    currentDefinitions = currentDefinitions,
                    stableId = HEALTH_BALANCE_ID,
                    defaultName = "平衡训练",
                    unit = "次",
                    goal = 3,
                    quickIncrement = 1,
                    goalPeriod = FitnessGoalPeriod.WEEKLY
                ),
                reason = "可选择单脚站立、太极等安全动作；稳定性不足时应有支撑保护。"
            )
        )
    }

    advice.add("如运动时出现胸痛、明显气短、眩晕或其他异常，应立即停止并寻求专业帮助。")
    return HealthRecommendationPlan(
        bmiValue = bmi,
        bmiCategory = bmiCategory,
        adviceTexts = advice,
        exercises = exercises
    )
}

/**
 * 复用现有同一运动项目，或创建带稳定id的新推荐项目。
 *
 * @param currentDefinitions 当前全部运动项目。
 * @param stableId 推荐项目固定id。
 * @param defaultName 首次新增时使用的名称。
 * @param unit 推荐单位。
 * @param goal 推荐目标。
 * @param quickIncrement 推荐快捷增加量。
 * @param goalPeriod 推荐目标周期。
 *
 * @return 可放入建议预览的项目定义。
 */
private fun resolveRecommendedDefinition(
    currentDefinitions: List<FitnessExerciseDefinition>,
    stableId: String,
    defaultName: String,
    unit: String,
    goal: Int,
    quickIncrement: Int,
    goalPeriod: FitnessGoalPeriod
): FitnessExerciseDefinition {
    val existing = currentDefinitions.firstOrNull { definition ->
        definition.id == stableId
    } ?: currentDefinitions.firstOrNull { definition ->
        definition.name.lowercase(Locale.ROOT) == defaultName.lowercase(Locale.ROOT)
    }
    return FitnessExerciseDefinition(
        id = existing?.id ?: stableId,
        name = existing?.name ?: defaultName,
        unit = unit,
        dailyGoal = goal,
        quickIncrement = quickIncrement,
        trackingType = FitnessTrackingType.MANUAL,
        goalPeriod = goalPeriod
    )
}

private const val OLDER_ADULT_AGE = 65
private const val HEALTH_AEROBIC_ID = "health_aerobic"
private const val HEALTH_STRENGTH_ID = "health_strength"
private const val HEALTH_BALANCE_ID = "health_balance"
