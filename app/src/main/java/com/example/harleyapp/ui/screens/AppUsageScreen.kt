package com.example.harleyapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.harleyapp.model.AppUsageDashboard
import com.example.harleyapp.model.AppUsageDayDetail
import com.example.harleyapp.model.AppUsageDayTrend
import com.example.harleyapp.model.AppUsageEntry
import com.example.harleyapp.model.AppUsageQueryError
import com.example.harleyapp.system.AppUsageController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示最近七天逐日应用时长、打开次数、夜间使用、趋势和应用排行。
 *
 * 使用方法：
 * HarleyApp把页面登记为功能中心的详情页，并传入Application Context创建的
 * [AppUsageController]。页面首次进入会检查“使用情况访问”权限；用户完成系统授权并返回后，
 * 页面自动查询。点击日期可切换对应自然日的完整明细；页面从后台回到前台或用户点击刷新时，
 * 会重新读取最新统计。页面只展示控制器返回的最近七天数据，不提供删除、清空或重置入口。
 *
 * @param controller 使用情况权限检查和统计查询控制器。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部传入的安全区域与布局修饰器。
 * @return 无返回值，直接输出应用使用统计页面。
 */
@Composable
fun AppUsageScreen(
    controller: AppUsageController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var usageAccessGranted by remember {
        mutableStateOf(controller.hasUsageAccess())
    }
    var dashboard by remember {
        mutableStateOf<AppUsageDashboard?>(null)
    }
    var queryError by remember {
        mutableStateOf<AppUsageQueryError?>(null)
    }
    var isLoading by remember {
        mutableStateOf(false)
    }
    var refreshToken by remember {
        mutableIntStateOf(0)
    }
    var settingsError by remember {
        mutableStateOf("")
    }
    var selectedEpochDay by rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var hasFinishedInitialQuery by remember {
        mutableStateOf(false)
    }
    val usageSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        usageAccessGranted = controller.hasUsageAccess()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasFinishedInitialQuery) {
                // 页面通常在Activity已经RESUMED时才进入；用首次查询完成标记而不是猜测首次生命周期事件。
                refreshToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(refreshToken) {
        try {
            usageAccessGranted = controller.hasUsageAccess()
            settingsError = ""
            if (!usageAccessGranted) {
                dashboard = null
                queryError = AppUsageQueryError.USAGE_ACCESS_REQUIRED
                isLoading = false
                return@LaunchedEffect
            }

            isLoading = true
            queryError = null
            val result = controller.query()
            dashboard = result.dashboard
            queryError = result.error
            isLoading = false
        } finally {
            hasFinishedInitialQuery = true
        }
    }

    LaunchedEffect(dashboard) {
        val availableDays = dashboard?.dayDetails.orEmpty()
        val selectionStillExists = availableDays.any { detail ->
            detail.date.toEpochDay() == selectedEpochDay
        }
        if (!selectionStillExists) {
            selectedEpochDay = availableDays.lastOrNull()?.date?.toEpochDay()
        }
    }

    val selectedDayDetail = dashboard?.dayDetails
        ?.firstOrNull { detail -> detail.date.toEpochDay() == selectedEpochDay }
        ?: dashboard?.dayDetails?.lastOrNull()
    val activeEpochDay = selectedDayDetail?.date?.toEpochDay()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFDBF3FF),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppUsageHeader(
                isLoading = isLoading,
                onBack = onBack,
                onRefresh = {
                    refreshToken += 1
                }
            )
        }

        if (!usageAccessGranted) {
            item {
                AppUsagePermissionCard(
                    settingsError = settingsError,
                    onOpenSettings = {
                        val opened = runCatching {
                            usageSettingsLauncher.launch(controller.createUsageAccessIntent())
                        }.isSuccess
                        if (!opened) {
                            settingsError = "无法打开系统授权页，请在设置中搜索“使用情况访问”"
                        }
                    }
                )
            }
        } else {
            dashboard?.let { currentDashboard ->
                item {
                    AppUsageDaySelector(
                        dayDetails = currentDashboard.dayDetails,
                        selectedEpochDay = activeEpochDay,
                        onDateSelected = { epochDay ->
                            selectedEpochDay = epochDay
                        }
                    )
                }

                selectedDayDetail?.let { currentDay ->
                    item {
                        AnimatedContent(
                            targetState = currentDay,
                            transitionSpec = {
                                fadeIn().togetherWith(fadeOut())
                            },
                            label = "app_usage_selected_day"
                        ) { selectedDay ->
                            AppUsageSummaryCard(selectedDay)
                        }
                    }

                    item {
                        AppUsageTrendCard(
                            trends = currentDashboard.sevenDayTrend,
                            selectedEpochDay = activeEpochDay,
                            onDateSelected = { epochDay ->
                                selectedEpochDay = epochDay
                            }
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${currentDay.date.format(APP_USAGE_DATE_TITLE_FORMATTER)}应用排行",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "按当日前台使用时长排序",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${currentDay.appEntries.size}个",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (currentDay.appEntries.isEmpty()) {
                        item {
                            AppUsageEmptyCard(currentDay.date)
                        }
                    } else {
                        val largestDuration = currentDay.appEntries
                            .maxOf(AppUsageEntry::foregroundMillis)
                            .coerceAtLeast(1L)
                        items(
                            items = currentDay.appEntries,
                            key = AppUsageEntry::packageName
                        ) { usage ->
                            AppUsageAppRow(
                                usage = usage,
                                largestDurationMillis = largestDuration
                            )
                        }
                    }
                }

                item {
                    Text(
                        text = "说明：各日期时长和次数由Android前台Activity、亮屏与锁屏事件综合推算；夜间固定为23:00—次日06:00。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } ?: item {
                when {
                    isLoading -> AppUsageLoadingCard()
                    else -> AppUsageErrorCard(queryError)
                }
            }
        }
    }
}

/**
 * 显示应用使用页面标题、返回和刷新操作。
 *
 * @param isLoading 当前是否正在查询，用于避免重复刷新。
 * @param onBack 返回上级页面的回调。
 * @param onRefresh 重新查询统计的回调。
 * @return 无返回值。
 */
@Composable
private fun AppUsageHeader(
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(text = "‹ 返回")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "应用使用",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "看见时间去了哪里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onRefresh,
            enabled = !isLoading
        ) {
            Text(text = "刷新")
        }
    }
}

/**
 * 显示使用情况授权原因及系统设置入口。
 *
 * @param settingsError 系统设置无法打开时的错误文案。
 * @param onOpenSettings 打开使用情况访问设置的回调。
 * @return 无返回值。
 */
@Composable
private fun AppUsagePermissionCard(
    settingsError: String,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "需要一次系统授权",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Android要求你亲自在“使用情况访问”中允许。本功能只读取应用进入和离开前台的时间，不读取聊天、网页内容、账号或输入内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenSettings
            ) {
                Text(text = "去系统设置授权")
            }
            if (settingsError.isNotBlank()) {
                Text(
                    text = settingsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 显示最近七个自然日的固定日期入口，并自动让当前选中日期进入可见区域。
 *
 * 使用方法：传入控制器已经补齐的七天明细和当前选中日期。用户点击任意日期卡后，函数通过
 * [onDateSelected]返回该日期的Epoch Day，页面据此统一切换总览、趋势选中态和应用排行。
 *
 * @param dayDetails 按日期升序排列的最近七天完整明细。
 * @param selectedEpochDay 当前选中日期的Epoch Day；页面尚未建立选择时可以为空。
 * @param onDateSelected 用户选择日期后的回调，参数为所选日期的Epoch Day。
 * @return 无返回值，直接输出可横向滚动的日期选择卡片。
 */
@Composable
private fun AppUsageDaySelector(
    dayDetails: List<AppUsageDayDetail>,
    selectedEpochDay: Long?,
    onDateSelected: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(dayDetails, selectedEpochDay) {
        val selectedIndex = dayDetails.indexOfFirst { detail ->
            detail.date.toEpochDay() == selectedEpochDay
        }
        if (selectedIndex >= 0) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "选择日期",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "查看最近7天中每一天的应用明细",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = dayDetails,
                    key = { detail -> detail.date.toEpochDay() }
                ) { detail ->
                    val epochDay = detail.date.toEpochDay()
                    val isSelected = epochDay == selectedEpochDay
                    val isLatestDay = detail.date == dayDetails.lastOrNull()?.date
                    Surface(
                        modifier = Modifier
                            .width(76.dp)
                            .clickable {
                                onDateSelected(epochDay)
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = if (isLatestDay) {
                                    "今天"
                                } else {
                                    detail.date.format(APP_USAGE_WEEKDAY_FORMATTER)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                text = detail.date.format(APP_USAGE_DAY_FORMATTER),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = compactAppUsageDuration(detail.foregroundMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 显示所选日期的总时长、打开次数和夜间使用三个核心指标。
 *
 * @param dayDetail 当前选中自然日的完整统计明细。
 * @return 无返回值，直接输出所选日期的总览卡片。
 */
@Composable
private fun AppUsageSummaryCard(dayDetail: AppUsageDayDetail) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF1677FF), Color(0xFF6857E5))
                    )
                )
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "${dayDetail.date.format(APP_USAGE_DATE_TITLE_FORMATTER)}屏幕里的时间",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.82f)
            )
            Text(
                text = formatAppUsageDuration(dayDetail.foregroundMillis),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppUsageMetric(
                    modifier = Modifier.weight(1f),
                    symbol = "↗",
                    title = "打开次数",
                    value = "${dayDetail.launchCount}次"
                )
                AppUsageMetric(
                    modifier = Modifier.weight(1f),
                    symbol = "☾",
                    title = "夜间使用",
                    value = formatAppUsageDuration(dayDetail.nightMillis)
                )
            }
        }
    }
}

/**
 * 显示总览卡片内的单个指标。
 *
 * @param symbol 指标前的简洁符号。
 * @param title 指标标题。
 * @param value 已格式化的指标值。
 * @param modifier 外部权重与布局修饰器。
 * @return 无返回值。
 */
@Composable
private fun AppUsageMetric(
    symbol: String,
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.16f)
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$symbol $title",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.78f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * 以七根可点击的相对高度柱显示最近七个自然日的总前台时长。
 *
 * @param trends 按日期升序排列并已补零的七天趋势。
 * @param selectedEpochDay 当前选中日期的Epoch Day，用于突出对应柱体。
 * @param onDateSelected 用户点击趋势柱时的日期选择回调。
 * @return 无返回值，直接输出可联动切换明细的七天趋势卡片。
 */
@Composable
private fun AppUsageTrendCard(
    trends: List<AppUsageDayTrend>,
    selectedEpochDay: Long?,
    onDateSelected: (Long) -> Unit
) {
    val largestDuration = trends.maxOfOrNull(AppUsageDayTrend::foregroundMillis)
        ?.coerceAtLeast(1L)
        ?: 1L
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近7天趋势",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "共${formatAppUsageDuration(trends.sumOf(AppUsageDayTrend::foregroundMillis))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "点击日期柱也可以切换下方明细",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                trends.forEach { trend ->
                    val epochDay = trend.date.toEpochDay()
                    val isSelected = epochDay == selectedEpochDay
                    val fraction = (
                        trend.foregroundMillis.toFloat() / largestDuration.toFloat()
                        ).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onDateSelected(epochDay)
                            }
                            .padding(horizontal = 2.dp, vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            text = compactAppUsageDuration(trend.foregroundMillis),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((16f + 78f * fraction).dp)
                                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = if (isSelected) {
                                            listOf(Color(0xFF5448F5), Color(0xFF129FD8))
                                        } else {
                                            listOf(
                                                Color(0xFF6C63FF).copy(alpha = 0.48f),
                                                Color(0xFF26B7E8).copy(alpha = 0.48f)
                                            )
                                        }
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = trend.date.format(APP_USAGE_DAY_FORMATTER),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示单个应用在所选日期的时长、打开次数、夜间时长和相对使用占比。
 *
 * @param usage 当前应用在所选日期的统计。
 * @param largestDurationMillis 排名第一应用的时长，用于生成相对进度条。
 * @return 无返回值。
 */
@Composable
private fun AppUsageAppRow(
    usage: AppUsageEntry,
    largestDurationMillis: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (usage.icon != null) {
                    Image(
                        bitmap = usage.icon.asImageBitmap(),
                        contentDescription = usage.label,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(13.dp))
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = usage.label.take(1).ifBlank { "应" },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = usage.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = usage.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "最后使用 ${formatAppUsageLastUsed(usage.lastUsedAtMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = formatAppUsageDuration(usage.foregroundMillis),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = {
                    (usage.foregroundMillis.toFloat() / largestDurationMillis.toFloat())
                        .coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "打开 ${usage.launchCount}次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "夜间 ${formatAppUsageDuration(usage.nightMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 显示系统统计查询中的加载状态。 */
@Composable
private fun AppUsageLoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text(text = "正在整理应用使用记录…")
        }
    }
}

/**
 * 显示已授权但所选日期没有前台应用事件的空状态。
 *
 * @param date 当前选中的自然日期，用于让空状态与日期选择保持一致。
 * @return 无返回值，直接输出所选日期的空状态卡片。
 */
@Composable
private fun AppUsageEmptyCard(date: LocalDate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            modifier = Modifier.padding(20.dp),
            text = "${date.format(APP_USAGE_DATE_TITLE_FORMATTER)}没有读取到应用使用记录。刚授权或查看较早日期时，系统数据可能不完整，可稍后刷新。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 显示系统服务缺失或统计查询失败的稳定提示。
 *
 * @param error 控制器返回的错误类型。
 * @return 无返回值。
 */
@Composable
private fun AppUsageErrorCard(error: AppUsageQueryError?) {
    val message = when (error) {
        AppUsageQueryError.USAGE_ACCESS_REQUIRED -> "请先授予使用情况访问权限"
        AppUsageQueryError.SERVICE_UNAVAILABLE -> "系统暂未提供应用使用服务，请稍后刷新"
        AppUsageQueryError.QUERY_FAILED -> "应用使用记录读取失败，请确认授权后重试"
        null -> "暂时没有可显示的数据"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            modifier = Modifier.padding(20.dp),
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 把毫秒时长转换为适合主指标和列表展示的中文文本。
 *
 * @param durationMillis 原始毫秒数，负数按0处理。
 * @return 大于等于一小时时返回“X小时Y分”，否则返回“X分”；不足一分钟显示“<1分”。
 */
private fun formatAppUsageDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis.coerceAtLeast(0L) / 60_000L
    if (totalMinutes <= 0L) {
        return if (durationMillis > 0L) "<1分" else "0分"
    }
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}小时${minutes}分"
        hours > 0L -> "${hours}小时"
        else -> "${minutes}分"
    }
}

/**
 * 把毫秒时长压缩为七天柱状图顶部的短文本。
 *
 * @param durationMillis 原始毫秒数。
 * @return “Xm”或“Xh”形式的紧凑文本，零值返回“0”。
 */
private fun compactAppUsageDuration(durationMillis: Long): String {
    val minutes = durationMillis.coerceAtLeast(0L) / 60_000L
    return when {
        minutes <= 0L -> "0"
        minutes < 60L -> "${minutes}m"
        else -> String.format(Locale.CHINA, "%.1fh", minutes / 60.0)
    }
}

/**
 * 把单日最后前台交互时间转换为本地时分，供应用明细快速确认最后使用时刻。
 *
 * @param timestampMillis 当日最后前台交互的Unix毫秒时间；非正值表示没有有效时间。
 * @return 当前手机时区下的“HH:mm”，没有有效时间时返回“--:--”。
 */
private fun formatAppUsageLastUsed(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "--:--"
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(APP_USAGE_TIME_FORMATTER)
}

/** 最近七天日期选择和趋势图使用的紧凑月日格式。 */
private val APP_USAGE_DAY_FORMATTER = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)

/** 总览与排行标题使用的完整中文月日格式。 */
private val APP_USAGE_DATE_TITLE_FORMATTER = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)

/** 日期选择卡片使用的中文星期格式。 */
private val APP_USAGE_WEEKDAY_FORMATTER = DateTimeFormatter.ofPattern("EEE", Locale.CHINA)

/** 应用明细最后使用时间采用的二十四小时制格式。 */
private val APP_USAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
