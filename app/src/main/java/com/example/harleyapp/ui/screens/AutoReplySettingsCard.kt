package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.example.harleyapp.model.AutoReplyCompatibility
import com.example.harleyapp.model.AutoReplySettings
import com.example.harleyapp.model.AutoReplyStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 显示微信定时自动回复的完整本地设置卡片。
 *
 * 使用方法：
 * MoreScreen传入仓库当前配置、兼容状态和通知使用权状态。用户可编辑总开关、时间段、
 * 回复内容、同会话冷却、每日上限和群聊策略，点击“保存设置”后才通过回调持久化。
 *
 * @param settings 已持久化的自动回复配置。
 * @param status 最近一次微信快捷回复兼容性检测与发送统计。
 * @param notificationAccessGranted 是否已经授予系统通知使用权。
 * @param onSaveSettings 保存完整配置的回调，成功返回true。
 * @param onOpenNotificationAccess 打开Android通知使用权设置页的回调。
 * @param modifier 外部传入的布局修饰器。
 *
 * @return 无返回值，直接输出Compose卡片。
 */
@Composable
fun AutoReplySettingsCard(
    settings: AutoReplySettings,
    status: AutoReplyStatus,
    notificationAccessGranted: Boolean,
    onSaveSettings: (AutoReplySettings) -> Boolean,
    onOpenNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enabled by remember(settings.enabled) {
        mutableStateOf(settings.enabled)
    }
    var replyText by remember(settings.replyText) {
        mutableStateOf(settings.replyText)
    }
    var startTimeText by remember(settings.startMinuteOfDay) {
        mutableStateOf(formatMinuteOfDay(settings.startMinuteOfDay))
    }
    var endTimeText by remember(settings.endMinuteOfDay) {
        mutableStateOf(formatMinuteOfDay(settings.endMinuteOfDay))
    }
    var cooldownText by remember(settings.cooldownMinutes) {
        mutableStateOf(settings.cooldownMinutes.toString())
    }
    var dailyLimitText by remember(settings.dailyLimit) {
        mutableStateOf(settings.dailyLimit.toString())
    }
    var replyToGroups by remember(settings.replyToGroups) {
        mutableStateOf(settings.replyToGroups)
    }
    var feedbackText by remember {
        mutableStateOf("")
    }
    val statusPresentation = autoReplyStatusPresentation(
        settings = settings,
        status = status,
        notificationAccessGranted = notificationAccessGranted
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
                        text = "微信定时自动回复",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "只使用通知自带的快捷回复，不模拟点击微信",
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
                value = replyText,
                onValueChange = {
                    replyText = it.take(MAX_REPLY_TEXT_LENGTH)
                    feedbackText = ""
                },
                label = {
                    Text(text = "自动回复内容")
                },
                supportingText = {
                    Text(text = "${replyText.length}/$MAX_REPLY_TEXT_LENGTH")
                },
                minLines = 2,
                maxLines = 4
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = startTimeText,
                    onValueChange = {
                        startTimeText = it.take(MAX_TIME_TEXT_LENGTH)
                        feedbackText = ""
                    },
                    label = {
                        Text(text = "开始 HH:mm")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = endTimeText,
                    onValueChange = {
                        endTimeText = it.take(MAX_TIME_TEXT_LENGTH)
                        feedbackText = ""
                    },
                    label = {
                        Text(text = "结束 HH:mm")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Text(
                text = "支持跨午夜，例如22:00到07:00；开始和结束相同表示全天。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = cooldownText,
                    onValueChange = {
                        cooldownText = it.filter(Char::isDigit).take(MAX_NUMBER_TEXT_LENGTH)
                        feedbackText = ""
                    },
                    label = {
                        Text(text = "同会话冷却/分钟")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = dailyLimitText,
                    onValueChange = {
                        dailyLimitText = it.filter(Char::isDigit).take(MAX_NUMBER_TEXT_LENGTH)
                        feedbackText = ""
                    },
                    label = {
                        Text(text = "每日上限/条")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "允许回复群聊",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "默认关闭；群聊识别依赖微信通知提供的信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = replyToGroups,
                    onCheckedChange = {
                        replyToGroups = it
                        feedbackText = ""
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onOpenNotificationAccess) {
                    Text(
                        text = if (notificationAccessGranted) {
                            "通知使用权已授权"
                        } else {
                            "授予通知使用权"
                        }
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val startMinute = parseMinuteOfDay(startTimeText)
                        val endMinute = parseMinuteOfDay(endTimeText)
                        val cooldown = cooldownText.toIntOrNull()
                        val dailyLimit = dailyLimitText.toIntOrNull()

                        feedbackText = when {
                            replyText.trim().isBlank() -> "回复内容不能为空"
                            startMinute == null || endMinute == null -> "时间格式应为HH:mm，例如09:30"
                            cooldown == null || cooldown !in MIN_COOLDOWN_MINUTES..MAX_COOLDOWN_MINUTES ->
                                "冷却时间应为5到1440分钟"
                            dailyLimit == null || dailyLimit !in MIN_DAILY_LIMIT..MAX_DAILY_LIMIT ->
                                "每日上限应为1到200条"
                            onSaveSettings(
                                AutoReplySettings(
                                    enabled = enabled,
                                    replyText = replyText.trim(),
                                    startMinuteOfDay = startMinute,
                                    endMinuteOfDay = endMinute,
                                    cooldownMinutes = cooldown,
                                    dailyLimit = dailyLimit,
                                    replyToGroups = replyToGroups
                                )
                            ) -> if (enabled && !notificationAccessGranted) {
                                "设置已保存；还需要授予通知使用权"
                            } else {
                                "设置已保存"
                            }
                            else -> "保存失败，请重试"
                        }
                    }
                ) {
                    Text(text = "保存设置")
                }
            }

            if (feedbackText.isNotBlank()) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val lastReplyText = formatStatusTime(status.lastReplyAtMillis)
            Text(
                text = if (lastReplyText.isBlank()) {
                    "今日已自动回复 ${status.repliesToday} 条，尚无成功发送记录。"
                } else {
                    "今日已自动回复 ${status.repliesToday} 条，上次成功：$lastReplyText"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "微信语音/视频通话、支付通知、系统通知始终跳过。同一联系人默认冷却30分钟，防止重复回复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 自动回复状态在界面中的标题、说明和色彩类型。
 *
 * @param title 状态标题。
 * @param description 用户可理解的状态说明。
 * @param tone 0为普通、1为成功、2为提醒。
 */
private data class AutoReplyStatusPresentation(
    val title: String,
    val description: String,
    val tone: Int
) {
    /**
     * 根据状态色彩类型读取当前Material主题容器色。
     *
     * @return 适合作为状态Surface背景的颜色。
     */
    @Composable
    fun containerColor() = when (tone) {
        STATUS_TONE_SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        STATUS_TONE_WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
}

/**
 * 将自动回复设置、系统授权和兼容检测合并为单一界面状态。
 *
 * @param settings 当前保存配置。
 * @param status 最近兼容性状态。
 * @param notificationAccessGranted 系统通知使用权状态。
 *
 * @return 用于状态Surface的标题、说明和色彩类型。
 */
private fun autoReplyStatusPresentation(
    settings: AutoReplySettings,
    status: AutoReplyStatus,
    notificationAccessGranted: Boolean
): AutoReplyStatusPresentation {
    return when {
        !settings.enabled -> AutoReplyStatusPresentation(
            title = "自动回复未启用",
            description = "设置时间和内容后打开开关并保存。",
            tone = STATUS_TONE_NORMAL
        )
        !notificationAccessGranted -> AutoReplyStatusPresentation(
            title = "等待通知使用权",
            description = "Android未授权时无法读取微信通知或调用快捷回复。",
            tone = STATUS_TONE_WARNING
        )
        status.compatibility == AutoReplyCompatibility.SUPPORTED -> AutoReplyStatusPresentation(
            title = "已检测到快捷回复支持",
            description = "最近一条符合条件的微信通知包含系统快捷回复入口。",
            tone = STATUS_TONE_SUCCESS
        )
        status.compatibility == AutoReplyCompatibility.NO_REPLY_ACTION -> AutoReplyStatusPresentation(
            title = "最近通知不支持快捷回复",
            description = "请确认微信开启了消息详情通知；不同微信版本可能不提供此入口。",
            tone = STATUS_TONE_WARNING
        )
        status.compatibility == AutoReplyCompatibility.SEND_FAILED -> AutoReplyStatusPresentation(
            title = "最近一次发送失败",
            description = "系统快捷回复入口已失效或被安全策略拒绝，请用新消息再次测试。",
            tone = STATUS_TONE_WARNING
        )
        else -> AutoReplyStatusPresentation(
            title = "等待首条测试消息",
            description = "请让一位联系人发送普通文字消息，App会自动检测当前微信版本。",
            tone = STATUS_TONE_NORMAL
        )
    }
}

/**
 * 把一天内的分钟数格式化为固定HH:mm文本。
 *
 * @param minuteOfDay 从00:00起计算的分钟数。
 *
 * @return 00:00到23:59之间的时间文本。
 */
private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val safeMinute = minuteOfDay.coerceIn(0, 23 * 60 + 59)
    return "%02d:%02d".format(safeMinute / 60, safeMinute % 60)
}

/**
 * 解析用户输入的HH:mm时间。
 *
 * @param value 输入文本，允许一位或两位小时和分钟。
 *
 * @return 从00:00起计算的分钟数；格式或范围不合法时返回null。
 */
private fun parseMinuteOfDay(value: String): Int? {
    val match = TIME_REGEX.matchEntire(value.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) {
        return null
    }
    return hour * 60 + minute
}

/**
 * 将自动回复状态时间戳格式化为本机日期和时间。
 *
 * @param timestampMillis Unix毫秒时间戳，0表示没有记录。
 *
 * @return MM-dd HH:mm文本；没有记录时返回空字符串。
 */
private fun formatStatusTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) {
        return ""
    }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(STATUS_TIME_FORMATTER)
}

private val TIME_REGEX = Regex("^(\\d{1,2}):(\\d{2})$")
private val STATUS_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val MAX_REPLY_TEXT_LENGTH = 200
private const val MAX_TIME_TEXT_LENGTH = 5
private const val MAX_NUMBER_TEXT_LENGTH = 4
private const val MIN_COOLDOWN_MINUTES = 5
private const val MAX_COOLDOWN_MINUTES = 1_440
private const val MIN_DAILY_LIMIT = 1
private const val MAX_DAILY_LIMIT = 200
private const val STATUS_TONE_NORMAL = 0
private const val STATUS_TONE_SUCCESS = 1
private const val STATUS_TONE_WARNING = 2
