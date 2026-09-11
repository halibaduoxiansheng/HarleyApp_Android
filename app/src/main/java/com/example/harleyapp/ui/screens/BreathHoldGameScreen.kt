package com.example.harleyapp.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.BreathHoldRepository
import com.example.harleyapp.model.BreathHoldRecord
import com.example.harleyapp.model.calculateBreathHoldSummary
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * 显示带深海动画、安全确认、三秒倒计时、历史和个人最佳的憋气计时游戏。
 *
 * 使用方法：
 * HarleyApp把页面登记为功能中心详情页，并传入[BreathHoldRepository]。用户点击开始后必须先确认
 * 已在干燥地面坐稳或躺好；倒计时结束时使用Android单调时钟计时。憋气期间点击大面积结束区即可
 * 保存结果。页面只在倒计时和计时期间保持屏幕常亮，结束或离开页面会恢复原状态。
 *
 * 本功能是娱乐计时器，不进行肺功能、缺氧风险或医疗诊断。页面始终提醒不要在水中、驾驶、站立
 * 或过度换气后使用，出现头晕、胸闷或不适应立即停止。
 *
 * @param repository 本机历史记录仓库。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部传入的安全区域与布局修饰器。
 * @return 无返回值，直接输出沉浸式憋气计时页面。
 */
@Composable
fun BreathHoldGameScreen(
    repository: BreathHoldRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sessionState by remember {
        mutableStateOf(BreathHoldSessionState.READY)
    }
    var countdownValue by remember {
        mutableIntStateOf(3)
    }
    var sessionStartElapsedMillis by remember {
        mutableLongStateOf(0L)
    }
    var elapsedMillis by remember {
        mutableLongStateOf(0L)
    }
    var latestResultMillis by remember {
        mutableLongStateOf(0L)
    }
    var records by remember {
        mutableStateOf(repository.loadRecords())
    }
    var showSafetyDialog by remember {
        mutableStateOf(false)
    }
    var historySelectionMode by remember {
        mutableStateOf(false)
    }
    var selectedRecordIds by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    var showDeleteDialog by remember {
        mutableStateOf(false)
    }
    var historyOperationMessage by remember {
        mutableStateOf("")
    }
    val hapticFeedback = LocalHapticFeedback.current
    val keepScreenOn = sessionState == BreathHoldSessionState.COUNTDOWN ||
        sessionState == BreathHoldSessionState.HOLDING
    val view = LocalView.current

    /**
     * 结束当前憋气计时并立即把有效时长保存到本机。
     *
     * 仅在HOLDING状态调用；重复点击不会产生多条记录。使用单调时钟计算时长，避免用户调整系统
     * 时间影响秒表。无参数，无返回值，结果通过页面状态和Repository体现。
     */
    fun finishHoldingSession() {
        if (sessionState != BreathHoldSessionState.HOLDING) {
            return
        }
        val finalDuration = (SystemClock.elapsedRealtime() - sessionStartElapsedMillis)
            .coerceAtLeast(1L)
        elapsedMillis = finalDuration
        latestResultMillis = finalDuration
        records = repository.addRecord(finalDuration)
        sessionState = BreathHoldSessionState.RESULT
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    DisposableEffect(keepScreenOn, view) {
        val previousValue = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = previousValue
        }
    }

    LaunchedEffect(sessionState) {
        when (sessionState) {
            BreathHoldSessionState.COUNTDOWN -> {
                for (value in 3 downTo 1) {
                    countdownValue = value
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    delay(1_000L)
                }
                sessionStartElapsedMillis = SystemClock.elapsedRealtime()
                elapsedMillis = 0L
                sessionState = BreathHoldSessionState.HOLDING
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }

            BreathHoldSessionState.HOLDING -> {
                while (true) {
                    withFrameNanos {
                        elapsedMillis = (
                            SystemClock.elapsedRealtime() - sessionStartElapsedMillis
                            ).coerceAtLeast(0L)
                    }
                }
            }

            BreathHoldSessionState.READY,
            BreathHoldSessionState.RESULT -> Unit
        }
    }

    BackHandler(
        enabled = sessionState == BreathHoldSessionState.COUNTDOWN ||
            sessionState == BreathHoldSessionState.HOLDING
    ) {
        if (sessionState == BreathHoldSessionState.HOLDING) {
            finishHoldingSession()
        } else {
            sessionState = BreathHoldSessionState.READY
            elapsedMillis = 0L
        }
    }

    BackHandler(enabled = historySelectionMode) {
        // 选择模式优先消费系统返回键，只退出管理状态，不误删记录也不离开页面。
        selectedRecordIds = emptySet()
        historySelectionMode = false
        historyOperationMessage = ""
    }

    if (showSafetyDialog) {
        BreathHoldSafetyDialog(
            onDismiss = {
                showSafetyDialog = false
            },
            onConfirm = {
                showSafetyDialog = false
                countdownValue = 3
                elapsedMillis = 0L
                sessionState = BreathHoldSessionState.COUNTDOWN
            }
        )
    }

    if (showDeleteDialog) {
        BreathHoldDeleteDialog(
            selectedCount = selectedRecordIds.size,
            onDismiss = {
                // 取消二次确认时保留当前勾选，方便用户继续核对或调整。
                showDeleteDialog = false
            },
            onConfirm = {
                val selectedIdsSnapshot = selectedRecordIds
                val updatedRecords = repository.deleteRecords(selectedIdsSnapshot)
                val deletionSucceeded = updatedRecords.none { record ->
                    record.id in selectedIdsSnapshot
                }
                if (deletionSucceeded) {
                    val deletedCount = (records.size - updatedRecords.size).coerceAtLeast(0)
                    records = updatedRecords
                    selectedRecordIds = emptySet()
                    historySelectionMode = false
                    historyOperationMessage = "已删除${deletedCount}条，统计已按剩余记录更新"
                } else {
                    historyOperationMessage = "删除失败，原记录仍保留，请重试"
                }
                showDeleteDialog = false
            }
        )
    }

    val depthProgress = (elapsedMillis.toFloat() / DEEP_COLOR_DURATION_MILLIS)
        .coerceIn(0f, 1f)
    val topColor = lerp(Color(0xFF053D69), Color(0xFF180A3B), depthProgress)
    val bottomColor = lerp(Color(0xFF07182F), Color(0xFF050817), depthProgress)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
    ) {
        BreathHoldOceanAnimation(
            elapsedMillis = elapsedMillis,
            active = sessionState == BreathHoldSessionState.COUNTDOWN ||
                sessionState == BreathHoldSessionState.HOLDING,
            modifier = Modifier.fillMaxSize()
        )

        AnimatedContent(
            targetState = sessionState,
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.97f)).togetherWith(
                    fadeOut() + scaleOut(targetScale = 1.03f)
                )
            },
            label = "breath_hold_state"
        ) { state ->
            when (state) {
                BreathHoldSessionState.READY -> BreathHoldReadyContent(
                    records = records,
                    historySelectionMode = historySelectionMode,
                    selectedRecordIds = selectedRecordIds,
                    historyOperationMessage = historyOperationMessage,
                    onBack = {
                        if (historySelectionMode) {
                            selectedRecordIds = emptySet()
                            historySelectionMode = false
                            historyOperationMessage = ""
                        } else {
                            onBack()
                        }
                    },
                    onStart = {
                        selectedRecordIds = emptySet()
                        historySelectionMode = false
                        historyOperationMessage = ""
                        showSafetyDialog = true
                    },
                    onEnterHistorySelection = {
                        selectedRecordIds = emptySet()
                        historySelectionMode = true
                        historyOperationMessage = ""
                    },
                    onCancelHistorySelection = {
                        selectedRecordIds = emptySet()
                        historySelectionMode = false
                        historyOperationMessage = ""
                    },
                    onToggleRecordSelection = { recordId ->
                        selectedRecordIds = if (recordId in selectedRecordIds) {
                            selectedRecordIds - recordId
                        } else {
                            selectedRecordIds + recordId
                        }
                    },
                    onToggleSelectAll = {
                        val allRecordIds = records.mapTo(linkedSetOf(), BreathHoldRecord::id)
                        selectedRecordIds = if (selectedRecordIds == allRecordIds) {
                            emptySet()
                        } else {
                            allRecordIds
                        }
                    },
                    onDeleteSelected = {
                        if (selectedRecordIds.isNotEmpty()) {
                            showDeleteDialog = true
                        }
                    }
                )

                BreathHoldSessionState.COUNTDOWN -> BreathHoldCountdownContent(
                    countdownValue = countdownValue,
                    onCancel = {
                        sessionState = BreathHoldSessionState.READY
                        elapsedMillis = 0L
                    }
                )

                BreathHoldSessionState.HOLDING -> BreathHoldActiveContent(
                    elapsedMillis = elapsedMillis,
                    bestDurationMillis = calculateBreathHoldSummary(records).bestDurationMillis,
                    onFinish = ::finishHoldingSession
                )

                BreathHoldSessionState.RESULT -> BreathHoldResultContent(
                    resultMillis = latestResultMillis,
                    records = records,
                    onBack = onBack,
                    onTryAgain = { showSafetyDialog = true },
                    onViewHistory = {
                        sessionState = BreathHoldSessionState.READY
                    }
                )
            }
        }
    }
}

/** 计时页面的四个互斥阶段。 */
private enum class BreathHoldSessionState {
    READY,
    COUNTDOWN,
    HOLDING,
    RESULT
}

/**
 * 绘制持续漂浮的气泡、能量环和深海光束。
 *
 * @param elapsedMillis 当前计时时长，用于让能量环随时间缓慢扩张。
 * @param active 是否处于倒计时或正式计时状态，用于增强动画亮度。
 * @param modifier 外部尺寸修饰器。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldOceanAnimation(
    elapsedMillis: Long,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "breath_ocean")
    val floatPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bubble_float"
    )
    val pulsePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "energy_pulse"
    )
    val activeAlpha = if (active) 1f else 0.58f

    Canvas(modifier = modifier) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF70E7FF).copy(alpha = 0.12f * activeAlpha),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.42f),
                radius = size.minDimension * 0.55f
            ),
            radius = size.minDimension * 0.55f,
            center = Offset(size.width * 0.5f, size.height * 0.42f)
        )

        val center = Offset(size.width * 0.5f, size.height * 0.43f)
        repeat(3) { ringIndex ->
            val progress = (pulsePhase + ringIndex / 3f) % 1f
            drawCircle(
                color = Color(0xFF8AE7FF).copy(
                    alpha = (0.24f * (1f - progress) * activeAlpha).coerceAtLeast(0f)
                ),
                radius = size.minDimension * (0.13f + progress * 0.32f),
                center = center,
                style = Stroke(width = 2.2f + (1f - progress) * 2.4f)
            )
        }

        repeat(BUBBLE_COUNT) { index ->
            val horizontalPhase = sin(index * 1.73f + floatPhase * PI.toFloat() * 2f)
            val x = size.width * BUBBLE_HORIZONTAL_POSITIONS[index] +
                horizontalPhase * size.width * 0.018f
            val travel = size.height * (0.55f + index % 4 * 0.08f)
            val rawY = size.height * BUBBLE_VERTICAL_POSITIONS[index] - floatPhase * travel
            val y = ((rawY % size.height) + size.height) % size.height
            val radius = 3.5f + (index % 4) * 2.2f
            drawCircle(
                color = Color.White.copy(alpha = (0.22f + index % 3 * 0.08f) * activeAlpha),
                radius = radius,
                center = Offset(x, y),
                style = Stroke(width = 1.5f)
            )
            drawCircle(
                color = Color(0xFFB7F2FF).copy(alpha = 0.10f * activeAlpha),
                radius = radius * 0.7f,
                center = Offset(x - radius * 0.25f, y - radius * 0.25f)
            )
        }

        val elapsedSweep = ((elapsedMillis % 60_000L) / 60_000f) * 360f
        if (active) {
            drawArc(
                color = Color(0xFFA5F3FF).copy(alpha = 0.34f),
                startAngle = -90f,
                sweepAngle = elapsedSweep,
                useCenter = false,
                topLeft = Offset(center.x - size.minDimension * 0.2f, center.y - size.minDimension * 0.2f),
                size = androidx.compose.ui.geometry.Size(
                    size.minDimension * 0.4f,
                    size.minDimension * 0.4f
                ),
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 显示开始前的安全提示、个人数据、开始按钮和本机历史。
 *
 * @param records 当前本机历史。
 * @param historySelectionMode 是否正在选择要删除的历史记录。
 * @param selectedRecordIds 当前已勾选记录的稳定ID集合。
 * @param historyOperationMessage 最近一次删除操作反馈；没有反馈时为空字符串。
 * @param onBack 返回功能中心回调。
 * @param onStart 请求开始并打开安全确认的回调。
 * @param onEnterHistorySelection 进入历史选择模式的回调。
 * @param onCancelHistorySelection 取消选择并清空勾选的回调。
 * @param onToggleRecordSelection 切换单条记录勾选状态的回调。
 * @param onToggleSelectAll 在全选和取消全选之间切换的回调。
 * @param onDeleteSelected 请求删除当前勾选记录并打开二次确认的回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldReadyContent(
    records: List<BreathHoldRecord>,
    historySelectionMode: Boolean,
    selectedRecordIds: Set<String>,
    historyOperationMessage: String,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onEnterHistorySelection: () -> Unit,
    onCancelHistorySelection: () -> Unit,
    onToggleRecordSelection: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    val summary = calculateBreathHoldSummary(records)
    val allRecordIds = remember(records) {
        records.mapTo(linkedSetOf(), BreathHoldRecord::id)
    }
    val allRecordsSelected = allRecordIds.isNotEmpty() && selectedRecordIds == allRecordIds
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            BreathHoldHeader(title = "深海憋气", onBack = onBack)
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "◌", style = MaterialTheme.typography.displayLarge, color = Color(0xFF9EEBFF))
                Text(
                    text = "沉入安静的蓝色",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "这是一枚娱乐秒表，不是肺功能或医疗测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f)
                )
            }
        }
        item {
            BreathHoldSafetyCard()
        }
        item {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                onClick = onStart,
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    text = "开始憋气",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        item {
            BreathHoldSummaryCard(
                attemptCount = summary.attemptCount,
                bestDurationMillis = summary.bestDurationMillis,
                averageDurationMillis = summary.averageDurationMillis
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = if (historySelectionMode) {
                        "已选择 ${selectedRecordIds.size}/${records.size}"
                    } else {
                        "本机记录"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (records.isNotEmpty()) {
                    if (historySelectionMode) {
                        TextButton(onClick = onToggleSelectAll) {
                            Text(
                                text = if (allRecordsSelected) "取消全选" else "全选",
                                color = Color.White.copy(alpha = 0.84f)
                            )
                        }
                        TextButton(onClick = onCancelHistorySelection) {
                            Text(text = "取消", color = Color.White.copy(alpha = 0.84f))
                        }
                    } else {
                        TextButton(onClick = onEnterHistorySelection) {
                            Text(text = "选择删除", color = Color.White.copy(alpha = 0.84f))
                        }
                    }
                }
            }
        }
        if (historySelectionMode) {
            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedRecordIds.isNotEmpty(),
                    onClick = onDeleteSelected
                ) {
                    Text(
                        text = if (selectedRecordIds.isEmpty()) {
                            "请先选择要删除的记录"
                        } else {
                            "删除选中的${selectedRecordIds.size}条记录"
                        },
                        color = if (selectedRecordIds.isEmpty()) {
                            Color.White.copy(alpha = 0.42f)
                        } else {
                            Color(0xFFFFB4AB)
                        }
                    )
                }
            }
        }
        if (historyOperationMessage.isNotBlank()) {
            item {
                Text(
                    text = historyOperationMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if ("失败" in historyOperationMessage) {
                        Color(0xFFFFB4AB)
                    } else {
                        Color(0xFFBCEFFF)
                    }
                )
            }
        }
        if (records.isEmpty()) {
            item {
                BreathHoldGlassCard {
                    Text(
                        text = "完成第一次计时后，这里会显示个人最佳和变化。",
                        color = Color.White.copy(alpha = 0.74f)
                    )
                }
            }
        } else {
            items(items = records, key = BreathHoldRecord::id) { record ->
                BreathHoldHistoryRow(
                    record = record,
                    selectionMode = historySelectionMode,
                    selected = record.id in selectedRecordIds,
                    onToggleSelection = { onToggleRecordSelection(record.id) }
                )
            }
        }
    }
}

/**
 * 显示三秒倒计时和取消入口。
 *
 * @param countdownValue 当前3、2或1。
 * @param onCancel 取消本次开始的回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldCountdownContent(
    countdownValue: Int,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "坐稳 · 放松 · 正常呼吸",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.78f)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = countdownValue.toString(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "倒计时结束后开始计时",
                color = Color.White.copy(alpha = 0.72f)
            )
        }
        TextButton(onClick = onCancel) {
            Text(text = "取消", color = Color.White)
        }
    }
}

/**
 * 显示正式计时、个人最佳参照和大面积结束按钮。
 *
 * @param elapsedMillis 当前单调时钟计时。
 * @param bestDurationMillis 开始本次前的个人最佳。
 * @param onFinish 用户点击结束区域的回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldActiveContent(
    elapsedMillis: Long,
    bestDurationMillis: Long,
    onFinish: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "正在憋气",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFBCEFFF)
            )
            Text(
                text = "不舒服就立即结束，不必追求数字",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.64f)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatBreathDuration(elapsedMillis),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(10.dp))
            val progressText = when {
                bestDurationMillis <= 0L -> "正在创造第一次记录"
                elapsedMillis > bestDurationMillis -> "已超过个人最佳"
                else -> "距离个人最佳 ${formatBreathDuration(bestDurationMillis - elapsedMillis)}"
            }
            Text(
                text = progressText,
                color = if (elapsedMillis > bestDurationMillis && bestDurationMillis > 0L) {
                    Color(0xFFFFE082)
                } else {
                    Color.White.copy(alpha = 0.72f)
                }
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(172.dp)
                .clip(RoundedCornerShape(38.dp))
                .clickable(onClick = onFinish),
            shape = RoundedCornerShape(38.dp),
            color = Color.White.copy(alpha = 0.16f),
            border = androidx.compose.foundation.BorderStroke(
                2.dp,
                Color(0xFFA7EDFF).copy(alpha = 0.72f)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "结束",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "轻触这片区域",
                    color = Color.White.copy(alpha = 0.66f)
                )
            }
        }
    }
}

/**
 * 显示本次结果、是否刷新个人最佳以及下一步操作。
 *
 * @param resultMillis 本次完成时长。
 * @param records 已包含本次结果的本机历史。
 * @param onBack 返回功能中心回调。
 * @param onTryAgain 再次打开安全确认回调。
 * @param onViewHistory 返回准备页查看历史回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldResultContent(
    resultMillis: Long,
    records: List<BreathHoldRecord>,
    onBack: () -> Unit,
    onTryAgain: () -> Unit,
    onViewHistory: () -> Unit
) {
    val summary = calculateBreathHoldSummary(records)
    val previousBest = records.drop(1).maxOfOrNull(BreathHoldRecord::durationMillis) ?: 0L
    val isBest = resultMillis > previousBest
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BreathHoldHeader(title = "本次完成", onBack = onBack)
        BreathHoldGlassCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isBest) "新的个人最佳" else "平稳完成",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isBest) Color(0xFFFFE082) else Color(0xFFBCEFFF),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatBreathDuration(resultMillis),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                if (summary.latestChangeMillis != 0L && records.size > 1) {
                    Text(
                        text = if (summary.latestChangeMillis > 0L) {
                            "比上次多 ${formatBreathDuration(summary.latestChangeMillis)}"
                        } else {
                            "比上次少 ${formatBreathDuration(-summary.latestChangeMillis)}"
                        },
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                onClick = onTryAgain,
                shape = RoundedCornerShape(22.dp)
            ) {
                Text(text = "再来一次", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onViewHistory
            ) {
                Text(text = "查看历史", color = Color.White)
            }
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = "先恢复正常呼吸；若头晕、胸闷或不适，请停止继续尝试。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.66f),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 显示深色背景上的统一页面标题和返回入口。
 *
 * @param title 页面阶段标题。
 * @param onBack 返回回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(text = "‹ 返回", color = Color.White)
        }
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.size(72.dp, 40.dp))
    }
}

/**
 * 显示准备页不可省略的核心安全规则。
 *
 * @return 无返回值。
 */
@Composable
private fun BreathHoldSafetyCard() {
    BreathHoldGlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "安全第一",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFE082)
            )
            Text(
                text = "• 只在干燥地面坐着或躺着使用\n• 绝不在水中、驾驶或站立时尝试\n• 不要过度换气；出现不适立即停止\n• 有心肺疾病、孕期或健康疑虑先咨询医生",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f)
            )
        }
    }
}

/**
 * 显示历史次数、个人最佳和平均时长。
 *
 * @param attemptCount 有效记录次数。
 * @param bestDurationMillis 最佳时长。
 * @param averageDurationMillis 平均时长。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldSummaryCard(
    attemptCount: Int,
    bestDurationMillis: Long,
    averageDurationMillis: Long
) {
    BreathHoldGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BreathHoldSummaryMetric(
                modifier = Modifier.weight(1f),
                title = "次数",
                value = "${attemptCount}次"
            )
            BreathHoldSummaryMetric(
                modifier = Modifier.weight(1f),
                title = "最佳",
                value = formatBreathDuration(bestDurationMillis)
            )
            BreathHoldSummaryMetric(
                modifier = Modifier.weight(1f),
                title = "平均",
                value = formatBreathDuration(averageDurationMillis)
            )
        }
    }
}

/**
 * 显示历史概览中的单个指标。
 *
 * @param title 指标标题。
 * @param value 已格式化值。
 * @param modifier 外部权重修饰器。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldSummaryMetric(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.62f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 显示一条本机历史记录。
 *
 * @param record 已校验的憋气记录。
 * @param selectionMode 是否处于历史多选删除模式。
 * @param selected 当前记录是否已被勾选。
 * @param onToggleSelection 切换当前记录勾选状态的回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldHistoryRow(
    record: BreathHoldRecord,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit
) {
    BreathHoldGlassCard(
        containerColor = if (selected) {
            Color(0xFF77DFF6).copy(alpha = 0.22f)
        } else {
            Color.White.copy(alpha = 0.11f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    enabled = selectionMode,
                    role = Role.Checkbox,
                    onValueChange = { onToggleSelection() }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null
                )
                Spacer(modifier = Modifier.size(8.dp))
            }
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = Color(0xFF77DFF6).copy(alpha = 0.20f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(text = "息", color = Color(0xFFBCEFFF), fontWeight = FontWeight.Bold)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = formatBreathRecordDate(record.completedAtMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
                Text(
                    text = "本机保存",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.54f)
                )
            }
            Text(
                text = formatBreathDuration(record.durationMillis),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFBCEFFF)
            )
        }
    }
}

/**
 * 提供半透明深海玻璃卡片容器。
 *
 * @param containerColor 卡片背景色；历史选中项可传入更明显的蓝色透明背景。
 * @param content 卡片内部内容。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldGlassCard(
    containerColor: Color = Color.White.copy(alpha = 0.11f),
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = 0.14f)
        )
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/**
 * 显示每次开始前的安全确认。
 *
 * @param onDismiss 取消开始回调。
 * @param onConfirm 确认坐稳并进入三秒倒计时的回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldSafetyDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "先确认安全环境") },
        text = {
            Text(
                text = "请确认你已经在干燥地面坐稳或躺好，不在水中、驾驶或站立，没有过度换气。出现头晕、胸闷、视线异常或任何不适，请立即结束。"
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = "我已坐稳，开始倒计时")
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
 * 显示删除指定憋气历史的二次确认。
 *
 * @param selectedCount 用户已经勾选且即将删除的记录数量。
 * @param onDismiss 取消回调。
 * @param onConfirm 最终删除所选记录的回调。
 * @return 无返回值。
 */
@Composable
private fun BreathHoldDeleteDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "删除选中的${selectedCount}条记录？") },
        text = {
            Text(text = "删除后无法恢复；次数、最佳和平均成绩会立即按剩余记录重新计算。")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(text = "确认删除")
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
 * 把毫秒转换为“分:秒.十分之一秒”的秒表文本。
 *
 * @param durationMillis 原始毫秒数，负数按0处理。
 * @return 例如“0:45.3”或“2:03.7”。
 */
private fun formatBreathDuration(durationMillis: Long): String {
    val safeMillis = durationMillis.coerceAtLeast(0L)
    val totalSeconds = safeMillis / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (safeMillis % 1_000L) / 100L
    return String.format(Locale.CHINA, "%d:%02d.%d", minutes, seconds, tenths)
}

/**
 * 把完成时间转换为本地日期和时分。
 *
 * @param timestampMillis Unix毫秒时间。
 * @return “M月d日 HH:mm”格式文本。
 */
private fun formatBreathRecordDate(timestampMillis: Long): String {
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(BREATH_RECORD_DATE_FORMATTER)
}

private const val DEEP_COLOR_DURATION_MILLIS = 120_000f
private const val BUBBLE_COUNT = 12
private val BUBBLE_HORIZONTAL_POSITIONS = floatArrayOf(
    0.09f, 0.18f, 0.29f, 0.38f, 0.47f, 0.57f,
    0.66f, 0.74f, 0.83f, 0.91f, 0.23f, 0.62f
)
private val BUBBLE_VERTICAL_POSITIONS = floatArrayOf(
    0.88f, 0.62f, 0.95f, 0.73f, 0.54f, 0.86f,
    0.68f, 0.91f, 0.57f, 0.78f, 0.44f, 0.49f
)
private val BREATH_RECORD_DATE_FORMATTER = DateTimeFormatter.ofPattern(
    "M月d日 HH:mm",
    Locale.CHINA
)
