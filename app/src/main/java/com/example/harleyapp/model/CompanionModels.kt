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
 * 伙伴成长图鉴中的一个外观形态。
 *
 * 使用方法：
 * 伙伴分类通过forms按等级从低到高保存全部形态，界面可读取unlockLevel显示解锁条件，
 * 也可读取symbol作为系统不支持动画时的轻量备用图形。
 *
 * @param unlockLevel 解锁该形态所需的最低等级。
 * @param name 图鉴中显示的形态名称。
 * @param symbol 该形态的备用Emoji图形，不参与原生动态伙伴的绘制。
 */
data class CompanionForm(
    val unlockLevel: Int,
    val name: String,
    val symbol: String
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
 * @param forms 六个成长形态，对应Lv.1、Lv.3、Lv.6、Lv.10、Lv.15和Lv.20。
 * @param skills 本分类在Lv.2、Lv.4、Lv.7、Lv.10、Lv.15和Lv.20解锁的技能。
 */
enum class CompanionCategory(
    val displayName: String,
    val companionName: String,
    val forms: List<CompanionForm>,
    val skills: List<CompanionSkill>
) {
    FOREST(
        displayName = "森林系",
        companionName = "星尾狐",
        forms = listOf(
            CompanionForm(1, "幼年星尾狐", "🦊"),
            CompanionForm(3, "灵尾星狐", "🦊✨"),
            CompanionForm(6, "星辉灵狐", "🦊🌟"),
            CompanionForm(10, "森林守护者", "🦊👑"),
            CompanionForm(15, "星冠巡林者", "🦊🌿"),
            CompanionForm(20, "万象森之灵", "🦊💫")
        ),
        skills = listOf(
            CompanionSkill(2, "寻路耳朵", "记住每一次探索过的网站。"),
            CompanionSkill(4, "幸运尾迹", "把完成的小任务变成闪亮足迹。"),
            CompanionSkill(7, "森林回声", "记录坚持运动留下的成长回声。"),
            CompanionSkill(10, "星辉共鸣", "让每一次坚持都化为柔和星光。"),
            CompanionSkill(15, "森语结界", "用成长足迹守护今天的专注时刻。"),
            CompanionSkill(20, "万象共生", "星尾狐完全成长的终极收藏技能。")
        )
    ),
    OCEAN(
        displayName = "海洋系",
        companionName = "泡泡獭",
        forms = listOf(
            CompanionForm(1, "小水獭", "🦦"),
            CompanionForm(3, "泡泡獭", "🦦🫧"),
            CompanionForm(6, "潮汐灵獭", "🦦🌊"),
            CompanionForm(10, "海洋守望者", "🦦👑"),
            CompanionForm(15, "珊瑚引航者", "🦦🪸"),
            CompanionForm(20, "深蓝潮汐神", "🦦💫")
        ),
        skills = listOf(
            CompanionSkill(2, "泡泡护盾", "收藏每日陪伴产生的第一颗泡泡。"),
            CompanionSkill(4, "潮汐寻宝", "在不同生活任务之间发现小宝藏。"),
            CompanionSkill(7, "深海回响", "让连续坚持变成悠长的海洋回声。"),
            CompanionSkill(10, "海洋之心", "把稳定节奏汇聚成温柔潮汐。"),
            CompanionSkill(15, "珊瑚乐园", "为完成的任务点亮一片彩色珊瑚。"),
            CompanionSkill(20, "潮汐共鸣", "泡泡獭完全成长的终极收藏技能。")
        )
    ),
    TECHNOLOGY(
        displayName = "科技系",
        companionName = "像素机器人",
        forms = listOf(
            CompanionForm(1, "迷你核心", "🤖"),
            CompanionForm(3, "像素助手", "🤖⚡"),
            CompanionForm(6, "机甲伙伴", "🤖🛡️"),
            CompanionForm(10, "星际终端", "🤖🚀"),
            CompanionForm(15, "量子领航员", "🤖🛰️"),
            CompanionForm(20, "银河智械", "🤖💫")
        ),
        skills = listOf(
            CompanionSkill(2, "快速扫描", "扫描并点亮今天的第一个任务。"),
            CompanionSkill(4, "能量缓存", "把每次完成记录为稳定能量。"),
            CompanionSkill(7, "任务协议", "汇总网站、账本和运动任务进度。"),
            CompanionSkill(10, "超频核心", "把成长动力转化为闪烁能量。"),
            CompanionSkill(15, "量子矩阵", "并行整理每一种生活目标。"),
            CompanionSkill(20, "银河演算", "像素机器人完全升级的终极收藏技能。")
        )
    ),
    SKY(
        displayName = "天空系",
        companionName = "云朵鸮",
        forms = listOf(
            CompanionForm(1, "雏羽团子", "🦉"),
            CompanionForm(3, "软云小鸮", "🦉☁️"),
            CompanionForm(6, "风铃云鸮", "🦉🎐"),
            CompanionForm(10, "天空观察员", "🦉🌤️"),
            CompanionForm(15, "极光领航者", "🦉🌈"),
            CompanionForm(20, "苍穹星使", "🦉💫")
        ),
        skills = listOf(
            CompanionSkill(2, "轻羽提醒", "用柔软羽毛记住今天的重要事情。"),
            CompanionSkill(4, "顺风启程", "为第一次行动送来一阵顺风。"),
            CompanionSkill(7, "云端视野", "从更高处观察长期成长轨迹。"),
            CompanionSkill(10, "晴空之眼", "让繁杂任务重新变得清晰。"),
            CompanionSkill(15, "极光羽翼", "为连续坚持染上一层极光。"),
            CompanionSkill(20, "苍穹共振", "云朵鸮完全成长的终极收藏技能。")
        )
    ),
    DESERT(
        displayName = "沙漠系",
        companionName = "暖阳蜥",
        forms = listOf(
            CompanionForm(1, "砂砾幼蜥", "🦎"),
            CompanionForm(3, "暖阳蜥", "🦎☀️"),
            CompanionForm(6, "晶砂游侠", "🦎💎"),
            CompanionForm(10, "沙海守望者", "🦎🏜️"),
            CompanionForm(15, "烈日领航者", "🦎🔥"),
            CompanionForm(20, "金曜龙蜥", "🦎💫")
        ),
        skills = listOf(
            CompanionSkill(2, "阳光蓄能", "把打开App的片刻收进温暖鳞片。"),
            CompanionSkill(4, "砂迹寻路", "沿着完成记录找到下一项目标。"),
            CompanionSkill(7, "晶甲守护", "用稳定习惯抵挡偶尔的懒散。"),
            CompanionSkill(10, "沙海绿洲", "在忙碌生活中留出一处休息空间。"),
            CompanionSkill(15, "烈日脉冲", "为长久坚持补充明亮动力。"),
            CompanionSkill(20, "金曜觉醒", "暖阳蜥完全成长的终极收藏技能。")
        )
    ),
    COSMOS(
        displayName = "星空系",
        companionName = "月光兔",
        forms = listOf(
            CompanionForm(1, "月芽兔", "🐰"),
            CompanionForm(3, "星尘月兔", "🐰✨"),
            CompanionForm(6, "流光跃兔", "🐰🌙"),
            CompanionForm(10, "月宫旅行家", "🐰🚀"),
            CompanionForm(15, "星环领航者", "🐰🪐"),
            CompanionForm(20, "银河梦旅兔", "🐰💫")
        ),
        skills = listOf(
            CompanionSkill(2, "月芽祝福", "为今天完成的第一件小事送上微光。"),
            CompanionSkill(4, "星尘跳跃", "轻快跨过不同任务之间的距离。"),
            CompanionSkill(7, "流光轨迹", "把连续成长记录成一条星轨。"),
            CompanionSkill(10, "月宫漫游", "让日常探索多一点轻松想象。"),
            CompanionSkill(15, "星环引力", "把分散目标重新聚拢到身边。"),
            CompanionSkill(20, "银河好梦", "月光兔完全成长的终极收藏技能。")
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
        return formFor(level).symbol
    }

    /**
     * 获取指定等级对应的形态名称。
     *
     * @param level 当前等级，小于1时按1级处理。
     *
     * @return 当前已解锁最高形态名称。
     */
    fun formNameFor(level: Int): String {
        return formFor(level).name
    }

    /**
     * 获取指定等级已经解锁的最高成长形态。
     *
     * @param level 当前等级。
     *
     * @return 等级门槛不高于当前等级的最后一个形态；异常低等级按Lv.1处理。
     */
    fun formFor(level: Int): CompanionForm {
        val safeLevel = level.coerceAtLeast(1)
        return forms.lastOrNull { form -> safeLevel >= form.unlockLevel } ?: forms.first()
    }

    /**
     * 计算指定等级对应的形态序号，供动态外观增加光环、饰品等进化细节。
     *
     * @param level 当前伙伴等级。
     *
     * @return 从0开始的形态序号，最大值不会超过当前分类的最后一个形态。
     */
    fun formIndexFor(level: Int): Int {
        val currentForm = formFor(level)
        return forms.indexOf(currentForm).coerceAtLeast(0)
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
    /** 当前等级，从Lv.1开始，每累计100经验提升一级，达到Lv.20后封顶。 */
    val level: Int
        get() = (totalExperience.coerceAtLeast(0) / EXPERIENCE_PER_LEVEL + 1)
            .coerceAtMost(MAX_LEVEL)

    /** 当前等级经验条已经填充的经验值；满级后固定显示为完整经验条。 */
    val experienceInLevel: Int
        get() = if (level >= MAX_LEVEL) {
            EXPERIENCE_PER_LEVEL
        } else {
            totalExperience.coerceAtLeast(0) % EXPERIENCE_PER_LEVEL
        }

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
            category.forms.drop(1).forEach { form ->
                add(form.unlockLevel to form.name)
            }
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
        const val MAX_LEVEL = 20
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
