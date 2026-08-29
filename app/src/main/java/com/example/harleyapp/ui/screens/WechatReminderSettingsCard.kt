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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.MAX_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.MIN_WECHAT_REMINDER_INTERVAL_MINUTES
import com.example.harleyapp.model.WechatReminderSettings
import com.example.harleyapp.model.WechatReminderStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 显示微信未查看消息重复提醒设置卡片。
 *
 * 使用方法：
 * MoreScreen传入仓库配置、匿名运行状态和两项系统权限。用户打开开关、填写提醒间隔并点击
 * “保存提醒设置”后才持久化。原微信通知仍在通知栏时，系统会按间隔持续展示通用提醒。
 *
 * @param settings 已持久化的提醒配置。
 * @param status 当前待查看通知数量和最近提醒时间。
 * @param notificationAccessGranted 是否已授予Android通知使用权，用于观察原微信通知。
 * @param notificationListenerConnected 通知监听服务是否已与Android通知管理器实际连接。
 * @param notificationPermissionGranted 是否允许本应用发布重复提醒通知。
 * @param onSaveSettings 保存完整配置的回调，成功返回true。
 * @param onOpenNotificationAccess 打开Android通知使用权设置页的回调。
 * @param onRequestNotificationPermission 请求本应用通知权限的回调。
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
    onSaveSettings: (WechatReminderSettings) -> Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by remember(settings.enabled) {
        mutableStateOf(settings.enabled)
    }
    var intervalText by remember(settings.intervalMinutes) {
        mutableStateOf(settings.intervalMinutes.toString())
    }
    var feedbackText by remember {
        mutableStateOf("")
    }
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
                        text = "原微信通知仍在通知栏时，按间隔反复提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        feedbackText = ""
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
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = intervalText,
                onValueChange = {
                    intervalText = it.filter(Char::isDigit).take(MAX_INTERVAL_TEXT_LENGTH)
                    feedbackText = ""
                },
                label = {
                    Text(text = "重复提醒间隔/分钟")
                },
                supportingText = {
                    Text(text = "可设置5到1440分钟；默认10分钟")
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

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val intervalMinutes = intervalText.toIntOrNull()
                    feedbackText = when {
                        intervalMinutes == null ||
                            intervalMinutes !in MIN_WECHAT_REMINDER_INTERVAL_MINUTES..
                            MAX_WECHAT_REMINDER_INTERVAL_MINUTES ->
                            "提醒间隔应为5到1440分钟"
                        onSaveSettings(
                            WechatReminderSettings(
                                enabled = enabled,
                                intervalMinutes = intervalMinutes
                            )
                        ) -> when {
                            enabled && !notificationAccessGranted ->
                                "设置已保存；还需要授予通知使用权"
                            enabled && !notificationPermissionGranted ->
                                "设置已保存；还需要允许本应用显示通知"
                            else -> "提醒设置已保存"
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
                        "当前等待查看${status.pendingNotificationCount}条，上次提醒：$lastReminderText"
                    status.pendingNotificationCount > 0 ->
                        "当前等待查看${status.pendingNotificationCount}条，尚未到首次提醒时间。"
                    lastReminderText.isNotBlank() -> "上次提醒：$lastReminderText"
                    else -> "尚无重复提醒记录。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "查看或清除原微信通知后自动停止。支付、通话和系统通知不会反复提醒；锁屏或省电模式下实际时间可能稍晚。",
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
            description = "当前有${status.pendingNotificationCount}条原微信通知仍在通知栏。",
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
private const val STATUS_TONE_NORMAL = 0
private const val STATUS_TONE_ACTIVE = 1
private const val STATUS_TONE_WARNING = 2
