package com.example.harleyapp.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.CompanionInteraction
import com.example.harleyapp.model.CompanionOperationResult
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.CompanionShopItem
import kotlinx.coroutines.delay
import kotlin.random.Random

/** 伙伴互动屋的三个页面。 */
private enum class CompanionRoomPage {
    INTERACTIONS,
    SHOP,
    BACKPACK
}

/**
 * 显示伙伴的免费互动、金币商店和背包，并承载猜拳、接星光与记忆翻牌小游戏。
 *
 * 使用方法：
 * 首页伙伴卡片点击“伙伴互动”后调用。界面只负责收集用户操作，金币、库存和经验必须通过
 * [onPurchaseItem]、[onUseItem]及[onCompleteInteraction]交给仓库处理，不能在Composable内部
 * 直接构造新的成长数据。外层保存成功后传回新的[progress]，本窗口会随重组刷新余额和库存。
 *
 * @param progress 当前等级、金币、背包和解锁状态。
 * @param onPurchaseItem 购买商品回调，返回仓库校验后的完整操作结果。
 * @param onUseItem 使用背包物品回调，返回扣除库存和增加经验后的结果。
 * @param onCompleteInteraction 完成一次免费互动后的回调；每日首次互动经验由仓库去重。
 * @param onDismiss 关闭互动屋回调。
 *
 * @return 无返回值，直接显示伙伴互动对话框。
 */
@Composable
fun CompanionInteractionDialog(
    progress: CompanionProgress,
    onPurchaseItem: (CompanionShopItem) -> CompanionOperationResult,
    onUseItem: (CompanionShopItem) -> CompanionOperationResult,
    onCompleteInteraction: (CompanionInteraction) -> CompanionOperationResult,
    onDismiss: () -> Unit
) {
    var page by remember { mutableStateOf(CompanionRoomPage.INTERACTIONS) }
    var activeGame by remember { mutableStateOf<CompanionInteraction?>(null) }
    var message by remember { mutableStateOf("请选择一种方式陪伴它吧") }
    var animationTrigger by remember { mutableIntStateOf(0) }
    var reactionSymbol by remember { mutableStateOf("♥  ✦  ♥") }
    var reacting by remember { mutableStateOf(false) }

    // 每次成功互动都重启动画计时；连续操作会取消旧动画并从当前反馈重新播放。
    LaunchedEffect(animationTrigger) {
        if (animationTrigger <= 0) return@LaunchedEffect
        reacting = true
        delay(COMPANION_REACTION_DURATION_MILLIS)
        reacting = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "${progress.category.companionName}的互动屋")
                Text(
                    text = "Lv.${progress.level}  ·  金币 ${progress.coins}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 570.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CompanionRoomPageSelector(
                    selectedPage = page,
                    onSelect = { selected ->
                        page = selected
                        activeGame = null
                    }
                )

                CompanionInteractionStage(
                    progress = progress,
                    reacting = reacting,
                    animationTrigger = animationTrigger,
                    reactionSymbol = reactionSymbol
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = message,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                when (page) {
                    CompanionRoomPage.INTERACTIONS -> CompanionFreeInteractionContent(
                        progress = progress,
                        activeGame = activeGame,
                        onGameChanged = { activeGame = it },
                        onMessageChanged = { message = it },
                        onInteractionEffect = { interaction, result ->
                            if (result.success) {
                                reactionSymbol = interaction.reactionSymbol()
                                animationTrigger += 1
                            }
                        },
                        onCompleteInteraction = onCompleteInteraction
                    )

                    CompanionRoomPage.SHOP -> CompanionShopContent(
                        progress = progress,
                        onPurchaseItem = { item ->
                            message = onPurchaseItem(item).message
                        }
                    )

                    CompanionRoomPage.BACKPACK -> CompanionBackpackContent(
                        progress = progress,
                        onUseItem = { item ->
                            val result = onUseItem(item)
                            message = result.message
                            if (result.success) {
                                reactionSymbol = item.reactionSymbol()
                                animationTrigger += 1
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        }
    )
}

/**
 * 显示互动时会弹跳摆动的伙伴，以及短暂出现的爱心和星光反馈。
 *
 * @param progress 当前伙伴分类和等级。
 * @param reacting 当前是否处于一次成功互动的反馈阶段。
 * @param animationTrigger 动画触发序号，用奇偶值决定左右摆动方向。
 * @param reactionSymbol 本次互动对应的爱心、星光或食物符号。
 * @return 无返回值，直接输出伙伴动画舞台。
 */
@Composable
private fun CompanionInteractionStage(
    progress: CompanionProgress,
    reacting: Boolean,
    animationTrigger: Int,
    reactionSymbol: String
) {
    val scale by animateFloatAsState(
        targetValue = if (reacting) 1.16f else 1f,
        animationSpec = spring(dampingRatio = 0.48f, stiffness = 420f),
        label = "companion_reaction_scale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (reacting) {
            if (animationTrigger % 2 == 0) -7f else 7f
        } else {
            0f
        },
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 360f),
        label = "companion_reaction_rotation"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
    ) {
        Box(
            modifier = Modifier.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedCompanion(
                category = progress.category,
                level = progress.level,
                modifier = Modifier
                    .size(116.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                        translationY = if (reacting) -9f else 0f
                    }
            )
            AnimatedVisibility(
                visible = reacting,
                enter = fadeIn() + scaleIn(initialScale = 0.55f),
                exit = fadeOut() + scaleOut(targetScale = 1.35f)
            ) {
                Text(
                    modifier = Modifier.padding(bottom = 88.dp),
                    text = reactionSymbol,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 显示互动屋页面切换按钮。
 *
 * @param selectedPage 当前页面。
 * @param onSelect 用户选择页面后的回调。
 * @return 无返回值。
 */
@Composable
private fun CompanionRoomPageSelector(
    selectedPage: CompanionRoomPage,
    onSelect: (CompanionRoomPage) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            CompanionRoomPage.INTERACTIONS to "免费互动",
            CompanionRoomPage.SHOP to "金币商店",
            CompanionRoomPage.BACKPACK to "我的背包"
        ).forEach { (page, title) ->
            if (page == selectedPage) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(page) }
                ) {
                    Text(text = title)
                }
            } else {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(page) }
                ) {
                    Text(text = title)
                }
            }
        }
    }
}

/**
 * 显示所有免费互动及当前打开的小游戏。
 *
 * @param progress 当前成长状态，用于等级门槛判断。
 * @param activeGame 当前正在进行的小游戏；普通互动完成后保持为null。
 * @param onGameChanged 切换小游戏回调。
 * @param onMessageChanged 更新顶部互动反馈回调。
 * @param onInteractionEffect 成功互动后的伙伴动画触发回调。
 * @param onCompleteInteraction 互动完成后的持久化回调。
 * @return 无返回值。
 */
@Composable
private fun CompanionFreeInteractionContent(
    progress: CompanionProgress,
    activeGame: CompanionInteraction?,
    onGameChanged: (CompanionInteraction?) -> Unit,
    onMessageChanged: (String) -> Unit,
    onInteractionEffect: (CompanionInteraction, CompanionOperationResult) -> Unit,
    onCompleteInteraction: (CompanionInteraction) -> CompanionOperationResult
) {
    if (activeGame != null) {
        when (activeGame) {
            CompanionInteraction.ROCK_PAPER_SCISSORS -> CompanionRockPaperScissorsGame(
                onResult = { text ->
                    val result = onCompleteInteraction(activeGame)
                    onMessageChanged("$text；${result.message}")
                    onInteractionEffect(activeGame, result)
                    onGameChanged(null)
                }
            )

            CompanionInteraction.STAR_CATCH,
            CompanionInteraction.CHALLENGE_MODE -> CompanionStarCatchGame(
                challengeMode = activeGame == CompanionInteraction.CHALLENGE_MODE,
                onCompleted = { score ->
                    val result = onCompleteInteraction(activeGame)
                    onMessageChanged("接住了${score}颗星光；${result.message}")
                    onInteractionEffect(activeGame, result)
                    onGameChanged(null)
                }
            )

            CompanionInteraction.MEMORY_MATCH -> CompanionMemoryMatchGame(
                onCompleted = {
                    val result = onCompleteInteraction(activeGame)
                    onMessageChanged("成功找出三组图案；${result.message}")
                    onInteractionEffect(activeGame, result)
                    onGameChanged(null)
                }
            )

            else -> onGameChanged(null)
        }
        TextButton(onClick = { onGameChanged(null) }) {
            Text(text = "返回互动列表")
        }
        return
    }

    CompanionInteraction.entries.forEach { interaction ->
        val unlocked = progress.level >= interaction.unlockLevel
        val completedCount = (progress.interactionCountsToday[interaction] ?: 0)
            .coerceIn(0, interaction.dailyLimit)
        val limitReached = completedCount >= interaction.dailyLimit
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (unlocked) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = interaction.displayName,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when {
                            !unlocked -> "Lv.${interaction.unlockLevel}解锁 · ${interaction.description}"
                            limitReached -> "今日已完成 $completedCount/${interaction.dailyLimit}"
                            else -> "${interaction.description}  ·  今日 $completedCount/${interaction.dailyLimit}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    enabled = unlocked && !limitReached,
                    onClick = {
                        when (interaction) {
                            CompanionInteraction.ROCK_PAPER_SCISSORS,
                            CompanionInteraction.STAR_CATCH,
                            CompanionInteraction.MEMORY_MATCH,
                            CompanionInteraction.CHALLENGE_MODE -> onGameChanged(interaction)

                            else -> {
                                val result = onCompleteInteraction(interaction)
                                onMessageChanged(
                                    companionSimpleInteractionText(progress, interaction) +
                                        "；${result.message}"
                                )
                                onInteractionEffect(interaction, result)
                            }
                        }
                    }
                ) {
                    Text(
                        text = when {
                            !unlocked -> "未解锁"
                            limitReached -> "已互动 $completedCount/${interaction.dailyLimit}"
                            else -> "开始 $completedCount/${interaction.dailyLimit}"
                        }
                    )
                }
            }
        }
    }
}

/**
 * 显示金币商店商品并提交购买请求。
 *
 * @param progress 当前金币和等级。
 * @param onPurchaseItem 购买按钮回调。
 * @return 无返回值。
 */
@Composable
private fun CompanionShopContent(
    progress: CompanionProgress,
    onPurchaseItem: (CompanionShopItem) -> Unit
) {
    Text(
        text = "每天首次打开App可获得1金币；购买物品后再到背包互动，可加速成长。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    CompanionShopItem.entries.forEach { item ->
        val alreadyOwned = !item.consumable && item in progress.ownedPermanentItems
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${item.description}  ·  ${item.priceCoins}金币",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    enabled = progress.level >= item.unlockLevel && !alreadyOwned,
                    onClick = { onPurchaseItem(item) }
                ) {
                    Text(text = if (alreadyOwned) "已拥有" else "购买")
                }
            }
        }
    }
}

/**
 * 显示当前背包库存和永久道具，并提交物品互动请求。
 *
 * @param progress 当前背包状态。
 * @param onUseItem 使用物品回调。
 * @return 无返回值。
 */
@Composable
private fun CompanionBackpackContent(
    progress: CompanionProgress,
    onUseItem: (CompanionShopItem) -> Unit
) {
    val availableItems = CompanionShopItem.entries.filter { item ->
        if (item.consumable) {
            (progress.consumableInventory[item] ?: 0) > 0
        } else {
            item in progress.ownedPermanentItems
        }
    }
    if (availableItems.isEmpty()) {
        Text(
            text = "背包还是空的，可以先到金币商店看看。免费互动不需要购买任何物品。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    availableItems.forEach { item ->
        val quantityText = if (item.consumable) {
            "库存 ${progress.consumableInventory[item] ?: 0}"
        } else {
            "永久拥有"
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "$quantityText  ·  使用经验 +${item.experienceReward}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { onUseItem(item) }) {
                    Text(text = "互动")
                }
            }
        }
    }
}

/**
 * 运行一局石头剪刀布。
 *
 * @param onResult 每次出拳完成后的中文赛果回调。
 * @return 无返回值。
 */
@Composable
private fun CompanionRockPaperScissorsGame(onResult: (String) -> Unit) {
    val choices = listOf("石头", "剪刀", "布")
    Text(text = "选择你的手势", fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEachIndexed { userIndex, choice ->
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val companionIndex = Random.nextInt(choices.size)
                    val outcome = when ((userIndex - companionIndex + choices.size) % choices.size) {
                        0 -> "平局"
                        1 -> "你输了"
                        else -> "你赢了"
                    }
                    onResult("你出$choice，伙伴出${choices[companionIndex]}，$outcome")
                }
            ) {
                Text(text = choice)
            }
        }
    }
}

/**
 * 运行普通或困难接星光游戏，只有点中星光才累计分数。
 *
 * @param challengeMode true表示使用更多格子和更高目标数。
 * @param onCompleted 达到目标后回传最终分数。
 * @return 无返回值。
 */
@Composable
private fun CompanionStarCatchGame(
    challengeMode: Boolean,
    onCompleted: (Int) -> Unit
) {
    val cellCount = if (challengeMode) 12 else 9
    val targetScore = if (challengeMode) 12 else 8
    var starIndex by remember(challengeMode) { mutableIntStateOf(Random.nextInt(cellCount)) }
    var score by remember(challengeMode) { mutableIntStateOf(0) }
    Text(
        text = "点中闪亮星光：$score/$targetScore",
        fontWeight = FontWeight.SemiBold
    )
    repeat(if (challengeMode) 3 else 3) { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val columns = if (challengeMode) 4 else 3
            repeat(columns) { column ->
                val index = row * columns + column
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (index == starIndex) {
                            val updatedScore = score + 1
                            score = updatedScore
                            if (updatedScore >= targetScore) {
                                onCompleted(updatedScore)
                            } else {
                                var nextIndex = Random.nextInt(cellCount)
                                while (nextIndex == starIndex) {
                                    nextIndex = Random.nextInt(cellCount)
                                }
                                starIndex = nextIndex
                            }
                        }
                    }
                ) {
                    Text(text = if (index == starIndex) "★" else "·")
                }
            }
        }
    }
}

/**
 * 运行三组图案的记忆翻牌游戏。
 *
 * 第二张牌不匹配时会暂时保留两张明牌；用户下一次选择时再盖回旧牌，便于观察和记忆。
 *
 * @param onCompleted 找齐全部三组图案后的回调。
 * @return 无返回值。
 */
@Composable
private fun CompanionMemoryMatchGame(onCompleted: () -> Unit) {
    val deck = remember { listOf("月", "星", "花", "月", "星", "花").shuffled() }
    var openIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var matchedIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var mismatchVisible by remember { mutableStateOf(false) }

    Text(text = "找出三组相同图案", fontWeight = FontWeight.SemiBold)
    repeat(2) { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) { column ->
                val index = row * 3 + column
                val visible = index in openIndexes || index in matchedIndexes
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = index !in matchedIndexes,
                    onClick = {
                        if (mismatchVisible) {
                            openIndexes = setOf(index)
                            mismatchVisible = false
                        } else if (index !in openIndexes) {
                            val updatedOpenIndexes = openIndexes + index
                            openIndexes = updatedOpenIndexes
                            if (updatedOpenIndexes.size == 2) {
                                val selected = updatedOpenIndexes.toList()
                                if (deck[selected[0]] == deck[selected[1]]) {
                                    val updatedMatched = matchedIndexes + updatedOpenIndexes
                                    matchedIndexes = updatedMatched
                                    openIndexes = emptySet()
                                    if (updatedMatched.size == deck.size) {
                                        onCompleted()
                                    }
                                } else {
                                    mismatchVisible = true
                                }
                            }
                        }
                    }
                ) {
                    Text(text = if (visible) deck[index] else "?")
                }
            }
        }
    }
}

/**
 * 为无需独立小游戏的互动生成与伙伴类型相符的即时反馈。
 *
 * @param progress 当前伙伴分类和等级。
 * @param interaction 已完成的普通互动。
 * @return 可直接显示在互动屋顶部的中文反馈。
 */
private fun companionSimpleInteractionText(
    progress: CompanionProgress,
    interaction: CompanionInteraction
): String {
    return when (interaction) {
        CompanionInteraction.PET -> listOf(
            "${progress.category.companionName}舒服地眯起了眼睛",
            "${progress.category.companionName}开心地靠近了你",
            "${progress.category.companionName}轻轻回应了你的抚摸"
        ).random()

        CompanionInteraction.TALK -> listOf(
            "今天也一起完成一件小事吧",
            "我会记住你认真生活的每一天",
            "休息一下也属于成长的一部分"
        ).random()

        CompanionInteraction.SPECIAL_ACTION ->
            "${progress.category.companionName}展示了${progress.category.displayName}专属动作"

        CompanionInteraction.FINAL_CELEBRATION ->
            "满级庆典开始，${progress.category.companionName}为你点亮全部星光"

        else -> "互动完成"
    }
}

/**
 * 获取免费互动成功时漂浮在伙伴上方的图形反馈。
 *
 * @return 与互动含义相符的离线符号组合，不依赖图片或网络资源。
 */
private fun CompanionInteraction.reactionSymbol(): String {
    return when (this) {
        CompanionInteraction.PET -> "♥  ♥  ♥"
        CompanionInteraction.TALK -> "♪  ♥  ♪"
        CompanionInteraction.ROCK_PAPER_SCISSORS -> "✊  ✦  ✋"
        CompanionInteraction.STAR_CATCH -> "★  ✦  ★"
        CompanionInteraction.MEMORY_MATCH -> "月  ♥  星"
        CompanionInteraction.SPECIAL_ACTION -> "✦  ✧  ✦"
        CompanionInteraction.CHALLENGE_MODE -> "★  ★  ★"
        CompanionInteraction.FINAL_CELEBRATION -> "✦  ♥  ★  ♥  ✦"
    }
}

/**
 * 获取背包物品使用成功时的图形反馈。
 *
 * @return 与食物或玩具类型相符的离线符号组合。
 */
private fun CompanionShopItem.reactionSymbol(): String {
    return when (this) {
        CompanionShopItem.BISCUIT -> "🍪  ♥  🍪"
        CompanionShopItem.FRUIT_PLATE -> "🍎  ♥  🍊"
        CompanionShopItem.CELEBRATION_CAKE -> "🎂  ✦  ♥"
        CompanionShopItem.COLOR_BALL -> "●  ✦  ●"
        CompanionShopItem.STAR_WAND -> "★  ✦  ★"
        CompanionShopItem.PHOTO_CAMERA -> "✦  📷  ✦"
    }
}

/** 成功互动反馈持续时间，结束后伙伴会通过弹簧动画自然回到原位。 */
private const val COMPANION_REACTION_DURATION_MILLIS = 850L
