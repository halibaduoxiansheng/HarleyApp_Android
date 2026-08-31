package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_NOTIFICATION_COUNT
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_NOTIFICATION_COUNT
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 显示微信未查看消息重复提醒设置卡片。
 *
 * 使用方法：
 * FeatureCenterScreen传入仓库配置、匿名运行状态和两项系统权限。开关和合法间隔会在修改时
 * 立即持久化，用户也可以点击保存按钮再次确认。收到普通微信消息后，页面显示下一次提醒的
 * 秒级倒计时；退出页面、重启Activity或微信撤换原通知都不会清除这轮等待状态。
 *
 * @param settings 已持久化的提醒配置。
 * @param status 当前待查看通知数量和最近提醒时间。
 * @param notificationAccessGranted 是否已授予Android通知使用权，用于观察原微信通知。
 * @param notificationListenerConnected 通知监听服务是否已与Android通知管理器实际连接。
 * @param notificationPermissionGranted 是否允许本应用发布重复提醒通知。
 * @param exactAlarmPermissionGranted 是否允许本应用使用精确Alarm准时结束倒计时。
 * @param notificationBackgroundUnrestricted 是否已允许App忽略系统电池优化。
 * @param onSaveSettings 保存完整配置的回调，成功返回true。
 * @param onOpenNotificationAccess 打开Android通知使用权设置页的回调。
 * @param onRequestNotificationPermission 请求本应用通知权限的回调。
 * @param onRequestExactAlarmPermission 打开Android“闹钟和提醒”特殊权限页的回调。
 * @param onChooseReminderSound 打开HarleyApp微信提醒独立提示音选择器的回调。
 * @param onRequestNotificationBackgroundAccess 请求解除电池后台限制的回调。
 * @param onTestNotificationNow 立即发布声音和振动测试并返回结果文本的回调。
 * @param onScheduleBackgroundNotificationTest 安排10秒后台测试并返回结果文本的回调。
 * @param modifier 外部传入的布局修饰器。
 *
 * @return 无返回值，直接输出Compose卡片。
 */
@Composable
fun WechatReminderSettingsCard(
    settings: WechatReminderSettings,
    status: WechatReminderStatus,
    notificationAccessGranted: Boolean,
    notificationListenerConnected: Boolean,
    notificationPermissionGranted: Boolean,
    exactAlarmPermissionGranted: Boolean,
    notificationBackgroundUnrestricted: Boolean,
    onSaveSettings: (WechatReminderSettings) -> Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onChooseReminderSound: () -> Unit,
    onRequestNotificationBackgroundAccess: () -> Unit,
    onTestNotificationNow: () -> String,
    onScheduleBackgroundNotificationTest: () -> String,
    modifier: Modifier = Modifier
) {
    var enabled by remember(settings.enabled) {
        mutableStateOf(settings.enabled)
    }
    var intervalText by remember(settings.intervalMinutes) {
        mutableStateOf(settings.intervalMinutes.toString())
    }
    var notificationCountText by remember(settings.notificationCount) {
        mutableStateOf(settings.notificationCount.toString())
    }
    var feedbackText by remember {
        mutableStateOf("")
    }
    var currentTimeMillis by remember {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(status.nextReminderAtMillis) {
        while (status.nextReminderAtMillis > 0L) {
            currentTimeMillis = System.currentTimeMillis()
            if (currentTimeMillis >= status.nextReminderAtMillis) {
                break
            }
            delay(COUNTDOWN_REFRESH_INTERVAL_MILLIS)
        }
    }
    val countdownText = formatCountdown(
        nextReminderAtMillis = status.nextReminderAtMillis,
        currentTimeMillis = currentTimeMillis
    )
    val statusPresentation = reminderStatusPresentation(
        settings = settings,
        status = status,
        notificationAccessGranted = notificationAccessGranted,
        notificationListenerConnected = notificationListenerConnected,
        notificationPermissionGranted = notificationPermissionGranted
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "微信未查看消息提醒",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "收到微信消息后倒计时，到点由本App通知你查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = { requestedEnabled ->
                        val intervalMinutes = intervalText.toIntOrNull()
                            ?.takeIf { value ->
                                value in MIN_WECHAT_REMINDER_INTERVAL_MINUTES..
                                    MAX_WECHAT_REMINDER_INTERVAL_MINUTES
                            }
                            ?: settings.intervalMinutes
                        val success = onSaveSettings(
                            WechatReminderSettings(
                                enabled = requestedEnabled,
                                intervalMinutes = intervalMinutes,
                                notificationCount = settings.notificationCount
                            )
                        )
                        if (success) {
                            enabled = requestedEnabled
                            feedbackText = if (requestedEnabled) {
                                "已启用并自动保存"
                            } else {
                                "已关闭并自动保存"
                            }
                        } else {
                            feedbackText = "保存失败，开关未修改"
                        }
                    }
                )
            }

            Surface(
                color = statusPresentation.containerColor(),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = statusPresentation.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusPresentation.description,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (status.pendingNotificationCount > 0) {
                        Text(
                            text = "距离本App提醒：$countdownText",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!exactAlarmPermissionGranted) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "请允许准时提醒，否则小米省电策略可能延迟倒计时通知。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onRequestExactAlarmPermission) {
                            Text(text = "允许")
                        }
                    }
                }
            }

            if (!notificationBackgroundUnrestricted) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "系统仍可能限制后台。建议允许忽略电池优化，并在小米应用详情中开启自启动。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onRequestNotificationBackgroundAccess) {
                            Text(text = "解除限制")
                        }
                    }
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = intervalText,
                onValueChange = { value ->
                    val filteredValue = value.filter(Char::isDigit)
                        .take(MAX_INTERVAL_TEXT_LENGTH)
                    intervalText = filteredValue
                    val intervalMinutes = filteredValue.toIntOrNull()
                    if (intervalMinutes != null &&
                        intervalMinutes in MIN_WECHAT_REMINDER_INTERVAL_MINUTES..
                        MAX_WECHAT_REMINDER_INTERVAL_MINUTES
                    ) {
                        feedbackText = if (
                            onSaveSettings(
                                WechatReminderSettings(
                                    enabled = enabled,
                                    intervalMinutes = intervalMinutes,
                                    notificationCount = settings.notificationCount
                                )
                            )
                        ) {
                            "提醒间隔已自动保存"
                        } else {
                            "间隔保存失败，请点击下方按钮重试"
                        }
                    } else {
                        feedbackText = "输入1到1440分钟后会自动保存"
                    }
                },
                label = {
                    Text(text = "等待及重复间隔/分钟")
                },
                supportingText = {
                    Text(text = "可设置1到1440分钟；默认10分钟")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = notificationCountText,
                onValueChange = { value ->
                    val filteredValue = value.filter(Char::isDigit)
                        .take(MAX_NOTIFICATION_COUNT_TEXT_LENGTH)
                    notificationCountText = filteredValue
                    val notificationCount = filteredValue.toIntOrNull()
                    if (notificationCount != null &&
                        notificationCount in MIN_WECHAT_REMINDER_NOTIFICATION_COUNT..
                        MAX_WECHAT_REMINDER_NOTIFICATION_COUNT
                    ) {
                        feedbackText = if (
                            onSaveSettings(
                                WechatReminderSettings(
                                    enabled = enabled,
                                    intervalMinutes = intervalText.toIntOrNull()
                                        ?.coerceIn(
                                            MIN_WECHAT_REMINDER_INTERVAL_MINUTES,
                                            MAX_WECHAT_REMINDER_INTERVAL_MINUTES
                                        ) ?: settings.intervalMinutes,
                                    notificationCount = notificationCount
                                )
                            )
                        ) {
                            "通知次数已自动保存"
                        } else {
                            "通知次数保存失败，请点击下方按钮重试"
                        }
                    } else {
                        feedbackText = "通知次数请输入0到20"
                    }
                },
                label = {
                    Text(text = "每轮通知次数")
                },
                supportingText = {
                    Text(text = "1表示只通知一次；2到20为固定次数；0表示持续重复")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onOpenNotificationAccess
                ) {
                    Text(
                        text = if (notificationAccessGranted) {
                            if (notificationListenerConnected) {
                                "通知监听已连接"
                            } else {
                                "重新连接通知监听"
                            }
                        } else {
                            "授予通知使用权"
                        }
                    )
                }

                if (!notificationPermissionGranted) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = onRequestNotificationPermission
                    ) {
                        Text(text = "允许提醒通知")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onChooseReminderSound
                ) {
                    Text(text = "选择App提示音")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onRequestNotificationBackgroundAccess
                ) {
                    Text(
                        text = if (notificationBackgroundUnrestricted) {
                            "后台已放行"
                        } else {
                            "后台运行设置"
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        feedbackText = onTestNotificationNow()
                    }
                ) {
                    Text(text = "立即测试提示音")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        feedbackText = onScheduleBackgroundNotificationTest()
                    }
                ) {
                    Text(text = "10秒后台测试")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val intervalMinutes = intervalText.toIntOrNull()
                    val notificationCount = notificationCountText.toIntOrNull()
                    feedbackText = when {
                        intervalMinutes == null ||
                            intervalMinutes !in MIN_WECHAT_REMINDER_INTERVAL_MINUTES..
                            MAX_WECHAT_REMINDER_INTERVAL_MINUTES ->
                            "提醒间隔应为1到1440分钟"
                        notificationCount == null ||
                            notificationCount !in MIN_WECHAT_REMINDER_NOTIFICATION_COUNT..
                            MAX_WECHAT_REMINDER_NOTIFICATION_COUNT ->
                            "通知次数应为0到20；0表示持续重复"
                        onSaveSettings(
                            WechatReminderSettings(
                                enabled = enabled,
                                intervalMinutes = intervalMinutes,
                                notificationCount = notificationCount
                            )
                        ) -> when {
                            enabled && !notificationAccessGranted ->
                                "设置已保存；还需要授予通知使用权"
                            enabled && !notificationPermissionGranted ->
                                "设置已保存；还需要允许本应用显示通知"
                        else -> "提醒设置已保存，退出页面后仍会保留"
                        }
                        else -> "保存失败，请重试"
                    }
                }
            ) {
                Text(text = "保存提醒设置")
            }

            if (feedbackText.isNotBlank()) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val lastReminderText = formatReminderTime(status.lastReminderAtMillis)
            Text(
                text = when {
                    status.pendingNotificationCount > 0 && lastReminderText.isNotBlank() ->
                        "当前等待提醒${status.pendingNotificationCount}条，上次提醒：$lastReminderText"
                    status.pendingNotificationCount > 0 ->
                        "当前等待提醒${status.pendingNotificationCount}条，本轮已通知${status.notificationsShownInCycle}次。"
                    lastReminderText.isNotBlank() -> "上次提醒：$lastReminderText"
                    else -> "尚无重复提醒记录。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "支付、通话和系统通知不会进入等待提醒。每轮倒计时结束后只发一条本App通知；新的普通微信消息会重新开始倒计时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 微信未查看提醒状态在界面中的标题、说明和色彩类型。
 *
 * @param title 状态标题。
 * @param description 用户可理解的状态说明。
 * @param tone 0为普通、1为运行中、2为提醒处理权限。
 */
private data class WechatReminderStatusPresentation(
    val title: String,
    val description: String,
    val tone: Int
) {
    /**
     * 根据状态类型读取当前Material主题容器色。
     *
     * @return 适合作为状态Surface背景的颜色。
     */
    @Composable
    fun containerColor() = when (tone) {
        STATUS_TONE_ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        STATUS_TONE_WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

/**
 * 将配置、匿名运行状态和系统权限合并为单一界面状态。
 *
 * @param settings 当前保存配置。
 * @param status 当前待查看通知状态。
 * @param notificationAccessGranted 是否已授予通知使用权。
 * @param notificationListenerConnected 通知监听服务是否已经实际在线。
 * @param notificationPermissionGranted 是否允许本应用发布通知。
 *
 * @return 用于状态Surface的标题、说明和色彩类型。
 */
private fun reminderStatusPresentation(
    settings: WechatReminderSettings,
    status: WechatReminderStatus,
    notificationAccessGranted: Boolean,
    notificationListenerConnected: Boolean,
    notificationPermissionGranted: Boolean
): WechatReminderStatusPresentation {
    return when {
        !settings.enabled -> WechatReminderStatusPresentation(
            title = "重复提醒未启用",
            description = "打开开关、设置间隔并保存后开始监听新的普通微信消息。",
            tone = STATUS_TONE_NORMAL
        )
        !notificationAccessGranted -> WechatReminderStatusPresentation(
            title = "等待通知使用权",
            description = "Android未授权时无法判断原微信通知是否仍在通知栏。",
            tone = STATUS_TONE_WARNING
        )
        !notificationListenerConnected -> WechatReminderStatusPresentation(
            title = "通知监听尚未连接",
            description = "请点“重新连接通知监听”，在系统页面关闭后再开启一次通知使用权。",
            tone = STATUS_TONE_WARNING
        )
        !notificationPermissionGranted -> WechatReminderStatusPresentation(
            title = "等待提醒通知权限",
            description = "需要允许本应用显示通知，才能发出重复提醒。",
            tone = STATUS_TONE_WARNING
        )
        status.pendingNotificationCount > 0 -> WechatReminderStatusPresentation(
            title = "正在等待你查看微信",
            description = "配置和倒计时已保存，退出本页面也会继续等待。",
            tone = STATUS_TONE_ACTIVE
        )
        else -> WechatReminderStatusPresentation(
            title = "等待新的微信消息",
            description = "收到普通聊天通知后，将从最新消息重新计算提醒间隔。",
            tone = STATUS_TONE_NORMAL
        )
    }
}

/**
 * 把下一次提醒时间转换为适合实时展示的时分秒倒计时。
 *
 * @param nextReminderAtMillis 下一次提醒的Unix毫秒时间戳；0表示没有计划。
 * @param currentTimeMillis 当前Unix毫秒时间戳，由页面每秒刷新一次。
 *
 * @return HH:mm:ss格式的剩余时间；时间已到返回“即将发送”，没有计划返回“尚未开始”。
 */
private fun formatCountdown(
    nextReminderAtMillis: Long,
    currentTimeMillis: Long
): String {
    if (nextReminderAtMillis <= 0L) {
        return "尚未开始"
    }

    val remainingMillis = nextReminderAtMillis - currentTimeMillis
    if (remainingMillis <= 0L) {
        return "即将发送"
    }

    val totalSeconds = (remainingMillis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/**
 * 将提醒时间戳格式化为本机月日和时间。
 *
 * @param timestampMillis Unix毫秒时间戳，0表示没有记录。
 *
 * @return MM-dd HH:mm文本；没有记录时返回空字符串。
 */
private fun formatReminderTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) {
        return ""
    }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(REMINDER_TIME_FORMATTER)
}

private val REMINDER_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val MAX_INTERVAL_TEXT_LENGTH = 4
private const val MAX_NOTIFICATION_COUNT_TEXT_LENGTH = 2
private const val STATUS_TONE_NORMAL = 0
private const val STATUS_TONE_ACTIVE = 1
private const val STATUS_TONE_WARNING = 2
private const val COUNTDOWN_REFRESH_INTERVAL_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE
