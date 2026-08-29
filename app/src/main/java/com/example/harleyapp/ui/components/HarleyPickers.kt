package com.example.harleyapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示应用统一风格的Material 3日历弹窗。
 *
 * 使用方法：
 * 页面把[visible]设为true后传入当前日期和可选范围；用户点击“确定”时返回Epoch Day，
 * 点击弹窗外部或“取消”只调用[onDismiss]。Material DatePicker内部使用UTC日期，因此本函数
 * 统一负责Epoch Day与UTC毫秒值转换，避免东八区日期前后偏移一天。
 *
 * @param visible 是否显示日历弹窗。
 * @param title 日历顶部用途标题，例如“选择补记日期”。
 * @param initialEpochDay 初始选中日期，从1970-01-01开始计算的天数。
 * @param minEpochDay 最早可选日期；不限制时使用Long.MIN_VALUE。
 * @param maxEpochDay 最晚可选日期；不限制时使用Long.MAX_VALUE。
 * @param onDismiss 关闭弹窗且不修改业务日期的回调。
 * @param onDateSelected 用户确认后返回所选Epoch Day的回调。
 *
 * @return 无返回值；[visible]为true时直接输出圆角Material 3日历弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarleyDatePickerDialog(
    visible: Boolean,
    title: String,
    initialEpochDay: Long,
    minEpochDay: Long = Long.MIN_VALUE,
    maxEpochDay: Long = Long.MAX_VALUE,
    onDismiss: () -> Unit,
    onDateSelected: (Long) -> Unit
) {
    if (!visible) {
        return
    }

    val safeMinEpochDay = minOf(minEpochDay, maxEpochDay)
    val safeMaxEpochDay = maxOf(minEpochDay, maxEpochDay)
    val safeInitialEpochDay = initialEpochDay.coerceIn(
        minimumValue = safeMinEpochDay,
        maximumValue = safeMaxEpochDay
    )
    val selectableDates = remember(safeMinEpochDay, safeMaxEpochDay) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val epochDay = utcMillisToEpochDay(utcTimeMillis)
                return epochDay in safeMinEpochDay..safeMaxEpochDay
            }

            override fun isSelectableYear(year: Int): Boolean {
                val firstDayOfYear = LocalDate.of(year, 1, 1).toEpochDay()
                val lastDayOfYear = LocalDate.of(year, 12, 31).toEpochDay()
                return lastDayOfYear >= safeMinEpochDay && firstDayOfYear <= safeMaxEpochDay
            }
        }
    }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = epochDayToUtcMillis(safeInitialEpochDay),
        selectableDates = selectableDates
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let { selectedMillis ->
                        onDateSelected(utcMillisToEpochDay(selectedMillis))
                    }
                    onDismiss()
                }
            ) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
        shape = RoundedCornerShape(30.dp)
    ) {
        DatePicker(
            state = datePickerState,
            title = {
                Text(
                    modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp),
                    text = "📅  $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            headline = {
                val selectedEpochDay = datePickerState.selectedDateMillis?.let(
                    ::utcMillisToEpochDay
                ) ?: safeInitialEpochDay
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    text = LocalDate.ofEpochDay(selectedEpochDay).format(DATE_FORMATTER),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            showModeToggle = true
        )
    }
}

/**
 * 显示应用统一风格的24小时制Material 3时间表盘。
 *
 * 使用方法：
 * 日期已经选定后，把当前小时和分钟传入；用户确认后通过[onTimeSelected]返回新的时分。
 * 本函数只负责时钟界面，不修改日期，也不会自行关闭页面业务对话框。
 *
 * @param visible 是否显示时间选择弹窗。
 * @param title 时间选择用途标题。
 * @param initialHour 初始小时，超出0到23时会自动限制。
 * @param initialMinute 初始分钟，超出0到59时会自动限制。
 * @param onDismiss 关闭弹窗且不修改时间的回调。
 * @param onTimeSelected 用户确认后返回24小时制小时和分钟的回调。
 *
 * @return 无返回值；[visible]为true时输出带表盘的圆角时间弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarleyTimePickerDialog(
    visible: Boolean,
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onTimeSelected: (hour: Int, minute: Int) -> Unit
) {
    if (!visible) {
        return
    }

    val timePickerState = rememberTimePickerState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = initialMinute.coerceIn(0, 59),
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "⏱  $title",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "%02d:%02d".format(timePickerState.hour, timePickerState.minute),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onTimeSelected(timePickerState.hour, timePickerState.minute)
                    onDismiss()
                }
            ) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        },
        shape = RoundedCornerShape(30.dp)
    )
}

/**
 * 把Epoch Day转换为Material DatePicker使用的UTC零点毫秒值。
 *
 * @param epochDay 从1970-01-01开始计算的日期天数。
 *
 * @return 对应日期UTC零点的Unix毫秒时间戳。
 */
fun epochDayToUtcMillis(epochDay: Long): Long {
    return LocalDate.ofEpochDay(epochDay)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
}

/**
 * 把Material DatePicker返回的UTC毫秒值转换为Epoch Day。
 *
 * @param utcMillis DatePicker返回的UTC时间戳。
 *
 * @return 对应日历日期从1970-01-01开始计算的天数。
 */
fun utcMillisToEpochDay(utcMillis: Long): Long {
    return Instant.ofEpochMilli(utcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toEpochDay()
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern(
    "yyyy年M月d日 EEEE",
    Locale.CHINA
)
