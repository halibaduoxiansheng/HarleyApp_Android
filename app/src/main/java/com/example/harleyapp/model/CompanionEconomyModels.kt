package com.example.harleyapp.model

/**
 * 伙伴互动屋中会随等级逐步开放的一项免费互动。
 *
 * 使用方法：
 * 首页伙伴卡片按照[unlockLevel]判断入口是否可用；互动完成后把本枚举传给
 * CompanionRepository，由仓库统一处理每日首次互动经验，页面不能自行修改经验值。
 *
 * @param unlockLevel 允许进入该互动所需的最低伙伴等级。
 * @param dailyLimit 每个本地自然日允许完成的最大次数。
 * @param displayName 页面显示的中文名称。
 * @param description 未解锁提示和互动说明。
 */
enum class CompanionInteraction(
    val unlockLevel: Int,
    val dailyLimit: Int,
    val displayName: String,
    val description: String
) {
    PET(1, 3, "轻轻抚摸", "点击伙伴，观察它的随机回应。"),
    TALK(5, 3, "伙伴对话", "听伙伴分享今天的心情。"),
    ROCK_PAPER_SCISSORS(10, 3, "伙伴猜拳", "和伙伴进行一局石头剪刀布。"),
    STAR_CATCH(20, 2, "接住星光", "连续点击出现的星光并记录得分。"),
    MEMORY_MATCH(35, 2, "记忆翻牌", "找出三组相同图案。"),
    SPECIAL_ACTION(50, 3, "专属动作", "播放当前伙伴分类的专属动作和台词。"),
    CHALLENGE_MODE(75, 2, "困难挑战", "完成格子更多的进阶星光挑战。"),
    FINAL_CELEBRATION(100, 1, "终极庆典", "解锁满级伙伴庆典和全部互动。")
}

/**
 * 金币商店中的一件商品。
 *
 * 使用方法：
 * 消耗品购买后增加背包数量，每次使用消耗一件并增加[experienceReward]经验；永久道具只购买
 * 一次，购买后可以反复互动，但每天只有第一次使用会增加经验。所有购买和使用必须交给
 * CompanionRepository完成，避免页面绕过金币与背包校验。
 *
 * @param displayName 商品名称。
 * @param description 商店和背包中的中文说明。
 * @param priceCoins 购买一次需要扣除的金币。
 * @param experienceReward 实际使用物品后增加的伙伴经验。
 * @param consumable true表示每次使用消耗一个库存；false表示永久拥有。
 * @param unlockLevel 可以购买和使用该商品的最低伙伴等级。
 */
enum class CompanionShopItem(
    val displayName: String,
    val description: String,
    val priceCoins: Int,
    val experienceReward: Int,
    val consumable: Boolean,
    val unlockLevel: Int
) {
    BISCUIT("小饼干", "喂伙伴一块香脆饼干。", 1, 10, true, 1),
    FRUIT_PLATE("水果拼盘", "和伙伴分享一份新鲜水果。", 2, 25, true, 1),
    CELEBRATION_CAKE("庆祝蛋糕", "为伙伴举行一次成长庆祝。", 3, 40, true, 1),
    COLOR_BALL("彩色皮球", "永久解锁抛球互动，每日首次互动增加经验。", 5, 5, false, 10),
    STAR_WAND("星光逗宠棒", "永久解锁追光互动，每日首次互动增加经验。", 8, 8, false, 20),
    PHOTO_CAMERA("合影相机", "永久解锁伙伴合影和专属姿势。", 12, 0, false, 50)
}

/**
 * 一次金币商店或伙伴互动操作的纯数据结果。
 *
 * @param progress 操作后的伙伴完整状态；失败时与操作前状态相同。
 * @param success true表示购买或互动已经发生。
 * @param message 页面可以直接显示的中文结果说明。
 * @param experienceGained 本次实际增加的经验，用于页面展示成长反馈。
 */
data class CompanionOperationResult(
    val progress: CompanionProgress,
    val success: Boolean,
    val message: String,
    val experienceGained: Int = 0
)

/** 电子书有效阅读达到5分钟时发放第一档经验。 */
const val COMPANION_READING_5_MINUTES_MILLIS = 5L * 60L * 1_000L

/** 电子书有效阅读达到15分钟时发放第二档经验。 */
const val COMPANION_READING_15_MINUTES_MILLIS = 15L * 60L * 1_000L

/** 电子书有效阅读达到30分钟时发放最后一档经验。 */
const val COMPANION_READING_30_MINUTES_MILLIS = 30L * 60L * 1_000L

/** 阅读统计的单日安全上限，避免异常生命周期事件导致毫秒数溢出。 */
private const val COMPANION_MAX_DAILY_READING_MILLIS = 24L * 60L * 60L * 1_000L

/**
 * 完成一次指定的免费互动，并记录该互动今天已经完成的次数。
 *
 * 使用方法：
 * 小游戏真正结束或普通互动动画成功触发后调用。函数会同时校验等级和每日上限；当天第一次
 * 完成任意免费互动时，继续通过[CompanionTask.COMPANION_INTERACTION]领取一次成长经验，后续
 * 次数只保留互动次数与动画反馈，不重复增加该任务经验。
 *
 * @param interaction 本次实际完成的互动类型。
 * @param currentEpochDay 当前本地日期对应的Epoch Day。
 * @return 包含新次数、经验和中文提示的操作结果；未解锁或次数用完时返回失败。
 */
fun CompanionProgress.afterCompletingInteraction(
    interaction: CompanionInteraction,
    currentEpochDay: Long
): CompanionOperationResult {
    val currentDayProgress = normalizedForDay(currentEpochDay)
    if (currentDayProgress.level < interaction.unlockLevel) {
        return CompanionOperationResult(
            progress = currentDayProgress,
            success = false,
            message = "需要达到Lv.${interaction.unlockLevel}才能进行${interaction.displayName}"
        )
    }

    val completedCount = currentDayProgress.interactionCountsToday[interaction] ?: 0
    if (completedCount >= interaction.dailyLimit) {
        return CompanionOperationResult(
            progress = currentDayProgress,
            success = false,
            message = "${interaction.displayName}今日次数已经用完"
        )
    }

    val countedProgress = currentDayProgress.copy(
        interactionCountsToday = currentDayProgress.interactionCountsToday.toMutableMap().apply {
            this[interaction] = completedCount + 1
        }
    )
    val rewardedProgress = countedProgress.afterClaimingTask(
        task = CompanionTask.COMPANION_INTERACTION,
        currentEpochDay = currentEpochDay
    )
    val actualGain = rewardedProgress.totalExperience - currentDayProgress.totalExperience
    return CompanionOperationResult(
        progress = rewardedProgress,
        success = true,
        message = if (actualGain > 0) {
            "今日首次伙伴互动，成长经验+$actualGain"
        } else {
            "互动完成 ${completedCount + 1}/${interaction.dailyLimit}"
        },
        experienceGained = actualGain
    )
}

/**
 * 领取每日首次登录金币。
 *
 * 使用方法：
 * App启动或前台跨日时调用。只有[currentEpochDay]严格大于历史领取日期才增加1金币，因此同一天
 * 重复启动和把系统日期向后调整都不会重复领取；无网络的纯本地模式无法完全防止主动把日期不断
 * 调到未来，这项限制符合金币不涉及真实支付的当前用途。
 *
 * @param currentEpochDay 当前本地日期对应的Epoch Day。
 * @return 规范化到当天后的伙伴状态。
 */
fun CompanionProgress.afterClaimingDailyCoin(currentEpochDay: Long): CompanionProgress {
    val currentDayProgress = normalizedForDay(currentEpochDay)
    if (currentEpochDay <= lastDailyCoinEpochDay) return currentDayProgress

    return currentDayProgress.copy(
        coins = (coins.toLong() + 1L)
            .coerceAtMost(CompanionProgress.MAX_COINS.toLong())
            .toInt(),
        lastDailyCoinEpochDay = currentEpochDay
    )
}

/**
 * 累加一段电子书前台有效阅读时间，并在首次跨过5、15和30分钟门槛时发放经验。
 *
 * @param elapsedMillis 本次阅读器处于前台RESUMED状态的单调时钟时长。
 * @param currentEpochDay 本次写入所属的本地日期。
 * @return 已累计阅读时长并完成相应每日任务的伙伴状态。
 */
fun CompanionProgress.afterRecordingReading(
    elapsedMillis: Long,
    currentEpochDay: Long
): CompanionProgress {
    val currentDayProgress = normalizedForDay(currentEpochDay)
    if (elapsedMillis <= 0L) return currentDayProgress

    var updated = currentDayProgress.copy(
        readingMillisToday = (currentDayProgress.readingMillisToday + elapsedMillis)
            .coerceIn(0L, COMPANION_MAX_DAILY_READING_MILLIS)
    )
    if (updated.readingMillisToday >= COMPANION_READING_5_MINUTES_MILLIS) {
        updated = updated.afterClaimingTask(
            CompanionTask.EBOOK_READ_5_MINUTES,
            currentEpochDay
        )
    }
    if (updated.readingMillisToday >= COMPANION_READING_15_MINUTES_MILLIS) {
        updated = updated.afterClaimingTask(
            CompanionTask.EBOOK_READ_15_MINUTES,
            currentEpochDay
        )
    }
    if (updated.readingMillisToday >= COMPANION_READING_30_MINUTES_MILLIS) {
        updated = updated.afterClaimingTask(
            CompanionTask.EBOOK_READ_30_MINUTES,
            currentEpochDay
        )
    }

    return updated
}

/**
 * 使用金币购买一件伙伴商品。
 *
 * @param item 需要购买的商品。
 * @param currentEpochDay 当前本地日期，用于同步清理昨日互动奖励状态。
 * @return 包含购买后余额、背包和提示语的操作结果。
 */
fun CompanionProgress.afterPurchasingItem(
    item: CompanionShopItem,
    currentEpochDay: Long
): CompanionOperationResult {
    val currentDayProgress = normalizedForDay(currentEpochDay)
    return when {
        currentDayProgress.level < item.unlockLevel -> CompanionOperationResult(
            progress = currentDayProgress,
            success = false,
            message = "需要达到Lv.${item.unlockLevel}才能购买${item.displayName}"
        )
        currentDayProgress.coins < item.priceCoins -> CompanionOperationResult(
            progress = currentDayProgress,
            success = false,
            message = "金币不足，还需要${item.priceCoins - currentDayProgress.coins}金币"
        )
        !item.consumable && item in currentDayProgress.ownedPermanentItems ->
            CompanionOperationResult(
                progress = currentDayProgress,
                success = false,
                message = "${item.displayName}已经永久拥有"
            )
        else -> {
            val updatedInventory = if (item.consumable) {
                currentDayProgress.consumableInventory.toMutableMap().apply {
                    this[item] = (get(item) ?: 0).plus(1)
                }
            } else {
                currentDayProgress.consumableInventory
            }
            val updatedOwnedItems = if (item.consumable) {
                currentDayProgress.ownedPermanentItems
            } else {
                currentDayProgress.ownedPermanentItems + item
            }
            CompanionOperationResult(
                progress = currentDayProgress.copy(
                    coins = currentDayProgress.coins - item.priceCoins,
                    consumableInventory = updatedInventory,
                    ownedPermanentItems = updatedOwnedItems
                ),
                success = true,
                message = "已购买${item.displayName}"
            )
        }
    }
}

/**
 * 使用背包中的消耗品或永久道具与伙伴互动。
 *
 * 消耗品每次使用都会扣除一个库存并增加经验；永久道具可以反复使用，但每天只有第一次增加经验。
 * 相机本身不增加经验，只负责永久解锁合影互动。
 *
 * @param item 需要使用的商品。
 * @param currentEpochDay 当前本地日期。
 * @return 使用后的背包、经验与用户提示。
 */
fun CompanionProgress.afterUsingItem(
    item: CompanionShopItem,
    currentEpochDay: Long
): CompanionOperationResult {
    val currentDayProgress = normalizedForDay(currentEpochDay)
    if (currentDayProgress.level < item.unlockLevel) {
        return CompanionOperationResult(
            progress = currentDayProgress,
            success = false,
            message = "需要达到Lv.${item.unlockLevel}才能使用${item.displayName}"
        )
    }

    if (item.consumable) {
        val currentCount = currentDayProgress.consumableInventory[item] ?: 0
        if (currentCount <= 0) {
            return CompanionOperationResult(
                progress = currentDayProgress,
                success = false,
                message = "背包里没有${item.displayName}，请先到商店购买"
            )
        }
        val updatedInventory = currentDayProgress.consumableInventory.toMutableMap().apply {
            if (currentCount == 1) remove(item) else this[item] = currentCount - 1
        }
        val updatedExperience = (currentDayProgress.totalExperience.toLong() +
            item.experienceReward.toLong())
            .coerceAtMost(CompanionProgress.MAX_TOTAL_EXPERIENCE.toLong())
            .toInt()
        val actualGain = updatedExperience - currentDayProgress.totalExperience
        return CompanionOperationResult(
            progress = currentDayProgress.copy(
                totalExperience = updatedExperience,
                consumableInventory = updatedInventory
            ),
            success = true,
            message = if (actualGain > 0) {
                "伙伴很喜欢${item.displayName}，成长经验+$actualGain"
            } else {
                "伙伴很喜欢${item.displayName}，当前等级已经满级"
            },
            experienceGained = actualGain
        )
    }

    if (item !in currentDayProgress.ownedPermanentItems) {
        return CompanionOperationResult(
            progress = currentDayProgress,
            success = false,
            message = "尚未拥有${item.displayName}，请先到商店购买"
        )
    }
    if (item in currentDayProgress.rewardedPermanentItemsToday) {
        return CompanionOperationResult(
            progress = currentDayProgress,
            success = true,
            message = "已再次使用${item.displayName}互动，今日首次经验已经领取"
        )
    }

    val updatedExperience = (currentDayProgress.totalExperience.toLong() +
        item.experienceReward.toLong())
        .coerceAtMost(CompanionProgress.MAX_TOTAL_EXPERIENCE.toLong())
        .toInt()
    val actualGain = updatedExperience - currentDayProgress.totalExperience
    return CompanionOperationResult(
        progress = currentDayProgress.copy(
            totalExperience = updatedExperience,
            rewardedPermanentItemsToday =
                currentDayProgress.rewardedPermanentItemsToday + item
        ),
        success = true,
        message = if (actualGain > 0) {
            "已使用${item.displayName}互动，今日首次经验+$actualGain"
        } else {
            "已使用${item.displayName}互动"
        },
        experienceGained = actualGain
    )
}

/**
 * 开发者面板按指定数量直接增加等级，同时保留当前等级内的经验进度。
 *
 * @param levels 需要增加的正整数等级数。
 * @return 增加后的状态；达到Lv.100后总经验固定在满级门槛。
 */
fun CompanionProgress.afterDeveloperAddingLevels(levels: Int): CompanionProgress {
    if (levels <= 0) return this
    val updatedExperience = (totalExperience.coerceAtLeast(0).toLong() +
        levels.toLong() * CompanionProgress.EXPERIENCE_PER_LEVEL.toLong())
        .coerceAtMost(CompanionProgress.MAX_TOTAL_EXPERIENCE.toLong())
        .toInt()
    return copy(totalExperience = updatedExperience)
}

/**
 * 开发者面板直接增加金币。
 *
 * @param coinsToAdd 需要增加的正整数金币数。
 * @return 金币增加后的状态，余额不会超过[CompanionProgress.MAX_COINS]。
 */
fun CompanionProgress.afterDeveloperAddingCoins(coinsToAdd: Int): CompanionProgress {
    if (coinsToAdd <= 0) return this
    return copy(
        coins = (coins.toLong() + coinsToAdd.toLong())
            .coerceAtMost(CompanionProgress.MAX_COINS.toLong())
            .toInt()
    )
}
