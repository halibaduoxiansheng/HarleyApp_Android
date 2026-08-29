package com.example.harleyapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.CompanionCategory
import com.example.harleyapp.model.CompanionProgress
import com.example.harleyapp.model.CompanionTask

/**
 * 显示首页玩偶、等级经验条、今日任务进度和成长入口。
 *
 * 使用方法：
 * HomeScreen传入CompanionRepository提供的最新状态。更换分类只通过onSelectCategory提交，
 * 经验和任务由实际业务成功回调发放，本卡片不会因为浏览或重复点击自行增加经验。
 *
 * @param progress 当前玩偶成长状态。
 * @param onSelectCategory 保存新玩偶分类的回调，成功返回true。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出成长卡片和相关对话框。
 */
@Composable
fun CompanionCard(
    progress: CompanionProgress,
    onSelectCategory: (CompanionCategory) -> Boolean,
    modifier: Modifier = Modifier
) {
    var showCategoryDialog by remember {
        mutableStateOf(false)
    }
    var showCollectionDialog by remember {
        mutableStateOf(false)
    }
    val requiredTasks = CompanionTask.entries.filter { task ->
        task.countsTowardDailyBonus
    }
    val completedRequiredTasks = requiredTasks.count { task ->
        task in progress.completedTasks
    }
    val colors = companionColors(progress.category)

    if (showCategoryDialog) {
        CompanionCategoryDialog(
            selectedCategory = progress.category,
            onDismiss = {
                showCategoryDialog = false
            },
            onSelect = { category ->
                val saved = onSelectCategory(category)
                if (saved) {
                    showCategoryDialog = false
                }
                saved
            }
        )
    }

    if (showCollectionDialog) {
        CompanionCollectionDialog(
            progress = progress,
            onDismiss = {
                showCollectionDialog = false
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(colors))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(82.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.92f),
                    shadowElevation = 3.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedCompanion(
                            category = progress.category,
                            level = progress.level,
                            modifier = Modifier
                                .size(76.dp)
                                .padding(4.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "${progress.category.companionName} · Lv.${progress.level}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = progress.category.formNameFor(progress.level),
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = progress.nextUnlockText(),
                        color = Color.White.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            LinearProgressIndicator(
                progress = {
                    progress.experienceInLevel.toFloat() /
                        CompanionProgress.EXPERIENCE_PER_LEVEL.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f)
            )
            Text(
                text = "本级经验 ${progress.experienceInLevel}/${CompanionProgress.EXPERIENCE_PER_LEVEL}  ·  " +
                    "累计 ${progress.totalExperience} EXP",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.14f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "今日任务 $completedRequiredTasks/${requiredTasks.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    requiredTasks.forEach { task ->
                        CompanionTaskRow(
                            task = task,
                            completed = task in progress.completedTasks
                        )
                    }
                    val bonusCompleted = CompanionTask.DAILY_BONUS in progress.completedTasks
                    Text(
                        text = if (bonusCompleted) {
                            "✓ 全部完成奖励 +${CompanionTask.DAILY_BONUS.experience} EXP"
                        } else {
                            "全部完成再得 +${CompanionTask.DAILY_BONUS.experience} EXP"
                        },
                        color = Color.White.copy(alpha = if (bonusCompleted) 1f else 0.74f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (bonusCompleted) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showCategoryDialog = true }) {
                    Text(text = "更换伙伴", color = Color.White)
                }
                TextButton(onClick = { showCollectionDialog = true }) {
                    Text(text = "成长图鉴", color = Color.White)
                }
            }
        }
    }
}

/**
 * 显示一项每日任务的完成状态和经验值。
 *
 * @param task 任务定义。
 * @param completed 今天是否已领取。
 *
 * @return 无返回值。
 */
@Composable
private fun CompanionTaskRow(
    task: CompanionTask,
    completed: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (completed) "✓" else "○",
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            text = task.title,
            color = Color.White.copy(alpha = if (completed) 1f else 0.78f),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "+${task.experience} EXP",
            color = Color.White.copy(alpha = if (completed) 1f else 0.7f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 让用户从六种玩偶分类中自由切换，并动态预览每位伙伴的初始形态。
 *
 * @param selectedCategory 当前分类。
 * @param onDismiss 关闭回调。
 * @param onSelect 保存新分类的回调，成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun CompanionCategoryDialog(
    selectedCategory: CompanionCategory,
    onDismiss: () -> Unit,
    onSelect: (CompanionCategory) -> Boolean
) {
    var saveError by remember {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "选择伙伴分类")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "切换伙伴不会重置等级、经验或今日任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CompanionCategory.entries.forEach { category ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!onSelect(category)) {
                                    saveError = "伙伴保存失败，请重试"
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (category == selectedCategory) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(58.dp),
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.72f)
                            ) {
                                AnimatedCompanion(
                                    category = category,
                                    level = 1,
                                    modifier = Modifier.padding(4.dp)
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text(
                                    text = "${category.displayName} · ${category.companionName}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "终极形态：${category.formNameFor(CompanionProgress.MAX_LEVEL)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (category == selectedCategory) {
                                Text(
                                    text = "当前",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
                if (saveError.isNotBlank()) {
                    Text(
                        text = saveError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
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
 * 展示当前动态形象、六段形态路线和全部技能解锁状态。
 *
 * 使用方法：
 * 点击首页“成长图鉴”后传入当前成长状态。形态和技能列表可以纵向滚动，已经解锁的形态
 * 会保持完整色彩并持续动画，尚未解锁的形态则以低透明度预览。
 *
 * @param progress 当前成长状态。
 * @param onDismiss 关闭回调。
 *
 * @return 无返回值，直接显示可滚动的成长图鉴对话框。
 */
@Composable
private fun CompanionCollectionDialog(
    progress: CompanionProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "${progress.category.companionName}成长图鉴")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    ) {
                        AnimatedCompanion(
                            category = progress.category,
                            level = progress.level,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Lv.${progress.level} · ${progress.category.formNameFor(progress.level)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (progress.level >= CompanionProgress.MAX_LEVEL) {
                                "已完成全部成长阶段"
                            } else {
                                progress.nextUnlockText()
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "成长形态",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "形态在 Lv.1、Lv.3、Lv.6、Lv.10、Lv.15、Lv.20 依次解锁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                progress.category.forms.forEach { form ->
                    val unlocked = progress.level >= form.unlockLevel
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (unlocked) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedCompanion(
                                category = progress.category,
                                level = form.unlockLevel,
                                modifier = Modifier
                                    .size(52.dp)
                                    .alpha(if (unlocked) 1f else 0.34f)
                                    .padding(3.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 10.dp)
                            ) {
                                Text(
                                    text = "Lv.${form.unlockLevel} ${form.name}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (unlocked) "已解锁，可动态预览" else "继续成长后解锁",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (unlocked) "✓" else "🔒",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                Text(
                    text = "伙伴技能",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                progress.category.skills.forEach { skill ->
                    val unlocked = progress.level >= skill.unlockLevel
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = if (unlocked) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (unlocked) {
                                    "✓ Lv.${skill.unlockLevel} ${skill.name}"
                                } else {
                                    "🔒 Lv.${skill.unlockLevel} ${skill.name}"
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = skill.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "知道了")
            }
        }
    )
}

/**
 * 获取不同玩偶分类的卡片渐变色。
 *
 * @param category 玩偶分类。
 *
 * @return 两端颜色组成的渐变列表。
 */
private fun companionColors(category: CompanionCategory): List<Color> {
    return when (category) {
        CompanionCategory.FOREST -> listOf(Color(0xFF2E6B45), Color(0xFF73A442))
        CompanionCategory.OCEAN -> listOf(Color(0xFF176B9A), Color(0xFF20A6A1))
        CompanionCategory.TECHNOLOGY -> listOf(Color(0xFF3F3D8F), Color(0xFF7B4EC7))
        CompanionCategory.SKY -> listOf(Color(0xFF2479B8), Color(0xFF79C9E8))
        CompanionCategory.DESERT -> listOf(Color(0xFF9A5B21), Color(0xFFE0A24A))
        CompanionCategory.COSMOS -> listOf(Color(0xFF342A78), Color(0xFF8B5CC7))
    }
}
