package com.example.harleyapp.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.model.CompanionTask
import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.FitnessExercise
import com.example.harleyapp.model.FitnessGoals
import com.example.harleyapp.system.StepCounterMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.Locale

/**
 * 显示每日运动监督、快速打卡、自动计步和可增删改查的运动历史。
 *
 * 使用方法：
 * 由HarleyApp在“运动”导航项选中时调用。页面首次显示会读取本地目标和当天记录；
 * 用户允许身体活动权限后，页面可见期间自动监听低功耗计步传感器，离开页面立即释放监听。
 * 俯卧撑和仰卧起坐支持分组累加、减少误记和一键达标，步数始终保留手动校准入口。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param onCompanionTaskCompleted 首次达成单项或全部运动目标时通知伙伴系统的回调。
 *
 * @return 无返回值，直接输出完整运动页面。
 */
@Composable
fun FitnessScreen(
    modifier: Modifier = Modifier,
    onCompanionTaskCompleted: (CompanionTask) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val applicationContext = context.applicationContext
    val repository = remember {
        FitnessRepository(applicationContext)
    }
    val stepCounterMonitor = remember {
        StepCounterMonitor(applicationContext)
    }
    var todayEpochDay by remember {
        mutableLongStateOf(LocalDate.now().toEpochDay())
    }

    var goals by remember {
        mutableStateOf(repository.getGoals())
    }
    var todayRecord by remember {
        mutableStateOf(repository.getTodayRecord(todayEpochDay))
    }
    var historyDays by rememberSaveable {
        mutableIntStateOf(HISTORY_DAYS_WEEK)
    }
    var recentRecords by remember {
        mutableStateOf(
            repository.getRecentRecords(
                days = historyDays,
                endEpochDay = todayEpochDay
            )
        )
    }
    var streakDays by remember {
        mutableIntStateOf(
            calculateCurrentStreak(
                records = repository.getRecentRecords(
                    days = STREAK_LOOKBACK_DAYS,
                    endEpochDay = todayEpochDay
                ),
                todayEpochDay = todayEpochDay
            )
        )
    }
    var showGoalEditor by rememberSaveable {
        mutableStateOf(false)
    }
    var showStepCalibration by rememberSaveable {
        mutableStateOf(false)
    }
    var recordBeingEdited by remember {
        mutableStateOf<DailyFitnessRecord?>(null)
    }
    var recordPendingDeletion by remember {
        mutableStateOf<DailyFitnessRecord?>(null)
    }
    var pageMessage by rememberSaveable {
        mutableStateOf("")
    }
    var sensorMessage by rememberSaveable {
        mutableStateOf("")
    }
    var sensorActive by remember {
        mutableStateOf(false)
    }

    val sensorSupported = remember {
        stepCounterMonitor.isSupported()
    }
    val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    var stepPermissionGranted by remember {
        mutableStateOf(
            !permissionRequired || ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        stepPermissionGranted = granted
        sensorMessage = if (granted) {
            "自动计步正在启动"
        } else {
            "未获得身体活动权限，可继续手动校准步数"
        }
    }

    /**
     * 从仓库重新读取今天、历史和连续达标状态，保证每次写入后的界面数据保持一致。
     */
    val refreshFitnessState = {
        goals = repository.getGoals()
        todayRecord = repository.getTodayRecord(todayEpochDay)
        recentRecords = repository.getRecentRecords(
            days = historyDays,
            endEpochDay = todayEpochDay
        )
        streakDays = calculateCurrentStreak(
            records = repository.getRecentRecords(
                days = STREAK_LOOKBACK_DAYS,
                endEpochDay = todayEpochDay
            ),
            todayEpochDay = todayEpochDay
        )
    }

    /**
     * 比较写入前后的当天记录，只在跨过目标门槛时通知伙伴系统。
     * 减少记录、重复点击“完成”或仅浏览页面都不会触发新的经验任务。
     */
    val reportNewCompanionMilestones = {
            previousRecord: DailyFitnessRecord,
            updatedRecord: DailyFitnessRecord ->
        if (previousRecord.completedTaskCount() == 0 &&
            updatedRecord.completedTaskCount() > 0
        ) {
            onCompanionTaskCompleted(CompanionTask.FITNESS_ITEM)
        }
        if (!previousRecord.isComplete() && updatedRecord.isComplete()) {
            onCompanionTaskCompleted(CompanionTask.FITNESS_ALL)
        }
    }

    // 页面长时间停留在前台时定期检查本地日期，跨过零点后自动切换到新一天的目标和记录。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(DATE_REFRESH_INTERVAL_MILLIS)
            val currentEpochDay = LocalDate.now().toEpochDay()
            if (currentEpochDay != todayEpochDay) {
                todayEpochDay = currentEpochDay
            }
        }
    }

    // 日期变化后统一刷新三项任务和历史数据，避免任何一项仍显示前一天状态。
    LaunchedEffect(todayEpochDay, historyDays) {
        refreshFitnessState()
        pageMessage = ""
        sensorMessage = ""
    }

    // 只有页面可见且权限满足时才监听传感器，离开页面后立即释放，减少无意义耗电。
    DisposableEffect(
        stepCounterMonitor,
        sensorSupported,
        stepPermissionGranted,
        todayEpochDay
    ) {
        sensorActive = if (sensorSupported && stepPermissionGranted) {
            stepCounterMonitor.start { sensorTotal ->
                val syncResult = repository.syncSensorSteps(
                    sensorTotal = sensorTotal,
                    epochDay = todayEpochDay
                )
                if (syncResult == null) {
                    pageMessage = "步数保存失败，请稍后重试"
                } else {
                    reportNewCompanionMilestones(todayRecord, syncResult.record)
                    todayRecord = syncResult.record
                    recentRecords = repository.getRecentRecords(
                        days = historyDays,
                        endEpochDay = todayEpochDay
                    )
                    streakDays = calculateCurrentStreak(
                        records = repository.getRecentRecords(
                            days = STREAK_LOOKBACK_DAYS,
                            endEpochDay = todayEpochDay
                        ),
                        todayEpochDay = todayEpochDay
                    )
                    if (syncResult.baselineEstablished) {
                        sensorMessage = "自动计步已开始，将从当前系统读数继续累计"
                    } else if (syncResult.addedSteps > 0) {
                        sensorMessage = "自动计步中"
                    }
                }
            }
        } else {
            false
        }

        if (sensorSupported && stepPermissionGranted && !sensorActive) {
            sensorMessage = "计步传感器暂时无法启动，可手动校准步数"
        }

        onDispose {
            stepCounterMonitor.stop()
        }
    }

    if (showGoalEditor) {
        GoalEditorDialog(
            goals = goals,
            onDismiss = {
                showGoalEditor = false
            },
            onSave = { newGoals ->
                val saved = repository.saveGoals(
                    goals = newGoals,
                    todayEpochDay = todayEpochDay
                )
                if (saved) {
                    refreshFitnessState()
                    showGoalEditor = false
                    pageMessage = "每日目标已更新"
                }
                saved
            }
        )
    }

    if (showStepCalibration) {
        StepCalibrationDialog(
            currentSteps = todayRecord.steps,
            onDismiss = {
                showStepCalibration = false
            },
            onSave = { steps ->
                val updated = repository.setSteps(
                    steps = steps,
                    epochDay = todayEpochDay
                )
                if (updated != null) {
                    reportNewCompanionMilestones(todayRecord, updated)
                    refreshFitnessState()
                    showStepCalibration = false
                    pageMessage = "今日步数已校准"
                    true
                } else {
                    false
                }
            }
        )
    }

    recordBeingEdited?.let { editingRecord ->
        FitnessRecordEditorDialog(
            record = editingRecord,
            onDismiss = {
                recordBeingEdited = null
            },
            onSave = { updatedRecord ->
                val savedRecord = repository.upsertRecord(updatedRecord)
                if (savedRecord != null) {
                    refreshFitnessState()
                    recordBeingEdited = null
                    pageMessage = if (updatedRecord.hasRecordedActivity()) {
                        "运动记录已保存"
                    } else {
                        "运动记录未发生变化"
                    }
                    true
                } else {
                    false
                }
            }
        )
    }

    recordPendingDeletion?.let { deletingRecord ->
        DeleteFitnessRecordDialog(
            record = deletingRecord,
            onDismiss = {
                recordPendingDeletion = null
            },
            onConfirm = {
                val deleted = repository.deleteRecord(deletingRecord.dateEpochDay)
                if (deleted) {
                    refreshFitnessState()
                    recordPendingDeletion = null
                    pageMessage = "运动记录已删除"
                } else {
                    pageMessage = "运动记录删除失败，请重试"
                }
            }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 20.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            FitnessSummaryCard(
                record = todayRecord,
                streakDays = streakDays,
                onEditGoals = {
                    showGoalEditor = true
                }
            )
        }

        if (pageMessage.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        text = pageMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item {
            Text(
                text = "今日训练",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            ExerciseTaskCard(
                title = "俯卧撑",
                suggestion = "建议分组完成，动作标准比一次做完更重要",
                count = todayRecord.pushUps,
                goal = todayRecord.pushUpGoal,
                onSubtract = {
                    val updated = repository.updateExercise(
                        exercise = FitnessExercise.PUSH_UP,
                        delta = -QUICK_SUBTRACT_COUNT,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "已减少$QUICK_SUBTRACT_COUNT 个俯卧撑"
                    } else {
                        pageMessage = "俯卧撑记录保存失败，请重试"
                    }
                },
                onAddFive = {
                    val updated = repository.updateExercise(
                        exercise = FitnessExercise.PUSH_UP,
                        delta = QUICK_ADD_SMALL_COUNT,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "已记录$QUICK_ADD_SMALL_COUNT 个俯卧撑"
                    } else {
                        pageMessage = "俯卧撑记录保存失败，请重试"
                    }
                },
                onAddTen = {
                    val updated = repository.updateExercise(
                        exercise = FitnessExercise.PUSH_UP,
                        delta = QUICK_ADD_LARGE_COUNT,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "已记录$QUICK_ADD_LARGE_COUNT 个俯卧撑"
                    } else {
                        pageMessage = "俯卧撑记录保存失败，请重试"
                    }
                },
                onComplete = {
                    val updated = repository.completeExercise(
                        exercise = FitnessExercise.PUSH_UP,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "俯卧撑已标记达标"
                    } else {
                        pageMessage = "俯卧撑记录保存失败，请重试"
                    }
                }
            )
        }

        item {
            ExerciseTaskCard(
                title = "仰卧起坐",
                suggestion = "腰背不适时请立即停止，不要为了数字勉强完成",
                count = todayRecord.sitUps,
                goal = todayRecord.sitUpGoal,
                onSubtract = {
                    val updated = repository.updateExercise(
                        exercise = FitnessExercise.SIT_UP,
                        delta = -QUICK_SUBTRACT_COUNT,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "已减少$QUICK_SUBTRACT_COUNT 个仰卧起坐"
                    } else {
                        pageMessage = "仰卧起坐记录保存失败，请重试"
                    }
                },
                onAddFive = {
                    val updated = repository.updateExercise(
                        exercise = FitnessExercise.SIT_UP,
                        delta = QUICK_ADD_SMALL_COUNT,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "已记录$QUICK_ADD_SMALL_COUNT 个仰卧起坐"
                    } else {
                        pageMessage = "仰卧起坐记录保存失败，请重试"
                    }
                },
                onAddTen = {
                    val updated = repository.updateExercise(
                        exercise = FitnessExercise.SIT_UP,
                        delta = QUICK_ADD_LARGE_COUNT,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "已记录$QUICK_ADD_LARGE_COUNT 个仰卧起坐"
                    } else {
                        pageMessage = "仰卧起坐记录保存失败，请重试"
                    }
                },
                onComplete = {
                    val updated = repository.completeExercise(
                        exercise = FitnessExercise.SIT_UP,
                        epochDay = todayEpochDay
                    )
                    if (updated != null) {
                        reportNewCompanionMilestones(todayRecord, updated)
                        refreshFitnessState()
                        pageMessage = "仰卧起坐已标记达标"
                    } else {
                        pageMessage = "仰卧起坐记录保存失败，请重试"
                    }
                }
            )
        }

        item {
            StepTaskCard(
                steps = todayRecord.steps,
                goal = todayRecord.stepGoal,
                sensorSupported = sensorSupported,
                permissionGranted = stepPermissionGranted,
                sensorActive = sensorActive,
                sensorMessage = sensorMessage,
                onRequestPermission = {
                    if (permissionRequired) {
                        permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    } else {
                        stepPermissionGranted = true
                    }
                },
                onCalibrate = {
                    showStepCalibration = true
                }
            )
        }

        item {
            FitnessHistoryHeader(
                historyDays = historyDays,
                onHistoryDaysChanged = { selectedDays ->
                    historyDays = selectedDays
                },
                onAddRecord = {
                    showFitnessDatePicker(
                        context = context,
                        initialEpochDay = todayEpochDay,
                        maxEpochDay = todayEpochDay
                    ) { selectedEpochDay ->
                        recordBeingEdited = repository.getRecord(selectedEpochDay)
                    }
                }
            )
        }

        items(
            items = recentRecords,
            key = { record -> record.dateEpochDay }
        ) { record ->
            FitnessHistoryRow(
                record = record,
                todayEpochDay = todayEpochDay,
                onEdit = {
                    recordBeingEdited = repository.getRecord(record.dateEpochDay)
                },
                onDelete = {
                    recordPendingDeletion = record
                }
            )
        }
    }
}

/**
 * 显示当天总进度、连续达标天数和目标设置入口。
 *
 * @param record 当天记录。
 * @param streakDays 当前连续达标天数。
 * @param onEditGoals 点击设置目标后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessSummaryCard(
    record: DailyFitnessRecord,
    streakDays: Int,
    onEditGoals: () -> Unit
) {
    val completedCount = record.completedTaskCount()
    val summaryText = if (record.isComplete()) {
        "今天三项目标已全部完成"
    } else {
        "已完成$completedCount 项，还差${DailyFitnessRecord.FITNESS_TASK_COUNT - completedCount}项"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日运动监督",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = summaryText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.84f)
                    )
                }

                TextButton(onClick = onEditGoals) {
                    Text(
                        text = "设置目标",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            LinearProgressIndicator(
                progress = {
                    completedCount.toFloat() / DailyFitnessRecord.FITNESS_TASK_COUNT
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f)
            )

            Text(
                text = if (streakDays > 0) {
                    "连续达标 $streakDays 天"
                } else {
                    "完成今天三项任务，开始你的连续记录"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * 显示俯卧撑或仰卧起坐的单项进度与快速打卡按钮。
 *
 * @param title 项目名称。
 * @param suggestion 安全、可执行的训练提示。
 * @param count 当前完成次数。
 * @param goal 当天目标次数。
 * @param onSubtract 减少5次的回调，用于纠正误点。
 * @param onAddFive 增加5次的回调。
 * @param onAddTen 增加10次的回调。
 * @param onComplete 直接标记达到目标的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ExerciseTaskCard(
    title: String,
    suggestion: String,
    count: Int,
    goal: Int,
    onSubtract: () -> Unit,
    onAddFive: () -> Unit,
    onAddTen: () -> Unit,
    onComplete: () -> Unit
) {
    val completed = count >= goal
    val progress = (count.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (completed) "已达标" else "$count / $goal 次",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (completed) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            Text(
                text = suggestion,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = count > 0,
                    onClick = onSubtract,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "-5")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAddFive,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "+5")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAddTen,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "+10")
                }
                Button(
                    modifier = Modifier.weight(1.25f),
                    enabled = !completed,
                    onClick = onComplete,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "完成")
                }
            }
        }
    }
}

/**
 * 显示步数进度、自动计步状态和手动校准入口。
 *
 * @param steps 当前已记录步数。
 * @param goal 当天步数目标。
 * @param sensorSupported 手机是否提供累计计步传感器。
 * @param permissionGranted 是否已经获得身体活动权限。
 * @param sensorActive 传感器监听是否启动成功。
 * @param sensorMessage 最近一次计步状态说明。
 * @param onRequestPermission 请求身体活动权限的回调。
 * @param onCalibrate 打开手动步数校准的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun StepTaskCard(
    steps: Int,
    goal: Int,
    sensorSupported: Boolean,
    permissionGranted: Boolean,
    sensorActive: Boolean,
    sensorMessage: String,
    onRequestPermission: () -> Unit,
    onCalibrate: () -> Unit
) {
    val completed = steps >= goal
    val progress = (steps.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val statusText = when {
        !sensorSupported -> "本机没有可用计步传感器，请使用手动校准"
        !permissionGranted -> "允许身体活动权限后，可在运动页打开时自动累计步数"
        sensorActive && sensorMessage.isNotBlank() -> sensorMessage
        sensorActive -> "自动计步中"
        else -> "计步传感器暂时不可用，请使用手动校准"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "步行",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (completed) "已达标" else "$steps / $goal 步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.tertiary
            )

            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (sensorSupported && !permissionGranted) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = onRequestPermission
                    ) {
                        Text(text = "开启自动计步")
                    }
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onCalibrate
                ) {
                    Text(text = "校准步数")
                }
            }
        }
    }
}

/**
 * 显示历史查询范围和新增补记入口。
 *
 * @param historyDays 当前查询天数，仅使用7天或30天。
 * @param onHistoryDaysChanged 用户切换查询范围后的回调。
 * @param onAddRecord 用户点击补记后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessHistoryHeader(
    historyDays: Int,
    onHistoryDaysChanged: (Int) -> Unit,
    onAddRecord: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "运动记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "可补记、编辑和删除；历史目标按当天设置计算",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(onClick = onAddRecord) {
                Text(text = "补记")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (historyDays == HISTORY_DAYS_WEEK) {
                Button(onClick = { onHistoryDaysChanged(HISTORY_DAYS_WEEK) }) {
                    Text(text = "近7天")
                }
            } else {
                OutlinedButton(onClick = { onHistoryDaysChanged(HISTORY_DAYS_WEEK) }) {
                    Text(text = "近7天")
                }
            }

            if (historyDays == HISTORY_DAYS_MONTH) {
                Button(onClick = { onHistoryDaysChanged(HISTORY_DAYS_MONTH) }) {
                    Text(text = "近30天")
                }
            } else {
                OutlinedButton(onClick = { onHistoryDaysChanged(HISTORY_DAYS_MONTH) }) {
                    Text(text = "近30天")
                }
            }
        }
    }
}

/**
 * 显示单日三项运动的完成摘要及编辑、删除入口。
 *
 * @param record 需要展示的历史记录。
 * @param todayEpochDay 今天日期序号，用于显示“今天”标记。
 * @param onEdit 编辑该日期记录的回调。
 * @param onDelete 删除该日期记录的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessHistoryRow(
    record: DailyFitnessRecord,
    todayEpochDay: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val completedCount = record.completedTaskCount()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = formatHistoryDate(record.dateEpochDay, todayEpochDay),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (record.isComplete()) {
                        "全部达标"
                    } else {
                        "$completedCount / ${DailyFitnessRecord.FITNESS_TASK_COUNT} 项"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (record.isComplete()) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Text(
                text = "俯卧撑 ${record.pushUps}/${record.pushUpGoal}  ·  " +
                    "仰卧起坐 ${record.sitUps}/${record.sitUpGoal}  ·  " +
                    "步数 ${record.steps}/${record.stepGoal}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (record.hasRecordedActivity()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onEdit) {
                        Text(text = "编辑")
                    }
                    TextButton(onClick = onDelete) {
                        Text(
                            text = "删除",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            } else {
                TextButton(onClick = onEdit) {
                    Text(text = "补记这一天")
                }
            }
        }
    }
}

/**
 * 新增或编辑某一天的完整运动记录。
 *
 * 使用方法：
 * 用户从“补记”日期选择器或历史行“编辑”进入。三个输入框表示当天总完成量，
 * 至少填写一项大于0的数据后才能保存；保存时保留该日期原有目标快照。
 *
 * @param record 待新增或编辑的记录；空占位记录表示新增。
 * @param onDismiss 取消编辑的回调。
 * @param onSave 保存回调；写入成功返回true，失败返回false并保留对话框。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessRecordEditorDialog(
    record: DailyFitnessRecord,
    onDismiss: () -> Unit,
    onSave: (DailyFitnessRecord) -> Boolean
) {
    var pushUpText by rememberSaveable(record.dateEpochDay, record.pushUps) {
        mutableStateOf(record.pushUps.toString())
    }
    var sitUpText by rememberSaveable(record.dateEpochDay, record.sitUps) {
        mutableStateOf(record.sitUps.toString())
    }
    var stepText by rememberSaveable(record.dateEpochDay, record.steps) {
        mutableStateOf(record.steps.toString())
    }
    var errorText by rememberSaveable(record.dateEpochDay) {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (record.hasRecordedActivity()) {
                    "编辑运动记录"
                } else {
                    "补记运动"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = LocalDate.ofEpochDay(record.dateEpochDay).format(
                        DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "当天目标：俯卧撑 ${record.pushUpGoal} 次、" +
                        "仰卧起坐 ${record.sitUpGoal} 次、步数 ${record.stepGoal} 步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                GoalNumberField(
                    value = pushUpText,
                    onValueChange = {
                        pushUpText = it.filter(Char::isDigit).take(MAX_STEP_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "俯卧撑完成次数"
                )
                GoalNumberField(
                    value = sitUpText,
                    onValueChange = {
                        sitUpText = it.filter(Char::isDigit).take(MAX_STEP_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "仰卧起坐完成次数"
                )
                GoalNumberField(
                    value = stepText,
                    onValueChange = {
                        stepText = it.filter(Char::isDigit).take(MAX_STEP_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "步数"
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
                    val pushUps = pushUpText.toIntOrNull()
                    val sitUps = sitUpText.toIntOrNull()
                    val steps = stepText.toIntOrNull()
                    errorText = when {
                        pushUps == null || pushUps !in 0..MAX_RECORD_COUNT -> {
                            "俯卧撑请输入0到1000000"
                        }

                        sitUps == null || sitUps !in 0..MAX_RECORD_COUNT -> {
                            "仰卧起坐请输入0到1000000"
                        }

                        steps == null || steps !in 0..MAX_RECORD_COUNT -> {
                            "步数请输入0到1000000"
                        }

                        pushUps == 0 && sitUps == 0 && steps == 0 -> {
                            "至少需要记录一项大于0的运动量"
                        }

                        onSave(
                            record.copy(
                                pushUps = pushUps,
                                sitUps = sitUps,
                                steps = steps
                            )
                        ) -> ""

                        else -> "运动记录保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 删除单日运动记录前进行二次确认，避免误触造成历史数据丢失。
 *
 * @param record 即将删除的记录。
 * @param onDismiss 取消删除的回调。
 * @param onConfirm 用户确认删除后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun DeleteFitnessRecordDialog(
    record: DailyFitnessRecord,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "删除运动记录")
        },
        text = {
            Text(
                text = "确定删除${formatHistoryDate(record.dateEpochDay, Long.MIN_VALUE)}的记录吗？" +
                    "删除后当天完成量将归零，目标设置不会改变。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "确认删除",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 打开系统日期选择器，供用户选择需要补记的过去日期。
 *
 * 使用方法：
 * “补记”按钮调用本函数。可选范围限制为最近365天且不允许选择未来日期，
 * 避免误建无意义的未来运动记录。
 *
 * @param context 用于创建系统DatePickerDialog的页面上下文。
 * @param initialEpochDay 日期选择器初始日期序号。
 * @param maxEpochDay 允许选择的最晚日期，通常为今天。
 * @param onDateSelected 用户确认日期后的回调，参数为选中日期序号。
 *
 * @return 无返回值。
 */
private fun showFitnessDatePicker(
    context: Context,
    initialEpochDay: Long,
    maxEpochDay: Long,
    onDateSelected: (Long) -> Unit
) {
    val minEpochDay = maxEpochDay - MAX_RECORD_LOOKBACK_DAYS + 1L
    val safeInitialEpochDay = initialEpochDay.coerceIn(minEpochDay, maxEpochDay)
    val initialDate = LocalDate.ofEpochDay(safeInitialEpochDay)
    val zoneId = ZoneId.systemDefault()

    DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            onDateSelected(
                LocalDate.of(year, month + 1, dayOfMonth).toEpochDay()
            )
        },
        initialDate.year,
        initialDate.monthValue - 1,
        initialDate.dayOfMonth
    ).apply {
        datePicker.minDate = LocalDate.ofEpochDay(minEpochDay)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
        datePicker.maxDate = LocalDate.ofEpochDay(maxEpochDay)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
    }.show()
}

/**
 * 编辑三项每日目标。
 *
 * @param goals 当前目标，用于填充输入框。
 * @param onDismiss 取消编辑的回调。
 * @param onSave 保存回调；返回true时关闭对话框，false时保留输入供用户重试。
 *
 * @return 无返回值。
 */
@Composable
private fun GoalEditorDialog(
    goals: FitnessGoals,
    onDismiss: () -> Unit,
    onSave: (FitnessGoals) -> Boolean
) {
    var pushUpText by rememberSaveable(goals.pushUpGoal) {
        mutableStateOf(goals.pushUpGoal.toString())
    }
    var sitUpText by rememberSaveable(goals.sitUpGoal) {
        mutableStateOf(goals.sitUpGoal.toString())
    }
    var stepText by rememberSaveable(goals.stepGoal) {
        mutableStateOf(goals.stepGoal.toString())
    }
    var errorText by rememberSaveable {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "设置每日目标")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "目标要能长期坚持，完成后仍可继续累计，不会截断超额记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                GoalNumberField(
                    value = pushUpText,
                    onValueChange = {
                        pushUpText = it.filter(Char::isDigit).take(MAX_NUMBER_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "俯卧撑（次）"
                )

                GoalNumberField(
                    value = sitUpText,
                    onValueChange = {
                        sitUpText = it.filter(Char::isDigit).take(MAX_NUMBER_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "仰卧起坐（次）"
                )

                GoalNumberField(
                    value = stepText,
                    onValueChange = {
                        stepText = it.filter(Char::isDigit).take(MAX_NUMBER_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "步数（步）"
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
                    val pushUpGoal = pushUpText.toIntOrNull()
                    val sitUpGoal = sitUpText.toIntOrNull()
                    val stepGoal = stepText.toIntOrNull()
                    errorText = when {
                        pushUpGoal == null || pushUpGoal !in MIN_EXERCISE_GOAL..MAX_EXERCISE_GOAL -> {
                            "俯卧撑目标请输入1到1000"
                        }

                        sitUpGoal == null || sitUpGoal !in MIN_EXERCISE_GOAL..MAX_EXERCISE_GOAL -> {
                            "仰卧起坐目标请输入1到1000"
                        }

                        stepGoal == null || stepGoal !in MIN_STEP_GOAL..MAX_STEP_GOAL -> {
                            "步数目标请输入100到100000"
                        }

                        onSave(
                            FitnessGoals(
                                pushUpGoal = pushUpGoal,
                                sitUpGoal = sitUpGoal,
                                stepGoal = stepGoal
                            )
                        ) -> ""

                        else -> "目标保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 输入单个数字目标，统一数字键盘、单行和圆角样式。
 *
 * @param value 当前输入文本。
 * @param onValueChange 输入变化回调。
 * @param label 输入框标题。
 *
 * @return 无返回值。
 */
@Composable
private fun GoalNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(text = label)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(14.dp)
    )
}

/**
 * 手动设置当天总步数，作为传感器不可用或计步遗漏时的可靠后备入口。
 *
 * @param currentSteps 当前步数，用于填充输入框。
 * @param onDismiss 取消校准的回调。
 * @param onSave 保存回调；返回true表示写入成功。
 *
 * @return 无返回值。
 */
@Composable
private fun StepCalibrationDialog(
    currentSteps: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Boolean
) {
    var stepText by rememberSaveable(currentSteps) {
        mutableStateOf(currentSteps.toString())
    }
    var errorText by rememberSaveable {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "校准今日步数")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "请输入手机健康应用或运动手环显示的今日总步数，后续自动计步会在此基础上继续增加。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GoalNumberField(
                    value = stepText,
                    onValueChange = {
                        stepText = it.filter(Char::isDigit).take(MAX_STEP_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "今日总步数"
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
                    val steps = stepText.toIntOrNull()
                    errorText = when {
                        steps == null || steps !in 0..MAX_STEP_CALIBRATION -> {
                            "请输入0到1000000之间的步数"
                        }

                        onSave(steps) -> ""
                        else -> "步数保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

/**
 * 计算当前连续达标天数。今天尚未完成时从昨天开始统计，避免白天尚未训练就中断已有连续记录。
 *
 * @param records 按日期查询得到的近期记录，可为任意顺序。
 * @param todayEpochDay 今天日期序号。
 *
 * @return 从今天或昨天向前连续全部达标的天数。
 */
private fun calculateCurrentStreak(
    records: List<DailyFitnessRecord>,
    todayEpochDay: Long
): Int {
    val recordsByDate = records.associateBy { it.dateEpochDay }
    var cursor = todayEpochDay
    if (recordsByDate[cursor]?.isComplete() != true) {
        cursor -= 1L
    }

    var streak = 0
    while (recordsByDate[cursor]?.isComplete() == true) {
        streak += 1
        cursor -= 1L
    }
    return streak
}

/**
 * 将历史日期格式化为紧凑中文文本，并为当天追加清晰标记。
 *
 * @param epochDay 待格式化日期序号。
 * @param todayEpochDay 今天日期序号。
 *
 * @return 例如“8月29日 周六 · 今天”的日期文本。
 */
private fun formatHistoryDate(
    epochDay: Long,
    todayEpochDay: Long
): String {
    val formatted = LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)
    )
    return if (epochDay == todayEpochDay) {
        "$formatted · 今天"
    } else {
        formatted
    }
}

private const val QUICK_SUBTRACT_COUNT = 5
private const val QUICK_ADD_SMALL_COUNT = 5
private const val QUICK_ADD_LARGE_COUNT = 10
private const val HISTORY_DAYS_WEEK = 7
private const val HISTORY_DAYS_MONTH = 30
private const val STREAK_LOOKBACK_DAYS = 31
private const val MAX_RECORD_LOOKBACK_DAYS = 365L
private const val MIN_EXERCISE_GOAL = 1
private const val MAX_EXERCISE_GOAL = 1_000
private const val MIN_STEP_GOAL = 100
private const val MAX_STEP_GOAL = 100_000
private const val MAX_STEP_CALIBRATION = 1_000_000
private const val MAX_RECORD_COUNT = 1_000_000
private const val MAX_NUMBER_INPUT_LENGTH = 6
private const val MAX_STEP_INPUT_LENGTH = 7
private const val DATE_REFRESH_INTERVAL_MILLIS = 60_000L
