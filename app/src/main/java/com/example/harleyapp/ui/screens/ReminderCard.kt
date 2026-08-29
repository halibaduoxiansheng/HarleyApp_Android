package com.example.harleyapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.ScheduledReminder
import com.example.harleyapp.model.isReminderTriggerInFuture
import com.example.harleyapp.ui.components.HarleyDatePickerDialog
import com.example.harleyapp.ui.components.HarleyTimePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 显示通用定时通知的新增、删除、修改和查询界面。
 *
 * 使用方法：
 * MoreScreen传入仓库查询结果、通知权限状态以及保存和删除回调。用户分别选择日期、时间，
 * 填写内容和重复间隔后保存；列表会显示当前全部计划。点击“编辑”会把原值完整回填，
 * 点击“删除”并确认后会同时由上层取消对应系统Alarm。
 *
 * @param reminders 当前本机保存的全部通用通知计划。
 * @param notificationPermissionGranted 是否允许本应用发布通知。
 * @param exactAlarmPermissionGranted 是否允许本应用使用精确Alarm准时唤醒。
 * @param onRequestNotificationPermission 请求Android通知权限的回调。
 * @param onRequestExactAlarmPermission 打开Android“闹钟和提醒”特殊权限页面的回调。
 * @param onSaveReminder 新增或修改通知计划的回调；数据与Alarm均成功时返回true。
 * @param onDeleteReminder 删除通知计划并取消Alarm的回调；成功时返回true。
 * @param modifier 外部传入的布局修饰器。
 *
 * @return 无返回值，直接输出Compose通知管理卡片。
 */
@Composable
fun ReminderCard(
    reminders: List<ScheduledReminder>,
    notificationPermissionGranted: Boolean,
    exactAlarmPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onSaveReminder: (ScheduledReminder) -> Boolean,
    onDeleteReminder: (Long) -> Boolean,
    modifier: Modifier = Modifier
) {
    val initialDateTime = defaultReminderDateTime()
    var editingReminderId by rememberSaveable {
        mutableLongStateOf(NEW_REMINDER_ID)
    }
    var selectedDateEpochDay by rememberSaveable {
        mutableLongStateOf(initialDateTime.toLocalDate().toEpochDay())
    }
    var selectedHour by rememberSaveable {
        mutableIntStateOf(initialDateTime.hour)
    }
    var selectedMinute by rememberSaveable {
        mutableIntStateOf(initialDateTime.minute)
    }
    var contentText by rememberSaveable {
        mutableStateOf("")
    }
    var repeatIntervalText by rememberSaveable {
        mutableStateOf("0")
    }
    var feedbackText by rememberSaveable {
        mutableStateOf("")
    }
    var pendingDeleteId by rememberSaveable {
        mutableLongStateOf(NEW_REMINDER_ID)
    }
    var showDatePicker by rememberSaveable {
        mutableStateOf(false)
    }
    var showTimePicker by rememberSaveable {
        mutableStateOf(false)
    }
    val selectedDate = LocalDate.ofEpochDay(selectedDateEpochDay)
    val editingReminder = reminders.firstOrNull { reminder ->
        reminder.id == editingReminderId
    }

    if (pendingDeleteId > NEW_REMINDER_ID) {
        AlertDialog(
            onDismissRequest = {
                pendingDeleteId = NEW_REMINDER_ID
            },
            title = {
                Text(text = "删除通知计划？")
            },
            text = {
                Text(text = "删除后将同时取消尚未触发的系统提醒，此操作不能撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val reminderId = pendingDeleteId
                        pendingDeleteId = NEW_REMINDER_ID
                        feedbackText = if (onDeleteReminder(reminderId)) {
                            if (editingReminderId == reminderId) {
                                editingReminderId = NEW_REMINDER_ID
                                resetReminderForm { dateTime ->
                                    selectedDateEpochDay = dateTime.toLocalDate().toEpochDay()
                                    selectedHour = dateTime.hour
                                    selectedMinute = dateTime.minute
                                    contentText = ""
                                    repeatIntervalText = "0"
                                }
                            }
                            "通知计划已删除"
                        } else {
                            "删除失败，请重试"
                        }
                    }
                ) {
                    Text(text = "确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = NEW_REMINDER_ID
                    }
                ) {
                    Text(text = "取消")
                }
            }
        )
    }

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
                text = "定时通知",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "自定义日期、时间和内容；间隔天数设为0时只提醒一次。",
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
                            text = "尚未允许通知，到点后系统无法显示提醒。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onRequestNotificationPermission) {
                            Text(text = "允许通知")
                        }
                    }
                }
            }

            if (!exactAlarmPermissionGranted) {
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
                            text = "尚未允许准时提醒，Android或小米省电策略可能让通知延迟数十分钟。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = onRequestExactAlarmPermission) {
                            Text(text = "允许准时提醒")
                        }
                    }
                }
            }

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
                    Text(text = selectedDate.format(DATE_FORMATTER))
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        showTimePicker = true
                    }
                ) {
                    Text(text = TIME_FORMATTER.format(selectedTime(selectedHour, selectedMinute)))
                }
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = contentText,
                onValueChange = { value ->
                    contentText = value.take(MAX_CONTENT_LENGTH)
                    feedbackText = ""
                },
                label = {
                    Text(text = "通知内容")
                },
                minLines = 2,
                maxLines = 5,
                supportingText = {
                    Text(text = "${contentText.length}/$MAX_CONTENT_LENGTH")
                }
            )

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = repeatIntervalText,
                onValueChange = { value ->
                    if (value.length <= MAX_REPEAT_TEXT_LENGTH && value.all(Char::isDigit)) {
                        repeatIntervalText = value
                        feedbackText = ""
                    }
                },
                label = {
                    Text(text = "重复间隔（天）")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(text = "0表示不重复；例如3表示每隔3天、同一时间提醒")
                }
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val repeatIntervalDays = repeatIntervalText.toIntOrNull()
                    val triggerAtMillis = selectedDate
                        .atTime(selectedHour, selectedMinute)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    val currentTimeMillis = System.currentTimeMillis()
                    feedbackText = when {
                        contentText.trim().isBlank() -> "请填写通知内容"
                        repeatIntervalDays == null || repeatIntervalDays !in 0..MAX_REPEAT_DAYS ->
                            "重复间隔请输入0到${MAX_REPEAT_DAYS}天"
                        !isReminderTriggerInFuture(triggerAtMillis, currentTimeMillis) ->
                            "首次提醒时间必须晚于当前时间"
                        else -> {
                            val oldReminder = editingReminder
                            val success = onSaveReminder(
                                ScheduledReminder(
                                    id = oldReminder?.id ?: NEW_REMINDER_ID,
                                    content = contentText,
                                    nextTriggerAtMillis = triggerAtMillis,
                                    repeatIntervalDays = repeatIntervalDays,
                                    createdAtMillis = oldReminder?.createdAtMillis
                                        ?: System.currentTimeMillis(),
                                    lastTriggeredAtMillis = 0L
                                )
                            )
                            if (success) {
                                editingReminderId = NEW_REMINDER_ID
                                resetReminderForm { dateTime ->
                                    selectedDateEpochDay = dateTime.toLocalDate().toEpochDay()
                                    selectedHour = dateTime.hour
                                    selectedMinute = dateTime.minute
                                    contentText = ""
                                    repeatIntervalText = "0"
                                }
                                if (notificationPermissionGranted) {
                                    if (exactAlarmPermissionGranted) {
                                        "通知计划已保存，将按设定时间准时提醒"
                                    } else {
                                        "计划已保存；请点击“允许准时提醒”避免系统延迟"
                                    }
                                } else {
                                    "计划已保存，请允许通知以免错过提醒"
                                }
                            } else {
                                "计划保存失败，原计划未改变，请重试"
                            }
                        }
                    }
                }
            ) {
                Text(text = if (editingReminder == null) "新增通知" else "保存修改")
            }

            if (editingReminder != null) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        editingReminderId = NEW_REMINDER_ID
                        resetReminderForm { dateTime ->
                            selectedDateEpochDay = dateTime.toLocalDate().toEpochDay()
                            selectedHour = dateTime.hour
                            selectedMinute = dateTime.minute
                            contentText = ""
                            repeatIntervalText = "0"
                        }
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
                text = "通知计划（${reminders.size}）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (reminders.isEmpty()) {
                Text(
                    text = "还没有通知计划。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                reminders.forEach { reminder ->
                    ReminderRow(
                        reminder = reminder,
                        onEdit = {
                            val triggerDateTime = Instant.ofEpochMilli(
                                reminder.nextTriggerAtMillis
                            ).atZone(ZoneId.systemDefault()).toLocalDateTime()
                            editingReminderId = reminder.id
                            selectedDateEpochDay = triggerDateTime.toLocalDate().toEpochDay()
                            selectedHour = triggerDateTime.hour
                            selectedMinute = triggerDateTime.minute
                            contentText = reminder.content
                            repeatIntervalText = reminder.repeatIntervalDays.toString()
                            feedbackText = "正在编辑该通知计划"
                        },
                        onDelete = {
                            pendingDeleteId = reminder.id
                        }
                    )
                }
            }
        }
    }

    HarleyDatePickerDialog(
        visible = showDatePicker,
        title = "选择首次提醒日期",
        initialEpochDay = selectedDateEpochDay,
        minEpochDay = LocalDate.now().toEpochDay(),
        onDismiss = {
            showDatePicker = false
        },
        onDateSelected = { epochDay ->
            selectedDateEpochDay = epochDay
            feedbackText = ""
        }
    )

    HarleyTimePickerDialog(
        visible = showTimePicker,
        title = "选择提醒时间",
        initialHour = selectedHour,
        initialMinute = selectedMinute,
        onDismiss = {
            showTimePicker = false
        },
        onTimeSelected = { hour, minute ->
            selectedHour = hour
            selectedMinute = minute
            feedbackText = ""
        }
    )
}

/**
 * 显示一条通知计划的内容摘要、下次时间、重复规则和编辑删除入口。
 *
 * @param reminder 待显示的通知计划。
 * @param onEdit 把本计划回填到上方表单的回调。
 * @param onDelete 请求删除本计划的回调，上层会先展示确认对话框。
 *
 * @return 无返回值，直接输出Compose计划行。
 */
@Composable
private fun ReminderRow(
    reminder: ScheduledReminder,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val nextDateTime = Instant.ofEpochMilli(reminder.nextTriggerAtMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    val isCompletedOneTime = reminder.repeatIntervalDays == 0 &&
        reminder.lastTriggeredAtMillis > 0L
    val repeatText = if (reminder.repeatIntervalDays == 0) {
        "仅一次"
    } else {
        "每隔${reminder.repeatIntervalDays}天"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = reminder.content,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isCompletedOneTime) "已提醒" else "等待提醒",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = if (isCompletedOneTime) {
                    "原定时间：${nextDateTime.format(DATE_TIME_FORMATTER)}"
                } else {
                    "下次：${nextDateTime.format(DATE_TIME_FORMATTER)}"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = repeatText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
 * 生成表单默认时间，取当前时间一小时后并清除秒和纳秒。
 *
 * @return 适合作为首次提醒默认值的本地日期时间。
 */
private fun defaultReminderDateTime(): LocalDateTime {
    return LocalDateTime.now()
        .plusHours(1)
        .withSecond(0)
        .withNano(0)
}

/**
 * 创建只用于格式化所选时分的LocalDateTime。
 *
 * @param hour 24小时制小时，范围0到23。
 * @param minute 分钟，范围0到59。
 *
 * @return 使用固定日期承载给定时分的LocalDateTime。
 */
private fun selectedTime(hour: Int, minute: Int): LocalDateTime {
    return LocalDate.of(2000, 1, 1).atTime(hour, minute)
}

/**
 * 重置新增或编辑表单。
 *
 * @param applyReset 接收新默认日期时间，并由调用方写回全部Compose状态的回调。
 *
 * @return 无返回值。
 */
private fun resetReminderForm(applyReset: (LocalDateTime) -> Unit) {
    applyReset(defaultReminderDateTime())
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val NEW_REMINDER_ID = 0L
private const val MAX_CONTENT_LENGTH = 1_000
private const val MAX_REPEAT_TEXT_LENGTH = 3
private const val MAX_REPEAT_DAYS = 365
