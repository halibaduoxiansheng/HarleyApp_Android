package com.example.harleyapp.model

/**
 * 用户每天希望完成的三项运动目标。
 *
 * 使用方法：
 * 由FitnessRepository读取和保存，FitnessScreen使用这些值计算当天完成进度。
 * 构造实例前应保证数值为正数；仓库层会再次限制到安全范围，避免异常输入污染本地数据。
 *
 * @param pushUpGoal 每日俯卧撑目标次数。
 * @param sitUpGoal 每日仰卧起坐目标次数。
 * @param stepGoal 每日步数目标。
 */
data class FitnessGoals(
    val pushUpGoal: Int = DEFAULT_PUSH_UP_GOAL,
    val sitUpGoal: Int = DEFAULT_SIT_UP_GOAL,
    val stepGoal: Int = DEFAULT_STEP_GOAL
) {

    companion object {
        const val DEFAULT_PUSH_UP_GOAL = 30
        const val DEFAULT_SIT_UP_GOAL = 50
        const val DEFAULT_STEP_GOAL = 8_000
    }
}

/**
 * 可通过手动打卡累计的训练项目。
 *
 * 使用方法：
 * 调用FitnessRepository.updateExercise时传入具体项目，仓库会只更新对应次数。
 */
enum class FitnessExercise {
    PUSH_UP,
    SIT_UP
}

/**
 * 某一天的训练完成记录及当天实际采用的目标快照。
 *
 * 使用方法：
 * 页面通过countFor、goalFor和progressFor显示单项进度，通过completedTaskCount和isComplete
 * 显示整日完成状态。目标保存在每天的记录内，后续修改新目标不会反向改变历史达标结果。
 *
 * @param dateEpochDay LocalDate转换得到的日期序号。
 * @param pushUps 当天已完成俯卧撑次数。
 * @param sitUps 当天已完成仰卧起坐次数。
 * @param steps 当天已记录步数。
 * @param pushUpGoal 当天采用的俯卧撑目标快照。
 * @param sitUpGoal 当天采用的仰卧起坐目标快照。
 * @param stepGoal 当天采用的步数目标快照。
 */
data class DailyFitnessRecord(
    val dateEpochDay: Long,
    val pushUps: Int = 0,
    val sitUps: Int = 0,
    val steps: Int = 0,
    val pushUpGoal: Int = FitnessGoals.DEFAULT_PUSH_UP_GOAL,
    val sitUpGoal: Int = FitnessGoals.DEFAULT_SIT_UP_GOAL,
    val stepGoal: Int = FitnessGoals.DEFAULT_STEP_GOAL
) {

    /**
     * 读取指定训练项目当天已经完成的次数。
     *
     * @param exercise 需要查询的训练项目。
     *
     * @return 对应项目的非负完成次数。
     */
    fun countFor(exercise: FitnessExercise): Int {
        return when (exercise) {
            FitnessExercise.PUSH_UP -> pushUps
            FitnessExercise.SIT_UP -> sitUps
        }
    }

    /**
     * 读取指定训练项目当天采用的目标次数。
     *
     * @param exercise 需要查询的训练项目。
     *
     * @return 对应项目的目标次数。
     */
    fun goalFor(exercise: FitnessExercise): Int {
        return when (exercise) {
            FitnessExercise.PUSH_UP -> pushUpGoal
            FitnessExercise.SIT_UP -> sitUpGoal
        }
    }

    /**
     * 计算指定训练项目的显示进度。
     *
     * @param exercise 需要计算进度的训练项目。
     *
     * @return 0到1之间的进度；超额完成时固定返回1，避免进度条越界。
     */
    fun progressFor(exercise: FitnessExercise): Float {
        val goal = goalFor(exercise).coerceAtLeast(1)
        return (countFor(exercise).toFloat() / goal).coerceIn(0f, 1f)
    }

    /**
     * 计算当天三项任务中已经达标的数量。
     *
     * @return 0到3之间的达标项数。
     */
    fun completedTaskCount(): Int {
        return listOf(
            pushUps >= pushUpGoal,
            sitUps >= sitUpGoal,
            steps >= stepGoal
        ).count { it }
    }

    /**
     * 判断当天俯卧撑、仰卧起坐和步数是否全部达标。
     *
     * @return 三项均达标时返回true，否则返回false。
     */
    fun isComplete(): Boolean {
        return completedTaskCount() == FITNESS_TASK_COUNT
    }

    /**
     * 判断当天是否至少记录过一项实际运动量。
     *
     * 使用方法：
     * 历史列表据此决定是否显示删除入口，避免用户删除系统为缺失日期生成的空占位记录。
     *
     * @return 俯卧撑、仰卧起坐或步数任一项大于0时返回true，否则返回false。
     */
    fun hasRecordedActivity(): Boolean {
        return pushUps > 0 || sitUps > 0 || steps > 0
    }

    /**
     * 使用新的目标更新当天目标快照，同时保留已经完成的运动量。
     *
     * @param goals 新的每日目标。
     *
     * @return 包含新目标快照的记录副本。
     */
    fun withGoals(goals: FitnessGoals): DailyFitnessRecord {
        return copy(
            pushUpGoal = goals.pushUpGoal,
            sitUpGoal = goals.sitUpGoal,
            stepGoal = goals.stepGoal
        )
    }

    companion object {
        const val FITNESS_TASK_COUNT = 3
    }
}
