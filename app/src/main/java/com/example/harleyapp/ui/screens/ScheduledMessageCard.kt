package com.example.harleyapp.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.ScheduledWechatMessage
import com.example.harleyapp.ui.components.HarleyDatePickerDialog
import com.example.harleyapp.ui.components.HarleyTimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * 显示微信图文定时发送助手的创建表单和本机计划列表。
 *
 * 使用方法：
 * MoreScreen传入仓库计划、通知权限和保存删除回调。图片由系统OpenDocument选择器授予长期只读权限；
 * 到点后只提醒用户点击并打开微信分享确认，不会在后台自动选择联系人或发送。
 *
 * @param messages 本机保存的全部图文提醒计划。
 * @param notificationPermissionGranted 是否已经允许本应用显示通知。
 * @param onRequestNotificationPermission 请求Android通知权限的回调。
 * @param onSaveMessage 新增或更新计划的回调，成功返回true。
 * @param onDeleteMessage 删除计划及对应Alarm的回调，成功返回true。
 * @param modifier 外部传入的布局修饰器。
 *
 * @return 无返回值，直接输出Compose卡片。
 */
@Composable
fun ScheduledMessageCard(
    messages: List<ScheduledWechatMessage>,
    notificationPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onSaveMessage: (ScheduledWechatMessage) -> Boolean,
    onDeleteMessage: (Long) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editingMessage by remember {
        mutableStateOf<ScheduledWechatMessage?>(null)
    }
    var contactNote by remember {
        mutableStateOf("")
    }
    var scheduledTimeText by remember {
        mutableStateOf(defaultScheduledTimeText())
    }
    var messageText by remember {
        mutableStateOf("")
    }
    var imageUriText by remember {
        mutableStateOf("")
    }
    var feedbackText by remember {
        mutableStateOf("")
    }
    var showDatePicker by remember {
        mutableStateOf(false)
    }
    var showTimePicker by remember {
        mutableStateOf(false)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        val permissionSaved = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        if (permissionSaved) {
            imageUriText = uri.toString()
            feedbackText = "图片已选择，将仅保留只读访问权限"
        } else {
            feedbackText = "无法长期读取该图片，请换一张或换一个相册来源"
        }
    }
    val visibleMessages = messages
        .sortedByDescending { it.scheduledAtMillis }
        .take(MAX_VISIBLE_MESSAGES)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "微信图文定时发送助手",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "到点提醒并准备图文，点击提醒后仍需在微信中核对联系人并确认发送。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!notificationPermissionGranted) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = "尚未允许提醒通知，到点后可能没有提示。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onRequestNotificationPermission) {
                            Text(text = "允许通知")
                        }
                    }
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = contactNote,
                onValueChange = {
                    contactNote = it.take(MAX_CONTACT_LENGTH)
                    feedbackText = ""
                },
                label = {
                    Text(text = "联系人/群聊备注（用于核对）")
                },
                singleLine = true
            )

            val selectedDateTime = parseScheduledDateTime(scheduledTimeText)
                ?: defaultScheduledDateTime()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showDatePicker = true
                    }
                ) {
                    Text(text = selectedDateTime.format(DATE_BUTTON_FORMATTER))
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showTimePicker = true
                    }
                ) {
                    Text(text = selectedDateTime.format(TIME_BUTTON_FORMATTER))
                }
            }
            Text(
                text = "省电模式下可能延迟几分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = messageText,
                onValueChange = {
                    messageText = it.take(MAX_MESSAGE_LENGTH)
                    feedbackText = ""
                },
                label = {
                    Text(text = "消息文字（图片与文字至少填一项）")
                },
                minLines = 2,
                maxLines = 5,
                supportingText = {
                    Text(text = "${messageText.length}/$MAX_MESSAGE_LENGTH")
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        imagePicker.launch(arrayOf("image/*"))
                    }
                ) {
                    Text(text = if (imageUriText.isBlank()) "选择图片" else "更换图片")
                }
                if (imageUriText.isNotBlank()) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = "已选择图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        onClick = {
                            imageUriText = ""
                            feedbackText = "图片已移除"
                        }
                    ) {
                        Text(text = "移除")
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val scheduledAtMillis = parseScheduledTime(scheduledTimeText)
                    feedbackText = when {
                        contactNote.trim().isBlank() -> "请填写联系人或群聊备注，方便发送前核对"
                        scheduledAtMillis == null -> "时间格式不正确，例如2026-08-29 21:30"
                        scheduledAtMillis <= System.currentTimeMillis() + MIN_SCHEDULE_LEAD_MILLIS ->
                            "提醒时间至少要比现在晚1分钟"
                        messageText.trim().isBlank() && imageUriText.isBlank() ->
                            "文字和图片至少需要提供一项"
                        else -> {
                            val oldMessage = editingMessage
                            val success = onSaveMessage(
                                ScheduledWechatMessage(
                                    id = oldMessage?.id ?: 0L,
                                    contactNote = contactNote.trim(),
                                    messageText = messageText.trim(),
                                    scheduledAtMillis = scheduledAtMillis,
                                    imageUri = imageUriText,
                                    createdAtMillis = oldMessage?.createdAtMillis
                                        ?: System.currentTimeMillis(),
                                    reminderShownAtMillis = 0L,
                                    shareOpenedAtMillis = 0L
                                )
                            )
                            if (success) {
                                editingMessage = null
                                contactNote = ""
                                scheduledTimeText = defaultScheduledTimeText()
                                messageText = ""
                                imageUriText = ""
                                if (notificationPermissionGranted) {
                                    "提醒计划已保存"
                                } else {
                                    "计划已保存，请允许通知以免错过提醒"
                                }
                            } else {
                                "计划保存失败，请重试"
                            }
                        }
                    }
                }
            ) {
                Text(text = if (editingMessage == null) "添加图文提醒" else "保存修改")
            }

            if (editingMessage != null) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        editingMessage = null
                        contactNote = ""
                        scheduledTimeText = defaultScheduledTimeText()
                        messageText = ""
                        imageUriText = ""
                        feedbackText = "已取消编辑"
                    }
                ) {
                    Text(text = "取消编辑")
                }
            }

            if (feedbackText.isNotBlank()) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            HorizontalDivider()

            Text(
                text = "提醒计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (visibleMessages.isEmpty()) {
                Text(
                    text = "还没有图文提醒计划。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                visibleMessages.forEach { message ->
                    ScheduledMessageRow(
                        message = message,
                        onEdit = {
                            editingMessage = message
                            contactNote = message.contactNote
                            scheduledTimeText = formatScheduledTime(message.scheduledAtMillis)
                            messageText = message.messageText
                            imageUriText = message.imageUri
                            feedbackText = "正在编辑“${message.contactNote}”的提醒"
                        },
                        onDelete = {
                            feedbackText = if (onDeleteMessage(message.id)) {
                                if (editingMessage?.id == message.id) {
                                    editingMessage = null
                                }
                                "提醒计划已删除"
                            } else {
                                "删除失败，请重试"
                            }
                        }
                    )
                }
                if (messages.size > MAX_VISIBLE_MESSAGES) {
                    Text(
                        text = "仅显示最近${MAX_VISIBLE_MESSAGES}条计划，可删除旧计划后继续管理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    val pickerDateTime = parseScheduledDateTime(scheduledTimeText)
        ?: defaultScheduledDateTime()
    HarleyDatePickerDialog(
        visible = showDatePicker,
        title = "选择微信发送提醒日期",
        initialEpochDay = pickerDateTime.toLocalDate().toEpochDay(),
        minEpochDay = LocalDate.now().toEpochDay(),
        onDismiss = {
            showDatePicker = false
        },
        onDateSelected = { epochDay ->
            scheduledTimeText = LocalDate.ofEpochDay(epochDay)
                .atTime(pickerDateTime.toLocalTime())
                .format(SCHEDULE_FORMATTER)
            feedbackText = ""
        }
    )

    HarleyTimePickerDialog(
        visible = showTimePicker,
        title = "选择微信发送提醒时间",
        initialHour = pickerDateTime.hour,
        initialMinute = pickerDateTime.minute,
        onDismiss = {
            showTimePicker = false
        },
        onTimeSelected = { hour, minute ->
            scheduledTimeText = pickerDateTime.toLocalDate()
                .atTime(hour, minute)
                .format(SCHEDULE_FORMATTER)
            feedbackText = ""
        }
    )
}

/**
 * 显示一条图文提醒计划的摘要、状态以及编辑删除入口。
 *
 * @param message 待显示计划。
 * @param onEdit 将计划载入上方编辑表单的回调。
 * @param onDelete 删除计划的回调。
 *
 * @return 无返回值，直接输出计划行。
 */
@Composable
private fun ScheduledMessageRow(
    message: ScheduledWechatMessage,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val statusText = when {
        message.shareOpenedAtMillis > 0L -> "已打开微信"
        message.reminderShownAtMillis > 0L -> "已提醒"
        message.scheduledAtMillis <= System.currentTimeMillis() -> "已过期"
        else -> "等待提醒"
    }
    val contentType = when {
        message.imageUri.isNotBlank() && message.messageText.isNotBlank() -> "图文"
        message.imageUri.isNotBlank() -> "图片"
        else -> "文字"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = message.contactNote,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$contentType · $statusText",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = formatScheduledTime(message.scheduledAtMillis),
                style = MaterialTheme.typography.bodySmall
            )
            if (message.messageText.isNotBlank()) {
                Text(
                    text = message.messageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Text(text = "编辑")
                }
                TextButton(onClick = onDelete) {
                    Text(text = "删除")
                }
            }
        }
    }
}

/**
 * 生成默认计划时间，取当前时间一小时后并清除秒和纳秒。
 *
 * @return yyyy-MM-dd HH:mm格式的本地时间文本。
 */
private fun defaultScheduledTimeText(): String {
    return defaultScheduledDateTime()
        .format(SCHEDULE_FORMATTER)
}

/**
 * 生成默认计划日期时间对象，便于日期和时间选择器分别更新其中一部分。
 *
 * @return 当前时间一小时后且秒、纳秒均为0的LocalDateTime。
 */
private fun defaultScheduledDateTime(): LocalDateTime {
    return LocalDateTime.now()
        .plusHours(1)
        .withSecond(0)
        .withNano(0)
}

/**
 * 把表单内部日期时间文本解析为可供Material选择器回填的LocalDateTime。
 *
 * @param value yyyy-MM-dd HH:mm格式文本。
 *
 * @return 合法日期时间；格式或日历值无效时返回null。
 */
private fun parseScheduledDateTime(value: String): LocalDateTime? {
    return try {
        LocalDateTime.parse(value.trim(), SCHEDULE_FORMATTER)
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * 解析本地计划时间并转换为Unix毫秒时间戳。
 *
 * @param value yyyy-MM-dd HH:mm格式的用户输入。
 *
 * @return 合法时间戳；格式或日历日期无效时返回null。
 */
private fun parseScheduledTime(value: String): Long? {
    return parseScheduledDateTime(value)
        ?.let { dateTime ->
            dateTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        }
}

/**
 * 将计划时间戳格式化为用户可继续编辑的本地时间文本。
 *
 * @param timestampMillis Unix毫秒时间戳。
 *
 * @return yyyy-MM-dd HH:mm格式文本。
 */
private fun formatScheduledTime(timestampMillis: Long): String {
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(SCHEDULE_FORMATTER)
}

private val SCHEDULE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)
private val DATE_BUTTON_FORMATTER = DateTimeFormatter.ofPattern("M月d日")
private val TIME_BUTTON_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private const val MAX_CONTACT_LENGTH = 60
private const val MAX_MESSAGE_LENGTH = 1_000
private const val MAX_VISIBLE_MESSAGES = 10
private const val MIN_SCHEDULE_LEAD_MILLIS = 60_000L
