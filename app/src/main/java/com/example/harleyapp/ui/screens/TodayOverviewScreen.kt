package com.example.harleyapp.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.FitnessRepository
import com.example.harleyapp.data.LedgerRepository
import com.example.harleyapp.data.ReminderRepository
import com.example.harleyapp.data.WechatReminderRepository
import com.example.harleyapp.model.DailyFitnessRecord
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.WeatherSnapshot
import com.example.harleyapp.model.weatherDescription
import com.example.harleyapp.model.weatherSymbol
import com.example.harleyapp.weather.DeviceLocationProvider
import com.example.harleyapp.weather.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 汇总当天账目、提醒、运动、微信待处理状态和定位天气。
 *
 * 使用方法：
 * 从首页或功能中心的“今日总览”卡片进入。页面只读取本机仓库；天气仅在用户已授予前台
 * 粗略位置权限且缓存过期时自动刷新，或由用户主动点击刷新。拒绝定位不会影响其他今日数据。
 *
 * @param ledgerRepository 本机账目仓库。
 * @param reminderRepository 本机通知提醒仓库。
 * @param fitnessRepository 本机运动仓库。
 * @param wechatReminderRepository 微信消息提醒状态仓库。
 * @param weatherRepository 天气缓存与HTTPS查询仓库。
 * @param locationProvider 前台一次性粗略定位提供者。
 * @param coroutineScope 宿主协程作用域。
 * @param onWeatherUpdated 天气缓存更新后的回调，用于同步桌面小组件。
 * @param onBack 返回来源页面的回调。
 * @param modifier 外部安全边距和布局修饰器。
 *
 * @return 无返回值，直接输出今日总览页面。
 */
@Composable
fun TodayOverviewScreen(
    ledgerRepository: LedgerRepository,
    reminderRepository: ReminderRepository,
    fitnessRepository: FitnessRepository,
    wechatReminderRepository: WechatReminderRepository,
    weatherRepository: WeatherRepository,
    locationProvider: DeviceLocationProvider,
    coroutineScope: CoroutineScope,
    onWeatherUpdated: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val todayEpochDay = LocalDate.now().toEpochDay()
    var ledgerEntries by remember { mutableStateOf(ledgerRepository.getEntries()) }
    var reminders by remember { mutableStateOf(reminderRepository.getReminders()) }
    var fitnessRecord by remember {
        mutableStateOf(fitnessRepository.getTodayRecord(todayEpochDay))
    }
    var pendingWechatCount by remember {
        mutableIntStateOf(wechatReminderRepository.getStatus().pendingNotificationCount)
    }
    var weather by remember { mutableStateOf(weatherRepository.getCached()) }
    var weatherMessage by remember { mutableStateOf("") }
    var isRefreshingWeather by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    /**
     * 重新读取全部不联网的今日数据。
     */
    fun refreshLocalData() {
        ledgerEntries = ledgerRepository.getEntries()
        reminders = reminderRepository.getReminders()
        fitnessRecord = fitnessRepository.getTodayRecord(todayEpochDay)
        pendingWechatCount = wechatReminderRepository.getStatus().pendingNotificationCount
    }

    /**
     * 在已有前台粗略位置权限时执行一次定位和天气查询。
     */
    fun refreshWeather() {
        if (!locationProvider.hasCoarseLocationPermission()) {
            weatherMessage = "需要前台粗略位置权限才能查询当前位置天气"
            return
        }
        isRefreshingWeather = true
        weatherMessage = "正在获取粗略位置和天气…"
        coroutineScope.launch {
            val location = locationProvider.getCurrentApproximateLocation().getOrElse {
                isRefreshingWeather = false
                weatherMessage = "无法取得当前位置，请确认系统定位已开启"
                return@launch
            }
            val result = weatherRepository.refresh(location)
            result.onSuccess { snapshot ->
                weather = snapshot
                weatherMessage = "天气已更新"
                onWeatherUpdated()
            }.onFailure {
                weatherMessage = if (weather != null) {
                    "更新失败，继续显示上次成功天气"
                } else {
                    "天气查询失败，请稍后重试"
                }
            }
            isRefreshingWeather = false
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionDenied = !granted
        if (granted) {
            refreshWeather()
        } else {
            weatherMessage = "位置权限已拒绝，其他今日数据仍可正常使用"
        }
    }

    LaunchedEffect(todayEpochDay) {
        refreshLocalData()
        if (locationProvider.hasCoarseLocationPermission() && weatherRepository.isRefreshDue()) {
            refreshWeather()
        }
    }

    val todayEntries = ledgerEntries.filter { entry -> entry.dateEpochDay == todayEpochDay }
    val incomeCents = todayEntries
        .filter { entry -> entry.type == LedgerType.INCOME }
        .sumOf { entry -> entry.amountCents }
    val expenseCents = todayEntries
        .filter { entry -> entry.type == LedgerType.EXPENSE }
        .sumOf { entry -> entry.amountCents }
    val todayReminders = reminders.filter(::isReminderToday)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text("‹ 返回")
            }
            Text(
                text = "今日总览",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = LocalDate.now().toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            TodayWeatherCard(
                weather = weather,
                message = weatherMessage,
                isRefreshing = isRefreshingWeather,
                hasPermission = locationProvider.hasCoarseLocationPermission(),
                permissionDenied = permissionDenied,
                onRequestPermission = {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                },
                onRefresh = ::refreshWeather
            )
        }

        item {
            TodaySectionCard(
                symbol = "¥",
                title = "今日收支",
                primary = "收入 ¥${formatMoney(incomeCents)} · 支出 ¥${formatMoney(expenseCents)}",
                secondary = if (todayEntries.isEmpty()) "今天还没有账目" else "共 ${todayEntries.size} 条记录"
            )
        }

        item {
            TodaySectionCard(
                symbol = "铃",
                title = "今日提醒",
                primary = if (todayReminders.isEmpty()) "今天没有待提醒事项" else "${todayReminders.size} 项提醒",
                secondary = todayReminders.firstOrNull()?.let { reminder ->
                    "最近：${formatReminderTime(reminder.nextTriggerAtMillis)} ${reminder.content}"
                } ?: "可以在功能中心添加本地通知"
            )
        }

        item {
            TodayFitnessCard(record = fitnessRecord)
        }

        item {
            TodaySectionCard(
                symbol = "微",
                title = "微信消息提醒",
                primary = if (pendingWechatCount > 0) "$pendingWechatCount 条未处理消息" else "没有待处理消息",
                secondary = "状态来自本机通知监听，不读取聊天记录"
            )
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** @return 今日天气卡片。 */
@Composable
private fun TodayWeatherCard(
    weather: WeatherSnapshot?,
    message: String,
    isRefreshing: Boolean,
    hasPermission: Boolean,
    permissionDenied: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "当前位置天气",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "前台粗略定位 · 不保存位置历史",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = weather?.let { snapshot -> weatherSymbol(snapshot.weatherCode) } ?: "天",
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            if (weather != null) {
                Text(
                    text = "${weather.temperatureCelsius.roundToInt()}°  ${weatherDescription(weather.weatherCode)}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "体感 ${weather.apparentTemperatureCelsius.roundToInt()}° · " +
                        "最高 ${weather.maxTemperatureCelsius.roundToInt()}° / " +
                        "最低 ${weather.minTemperatureCelsius.roundToInt()}°"
                )
                Text(
                    text = "湿度 ${weather.relativeHumidityPercent}% · " +
                        "风速 ${weather.windSpeedKmh.roundToInt()} km/h · " +
                        "降水概率 ${weather.precipitationProbabilityPercent}%",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "更新时间 ${formatWeatherTime(weather.fetchedAtMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("授权后可显示当前位置气温、体感、风速和今日降水概率")
            }

            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (isRefreshing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator()
                    Text("正在刷新…")
                }
            } else if (!hasPermission) {
                Button(onClick = onRequestPermission) {
                    Text(if (permissionDenied) "重新申请位置权限" else "允许粗略位置并查询")
                }
            } else {
                OutlinedButton(onClick = onRefresh) {
                    Text("刷新当前位置天气")
                }
            }
        }
    }
}

/** @return 通用今日摘要卡片。 */
@Composable
private fun TodaySectionCard(
    symbol: String,
    title: String,
    primary: String,
    secondary: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, fontWeight = FontWeight.Bold)
                Text(text = primary)
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** @return 今日运动完成度卡片。 */
@Composable
private fun TodayFitnessCard(record: DailyFitnessRecord) {
    val activeItems = record.items.filter { item -> item.count > 0 }
    val primary = if (activeItems.isEmpty()) {
        "今天还没有运动记录"
    } else {
        activeItems.take(MAX_VISIBLE_FITNESS_ITEMS).joinToString(" · ") { item ->
            "${item.name} ${item.count}${item.unit}"
        }
    }
    TodaySectionCard(
        symbol = "动",
        title = "今日运动",
        primary = primary,
        secondary = "完成 ${record.completedTaskCount()}/${record.items.size} 项今日目标"
    )
}

/** @return 是否属于设备当前时区的今天。 */
private fun isReminderToday(reminder: ScheduledReminder): Boolean {
    val date = java.time.Instant.ofEpochMilli(reminder.nextTriggerAtMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return date == LocalDate.now()
}

/** @return 金额分转换为两位小数。 */
private fun formatMoney(cents: Long): String {
    return BigDecimal.valueOf(cents)
        .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        .toPlainString()
}

/** @return 今日提醒时间的时分文本。 */
private fun formatReminderTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
}

/** @return 天气缓存更新时间文本。 */
private fun formatWeatherTime(timestamp: Long): String {
    return SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
}

private const val MAX_VISIBLE_FITNESS_ITEMS = 3
