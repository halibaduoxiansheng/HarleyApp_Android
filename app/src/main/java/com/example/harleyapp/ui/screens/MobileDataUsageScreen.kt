package com.example.harleyapp.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.FilterChip
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
import com.example.harleyapp.model.MobileDataAppUsage
import com.example.harleyapp.model.MobileDataPeriod
import com.example.harleyapp.model.MobileDataQueryError
import com.example.harleyapp.model.MobileDataUsageSnapshot
import com.example.harleyapp.system.MobileDataUsageController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示仅包含蜂窝网络的今日、本周、本月流量以及按应用排名。
 *
 * 使用方法：
 * HarleyApp把本页面登记为不占底部导航的功能详情页，并传入Application Context创建的
 * MobileDataUsageController。页面首次进入会检查“使用情况访问”权限；授权后自动在IO线程
 * 查询系统NetworkStats。切换周期或点击刷新会重新查询，不在本机重复保存系统统计。
 *
 * @param controller 手机流量权限检查和后台查询服务。
 * @param onBack 返回首页的回调。
 * @param modifier 外部传入的安全边距与布局修饰器。
 *
 * @return 无返回值，直接输出手机流量详情页。
 */
@Composable
fun MobileDataUsageScreen(
    controller: MobileDataUsageController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPeriodName by rememberSaveable {
        mutableStateOf(MobileDataPeriod.TODAY.name)
    }
    var usageAccessGranted by remember {
        mutableStateOf(controller.hasUsageAccess())
    }
    var wifiConnected by remember {
        mutableStateOf(controller.isWifiConnected())
    }
    var isLoading by remember {
        mutableStateOf(false)
    }
    var snapshot by remember {
        mutableStateOf<MobileDataUsageSnapshot?>(null)
    }
    var queryError by remember {
        mutableStateOf<MobileDataQueryError?>(null)
    }
    var refreshToken by remember {
        mutableIntStateOf(0)
    }
    var settingsError by remember {
        mutableStateOf("")
    }
    val selectedPeriod = MobileDataPeriod.entries.firstOrNull { period ->
        period.name == selectedPeriodName
    } ?: MobileDataPeriod.TODAY
    val usageSettingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        usageAccessGranted = controller.hasUsageAccess()
        refreshToken += 1
    }

    LaunchedEffect(selectedPeriod, refreshToken) {
        usageAccessGranted = controller.hasUsageAccess()
        wifiConnected = controller.isWifiConnected()
        settingsError = ""
        if (!usageAccessGranted) {
            snapshot = null
            queryError = MobileDataQueryError.USAGE_ACCESS_REQUIRED
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        queryError = null
        val result = controller.query(selectedPeriod)
        snapshot = result.snapshot
        queryError = result.error
        wifiConnected = result.snapshot?.wifiConnected ?: controller.isWifiConnected()
        isLoading = false
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.52f),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(text = "‹ 返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手机流量",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "仅统计蜂窝移动数据，不包含Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = {
                        refreshToken += 1
                    },
                    enabled = !isLoading
                ) {
                    Text(text = "刷新")
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = if (wifiConnected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (wifiConnected) "Wi-Fi" else "5G",
                        fontWeight = FontWeight.Bold,
                        color = if (wifiConnected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    Text(
                        text = if (wifiConnected) {
                            "当前已连接Wi-Fi；下方历史仍然只计算手机流量"
                        } else {
                            "当前未连接Wi-Fi，可查看手机流量由哪些应用产生"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MobileDataPeriod.entries.forEach { period ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = selectedPeriod == period,
                        onClick = {
                            selectedPeriodName = period.name
                        },
                        label = {
                            Text(text = period.title)
                        }
                    )
                }
            }
        }

        if (!usageAccessGranted) {
            item {
                PermissionGuideCard(
                    settingsError = settingsError,
                    onOpenSettings = {
                        val opened = runCatching {
                            usageSettingsLauncher.launch(controller.createUsageAccessIntent())
                        }.isSuccess
                        if (!opened) {
                            settingsError = "无法打开系统授权页面，请在设置中搜索“使用情况访问”"
                        }
                    }
                )
            }
        } else {
            item {
                AnimatedContent(
                    targetState = snapshot,
                    transitionSpec = {
                        (
                            fadeIn() + scaleIn(initialScale = 0.97f)
                            ).togetherWith(fadeOut())
                    },
                    label = "mobile_usage_summary"
                ) { currentSnapshot ->
                    when {
                        isLoading && currentSnapshot == null -> LoadingCard()
                        currentSnapshot != null -> MobileDataSummaryCard(currentSnapshot)
                        else -> QueryErrorCard(queryError)
                    }
                }
            }

            if (snapshot != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "应用使用排行",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${snapshot!!.apps.size}项",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (snapshot!!.apps.isEmpty()) {
                    item {
                        EmptyUsageCard()
                    }
                } else {
                    val largestAppBytes = snapshot!!.apps.first().totalBytes.coerceAtLeast(1L)
                    items(
                        items = snapshot!!.apps,
                        key = { usage -> usage.uid }
                    ) { usage ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn() + slideInVertically { height -> height / 4 }
                        ) {
                            MobileDataAppRow(
                                usage = usage,
                                largestAppBytes = largestAppBytes
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 显示使用情况访问授权原因和系统设置入口。
 *
 * @param settingsError 系统设置无法打开时的用户提示。
 * @param onOpenSettings 打开使用情况访问设置列表的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun PermissionGuideCard(
    settingsError: String,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
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
                text = "Android只允许用户主动开启“使用情况访问”。本功能仅读取每个应用的蜂窝上传、下载字节数和时间范围，不读取网页、聊天、手机号或Wi-Fi内容。",
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
 * 显示查询期间的加载状态。
 *
 * @return 无返回值。
 */
@Composable
private fun LoadingCard() {
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
            Text(text = "正在读取系统手机流量统计…")
        }
    }
}

/**
 * 显示当前周期总流量、上传下载拆分和统计日期范围。
 *
 * @param snapshot 当前查询成功快照。
 *
 * @return 无返回值。
 */
@Composable
private fun MobileDataSummaryCard(snapshot: MobileDataUsageSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${snapshot.period.title}手机流量",
                    color = Color.White.copy(alpha = 0.86f),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = formatBytes(snapshot.totalBytes),
                    color = Color.White,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatUsageRange(snapshot),
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryMetric(
                        modifier = Modifier.weight(1f),
                        symbol = "↓",
                        title = "下载",
                        bytes = snapshot.receivedBytes
                    )
                    SummaryMetric(
                        modifier = Modifier.weight(1f),
                        symbol = "↑",
                        title = "上传",
                        bytes = snapshot.transmittedBytes
                    )
                }
                Text(
                    text = "系统按时间段汇总，最新数据可能有少量延迟",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * 显示总览卡片中的下载或上传指标。
 *
 * @param symbol 方向符号。
 * @param title 下载或上传标题。
 * @param bytes 原始字节数。
 * @param modifier 外部权重和布局修饰器。
 *
 * @return 无返回值。
 */
@Composable
private fun SummaryMetric(
    symbol: String,
    title: String,
    bytes: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.16f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "$symbol $title",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = formatBytes(bytes),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 显示一个应用或共享UID的手机流量、上传下载拆分和相对占比。
 *
 * @param usage 当前UID流量汇总。
 * @param largestAppBytes 排名第一项总字节数，用于生成相对进度条。
 *
 * @return 无返回值。
 */
@Composable
private fun MobileDataAppRow(
    usage: MobileDataAppUsage,
    largestAppBytes: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
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
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "系",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
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
                        text = usage.packageNames.joinToString().ifBlank { "系统流量" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatBytes(usage.totalBytes),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            LinearProgressIndicator(
                progress = {
                    (usage.totalBytes.toFloat() / largestAppBytes.toFloat()).coerceIn(0f, 1f)
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
                    text = "下载 ${formatBytes(usage.receivedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "上传 ${formatBytes(usage.transmittedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 显示已授权但当前周期没有蜂窝流量的空状态。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyUsageCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "这个时间范围内没有读取到手机流量",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "可能尚未使用蜂窝网络，或系统统计仍在更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示系统服务不可用或查询失败提示。
 *
 * @param error Controller返回的稳定错误类型。
 *
 * @return 无返回值。
 */
@Composable
private fun QueryErrorCard(error: MobileDataQueryError?) {
    val message = when (error) {
        MobileDataQueryError.SERVICE_UNAVAILABLE -> "系统暂未提供流量统计，请稍后刷新"
        MobileDataQueryError.QUERY_FAILED -> "手机流量查询失败，请确认授权后重试"
        MobileDataQueryError.USAGE_ACCESS_REQUIRED -> "请先授予使用情况访问权限"
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
 * 把查询快照转换为简洁日期范围。
 *
 * @param snapshot 当前手机流量快照。
 *
 * @return “M月d日 HH:mm 至 M月d日 HH:mm”格式文本。
 */
private fun formatUsageRange(snapshot: MobileDataUsageSnapshot): String {
    val zoneId = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(snapshot.startTimeMillis).atZone(zoneId)
    val end = Instant.ofEpochMilli(snapshot.endTimeMillis).atZone(zoneId)
    return "${start.format(RANGE_FORMATTER)} 至 ${end.format(RANGE_FORMATTER)}"
}

/**
 * 把字节数转换为B、KB、MB、GB或TB容量文本。
 *
 * @param bytes 原始字节数，负数按0处理。
 *
 * @return 保留一位小数的易读容量文本。
 */
private fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = safeBytes
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }

    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.CHINA, "%.1f %s", value, units[unitIndex])
    }
}

private val RANGE_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
