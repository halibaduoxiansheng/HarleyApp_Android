package com.example.harleyapp.model

/**
 * 玩偶成长过程中可以解锁的一项收藏技能。
 *
 * @param unlockLevel 解锁所需等级。
 * @param name 技能名称。
 * @param description 技能图鉴中的中文说明；第一版仅作为成长收藏，不改变经验倍率。
 */
data class CompanionSkill(
    val unlockLevel: Int,
    val name: String,
    val description: String
)

/**
 * 用户可以选择的玩偶分类及其完整成长外观。
 *
 * 使用方法：
 * 界面通过displayName和companionName展示分类，通过symbolFor和formNameFor读取当前形态，
 * 通过skills展示随等级解锁的专属技能。切换分类只改变外观和图鉴，经验进度保持共用。
 *
 * @param displayName 分类名称。
 * @param companionName 玩偶名字。
 * @param stageSymbols 四个形态使用的轻量离线图形字符。
 * @param formNames 四个形态名称，对应Lv.1、Lv.3、Lv.6和Lv.10。
 * @param skills 本分类在Lv.2、Lv.4、Lv.7和Lv.10解锁的技能。
 */
enum class CompanionCategory(
    val displayName: String,
    val companionName: String,
    private val stageSymbols: List<String>,
    private val formNames: List<String>,
    val skills: List<CompanionSkill>
) {
    FOREST(
        displayName = "森林系",
        companionName = "星尾狐",
        stageSymbols = listOf("🦊", "🦊✨", "🦊🌟", "🦊👑"),
        formNames = listOf("幼年星尾狐", "灵尾星狐", "星辉灵狐", "森林守护者"),
        skills = listOf(
            CompanionSkill(2, "寻路耳朵", "记住每一次探索过的网站。"),
            CompanionSkill(4, "幸运尾迹", "把完成的小任务变成闪亮足迹。"),
            CompanionSkill(7, "森林回声", "记录坚持运动留下的成长回声。"),
            CompanionSkill(10, "星辉共鸣", "星尾完全觉醒的终极收藏技能。")
        )
    ),
    OCEAN(
        displayName = "海洋系",
        companionName = "泡泡獭",
        stageSymbols = listOf("🦦", "🦦🫧", "🦦🌊", "🦦👑"),
        formNames = listOf("小水獭", "泡泡獭", "潮汐灵獭", "海洋守望者"),
        skills = listOf(
            CompanionSkill(2, "泡泡护盾", "收藏每日陪伴产生的第一颗泡泡。"),
            CompanionSkill(4, "潮汐寻宝", "在不同生活任务之间发现小宝藏。"),
            CompanionSkill(7, "深海回响", "让连续坚持变成悠长的海洋回声。"),
            CompanionSkill(10, "海洋之心", "泡泡獭完全成长的终极收藏技能。")
        )
    ),
    TECHNOLOGY(
        displayName = "科技系",
        companionName = "像素机器人",
        stageSymbols = listOf("🤖", "🤖⚡", "🤖🛡️", "🤖🚀"),
        formNames = listOf("迷你核心", "像素助手", "机甲伙伴", "星际终端"),
        skills = listOf(
            CompanionSkill(2, "快速扫描", "扫描并点亮今天的第一个任务。"),
            CompanionSkill(4, "能量缓存", "把每次完成记录为稳定能量。"),
            CompanionSkill(7, "任务协议", "汇总网站、账本和运动任务进度。"),
            CompanionSkill(10, "超频核心", "像素机器人完全升级的终极收藏技能。")
        )
    );

    /**
     * 获取指定等级对应的外观字符。
     *
     * @param level 当前等级，小于1时按1级处理。
     *
     * @return 当前已解锁最高形态的离线字符组合。
     */
    fun symbolFor(level: Int): String {
        return stageSymbols[stageIndexFor(level)]
    }

    /**
     * 获取指定等级对应的形态名称。
     *
     * @param level 当前等级，小于1时按1级处理。
     *
     * @return 当前已解锁最高形态名称。
     */
    fun formNameFor(level: Int): String {
        return formNames[stageIndexFor(level)]
    }

    /**
     * 根据统一等级门槛计算四个形态的索引。
     *
     * @param level 当前等级。
     *
     * @return 0到3之间的形态索引。
     */
    private fun stageIndexFor(level: Int): Int {
        return when {
            level >= 10 -> 3
            level >= 6 -> 2
            level >= 3 -> 1
            else -> 0
        }
    }
}

/**
 * 能为玩偶提供经验的每日任务。
 *
 * @param title 首页任务列表显示名称。
 * @param experience 首次完成时增加的经验值。
 * @param countsTowardDailyBonus 是否属于“完成全部任务”所要求的常规任务。
 */
enum class CompanionTask(
    val title: String,
    val experience: Int,
    val countsTowardDailyBonus: Boolean = true
) {
    DAILY_OPEN("打开App", 10),
    WEBSITE_VISIT("访问网站", 5),
    LEDGER_ENTRY("新增账目", 10),
    FITNESS_ITEM("完成一项运动", 15),
    FITNESS_ALL("完成全部运动", 25),
    SHORTCUT_LAUNCH("打开快捷应用", 5),
    DAILY_BONUS("全部任务奖励", 20, false)
}

/**
 * 玩偶的持久化成长状态和当天任务完成情况。
 *
 * 使用方法：
 * CompanionRepository负责创建和更新本对象；首页只读取计算属性展示等级、经验条、
 * 当前形态和技能，不直接修改totalExperience或completedTasks。
 *
 * @param category 当前选择的玩偶分类。
 * @param totalExperience 历史累计经验，跨天和切换分类都保留。
 * @param taskEpochDay completedTasks所属日期，以1970-01-01起算天数表示。
 * @param completedTasks 当天已经领取过经验的任务集合。
 */
data class CompanionProgress(
    val category: CompanionCategory = CompanionCategory.FOREST,
    val totalExperience: Int = 0,
    val taskEpochDay: Long = 0L,
    val completedTasks: Set<CompanionTask> = emptySet()
) {
    /** 当前等级，从Lv.1开始，每累计100经验提升一级。 */
    val level: Int
        get() = totalExperience.coerceAtLeast(0) / EXPERIENCE_PER_LEVEL + 1

    /** 当前等级经验条已经填充的经验值。 */
    val experienceInLevel: Int
        get() = totalExperience.coerceAtLeast(0) % EXPERIENCE_PER_LEVEL

    /** 当前分类已经解锁的技能列表。 */
    val unlockedSkills: List<CompanionSkill>
        get() = category.skills.filter { skill -> level >= skill.unlockLevel }

    /**
     * 计算下一个尚未解锁的形态或技能提示。
     *
     * @return 下一项解锁的等级与名称；全部解锁后返回完成提示。
     */
    fun nextUnlockText(): String {
        val candidates = buildList {
            add(3 to category.formNameFor(3))
            add(6 to category.formNameFor(6))
            add(10 to category.formNameFor(10))
            category.skills.forEach { skill ->
                add(skill.unlockLevel to "技能·${skill.name}")
            }
        }
        val nextUnlock = candidates
            .filter { (unlockLevel, _) -> unlockLevel > level }
            .minByOrNull { (unlockLevel, _) -> unlockLevel }

        return if (nextUnlock == null) {
            "全部形态与技能已解锁"
        } else {
            "Lv.${nextUnlock.first} 解锁 ${nextUnlock.second}"
        }
    }

    companion object {
        const val EXPERIENCE_PER_LEVEL = 100
    }
}

/**
 * 纯计算地领取一项每日任务经验，方便仓库持久化前统一处理去重和全部完成奖励。
 *
 * 使用方法：
 * 传入业务操作成功时对应的任务与当天日期；返回值若与原状态相同，表示任务已领取或传入的是
 * 只能自动发放的DAILY_BONUS。日期变化时会先清空旧的每日任务，但保留累计经验和伙伴分类。
 *
 * @param task 本次完成的常规任务。
 * @param currentEpochDay 当前本地日期对应的Epoch Day。
 *
 * @return 计算后的新成长状态；无需发奖时返回按当前日期规范化后的状态。
 */
fun CompanionProgress.afterClaimingTask(
    task: CompanionTask,
    currentEpochDay: Long
): CompanionProgress {
    val currentDayTasks = if (taskEpochDay == currentEpochDay) {
        completedTasks
    } else {
        emptySet()
    }
    val currentDayProgress = copy(
        taskEpochDay = currentEpochDay,
        completedTasks = currentDayTasks
    )
    if (task == CompanionTask.DAILY_BONUS || task in currentDayTasks) {
        return currentDayProgress
    }

    val updatedTasks = currentDayTasks.toMutableSet().apply {
        add(task)
    }
    var gainedExperience = task.experience
    val requiredTasks = CompanionTask.entries.filter { candidate ->
        candidate.countsTowardDailyBonus
    }
    if (requiredTasks.all(updatedTasks::contains) && CompanionTask.DAILY_BONUS !in updatedTasks) {
        updatedTasks.add(CompanionTask.DAILY_BONUS)
        gainedExperience += CompanionTask.DAILY_BONUS.experience
    }
    val updatedExperience = totalExperience
        .coerceAtLeast(0)
        .toLong()
        .plus(gainedExperience.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    return currentDayProgress.copy(
        totalExperience = updatedExperience,
        completedTasks = updatedTasks
    )
}
