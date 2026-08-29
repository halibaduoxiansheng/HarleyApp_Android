package com.example.harleyapp.ui.screens

import android.Manifest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.example.harleyapp.model.FitnessExerciseDefinition
import com.example.harleyapp.model.FitnessGoalPeriod
import com.example.harleyapp.model.FitnessRangeSummary
import com.example.harleyapp.model.FitnessTrackingType
import com.example.harleyapp.model.calculateFitnessRangeSummary
import com.example.harleyapp.system.StepCounterMonitor
import com.example.harleyapp.ui.components.HarleyDatePickerDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示动态运动项目、今日打卡、日期区间汇总和可增删改查的历史记录。
 *
 * 使用方法：
 * 由HarleyApp在底部“运动”导航选中时调用。用户可通过“管理项目”新增、编辑、删除运动方式；
 * 每个手动项目拥有可配置目标、单位和快速增加量。自动步数项目仍连接系统计步传感器。
 * “区间汇总”支持任意开始、结束日期，并只在明细区显示真正保存过的数据。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param onCompanionTaskCompleted 首次达成单项或全部目标时通知伙伴系统的回调。
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
    var rangeStartEpochDay by rememberSaveable {
        mutableLongStateOf(todayEpochDay - DEFAULT_RANGE_DAYS + 1L)
    }
    var rangeEndEpochDay by rememberSaveable {
        mutableLongStateOf(todayEpochDay)
    }
    var definitions by remember {
        mutableStateOf(repository.getExerciseDefinitions())
    }
    var todayRecord by remember {
        mutableStateOf(repository.getTodayRecord(todayEpochDay))
    }
    var goalProgressCounts by remember {
        mutableStateOf(repository.getGoalProgressCounts(todayEpochDay))
    }
    var rangeRecords by remember {
        mutableStateOf(
            repository.getRecordsInRange(
                startEpochDay = rangeStartEpochDay,
                endEpochDay = rangeEndEpochDay
            )
        )
    }
    var streakDays by remember {
        mutableStateOf(
            calculateCurrentStreak(
                records = repository.getRecentRecords(
                    days = STREAK_LOOKBACK_DAYS,
                    endEpochDay = todayEpochDay
                ),
                todayEpochDay = todayEpochDay,
                currentDefinitions = definitions
            )
        )
    }

    var showExerciseManager by rememberSaveable {
        mutableStateOf(false)
    }
    var showExerciseEditor by rememberSaveable {
        mutableStateOf(false)
    }
    var reopenExerciseManagerAfterEditor by rememberSaveable {
        mutableStateOf(false)
    }
    var exerciseBeingEdited by remember {
        mutableStateOf<FitnessExerciseDefinition?>(null)
    }
    var exercisePendingDeletion by remember {
        mutableStateOf<FitnessExerciseDefinition?>(null)
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
    var datePickerRequest by remember {
        mutableStateOf<FitnessDatePickerRequest?>(null)
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

    val stepDefinition = definitions.firstOrNull {
        it.trackingType == FitnessTrackingType.STEP_COUNTER
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
     * 重新读取项目、今日记录、区间结果和连续达标状态，保证每次写入后所有模块一致。
     */
    val refreshFitnessState = {
        val refreshedDefinitions = repository.getExerciseDefinitions()
        definitions = refreshedDefinitions
        todayRecord = repository.getTodayRecord(todayEpochDay)
        goalProgressCounts = repository.getGoalProgressCounts(todayEpochDay)
        rangeRecords = repository.getRecordsInRange(
            startEpochDay = rangeStartEpochDay,
            endEpochDay = rangeEndEpochDay
        )
        streakDays = calculateCurrentStreak(
            records = repository.getRecentRecords(
                days = STREAK_LOOKBACK_DAYS,
                endEpochDay = todayEpochDay
            ),
            todayEpochDay = todayEpochDay,
            currentDefinitions = refreshedDefinitions
        )
    }

    /**
     * 比较写入前后的动态完成状态，只在跨过目标门槛时通知伙伴系统。
     */
    val reportNewCompanionMilestones = {
            previousRecord: DailyFitnessRecord,
            updatedRecord: DailyFitnessRecord ->
        if (previousRecord.completedTaskCount(definitions) == 0 &&
            updatedRecord.completedTaskCount(definitions) > 0
        ) {
            onCompanionTaskCompleted(CompanionTask.FITNESS_ITEM)
        }
        if (!previousRecord.isComplete(definitions) && updatedRecord.isComplete(definitions)) {
            onCompanionTaskCompleted(CompanionTask.FITNESS_ALL)
        }
    }

    // 页面长时间保持前台时每分钟检查日期，跨过零点自动进入新一天。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(DATE_REFRESH_INTERVAL_MILLIS)
            val currentEpochDay = LocalDate.now().toEpochDay()
            if (currentEpochDay != todayEpochDay) {
                todayEpochDay = currentEpochDay
                rangeEndEpochDay = currentEpochDay
            }
        }
    }

    // 日期区间变化后统一刷新，避免汇总与明细使用不同范围。
    LaunchedEffect(todayEpochDay, rangeStartEpochDay, rangeEndEpochDay) {
        refreshFitnessState()
        pageMessage = ""
    }

    // 只有存在自动步数项目、手机支持且权限满足时才监听传感器。
    DisposableEffect(
        stepCounterMonitor,
        stepDefinition?.id,
        sensorSupported,
        stepPermissionGranted,
        todayEpochDay
    ) {
        sensorActive = if (
            stepDefinition != null &&
            sensorSupported &&
            stepPermissionGranted
        ) {
            stepCounterMonitor.start { sensorTotal ->
                val syncResult = repository.syncSensorSteps(
                    sensorTotal = sensorTotal,
                    epochDay = todayEpochDay
                )
                if (syncResult == null) {
                    pageMessage = "步数保存失败，请稍后重试"
                } else {
                    reportNewCompanionMilestones(todayRecord, syncResult.record)
                    refreshFitnessState()
                    sensorMessage = when {
                        syncResult.baselineEstablished -> {
                            "自动计步已开始，将从当前系统读数继续累计"
                        }

                        syncResult.addedSteps > 0 -> "自动计步中"
                        else -> sensorMessage
                    }
                }
            }
        } else {
            false
        }

        if (
            stepDefinition != null &&
            sensorSupported &&
            stepPermissionGranted &&
            !sensorActive
        ) {
            sensorMessage = "计步传感器暂时无法启动，可手动校准步数"
        }

        onDispose {
            stepCounterMonitor.stop()
        }
    }

    if (showExerciseManager) {
        ExerciseManagerDialog(
            definitions = definitions,
            onDismiss = {
                showExerciseManager = false
            },
            onAdd = {
                exerciseBeingEdited = null
                reopenExerciseManagerAfterEditor = true
                showExerciseManager = false
                showExerciseEditor = true
            },
            onEdit = { definition ->
                exerciseBeingEdited = definition
                reopenExerciseManagerAfterEditor = true
                showExerciseManager = false
                showExerciseEditor = true
            },
            onDelete = { definition ->
                exercisePendingDeletion = definition
                showExerciseManager = false
            }
        )
    }

    if (showExerciseEditor) {
        ExerciseEditorDialog(
            definition = exerciseBeingEdited,
            currentDefinitions = definitions,
            onDismiss = {
                showExerciseEditor = false
                exerciseBeingEdited = null
                showExerciseManager = reopenExerciseManagerAfterEditor
                reopenExerciseManagerAfterEditor = false
            },
            onSave = { definition ->
                val saved = repository.upsertExerciseDefinition(
                    definition = definition,
                    todayEpochDay = todayEpochDay
                )
                if (saved != null) {
                    refreshFitnessState()
                    showExerciseEditor = false
                    exerciseBeingEdited = null
                    showExerciseManager = reopenExerciseManagerAfterEditor
                    reopenExerciseManagerAfterEditor = false
                    pageMessage = if (definition.id.isBlank()) {
                        "运动项目已新增"
                    } else {
                        "运动项目已更新"
                    }
                    true
                } else {
                    false
                }
            }
        )
    }

    exercisePendingDeletion?.let { deletingDefinition ->
        DeleteExerciseDefinitionDialog(
            definition = deletingDefinition,
            onDismiss = {
                exercisePendingDeletion = null
                showExerciseManager = true
            },
            onConfirm = {
                val deleted = repository.deleteExerciseDefinition(deletingDefinition.id)
                if (deleted) {
                    exercisePendingDeletion = null
                    refreshFitnessState()
                    showExerciseManager = true
                    pageMessage = "已删除项目“${deletingDefinition.name}”，历史记录仍保留"
                } else {
                    pageMessage = "运动项目删除失败，请重试"
                }
            }
        )
    }

    if (showStepCalibration && stepDefinition != null) {
        StepCalibrationDialog(
            definition = stepDefinition,
            currentCount = todayRecord.countFor(stepDefinition.id),
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
                    pageMessage = "今日${stepDefinition.name}已校准"
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
                    pageMessage = "运动记录已保存"
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
                    pageMessage = "${formatHistoryDate(deletingRecord.dateEpochDay, Long.MIN_VALUE)}记录已删除"
                } else {
                    pageMessage = "运动记录删除失败，请重试"
                }
            }
        )
    }

    val rangeSummary = remember(rangeRecords, rangeStartEpochDay, rangeEndEpochDay) {
        calculateFitnessRangeSummary(
            records = rangeRecords,
            startEpochDay = rangeStartEpochDay,
            endEpochDay = rangeEndEpochDay
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
                definitions = definitions,
                streakDays = streakDays,
                onManageExercises = {
                    showExerciseManager = true
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "运动计划",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { showExerciseManager = true }) {
                    Text(text = "管理项目")
                }
            }
        }

        if (definitions.isEmpty()) {
            item {
                EmptyExerciseCard(
                    onAddExercise = {
                        exerciseBeingEdited = null
                        reopenExerciseManagerAfterEditor = false
                        showExerciseEditor = true
                    }
                )
            }
        } else {
            items(
                items = definitions,
                key = { definition -> definition.id }
            ) { definition ->
                val recordItem = todayRecord.itemFor(definition.id)
                    ?: definition.toRecordItem()
                if (definition.trackingType == FitnessTrackingType.STEP_COUNTER) {
                    StepTaskCard(
                        definition = definition,
                        count = recordItem.count,
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
                        },
                        onSetGoal = {
                            exerciseBeingEdited = definition
                            reopenExerciseManagerAfterEditor = false
                            showExerciseEditor = true
                        }
                    )
                } else {
                    ExerciseTaskCard(
                        definition = definition,
                        count = goalProgressCounts[definition.id] ?: recordItem.count,
                        todayCount = recordItem.count,
                        goal = recordItem.goal,
                        onSubtract = {
                            val updated = repository.updateExercise(
                                exerciseId = definition.id,
                                delta = -definition.quickIncrement,
                                epochDay = todayEpochDay
                            )
                            if (updated != null) {
                                reportNewCompanionMilestones(todayRecord, updated)
                                refreshFitnessState()
                                pageMessage = "已减少${definition.quickIncrement}${definition.unit}${definition.name}"
                            } else {
                                pageMessage = "${definition.name}记录保存失败，请重试"
                            }
                        },
                        onAddSmall = {
                            val updated = repository.updateExercise(
                                exerciseId = definition.id,
                                delta = definition.quickIncrement,
                                epochDay = todayEpochDay
                            )
                            if (updated != null) {
                                reportNewCompanionMilestones(todayRecord, updated)
                                refreshFitnessState()
                                pageMessage = "已记录${definition.quickIncrement}${definition.unit}${definition.name}"
                            } else {
                                pageMessage = "${definition.name}记录保存失败，请重试"
                            }
                        },
                        onAddLarge = {
                            val largeIncrement = (definition.quickIncrement * 2)
                                .coerceAtMost(MAX_RECORD_COUNT)
                            val updated = repository.updateExercise(
                                exerciseId = definition.id,
                                delta = largeIncrement,
                                epochDay = todayEpochDay
                            )
                            if (updated != null) {
                                reportNewCompanionMilestones(todayRecord, updated)
                                refreshFitnessState()
                                pageMessage = "已记录$largeIncrement${definition.unit}${definition.name}"
                            } else {
                                pageMessage = "${definition.name}记录保存失败，请重试"
                            }
                        },
                        onComplete = {
                            val updated = repository.completeExercise(
                                exerciseId = definition.id,
                                epochDay = todayEpochDay
                            )
                            if (updated != null) {
                                reportNewCompanionMilestones(todayRecord, updated)
                                refreshFitnessState()
                                pageMessage = "${definition.name}已标记达标"
                            } else {
                                pageMessage = "${definition.name}记录保存失败，请重试"
                            }
                        }
                    )
                }
            }
        }

        item {
            FitnessRangeSummaryCard(
                summary = rangeSummary,
                onStartDateClick = {
                    datePickerRequest = FitnessDatePickerRequest(
                        title = "选择汇总开始日期",
                        initialEpochDay = rangeStartEpochDay,
                        minEpochDay = todayEpochDay - MAX_RECORD_LOOKBACK_DAYS + 1L,
                        maxEpochDay = rangeEndEpochDay,
                        onDateSelected = { selectedEpochDay ->
                            rangeStartEpochDay = selectedEpochDay
                        }
                    )
                },
                onEndDateClick = {
                    datePickerRequest = FitnessDatePickerRequest(
                        title = "选择汇总结束日期",
                        initialEpochDay = rangeEndEpochDay,
                        minEpochDay = rangeStartEpochDay,
                        maxEpochDay = todayEpochDay,
                        onDateSelected = { selectedEpochDay ->
                            rangeEndEpochDay = selectedEpochDay
                        }
                    )
                },
                onRecentDaysSelected = { days ->
                    rangeEndEpochDay = todayEpochDay
                    rangeStartEpochDay = todayEpochDay - days + 1L
                }
            )
        }

        item {
            FitnessHistoryHeader(
                recordCount = rangeRecords.size,
                onAddRecord = {
                    datePickerRequest = FitnessDatePickerRequest(
                        title = "选择运动补记日期",
                        initialEpochDay = todayEpochDay,
                        minEpochDay = todayEpochDay - MAX_RECORD_LOOKBACK_DAYS + 1L,
                        maxEpochDay = todayEpochDay,
                        onDateSelected = { selectedEpochDay ->
                            recordBeingEdited = repository.getRecord(selectedEpochDay)
                        }
                    )
                }
            )
        }

        if (rangeRecords.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        modifier = Modifier.padding(18.dp),
                        text = "所选日期范围内还没有运动记录。可点击“补记”新增某一天的数据。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(
                items = rangeRecords,
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

    datePickerRequest?.let { request ->
        HarleyDatePickerDialog(
            visible = true,
            title = request.title,
            initialEpochDay = request.initialEpochDay,
            minEpochDay = request.minEpochDay,
            maxEpochDay = request.maxEpochDay,
            onDismiss = {
                datePickerRequest = null
            },
            onDateSelected = { selectedEpochDay ->
                request.onDateSelected(selectedEpochDay)
            }
        )
    }
}

/**
 * 显示当天动态项目完成率、连续达标天数和项目管理入口。
 *
 * @param record 今天记录。
 * @param definitions 当前启用项目。
 * @param streakDays 连续全部达标天数。
 * @param onManageExercises 打开项目管理的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessSummaryCard(
    record: DailyFitnessRecord,
    definitions: List<FitnessExerciseDefinition>,
    streakDays: Int,
    onManageExercises: () -> Unit
) {
    val dailyDefinitions = definitions.filter { definition ->
        definition.goalPeriod == FitnessGoalPeriod.DAILY
    }
    val weeklyCount = definitions.count { definition ->
        definition.goalPeriod == FitnessGoalPeriod.WEEKLY
    }
    val completedCount = record.completedTaskCount(dailyDefinitions)
    val totalCount = dailyDefinitions.size
    val summaryText = when {
        definitions.isEmpty() -> "还没有运动项目，请先新增"
        totalCount == 0 -> "当前$weeklyCount 项计划按自然周累计"
        record.isComplete(dailyDefinitions) -> "今天$totalCount 项每日目标已全部完成"
        else -> "已完成$completedCount 项，还差${totalCount - completedCount}项"
    }
    val progress = if (totalCount > 0) {
        completedCount.toFloat() / totalCount
    } else {
        0f
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
                        text = "运动计划监督",
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
                TextButton(onClick = onManageExercises) {
                    Text(
                        text = "管理项目",
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f)
            )

            Text(
                text = if (streakDays > 0) {
                    "连续全部达标 $streakDays 天"
                } else {
                    "按自己的计划设置项目，完成后会自动统计"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * 在当前没有项目时显示明确新增入口。
 *
 * @param onAddExercise 打开新增项目编辑器的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyExerciseCard(onAddExercise: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "自由创建你的运动计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "可以添加跑步、跳绳、平板支撑、骑行等项目，并自定义单位、目标和快速增加量。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAddExercise) {
                Text(text = "新增运动项目")
            }
        }
    }
}

/**
 * 显示一个手动运动项目的进度和快捷打卡按钮。
 *
 * @param definition 当前项目定义。
 * @param count 当前目标周期累计完成量。
 * @param todayCount 今天实际记录量，用于判断能否撤销本日快捷记录。
 * @param goal 当前目标周期目标快照。
 * @param onSubtract 减少一个快速增量的回调。
 * @param onAddSmall 增加一个快速增量的回调。
 * @param onAddLarge 增加两个快速增量的回调。
 * @param onComplete 直接标记达标的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ExerciseTaskCard(
    definition: FitnessExerciseDefinition,
    count: Int,
    todayCount: Int,
    goal: Int,
    onSubtract: () -> Unit,
    onAddSmall: () -> Unit,
    onAddLarge: () -> Unit,
    onComplete: () -> Unit
) {
    val completed = count >= goal
    val progress = (count.toFloat() / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    val quickIncrement = definition.quickIncrement
    val largeIncrement = (quickIncrement * 2).coerceAtMost(MAX_RECORD_COUNT)
    val periodText = if (definition.goalPeriod == FitnessGoalPeriod.WEEKLY) {
        "本周"
    } else {
        "今日"
    }

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
                    text = definition.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (completed) {
                        "已达标"
                    } else {
                        "$count / $goal ${definition.unit}"
                    },
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
                text = "$periodText 累计$count${definition.unit}，" +
                    "${definition.goalPeriod.displayName}目标$goal${definition.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = todayCount > 0,
                    onClick = onSubtract,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "-$quickIncrement")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAddSmall,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "+$quickIncrement")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onAddLarge,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "+$largeIncrement")
                }
                Button(
                    modifier = Modifier.weight(1.2f),
                    enabled = !completed,
                    onClick = onComplete,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(text = "达标")
                }
            }
        }
    }
}

/**
 * 显示自动步数项目进度、传感器状态和手动校准入口。
 *
 * @param definition 自动步数项目定义。
 * @param count 当前步数。
 * @param sensorSupported 手机是否提供计步传感器。
 * @param permissionGranted 是否获得身体活动权限。
 * @param sensorActive 传感器监听是否成功启动。
 * @param sensorMessage 最近一次计步状态说明。
 * @param onRequestPermission 请求权限的回调。
 * @param onCalibrate 打开手动校准的回调。
 * @param onSetGoal 直接打开当前步行项目目标设置的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun StepTaskCard(
    definition: FitnessExerciseDefinition,
    count: Int,
    sensorSupported: Boolean,
    permissionGranted: Boolean,
    sensorActive: Boolean,
    sensorMessage: String,
    onRequestPermission: () -> Unit,
    onCalibrate: () -> Unit,
    onSetGoal: () -> Unit
) {
    val completed = count >= definition.dailyGoal
    val progress = (count.toFloat() / definition.dailyGoal.coerceAtLeast(1))
        .coerceIn(0f, 1f)
    val statusText = when {
        !sensorSupported -> "本机没有可用计步传感器，请使用手动校准"
        !permissionGranted -> "允许身体活动权限后，可在运动页打开时自动累计"
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
        )
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
                    text = definition.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (completed) {
                        "已达标"
                    } else {
                        "$count / ${definition.dailyGoal} ${definition.unit}"
                    },
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
                    Text(text = "校准${definition.name}")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onSetGoal
                ) {
                    Text(text = "设置目标")
                }
            }
        }
    }
}

/**
 * 显示任意日期区间选择、总体天数和各项目累计结果。
 *
 * @param summary 已计算的区间汇总。
 * @param onStartDateClick 选择开始日期的回调。
 * @param onEndDateClick 选择结束日期的回调。
 * @param onRecentDaysSelected 快速选择最近若干天的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessRangeSummaryCard(
    summary: FitnessRangeSummary,
    onStartDateClick: () -> Unit,
    onEndDateClick: () -> Unit,
    onRecentDaysSelected: (Long) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "日期区间汇总",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "选择任意开始和结束日期，汇总会与下方明细同步更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onStartDateClick
                ) {
                    Text(text = "开始 ${formatCompactDate(summary.startEpochDay)}")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onEndDateClick
                ) {
                    Text(text = "结束 ${formatCompactDate(summary.endEpochDay)}")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRecentDaysSelected(7L) }) {
                    Text(text = "最近7天")
                }
                TextButton(onClick = { onRecentDaysSelected(30L) }) {
                    Text(text = "最近30天")
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "有记录 ${summary.recordedDays} 天 / 共 ${summary.totalDays} 天",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "全部每日项目达标 ${summary.fullyCompletedDays} 天",
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (summary.itemSummaries.isEmpty()) {
                Text(
                    text = "这个范围内还没有可以汇总的运动量。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                summary.itemSummaries.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (item.goalPeriod == FitnessGoalPeriod.WEEKLY) {
                                    "运动${item.activeDays}天 · 目标按自然周累计"
                                } else {
                                    "运动${item.activeDays}天 · 达标${item.goalReachedDays}天"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${item.totalCount}${item.unit}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示记录明细标题、当前记录数量和补记入口。
 *
 * @param recordCount 当前日期区间内真实记录数量。
 * @param onAddRecord 打开补记日期选择器的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessHistoryHeader(
    recordCount: Int,
    onAddRecord: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "记录明细",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "共$recordCount 条；每条都可编辑或删除",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(onClick = onAddRecord) {
            Text(text = "补记")
        }
    }
}

/**
 * 显示单日动态项目摘要，并提供始终可见的编辑、删除按钮。
 *
 * @param record 已保存且至少有一项完成量的记录。
 * @param todayEpochDay 今天日期，用于增加“今天”标记。
 * @param onEdit 编辑回调。
 * @param onDelete 删除回调。
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
    val summaryText = record.items
        .filter { it.count > 0 }
        .joinToString(separator = "  ·  ") { item ->
            "${item.name} ${item.count}${item.unit}"
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
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
                        "${record.completedTaskCount()} / ${record.items.size} 项"
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
                text = summaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onEdit) {
                    Text(text = "编辑")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete) {
                    Text(
                        text = "删除这一天",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * 管理当前动态运动项目列表。
 *
 * @param definitions 当前项目列表。
 * @param onDismiss 关闭管理器的回调。
 * @param onAdd 新增项目回调。
 * @param onEdit 编辑指定项目回调。
 * @param onDelete 删除指定项目回调。
 *
 * @return 无返回值。
 */
@Composable
private fun ExerciseManagerDialog(
    definitions: List<FitnessExerciseDefinition>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (FitnessExerciseDefinition) -> Unit,
    onDelete: (FitnessExerciseDefinition) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "管理运动项目")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "可新增、修改或删除。删除项目不会删除以前日期已经保存的运动数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (definitions.isEmpty()) {
                    Text(text = "当前没有项目。")
                }

                definitions.forEach { definition ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = definition.name,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${definition.goalPeriod.displayName}目标 " +
                                            "${definition.dailyGoal}${definition.unit}" +
                                            if (definition.trackingType == FitnessTrackingType.STEP_COUNTER) {
                                                " · 自动计步"
                                            } else {
                                                " · 每次+${definition.quickIncrement}"
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { onEdit(definition) }) {
                                    Text(text = "编辑")
                                }
                                TextButton(onClick = { onDelete(definition) }) {
                                    Text(
                                        text = "删除",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAdd) {
                Text(text = "新增项目")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "完成")
            }
        }
    )
}

/**
 * 新增或编辑单个运动项目。
 *
 * @param definition 正在编辑的项目；null表示新增。
 * @param currentDefinitions 当前项目列表，用于检查名称和自动步数类型是否重复。
 * @param onDismiss 取消回调。
 * @param onSave 保存回调；成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun ExerciseEditorDialog(
    definition: FitnessExerciseDefinition?,
    currentDefinitions: List<FitnessExerciseDefinition>,
    onDismiss: () -> Unit,
    onSave: (FitnessExerciseDefinition) -> Boolean
) {
    var nameText by rememberSaveable(definition?.id) {
        mutableStateOf(definition?.name.orEmpty())
    }
    var unitText by rememberSaveable(definition?.id) {
        mutableStateOf(definition?.unit ?: "次")
    }
    var goalText by rememberSaveable(definition?.id) {
        mutableStateOf(definition?.dailyGoal?.toString() ?: "30")
    }
    var quickText by rememberSaveable(definition?.id) {
        mutableStateOf(definition?.quickIncrement?.toString() ?: "5")
    }
    var trackingType by rememberSaveable(definition?.id) {
        mutableStateOf(definition?.trackingType ?: FitnessTrackingType.MANUAL)
    }
    var goalPeriod by rememberSaveable(definition?.id) {
        mutableStateOf(definition?.goalPeriod ?: FitnessGoalPeriod.DAILY)
    }
    var errorText by rememberSaveable(definition?.id) {
        mutableStateOf("")
    }
    val anotherStepDefinitionExists = currentDefinitions.any { existing ->
        existing.id != definition?.id &&
            existing.trackingType == FitnessTrackingType.STEP_COUNTER
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = if (definition == null) "新增运动项目" else "编辑运动项目")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nameText,
                    onValueChange = {
                        nameText = it.take(MAX_EXERCISE_NAME_LENGTH)
                        errorText = ""
                    },
                    label = { Text(text = "项目名称") },
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = unitText,
                    onValueChange = {
                        unitText = it.take(MAX_EXERCISE_UNIT_LENGTH)
                        errorText = ""
                    },
                    label = { Text(text = "单位，例如 次、分钟、公里") },
                    singleLine = true
                )
                GoalNumberField(
                    value = goalText,
                    onValueChange = {
                        goalText = it.filter(Char::isDigit).take(MAX_NUMBER_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "${goalPeriod.displayName}目标"
                )

                Text(
                    text = "目标周期",
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FitnessGoalPeriod.entries.forEach { period ->
                        val selected = goalPeriod == period
                        if (selected) {
                            Button(
                                onClick = { goalPeriod = period }
                            ) {
                                Text(text = period.displayName)
                            }
                        } else {
                            OutlinedButton(
                                enabled = trackingType != FitnessTrackingType.STEP_COUNTER ||
                                    period == FitnessGoalPeriod.DAILY,
                                onClick = { goalPeriod = period }
                            ) {
                                Text(text = period.displayName)
                            }
                        }
                    }
                }

                Text(
                    text = "记录方式",
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trackingType == FitnessTrackingType.MANUAL) {
                        Button(onClick = { trackingType = FitnessTrackingType.MANUAL }) {
                            Text(text = "手动记录")
                        }
                    } else {
                        OutlinedButton(onClick = { trackingType = FitnessTrackingType.MANUAL }) {
                            Text(text = "手动记录")
                        }
                    }

                    if (trackingType == FitnessTrackingType.STEP_COUNTER) {
                        Button(onClick = { trackingType = FitnessTrackingType.STEP_COUNTER }) {
                            Text(text = "手机自动步数")
                        }
                    } else {
                        OutlinedButton(
                            enabled = !anotherStepDefinitionExists,
                            onClick = {
                                trackingType = FitnessTrackingType.STEP_COUNTER
                                goalPeriod = FitnessGoalPeriod.DAILY
                            }
                        ) {
                            Text(text = "手机自动步数")
                        }
                    }
                }

                if (anotherStepDefinitionExists) {
                    Text(
                        text = "当前已有一个自动步数项目；同一时间只能保留一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (trackingType == FitnessTrackingType.MANUAL) {
                    GoalNumberField(
                        value = quickText,
                        onValueChange = {
                            quickText = it.filter(Char::isDigit).take(MAX_NUMBER_INPUT_LENGTH)
                            errorText = ""
                        },
                        label = "快速增加量"
                    )
                }

                Text(
                    text = "数量按非负整数保存；例如跑步可使用“分钟”，骑行可使用“公里”。",
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
                    val safeName = nameText.trim()
                    val safeUnit = unitText.trim()
                    val goal = goalText.toIntOrNull()
                    val quickIncrement = if (trackingType == FitnessTrackingType.STEP_COUNTER) {
                        definition?.quickIncrement ?: 1_000
                    } else {
                        quickText.toIntOrNull()
                    }
                    errorText = when {
                        safeName.isBlank() -> "请输入项目名称"
                        safeUnit.isBlank() -> "请输入数量单位"
                        currentDefinitions.any { existing ->
                            existing.id != definition?.id &&
                                existing.name.equals(safeName, ignoreCase = true)
                        } -> "项目名称不能重复"
                        goal == null || goal !in MIN_EXERCISE_GOAL..MAX_RECORD_COUNT -> {
                            "${goalPeriod.displayName}目标请输入1到1000000"
                        }
                        quickIncrement == null ||
                            quickIncrement !in MIN_QUICK_INCREMENT..MAX_QUICK_INCREMENT -> {
                            "快速增加量请输入1到100000"
                        }
                        trackingType == FitnessTrackingType.STEP_COUNTER &&
                            anotherStepDefinitionExists -> "自动步数项目只能有一个"
                        onSave(
                            FitnessExerciseDefinition(
                                id = definition?.id.orEmpty(),
                                name = safeName,
                                unit = safeUnit,
                                dailyGoal = goal,
                                quickIncrement = quickIncrement,
                                trackingType = trackingType,
                                goalPeriod = if (
                                    trackingType == FitnessTrackingType.STEP_COUNTER
                                ) {
                                    FitnessGoalPeriod.DAILY
                                } else {
                                    goalPeriod
                                }
                            )
                        ) -> ""
                        else -> "项目保存失败，请重试"
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
 * 删除运动项目前二次确认，并明确历史数据保留策略。
 *
 * @param definition 即将删除的项目。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认删除回调。
 *
 * @return 无返回值。
 */
@Composable
private fun DeleteExerciseDefinitionDialog(
    definition: FitnessExerciseDefinition,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "删除运动项目") },
        text = {
            Text(
                text = "确定删除“${definition.name}”吗？今日训练将不再显示它，" +
                    "但以前日期已经保存的${definition.name}数据仍会保留在历史和汇总中。"
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
 * 新增或编辑某一天所有动态项目的完整运动量。
 *
 * @param record 待编辑记录，包含历史项目快照和当前可用项目。
 * @param onDismiss 取消回调。
 * @param onSave 保存回调；成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun FitnessRecordEditorDialog(
    record: DailyFitnessRecord,
    onDismiss: () -> Unit,
    onSave: (DailyFitnessRecord) -> Boolean
) {
    var countTexts by remember(record.dateEpochDay, record.items) {
        mutableStateOf(
            record.items.associate { item ->
                item.exerciseId to item.count.toString()
            }
        )
    }
    var errorText by remember(record.dateEpochDay) {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = if (record.hasRecordedActivity()) "编辑运动记录" else "补记运动")
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = LocalDate.ofEpochDay(record.dateEpochDay).format(
                        DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                if (record.items.isEmpty()) {
                    Text(text = "没有可记录的运动项目，请先到“管理项目”新增。")
                }

                record.items.forEach { item ->
                    GoalNumberField(
                        value = countTexts[item.exerciseId].orEmpty(),
                        onValueChange = { changedText ->
                            countTexts = countTexts + (
                                item.exerciseId to changedText
                                    .filter(Char::isDigit)
                                    .take(MAX_NUMBER_INPUT_LENGTH)
                                )
                            errorText = ""
                        },
                        label = if (item.goalPeriod == FitnessGoalPeriod.WEEKLY) {
                            "${item.name}（本日完成${item.unit}，周目标${item.goal}）"
                        } else {
                            "${item.name}（${item.unit}，每日目标${item.goal}）"
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
            }
        },
        confirmButton = {
            Button(
                enabled = record.items.isNotEmpty(),
                onClick = {
                    val parsedCounts = record.items.associate { item ->
                        item.exerciseId to countTexts[item.exerciseId]?.toIntOrNull()
                    }
                    val invalidItem = record.items.firstOrNull { item ->
                        val count = parsedCounts[item.exerciseId]
                        count == null || count !in 0..MAX_RECORD_COUNT
                    }
                    val hasPositiveCount = parsedCounts.values.any { count ->
                        count != null && count > 0
                    }

                    errorText = when {
                        invalidItem != null -> {
                            "${invalidItem.name}请输入0到1000000"
                        }
                        !hasPositiveCount -> "至少需要记录一项大于0的运动量"
                        onSave(
                            record.copy(
                                items = record.items.map { item ->
                                    item.copy(count = parsedCounts[item.exerciseId] ?: 0)
                                }
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
 * 删除单日完整记录前二次确认。
 *
 * @param record 即将删除的记录。
 * @param onDismiss 取消回调。
 * @param onConfirm 确认删除回调。
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
        title = { Text(text = "删除这一天的记录") },
        text = {
            Text(
                text = "确定删除${formatHistoryDate(record.dateEpochDay, Long.MIN_VALUE)}的全部运动记录吗？" +
                    "该操作不会删除运动项目设置。"
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
 * 手动设置自动步数项目当天总数。
 *
 * @param definition 自动步数项目定义。
 * @param currentCount 当前完成量。
 * @param onDismiss 取消回调。
 * @param onSave 保存回调；成功返回true。
 *
 * @return 无返回值。
 */
@Composable
private fun StepCalibrationDialog(
    definition: FitnessExerciseDefinition,
    currentCount: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Boolean
) {
    var countText by rememberSaveable(currentCount) {
        mutableStateOf(currentCount.toString())
    }
    var errorText by rememberSaveable {
        mutableStateOf("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "校准今日${definition.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "请输入手机健康应用或手环显示的今日总数，后续自动计步会在此基础上继续增加。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                GoalNumberField(
                    value = countText,
                    onValueChange = {
                        countText = it.filter(Char::isDigit).take(MAX_NUMBER_INPUT_LENGTH)
                        errorText = ""
                    },
                    label = "今日总${definition.unit}数"
                )
                if (errorText.isNotBlank()) {
                    Text(
                        text = errorText,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val count = countText.toIntOrNull()
                    errorText = when {
                        count == null || count !in 0..MAX_RECORD_COUNT -> {
                            "请输入0到1000000"
                        }
                        onSave(count) -> ""
                        else -> "保存失败，请重试"
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
 * 显示统一数字输入框。
 *
 * @param value 当前文本。
 * @param onValueChange 输入变化回调。
 * @param label 输入框标签。
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
        label = { Text(text = label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = RoundedCornerShape(14.dp)
    )
}

/**
 * 描述运动页面某一次Material日历请求及确认后的业务写入动作。
 *
 * @param title 日历顶部显示的具体用途。
 * @param initialEpochDay 初始选中日期。
 * @param minEpochDay 最早可选日期。
 * @param maxEpochDay 最晚可选日期。
 * @param onDateSelected 用户确认日期后执行的业务回调。
 */
private data class FitnessDatePickerRequest(
    val title: String,
    val initialEpochDay: Long,
    val minEpochDay: Long,
    val maxEpochDay: Long,
    val onDateSelected: (Long) -> Unit
)

/**
 * 计算当前连续全部达标天数。
 *
 * @param records 包含空占位的近期连续记录。
 * @param todayEpochDay 今天日期。
 * @param currentDefinitions 今天仍启用的项目，用于忽略当天刚删除的项目。
 *
 * @return 从今天或昨天向前连续全部达标的天数。
 */
private fun calculateCurrentStreak(
    records: List<DailyFitnessRecord>,
    todayEpochDay: Long,
    currentDefinitions: List<FitnessExerciseDefinition>
): Int {
    val recordsByDate = records.associateBy { it.dateEpochDay }
    val isDayComplete = { epochDay: Long ->
        val record = recordsByDate[epochDay]
        if (epochDay == todayEpochDay) {
            record?.isComplete(currentDefinitions) == true
        } else {
            record?.isComplete() == true
        }
    }
    var cursor = todayEpochDay
    if (!isDayComplete(cursor)) {
        cursor -= 1L
    }

    var streak = 0
    while (isDayComplete(cursor)) {
        streak += 1
        cursor -= 1L
    }
    return streak
}

/**
 * 格式化历史日期并为今天追加标记。
 *
 * @param epochDay 日期序号。
 * @param todayEpochDay 今天日期序号。
 *
 * @return 例如“8月29日 周六 · 今天”的中文日期。
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

/**
 * 格式化区间按钮使用的紧凑日期。
 *
 * @param epochDay 日期序号。
 *
 * @return yyyy.MM.dd格式文本。
 */
private fun formatCompactDate(epochDay: Long): String {
    return LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.CHINA)
    )
}

private const val DEFAULT_RANGE_DAYS = 7L
private const val STREAK_LOOKBACK_DAYS = 370
private const val MAX_RECORD_LOOKBACK_DAYS = 365L
private const val MIN_EXERCISE_GOAL = 1
private const val MIN_QUICK_INCREMENT = 1
private const val MAX_QUICK_INCREMENT = 100_000
private const val MAX_RECORD_COUNT = 1_000_000
private const val MAX_NUMBER_INPUT_LENGTH = 7
private const val MAX_EXERCISE_NAME_LENGTH = 20
private const val MAX_EXERCISE_UNIT_LENGTH = 8
private const val DATE_REFRESH_INTERVAL_MILLIS = 60_000L
