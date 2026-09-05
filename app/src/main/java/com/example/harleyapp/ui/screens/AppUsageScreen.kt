package com.example.harleyapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.harleyapp.model.AppUsageDashboard
import com.example.harleyapp.model.AppUsageDayTrend
import com.example.harleyapp.model.AppUsageEntry
import com.example.harleyapp.model.AppUsageQueryError
import com.example.harleyapp.system.AppUsageController
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示今日应用时长、打开次数、夜间使用、七天趋势和最常用应用排行。
 *
 * 使用方法：
 * HarleyApp把页面登记为功能中心的详情页，并传入Application Context创建的
 * [AppUsageController]。页面首次进入会检查“使用情况访问”权限；用户完成系统授权并返回后，
 * 页面自动查询。点击刷新可重新读取，所有结果仅在当前页面内存中展示，不上传也不另存历史。
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
    val usageSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        usageAccessGranted = controller.hasUsageAccess()
        refreshToken += 1
    }

    LaunchedEffect(refreshToken) {
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
    }

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
            item {
                AnimatedContent(
                    targetState = dashboard,
                    transitionSpec = {
                        fadeIn().togetherWith(fadeOut())
                    },
                    label = "app_usage_dashboard"
                ) { currentDashboard ->
                    when {
                        isLoading && currentDashboard == null -> AppUsageLoadingCard()
                        currentDashboard != null -> AppUsageSummaryCard(currentDashboard)
                        else -> AppUsageErrorCard(queryError)
                    }
                }
            }

            dashboard?.let { currentDashboard ->
                item {
                    AppUsageTrendCard(currentDashboard.sevenDayTrend)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "最常用应用",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "按今天前台使用时长排序",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${currentDashboard.appEntries.size}个",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (currentDashboard.appEntries.isEmpty()) {
                    item {
                        AppUsageEmptyCard()
                    }
                } else {
                    val largestDuration = currentDashboard.appEntries
                        .maxOf(AppUsageEntry::foregroundMillis)
                        .coerceAtLeast(1L)
                    items(
                        items = currentDashboard.appEntries,
                        key = AppUsageEntry::packageName
                    ) { usage ->
                        AppUsageAppRow(
                            usage = usage,
                            largestDurationMillis = largestDuration
                        )
                    }
                }

                item {
                    Text(
                        text = "说明：时长和次数由Android前台Activity事件推算，厂商系统可能延迟或清理记录；夜间固定为23:00—次日06:00。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
 * 显示今天的总时长、打开次数和夜间使用三个核心指标。
 *
 * @param dashboard 当前成功读取的统计快照。
 * @return 无返回值。
 */
@Composable
private fun AppUsageSummaryCard(dashboard: AppUsageDashboard) {
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
                text = "今天屏幕里的时间",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.82f)
            )
            Text(
                text = formatAppUsageDuration(dashboard.todayForegroundMillis),
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
                    value = "${dashboard.todayLaunchCount}次"
                )
                AppUsageMetric(
                    modifier = Modifier.weight(1f),
                    symbol = "☾",
                    title = "夜间使用",
                    value = formatAppUsageDuration(dashboard.todayNightMillis)
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
 * 以七根相对高度柱显示最近七个自然日的总前台时长。
 *
 * @param trends 按日期升序排列并已补零的七天趋势。
 * @return 无返回值。
 */
@Composable
private fun AppUsageTrendCard(trends: List<AppUsageDayTrend>) {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                trends.forEach { trend ->
                    val fraction = (
                        trend.foregroundMillis.toFloat() / largestDuration.toFloat()
                        ).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            text = compactAppUsageDuration(trend.foregroundMillis),
                            style = MaterialTheme.typography.labelSmall,
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
                                        colors = listOf(Color(0xFF6C63FF), Color(0xFF26B7E8))
                                    )
                                )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = trend.date.format(APP_USAGE_DAY_FORMATTER),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示单个应用今天的时长、打开次数、夜间时长和相对使用占比。
 *
 * @param usage 当前应用的今日统计。
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
 * 显示已授权但今天没有前台应用事件的空状态。
 *
 * @return 无返回值。
 */
@Composable
private fun AppUsageEmptyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            modifier = Modifier.padding(20.dp),
            text = "今天还没有读取到应用使用记录。刚授权时系统数据可能稍有延迟，可使用几个应用后再刷新。",
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

private val APP_USAGE_DAY_FORMATTER = DateTimeFormatter.ofPattern("M/d", Locale.CHINA)
