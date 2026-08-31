package com.example.harleyapp.data

import android.content.Context
import android.util.Log
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionInteraction
import com.example.harleyapp.model.CompanionOperationResult
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

/**
 * 保存玩偶分类、累计经验、金币、背包以及当天任务和阅读状态。
 *
 * 使用方法：
 * App启动后通过getProgress读取状态，再调用claimTask领取每日任务经验；
 * 用户更换玩偶时调用selectCategory。仓库会用日期和任务枚举双重去重，
 * 同一天反复进入页面或反复点击同一功能不会重复增加经验。
 *
 * @param context Android上下文，内部使用Application Context创建SharedPreferences。
 */
class CompanionRepository(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCE_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * 读取指定日期下的玩偶成长状态。
     *
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     *
     * @return 完整成长状态；保存的任务日期不是今天时，返回的当天任务集合为空。
     */
    fun getProgress(currentEpochDay: Long): CompanionProgress {
        val categoryName = preferences.getString(KEY_CATEGORY, null)
        val category = CompanionCategory.entries.firstOrNull { candidate ->
            candidate.name == categoryName
        } ?: CompanionCategory.FOREST
        val totalExperience = preferences.getInt(KEY_TOTAL_EXPERIENCE, 0)
            .coerceIn(0, CompanionProgress.MAX_TOTAL_EXPERIENCE)
        val storedEpochDay = preferences.getLong(KEY_TASK_EPOCH_DAY, currentEpochDay)
        val isCurrentDay = storedEpochDay == currentEpochDay
        val completedTasks = if (storedEpochDay == currentEpochDay) {
            preferences.getStringSet(KEY_COMPLETED_TASKS, emptySet())
                .orEmpty()
                .mapNotNullTo(mutableSetOf()) { taskName ->
                    CompanionTask.entries.firstOrNull { task -> task.name == taskName }
                }
        } else {
            emptySet()
        }
        val coins = preferences.getInt(KEY_COINS, 0)
            .coerceIn(0, CompanionProgress.MAX_COINS)
        val lastDailyCoinEpochDay = preferences.getLong(
            KEY_LAST_DAILY_COIN_EPOCH_DAY,
            Long.MIN_VALUE
        )
        val readingMillisToday = if (isCurrentDay) {
            preferences.getLong(KEY_READING_MILLIS_TODAY, 0L).coerceAtLeast(0L)
        } else {
            0L
        }
        val consumableInventory = decodeConsumableInventory(
            preferences.getStringSet(KEY_CONSUMABLE_INVENTORY, emptySet()).orEmpty()
        )
        val ownedPermanentItems = decodeShopItemSet(
            preferences.getStringSet(KEY_OWNED_PERMANENT_ITEMS, emptySet()).orEmpty()
        ).filterTo(mutableSetOf()) { item -> !item.consumable }
        val rewardedPermanentItemsToday = if (isCurrentDay) {
            decodeShopItemSet(
                preferences.getStringSet(
                    KEY_REWARDED_PERMANENT_ITEMS_TODAY,
                    emptySet()
                ).orEmpty()
            ).filterTo(mutableSetOf()) { item -> !item.consumable }
        } else {
            emptySet()
        }
        val interactionCountsToday = if (isCurrentDay) {
            decodeInteractionCounts(
                preferences.getStringSet(KEY_INTERACTION_COUNTS_TODAY, emptySet()).orEmpty()
            )
        } else {
            emptyMap()
        }

        return CompanionProgress(
            category = category,
            totalExperience = totalExperience,
            taskEpochDay = currentEpochDay,
            completedTasks = completedTasks,
            coins = coins,
            lastDailyCoinEpochDay = lastDailyCoinEpochDay,
            readingMillisToday = readingMillisToday,
            consumableInventory = consumableInventory,
            ownedPermanentItems = ownedPermanentItems,
            rewardedPermanentItemsToday = rewardedPermanentItemsToday,
            interactionCountsToday = interactionCountsToday
        )
    }

    /**
     * 原子领取每日首次打开经验和1枚登录金币。
     *
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 写入后的伙伴状态；同一天重复调用只返回当前状态。
     */
    fun claimDailyLogin(currentEpochDay: Long): CompanionProgress {
        val currentProgress = getProgress(currentEpochDay)
        val updatedProgress = currentProgress
            .afterClaimingTask(CompanionTask.DAILY_OPEN, currentEpochDay)
            .afterClaimingDailyCoin(currentEpochDay)
        if (updatedProgress == currentProgress) return currentProgress

        return if (persistProgress(updatedProgress)) updatedProgress else currentProgress
    }

    /**
     * 领取一项每日任务经验，并在所有常规任务完成后自动追加每日总奖励。
     *
     * @param task 本次成功完成的任务；DAILY_BONUS由仓库自动判断，不应由页面直接传入。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     *
     * @return 写入成功后的新状态；任务当天已经领取或写入失败时返回原状态。
     */
    fun claimTask(task: CompanionTask, currentEpochDay: Long): CompanionProgress {
        val currentProgress = getProgress(currentEpochDay)
        val updatedProgress = currentProgress.afterClaimingTask(task, currentEpochDay)
        if (updatedProgress == currentProgress) {
            return currentProgress
        }

        return if (persistProgress(updatedProgress)) {
            updatedProgress
        } else {
            currentProgress
        }
    }

    /**
     * 累加一段电子书有效阅读时长，并自动领取跨过的阅读里程碑经验。
     *
     * @param elapsedMillis 阅读器在前台RESUMED状态的单调时钟毫秒数。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 写入后的伙伴状态；无有效时长或写入失败时返回当前状态。
     */
    fun recordReading(elapsedMillis: Long, currentEpochDay: Long): CompanionProgress {
        val currentProgress = getProgress(currentEpochDay)
        val updatedProgress = currentProgress.afterRecordingReading(
            elapsedMillis = elapsedMillis,
            currentEpochDay = currentEpochDay
        )
        if (updatedProgress == currentProgress) return currentProgress

        return if (persistProgress(updatedProgress)) updatedProgress else currentProgress
    }

    /**
     * 使用当前金币购买一件商店商品。
     *
     * @param item 需要购买的商品。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 成功时包含新余额和背包；失败时保留原状态并说明原因。
     */
    fun purchaseItem(
        item: CompanionShopItem,
        currentEpochDay: Long
    ): CompanionOperationResult {
        val currentProgress = getProgress(currentEpochDay)
        return persistOperationResult(
            result = currentProgress.afterPurchasingItem(item, currentEpochDay),
            fallbackProgress = currentProgress
        )
    }

    /**
     * 使用一件已购买物品与伙伴互动并按规则增加经验。
     *
     * @param item 消耗品或永久道具。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 使用后的完整状态和反馈说明。
     */
    fun useItem(
        item: CompanionShopItem,
        currentEpochDay: Long
    ): CompanionOperationResult {
        val currentProgress = getProgress(currentEpochDay)
        return persistOperationResult(
            result = currentProgress.afterUsingItem(item, currentEpochDay),
            fallbackProgress = currentProgress
        )
    }

    /**
     * 记录一次真正完成的免费互动，并执行等级、每日次数和首次经验校验。
     *
     * 使用方法：
     * 普通互动的反馈动画触发后调用；猜拳、接星光和记忆翻牌必须在一局完成后调用，不能只在
     * 用户打开小游戏页面时占用次数。仓库会原子保存次数与经验，保存失败时恢复操作前状态。
     *
     * @param interaction 本次完成的互动类型。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 成功时包含更新后的次数和经验；等级不足、次数用完或写入失败时说明具体原因。
     */
    fun completeInteraction(
        interaction: CompanionInteraction,
        currentEpochDay: Long
    ): CompanionOperationResult {
        val currentProgress = getProgress(currentEpochDay)
        return persistOperationResult(
            result = currentProgress.afterCompletingInteraction(
                interaction = interaction,
                currentEpochDay = currentEpochDay
            ),
            fallbackProgress = currentProgress
        )
    }

    /**
     * 已验证开发者直接增加指定等级数。
     *
     * @param levels 需要增加的正整数等级数。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 写入后的状态；参数无效或写入失败时返回当前状态。
     */
    fun addDeveloperLevels(levels: Int, currentEpochDay: Long): CompanionProgress {
        val currentProgress = getProgress(currentEpochDay)
        val updatedProgress = currentProgress.afterDeveloperAddingLevels(levels)
        if (updatedProgress == currentProgress) return currentProgress

        return if (persistProgress(updatedProgress)) updatedProgress else currentProgress
    }

    /**
     * 已验证开发者直接增加指定金币数。
     *
     * @param coinsToAdd 需要增加的正整数金币数。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     * @return 写入后的状态；参数无效或写入失败时返回当前状态。
     */
    fun addDeveloperCoins(coinsToAdd: Int, currentEpochDay: Long): CompanionProgress {
        val currentProgress = getProgress(currentEpochDay)
        val updatedProgress = currentProgress.afterDeveloperAddingCoins(coinsToAdd)
        if (updatedProgress == currentProgress) return currentProgress

        return if (persistProgress(updatedProgress)) updatedProgress else currentProgress
    }

    /**
     * 更换当前玩偶分类，并保留累计经验和当天任务。
     *
     * @param category 用户选择的新分类。
     * @param currentEpochDay 当前本地日期对应的Epoch Day。
     *
     * @return 写入成功后的新状态；写入失败时返回null。
     */
    fun selectCategory(
        category: CompanionCategory,
        currentEpochDay: Long
    ): CompanionProgress? {
        val updatedProgress = getProgress(currentEpochDay).copy(category = category)
        return if (persistProgress(updatedProgress)) updatedProgress else null
    }

    /**
     * 同步保存成长状态的全部字段，保证经验和任务去重集合一起提交。
     *
     * @param progress 待保存的最新状态。
     *
     * @return SharedPreferences提交成功时返回true，否则返回false。
     */
    private fun persistProgress(progress: CompanionProgress): Boolean {
        val success = preferences.edit()
            .putString(KEY_CATEGORY, progress.category.name)
            .putInt(KEY_TOTAL_EXPERIENCE, progress.totalExperience.coerceAtLeast(0))
            .putLong(KEY_TASK_EPOCH_DAY, progress.taskEpochDay)
            .putStringSet(
                KEY_COMPLETED_TASKS,
                progress.completedTasks.mapTo(mutableSetOf()) { task -> task.name }
            )
            .putInt(KEY_COINS, progress.coins.coerceIn(0, CompanionProgress.MAX_COINS))
            .putLong(KEY_LAST_DAILY_COIN_EPOCH_DAY, progress.lastDailyCoinEpochDay)
            .putLong(KEY_READING_MILLIS_TODAY, progress.readingMillisToday.coerceAtLeast(0L))
            .putStringSet(
                KEY_CONSUMABLE_INVENTORY,
                encodeConsumableInventory(progress.consumableInventory)
            )
            .putStringSet(
                KEY_OWNED_PERMANENT_ITEMS,
                progress.ownedPermanentItems.mapTo(mutableSetOf()) { item -> item.name }
            )
            .putStringSet(
                KEY_REWARDED_PERMANENT_ITEMS_TODAY,
                progress.rewardedPermanentItemsToday.mapTo(mutableSetOf()) { item -> item.name }
            )
            .putStringSet(
                KEY_INTERACTION_COUNTS_TODAY,
                encodeInteractionCounts(progress.interactionCountsToday)
            )
            .commit()
        if (!success) {
            Log.e(TAG, "Failed to persist companion progress")
        }

        return success
    }

    /**
     * 只在业务操作成功且新状态持久化成功后对外返回成功。
     *
     * @param result 纯模型层生成的购买或使用结果。
     * @param fallbackProgress 持久化失败时必须恢复的操作前状态。
     * @return 可以安全交给页面刷新的最终结果。
     */
    private fun persistOperationResult(
        result: CompanionOperationResult,
        fallbackProgress: CompanionProgress
    ): CompanionOperationResult {
        if (!result.success) return result
        if (persistProgress(result.progress)) return result

        return CompanionOperationResult(
            progress = fallbackProgress,
            success = false,
            message = "伙伴数据保存失败，请重试"
        )
    }

    /** @return 只包含有效商店枚举名称的集合。 */
    private fun decodeShopItemSet(values: Set<String>): Set<CompanionShopItem> {
        return values.mapNotNullTo(mutableSetOf()) { name ->
            CompanionShopItem.entries.firstOrNull { item -> item.name == name }
        }
    }

    /**
     * 解码“商品枚举名=数量”的消耗品库存集合。
     *
     * @return 已过滤未知商品、永久道具、非法数量和重复项的库存映射。
     */
    private fun decodeConsumableInventory(values: Set<String>): Map<CompanionShopItem, Int> {
        val inventory = mutableMapOf<CompanionShopItem, Int>()
        values.forEach { encoded ->
            val parts = encoded.split(INVENTORY_SEPARATOR, limit = 2)
            val item = CompanionShopItem.entries.firstOrNull { candidate ->
                candidate.name == parts.firstOrNull()
            }
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (item != null && item.consumable && count != null && count > 0) {
                inventory[item] = count.coerceAtMost(MAX_SINGLE_ITEM_COUNT)
            }
        }
        return inventory
    }

    /** @return 可安全写入StringSet的“商品枚举名=数量”库存集合。 */
    private fun encodeConsumableInventory(
        inventory: Map<CompanionShopItem, Int>
    ): MutableSet<String> {
        return inventory.mapNotNullTo(mutableSetOf()) { (item, count) ->
            if (item.consumable && count > 0) {
                "${item.name}$INVENTORY_SEPARATOR${count.coerceAtMost(MAX_SINGLE_ITEM_COUNT)}"
            } else {
                null
            }
        }
    }

    /**
     * 解码“互动枚举名=次数”的当日互动记录。
     *
     * @param values SharedPreferences保存的字符串集合。
     * @return 已过滤未知互动、非法次数，并按各互动每日上限截断后的映射。
     */
    private fun decodeInteractionCounts(
        values: Set<String>
    ): Map<CompanionInteraction, Int> {
        val counts = mutableMapOf<CompanionInteraction, Int>()
        values.forEach { encoded ->
            val parts = encoded.split(INVENTORY_SEPARATOR, limit = 2)
            val interaction = CompanionInteraction.entries.firstOrNull { candidate ->
                candidate.name == parts.firstOrNull()
            }
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (interaction != null && count != null && count > 0) {
                counts[interaction] = count.coerceAtMost(interaction.dailyLimit)
            }
        }
        return counts
    }

    /**
     * 把当日互动次数编码为SharedPreferences可安全保存的字符串集合。
     *
     * @param counts 当前各互动完成次数。
     * @return 只包含正数且不超过各互动上限的“枚举名=次数”集合。
     */
    private fun encodeInteractionCounts(
        counts: Map<CompanionInteraction, Int>
    ): MutableSet<String> {
        return counts.mapNotNullTo(mutableSetOf()) { (interaction, count) ->
            if (count > 0) {
                "${interaction.name}$INVENTORY_SEPARATOR" +
                    count.coerceAtMost(interaction.dailyLimit)
            } else {
                null
            }
        }
    }

    private companion object {
        const val TAG = "CompanionRepository"
        const val PREFERENCE_NAME = "harley_companion"
        const val KEY_CATEGORY = "category"
        const val KEY_TOTAL_EXPERIENCE = "total_experience"
        const val KEY_TASK_EPOCH_DAY = "task_epoch_day"
        const val KEY_COMPLETED_TASKS = "completed_tasks"
        const val KEY_COINS = "coins"
        const val KEY_LAST_DAILY_COIN_EPOCH_DAY = "last_daily_coin_epoch_day"
        const val KEY_READING_MILLIS_TODAY = "reading_millis_today"
        const val KEY_CONSUMABLE_INVENTORY = "consumable_inventory"
        const val KEY_OWNED_PERMANENT_ITEMS = "owned_permanent_items"
        const val KEY_REWARDED_PERMANENT_ITEMS_TODAY = "rewarded_permanent_items_today"
        const val KEY_INTERACTION_COUNTS_TODAY = "interaction_counts_today"
        const val INVENTORY_SEPARATOR = "="
        const val MAX_SINGLE_ITEM_COUNT = 9_999
    }
}
