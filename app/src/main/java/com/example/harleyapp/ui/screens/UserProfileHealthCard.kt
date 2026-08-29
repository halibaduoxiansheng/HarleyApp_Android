package com.example.harleyapp.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.harleyapp.data.FitnessPlanSyncResult
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.data.UserProfileRepository
import com.example.harleyapp.model.FitnessExerciseDefinition
import com.example.harleyapp.model.FitnessGoalPeriod
import com.example.harleyapp.model.FitnessTrackingType
import com.example.harleyapp.model.HealthRecommendationPlan
import com.example.harleyapp.model.UserGender
import com.example.harleyapp.model.UserProfile
import com.example.harleyapp.model.buildAdultHealthRecommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 在“我的”页面显示用户资料、健康提示和可编辑的一键同步入口。
 *
 * 使用方法：
 * 直接放入ProfileScreen的LazyColumn。组件内部创建本地用户资料仓库和运动仓库，负责完整的
 * 新增、查询、修改、删除流程；建议同步前始终打开预览弹窗，用户可修改或停用任意建议项目。
 *
 * @return 无返回值，直接输出用户资料与健康建议卡片。
 */
@Composable
fun UserProfileHealthCard() {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val profileRepository = remember(applicationContext) {
        UserProfileRepository(applicationContext)
    }
    val fitnessRepository = remember(applicationContext) {
        FitnessRepository(applicationContext)
    }
    var profile by remember {
        mutableStateOf(profileRepository.getProfile())
    }
    var showProfileEditor by rememberSaveable {
        mutableStateOf(false)
    }
    var showDeleteConfirmation by rememberSaveable {
        mutableStateOf(false)
    }
    var recommendationPlan by remember {
        mutableStateOf<HealthRecommendationPlan?>(null)
    }
    var planDefinitions by remember {
        mutableStateOf<List<FitnessExerciseDefinition>>(emptyList())
    }
    var message by rememberSaveable {
        mutableStateOf("")
    }

    if (showProfileEditor) {
        UserProfileEditorDialog(
            initialProfile = profile,
            onDismiss = {
                showProfileEditor = false
            },
            onSave = { updatedProfile ->
                val previousAvatarUri = profile?.avatarUri.orEmpty()
                val savedProfile = profileRepository.saveProfile(updatedProfile)
                if (savedProfile != null) {
                    if (previousAvatarUri.isNotBlank() &&
                        previousAvatarUri != savedProfile.avatarUri
                    ) {
                        releaseAvatarPermission(context, previousAvatarUri)
                    }
                    profile = savedProfile
                    recommendationPlan = null
                    showProfileEditor = false
                    message = "用户资料已保存，可查看并调整新的运动建议"
                    true
                } else {
                    false
                }
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmation = false
            },
            title = { Text(text = "清除用户资料") },
            text = {
                Text(
                    text = "头像、姓名、性别、年龄、身高和体重将从本机删除。" +
                        "已经同步到运动功能的项目和历史记录会继续保留。"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val avatarUri = profile?.avatarUri.orEmpty()
                        if (profileRepository.deleteProfile()) {
                            if (avatarUri.isNotBlank()) {
                                releaseAvatarPermission(context, avatarUri)
                            }
                            profile = null
                            recommendationPlan = null
                            showDeleteConfirmation = false
                            message = "用户资料已清除，运动计划未受影响"
                        } else {
                            message = "用户资料删除失败，请重试"
                        }
                    }
                ) {
                    Text(text = "确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(text = "取消")
                }
            }
        )
    }

    recommendationPlan?.let { plan ->
        HealthRecommendationEditorDialog(
            plan = plan,
            currentDefinitions = planDefinitions,
            onDismiss = {
                recommendationPlan = null
            },
            onSync = { definitions ->
                fitnessRepository.syncExercisePlan(definitions)
            },
            onSynced = { result ->
                recommendationPlan = null
                message = "运动计划同步完成：新增${result.addedCount}项，更新${result.updatedCount}项"
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProfileAvatar(
                    avatarUri = profile?.avatarUri.orEmpty(),
                    name = profile?.name.orEmpty()
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.name?.ifBlank { "用户信息" } ?: "用户信息",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (profile == null) {
                            "完善资料后生成可编辑的健康运动建议"
                        } else {
                            "${profile?.gender?.displayName} · ${profile?.ageYears}岁 · " +
                                "${profile?.heightCm}cm · ${formatWeight(profile?.weightKg ?: 0.0)}kg"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
                TextButton(onClick = { showProfileEditor = true }) {
                    Text(text = if (profile == null) "填写" else "修改")
                }
            }

            AnimatedVisibility(visible = profile?.isReadyForHealthAdvice() == true) {
                profile?.let { currentProfile ->
                    val bmi = currentProfile.adultBmi()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = "健康运动建议",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "BMI筛查值 ${String.format(Locale.getDefault(), "%.1f", bmi ?: 0.0)}。" +
                                        "建议以每周有氧累计和力量训练频次为主，并循序渐进。",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "仅供日常健康管理参考，不作为诊断或个体化医疗处方。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val currentDefinitions = fitnessRepository.getExerciseDefinitions()
                                val generatedPlan = buildAdultHealthRecommendation(
                                    profile = currentProfile,
                                    currentDefinitions = currentDefinitions
                                )
                                if (generatedPlan == null) {
                                    message = "资料不完整，请先修改用户信息"
                                } else {
                                    planDefinitions = currentDefinitions
                                    recommendationPlan = generatedPlan
                                    message = ""
                                }
                            }
                        ) {
                            Text(text = "查看、修改并一键同步")
                        }
                    }
                }
            }

            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (profile != null) {
                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    onClick = { showDeleteConfirmation = true }
                ) {
                    Text(
                        text = "清除资料",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 编辑头像和全部用户资料字段。
 *
 * @param initialProfile 当前资料；null表示首次创建。
 * @param onDismiss 放弃本次编辑的回调。
 * @param onSave 提交资料的回调，仓库保存成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun UserProfileEditorDialog(
    initialProfile: UserProfile?,
    onDismiss: () -> Unit,
    onSave: (UserProfile) -> Boolean
) {
    val context = LocalContext.current
    val originalAvatarUri = initialProfile?.avatarUri.orEmpty()
    var avatarUri by rememberSaveable(initialProfile) {
        mutableStateOf(originalAvatarUri)
    }
    var nameText by rememberSaveable(initialProfile) {
        mutableStateOf(initialProfile?.name.orEmpty())
    }
    var gender by rememberSaveable(initialProfile) {
        mutableStateOf(initialProfile?.gender ?: UserGender.UNSPECIFIED)
    }
    var ageText by rememberSaveable(initialProfile) {
        mutableStateOf(initialProfile?.ageYears?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var heightText by rememberSaveable(initialProfile) {
        mutableStateOf(initialProfile?.heightCm?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var weightText by rememberSaveable(initialProfile) {
        mutableStateOf(
            initialProfile?.weightKg?.takeIf { it > 0.0 }?.let(::formatWeight).orEmpty()
        )
    }
    var errorText by rememberSaveable(initialProfile) {
        mutableStateOf("")
    }
    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { selectedUri ->
        if (selectedUri != null) {
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure { error ->
                Log.e(PROFILE_UI_TAG, "Failed to persist avatar permission", error)
            }.isSuccess
            if (granted) {
                if (avatarUri.isNotBlank() && avatarUri != originalAvatarUri) {
                    releaseAvatarPermission(context, avatarUri)
                }
                avatarUri = selectedUri.toString()
                errorText = ""
            } else {
                errorText = "头像读取授权失败，请重新选择"
            }
        }
    }
    val dismissWithoutSaving = {
        if (avatarUri.isNotBlank() && avatarUri != originalAvatarUri) {
            releaseAvatarPermission(context, avatarUri)
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismissWithoutSaving,
        title = { Text(text = if (initialProfile == null) "创建用户资料" else "修改用户资料") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileAvatar(avatarUri = avatarUri, name = nameText)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        OutlinedButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) {
                            Text(text = "从相册选择头像")
                        }
                        if (avatarUri.isNotBlank()) {
                            TextButton(onClick = { avatarUri = "" }) {
                                Text(text = "移除头像")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nameText,
                    onValueChange = {
                        nameText = it.take(MAX_PROFILE_NAME_LENGTH)
                        errorText = ""
                    },
                    label = { Text(text = "姓名或昵称") },
                    singleLine = true
                )
                ProfileGenderSelector(
                    selectedGender = gender,
                    onGenderSelected = {
                        gender = it
                        errorText = ""
                    }
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = ageText,
                    onValueChange = {
                        ageText = it.filter(Char::isDigit).take(3)
                        errorText = ""
                    },
                    label = { Text(text = "年龄（18-120岁）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = heightText,
                    onValueChange = {
                        heightText = it.filter(Char::isDigit).take(3)
                        errorText = ""
                    },
                    label = { Text(text = "身高（厘米）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = weightText,
                    onValueChange = {
                        weightText = sanitizeDecimalInput(it)
                        errorText = ""
                    },
                    label = { Text(text = "体重（千克）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Text(
                    text = "资料仅保存在本机；建议面向18岁及以上成年人。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (errorText.isNotBlank()) {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val age = ageText.toIntOrNull()
                    val height = heightText.toIntOrNull()
                    val weight = weightText.toDoubleOrNull()
                    errorText = when {
                        nameText.trim().isBlank() -> "请输入姓名或昵称"
                        age == null || age !in UserProfile.MIN_ADULT_AGE..UserProfile.MAX_PROFILE_AGE -> {
                            "年龄请输入18到120"
                        }
                        height == null || height !in UserProfile.MIN_HEIGHT_CM..UserProfile.MAX_HEIGHT_CM -> {
                            "身高请输入80到250厘米"
                        }
                        weight == null || weight !in UserProfile.MIN_WEIGHT_KG..UserProfile.MAX_WEIGHT_KG -> {
                            "体重请输入25到350千克"
                        }
                        onSave(
                            UserProfile(
                                avatarUri = avatarUri,
                                name = nameText.trim(),
                                gender = gender,
                                ageYears = age,
                                heightCm = height,
                                weightKg = weight
                            )
                        ) -> ""
                        else -> "用户资料保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存资料")
            }
        },
        dismissButton = {
            TextButton(onClick = dismissWithoutSaving) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 显示四种性别选择，保持资料输入布局在窄屏上不溢出。
 *
 * @param selectedGender 当前选中值。
 * @param onGenderSelected 用户点击新选项时的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ProfileGenderSelector(
    selectedGender: UserGender,
    onGenderSelected: (UserGender) -> Unit
) {
    Text(text = "性别", fontWeight = FontWeight.SemiBold)
    UserGender.entries.chunked(2).forEach { rowGenders ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowGenders.forEach { gender ->
                if (gender == selectedGender) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { onGenderSelected(gender) }
                    ) {
                        Text(text = gender.displayName)
                    }
                } else {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { onGenderSelected(gender) }
                    ) {
                        Text(text = gender.displayName)
                    }
                }
            }
        }
    }
}

/**
 * 建议预览中的可编辑临时项目。
 *
 * @param enabled 本次同步是否包含该项目。
 * @param definition 可继续修改的运动项目定义。
 * @param reason 通用健康说明。
 */
private data class EditableHealthRecommendation(
    val enabled: Boolean,
    val definition: FitnessExerciseDefinition,
    val reason: String
)

/**
 * 展示并编辑健康建议，确认后一次性同步到运动计划。
 *
 * @param plan 根据资料生成的初始建议。
 * @param currentDefinitions 打开弹窗时的现有运动项目，用于标记新增或更新。
 * @param onDismiss 放弃本次建议的回调。
 * @param onSync 原子同步全部启用项目的回调。
 * @param onSynced 同步成功后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun HealthRecommendationEditorDialog(
    plan: HealthRecommendationPlan,
    currentDefinitions: List<FitnessExerciseDefinition>,
    onDismiss: () -> Unit,
    onSync: (List<FitnessExerciseDefinition>) -> FitnessPlanSyncResult,
    onSynced: (FitnessPlanSyncResult) -> Unit
) {
    var editableItems by remember(plan) {
        mutableStateOf(
            plan.exercises.map { recommendation ->
                EditableHealthRecommendation(
                    enabled = true,
                    definition = recommendation.definition,
                    reason = recommendation.reason
                )
            }
        )
    }
    var errorText by rememberSaveable(plan) {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑并同步运动建议") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "BMI ${String.format(Locale.getDefault(), "%.1f", plan.bmiValue)}" +
                                "（${plan.bmiCategory.displayName}）",
                            fontWeight = FontWeight.Bold
                        )
                        plan.adviceTexts.forEach { advice ->
                            Text(text = "• $advice", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    text = "可修改名称、单位、目标、周期和快捷增加量，也可取消勾选。" +
                        "同步只新增或更新下列项目，不删除其他计划。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                editableItems.forEachIndexed { index, item ->
                    val willUpdate = currentDefinitions.any { existing ->
                        existing.id == item.definition.id ||
                            existing.name.equals(item.definition.name, ignoreCase = true)
                    }
                    RecommendationEditorCard(
                        item = item,
                        actionLabel = if (willUpdate) "将更新现有项目" else "将新增项目",
                        onChanged = { changedItem ->
                            editableItems = editableItems.toMutableList().also { items ->
                                items[index] = changedItem
                            }
                            errorText = ""
                        }
                    )
                }
                if (errorText.isNotBlank()) {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "这些内容属于通用健康建议；存在慢性病、孕期、近期手术或运动不适时，" +
                        "请先咨询医生或合格运动专业人员。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val selectedDefinitions = editableItems
                        .filter(EditableHealthRecommendation::enabled)
                        .map(EditableHealthRecommendation::definition)
                    val duplicateNameExists = selectedDefinitions
                        .groupingBy { definition -> definition.name.trim().lowercase() }
                        .eachCount()
                        .any { (_, count) -> count > 1 }
                    errorText = when {
                        selectedDefinitions.isEmpty() -> "请至少选择一项建议"
                        selectedDefinitions.any { definition -> definition.name.trim().isBlank() } -> {
                            "项目名称不能为空"
                        }
                        selectedDefinitions.any { definition -> definition.unit.trim().isBlank() } -> {
                            "项目单位不能为空"
                        }
                        selectedDefinitions.any { definition ->
                            definition.dailyGoal !in MIN_RECOMMENDATION_GOAL..MAX_RECOMMENDATION_GOAL
                        } -> "目标请输入1到1000000"
                        selectedDefinitions.any { definition ->
                            definition.quickIncrement !in MIN_RECOMMENDATION_QUICK..MAX_RECOMMENDATION_QUICK
                        } -> "快捷增加量请输入1到100000"
                        duplicateNameExists -> "本次建议中的项目名称不能重复"
                        else -> {
                            val result = onSync(selectedDefinitions)
                            if (result.isSuccess) {
                                onSynced(result)
                                ""
                            } else {
                                "同步失败，可能存在同名项目或项目数量已达上限，请调整后重试"
                            }
                        }
                    }
                }
            ) {
                Text(text = "一键同步")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "暂不同步")
            }
        }
    )
}

/**
 * 编辑单条待同步建议。
 *
 * @param item 当前临时建议。
 * @param actionLabel 同步后将新增或更新的预览文字。
 * @param onChanged 任一字段变化后的完整对象回调。
 *
 * @return 无返回值。
 */
@Composable
private fun RecommendationEditorCard(
    item: EditableHealthRecommendation,
    actionLabel: String,
    onChanged: (EditableHealthRecommendation) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = item.enabled,
                    onCheckedChange = { enabled ->
                        onChanged(item.copy(enabled = enabled))
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = actionLabel, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = item.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(visible = item.enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = item.definition.name,
                        onValueChange = { name ->
                            onChanged(
                                item.copy(
                                    definition = item.definition.copy(
                                        name = name.take(MAX_RECOMMENDATION_NAME_LENGTH)
                                    )
                                )
                            )
                        },
                        label = { Text(text = "项目名称") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = item.definition.unit,
                        onValueChange = { unit ->
                            onChanged(
                                item.copy(
                                    definition = item.definition.copy(
                                        unit = unit.take(MAX_RECOMMENDATION_UNIT_LENGTH)
                                    )
                                )
                            )
                        },
                        label = { Text(text = "单位") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = item.definition.dailyGoal.toString(),
                        onValueChange = { goalText ->
                            val goal = goalText.filter(Char::isDigit)
                                .take(MAX_RECOMMENDATION_NUMBER_LENGTH)
                                .toIntOrNull()
                                ?: 0
                            onChanged(
                                item.copy(definition = item.definition.copy(dailyGoal = goal))
                            )
                        },
                        label = { Text(text = "目标数量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    Text(text = "目标周期", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FitnessGoalPeriod.entries.forEach { period ->
                            if (period == item.definition.goalPeriod) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onChanged(
                                            item.copy(
                                                definition = item.definition.copy(goalPeriod = period)
                                            )
                                        )
                                    }
                                ) {
                                    Text(text = period.displayName)
                                }
                            } else {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        onChanged(
                                            item.copy(
                                                definition = item.definition.copy(goalPeriod = period)
                                            )
                                        )
                                    }
                                ) {
                                    Text(text = period.displayName)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = item.definition.quickIncrement.toString(),
                        onValueChange = { quickText ->
                            val quick = quickText.filter(Char::isDigit)
                                .take(MAX_RECOMMENDATION_NUMBER_LENGTH)
                                .toIntOrNull()
                                ?: 0
                            onChanged(
                                item.copy(
                                    definition = item.definition.copy(quickIncrement = quick)
                                )
                            )
                        },
                        label = { Text(text = "每次快捷增加量") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        }
    }
}

/**
 * 显示本地头像；图片不可读时自动回退到姓名首字或默认文字。
 *
 * @param avatarUri 系统文档Uri文本。
 * @param name 用户姓名，用于生成回退首字。
 *
 * @return 无返回值。
 */
@Composable
private fun ProfileAvatar(
    avatarUri: String,
    name: String
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = avatarUri
    ) {
        value = if (avatarUri.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(avatarUri.toUri())?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                }.onFailure { error ->
                    Log.e(PROFILE_UI_TAG, "Failed to decode avatar image", error)
                }.getOrNull()
            }
        }
    }
    Surface(
        modifier = Modifier.size(68.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary
    ) {
        if (bitmap != null) {
            Image(
                modifier = Modifier.clip(CircleShape),
                bitmap = bitmap!!,
                contentDescription = "用户头像",
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.trim().firstOrNull()?.toString() ?: "我",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * 释放已不再使用的持久头像读取授权。
 *
 * @param context Android上下文。
 * @param avatarUri 需要释放的Uri文本。
 *
 * @return 无返回值；Uri不是持久授权或系统拒绝时仅写入英文日志，不影响资料操作。
 */
private fun releaseAvatarPermission(context: Context, avatarUri: String) {
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            avatarUri.toUri(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }.onFailure { error ->
        Log.w(PROFILE_UI_TAG, "Failed to release avatar permission", error)
    }
}

/**
 * 过滤体重输入，只保留一个小数点和最多一位小数。
 *
 * @param rawInput 输入框原始文本。
 *
 * @return 可安全继续编辑或解析的数字文本。
 */
private fun sanitizeDecimalInput(rawInput: String): String {
    val filtered = rawInput.filter { character -> character.isDigit() || character == '.' }
    val dotIndex = filtered.indexOf('.')
    val normalized = if (dotIndex < 0) {
        filtered.take(3)
    } else {
        val integerPart = filtered.substring(0, dotIndex).take(3)
        val decimalPart = filtered.substring(dotIndex + 1).filter(Char::isDigit).take(1)
        "$integerPart.$decimalPart"
    }
    return normalized.take(5)
}

/**
 * 把体重格式化为最多一位小数，整数体重不显示多余小数点。
 *
 * @param weightKg 千克数。
 *
 * @return 页面可直接展示或放入输入框的文本。
 */
private fun formatWeight(weightKg: Double): String {
    return if (weightKg % 1.0 == 0.0) {
        weightKg.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", weightKg)
    }
}

private const val PROFILE_UI_TAG = "UserProfileHealthCard"
private const val MAX_PROFILE_NAME_LENGTH = 30
private const val MAX_RECOMMENDATION_NAME_LENGTH = 20
private const val MAX_RECOMMENDATION_UNIT_LENGTH = 8
private const val MAX_RECOMMENDATION_NUMBER_LENGTH = 7
private const val MIN_RECOMMENDATION_GOAL = 1
private const val MAX_RECOMMENDATION_GOAL = 1_000_000
private const val MIN_RECOMMENDATION_QUICK = 1
private const val MAX_RECOMMENDATION_QUICK = 100_000
