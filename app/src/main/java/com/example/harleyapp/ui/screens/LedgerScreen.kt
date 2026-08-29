package com.example.harleyapp.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.BillImportResult
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerSource
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.WechatCapture
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val expenseCategories = listOf("餐饮", "交通", "购物", "居住", "娱乐", "其他")
private val incomeCategories = listOf("工资", "奖金", "理财", "红包", "其他")
private val transferCategories = listOf("转账", "提现", "充值", "零钱通", "其他")

/**
 * 显示本地账本、本月汇总和账目增删改入口。
 *
 * 使用方法：
 * 由HarleyApp传入最新账目列表以及持久化回调。保存或删除成功后，父页面应重新读取列表。
 *
 * @param modifier 外部传入的页面安全边距。
 * @param entries 当前全部账目。
 * @param onSaveEntry 新增或编辑账目的同步保存回调，成功返回true。
 * @param onDeleteEntry 根据账目id删除的同步回调，成功返回true。
 * @param pendingCaptures 需要用户补充金额或方向的微信支付通知。
 * @param notificationAccessGranted 是否已经授予Android通知使用权。
 * @param onOpenNotificationAccess 打开系统通知使用权页面回调。
 * @param onImportWechatBill 导入微信官方账单文件的挂起回调。
 * @param onIgnoreWechatCapture 忽略并删除待确认通知的同步回调。
 *
 * @return 无返回值，直接输出账本页面。
 */
@Composable
fun LedgerScreen(
    entries: List<LedgerEntry>,
    onSaveEntry: (LedgerEntry) -> Boolean,
    onDeleteEntry: (Long) -> Boolean,
    pendingCaptures: List<WechatCapture>,
    notificationAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onImportWechatBill: suspend (Uri) -> BillImportResult,
    onIgnoreWechatCapture: (WechatCapture) -> Boolean,
    modifier: Modifier = Modifier
) {
    var showEditor by remember {
        mutableStateOf(false)
    }
    var editingEntry by remember {
        mutableStateOf<LedgerEntry?>(null)
    }
    var pendingDelete by remember {
        mutableStateOf<LedgerEntry?>(null)
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = 20.dp,
                end = 20.dp,
                bottom = 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "我的账本",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "数据仅保存在当前手机",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                MonthlySummaryCard(entries = entries)
            }

            item {
                WechatLedgerToolsCard(
                    entries = entries,
                    pendingCaptures = pendingCaptures,
                    notificationAccessGranted = notificationAccessGranted,
                    onOpenNotificationAccess = onOpenNotificationAccess,
                    onImportBill = onImportWechatBill,
                    onReviewCapture = { capture ->
                        val receivedAtMillis = capture.receivedAtMillis
                            .takeIf { it > 0L }
                            ?: System.currentTimeMillis()
                        editingEntry = LedgerEntry(
                            id = 0L,
                            type = capture.suggestedType ?: LedgerType.EXPENSE,
                            amountCents = capture.parsedAmountCents ?: 0L,
                            category = capture.suggestedCategory.ifBlank { "微信支付" },
                            note = "微信通知待确认",
                            dateEpochDay = Instant.ofEpochMilli(receivedAtMillis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                                .toEpochDay(),
                            createdAtMillis = receivedAtMillis,
                            source = LedgerSource.WECHAT_NOTIFICATION,
                            rawText = "${capture.title} | ${capture.content}".trim(' ', '|'),
                            externalKey = capture.externalKey
                        )
                        showEditor = true
                    },
                    onIgnoreCapture = onIgnoreWechatCapture
                )
            }

            item {
                Text(
                    text = "全部记录",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (entries.isEmpty()) {
                item {
                    EmptyLedgerCard()
                }
            } else {
                items(
                    items = entries,
                    key = { it.id }
                ) { entry ->
                    LedgerEntryCard(
                        entry = entry,
                        onEdit = {
                            editingEntry = entry
                            showEditor = true
                        },
                        onDelete = {
                            pendingDelete = entry
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .semantics {
                    contentDescription = "新增账目"
                },
            onClick = {
                editingEntry = null
                showEditor = true
            }
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (showEditor) {
        LedgerEditorDialog(
            entry = editingEntry,
            onDismiss = {
                showEditor = false
                editingEntry = null
            },
            onSave = onSaveEntry
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = {
                pendingDelete = null
            },
            title = {
                Text(text = "删除这条记录？")
            },
            text = {
                Text(text = "${entry.category} · ${formatMoney(entry.amountCents)}，删除后无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onDeleteEntry(entry.id)) {
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(text = "删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                    }
                ) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/**
 * 统计并显示当前月份的收入、支出与结余。
 *
 * @param entries 当前全部账目。
 *
 * @return 无返回值。
 */
@Composable
private fun MonthlySummaryCard(entries: List<LedgerEntry>) {
    val currentMonth = remember {
        YearMonth.now()
    }
    val monthlyEntries = remember(entries, currentMonth) {
        entries.filter { entry ->
            YearMonth.from(LocalDate.ofEpochDay(entry.dateEpochDay)) == currentMonth
        }
    }
    val income = monthlyEntries
        .filter { it.type == LedgerType.INCOME }
        .sumOf { it.amountCents }
    val expense = monthlyEntries
        .filter { it.type == LedgerType.EXPENSE }
        .sumOf { it.amountCents }
    val balance = income - expense

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "${currentMonth.monthValue}月结余",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
            )
            Text(
                text = formatSignedMoney(balance),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                SummaryValue(
                    modifier = Modifier.weight(1f),
                    title = "本月收入",
                    value = formatMoney(income)
                )
                SummaryValue(
                    modifier = Modifier.weight(1f),
                    title = "本月支出",
                    value = formatMoney(expense)
                )
            }
        }
    }
}

/**
 * 显示汇总卡片中的一项金额。
 *
 * @param modifier 外部布局修饰器。
 * @param title 金额说明。
 * @param value 已格式化金额。
 *
 * @return 无返回值。
 */
@Composable
private fun SummaryValue(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/**
 * 显示单条账目，并提供编辑和删除入口。
 *
 * @param entry 要显示的账目。
 * @param onEdit 点击记录主体后的编辑回调。
 * @param onDelete 点击删除按钮后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun LedgerEntryCard(
    entry: LedgerEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val amountColor = when (entry.type) {
        LedgerType.INCOME -> MaterialTheme.colorScheme.primary
        LedgerType.EXPENSE -> MaterialTheme.colorScheme.error
        LedgerType.TRANSFER -> MaterialTheme.colorScheme.tertiary
    }
    val amountPrefix = when (entry.type) {
        LedgerType.INCOME -> "+"
        LedgerType.EXPENSE -> "−"
        LedgerType.TRANSFER -> "↔ "
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = entry.category,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = ledgerSourceLabel(entry.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = buildString {
                        append(formatDate(entry.dateEpochDay))
                        if (entry.counterparty.isNotBlank()) {
                            append(" · ")
                            append(entry.counterparty)
                        }
                        if (entry.note.isNotBlank()) {
                            append(" · ")
                            append(entry.note)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$amountPrefix${formatMoney(entry.amountCents)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )
                TextButton(onClick = onDelete) {
                    Text(text = "删除")
                }
            }
        }
    }
}

/**
 * 显示账本为空时的说明卡片。
 *
 * @return 无返回值。
 */
@Composable
private fun EmptyLedgerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            text = "暂时没有账目\n点击右下角“＋”记录第一笔收支",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 新增或编辑账目的输入对话框。
 *
 * @param entry 编辑目标；新增记录时传null。
 * @param onDismiss 关闭对话框回调。
 * @param onSave 保存回调，成功返回true后对话框才会关闭。
 *
 * @return 无返回值。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedgerEditorDialog(
    entry: LedgerEntry?,
    onDismiss: () -> Unit,
    onSave: (LedgerEntry) -> Boolean
) {
    var typeName by rememberSaveable(entry?.id) {
        mutableStateOf(entry?.type?.name ?: LedgerType.EXPENSE.name)
    }
    var amountText by rememberSaveable(entry?.id) {
        mutableStateOf(entry?.let { formatAmountInput(it.amountCents) }.orEmpty())
    }
    var category by rememberSaveable(entry?.id) {
        mutableStateOf(entry?.category ?: expenseCategories.first())
    }
    var note by rememberSaveable(entry?.id) {
        mutableStateOf(entry?.note.orEmpty())
    }
    var dateEpochDay by rememberSaveable(entry?.id) {
        mutableStateOf(entry?.dateEpochDay ?: LocalDate.now().toEpochDay())
    }
    var errorMessage by rememberSaveable(entry?.id) {
        mutableStateOf("")
    }
    var showDatePicker by rememberSaveable {
        mutableStateOf(false)
    }
    val selectedType = LedgerType.valueOf(typeName)
    val categoryOptions = when (selectedType) {
        LedgerType.INCOME -> incomeCategories
        LedgerType.EXPENSE -> expenseCategories
        LedgerType.TRANSFER -> transferCategories
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    entry == null -> "记一笔"
                    entry.id == 0L && entry.source == LedgerSource.WECHAT_NOTIFICATION ->
                        "确认微信账目"
                    else -> "编辑账目"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LedgerType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = {
                                typeName = type.name
                                val newOptions = when (type) {
                                    LedgerType.INCOME -> incomeCategories
                                    LedgerType.EXPENSE -> expenseCategories
                                    LedgerType.TRANSFER -> transferCategories
                                }
                                if (category !in newOptions) {
                                    category = newOptions.first()
                                }
                            },
                            label = {
                                Text(
                                    text = when (type) {
                                        LedgerType.INCOME -> "收入"
                                        LedgerType.EXPENSE -> "支出"
                                        LedgerType.TRANSFER -> "内部转账"
                                    }
                                )
                            }
                        )
                    }
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = amountText,
                    onValueChange = { newValue ->
                        if (newValue.length <= MAX_AMOUNT_INPUT_LENGTH) {
                            amountText = newValue
                            errorMessage = ""
                        }
                    },
                    label = {
                        Text(text = "金额（元）")
                    },
                    prefix = {
                        Text(text = "¥ ")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = errorMessage.isNotBlank()
                )

                Text(
                    text = "常用分类",
                    style = MaterialTheme.typography.labelLarge
                )

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(categoryOptions) { option ->
                        FilterChip(
                            selected = category == option,
                            onClick = {
                                category = option
                            },
                            label = {
                                Text(text = option)
                            }
                        )
                    }
                }

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = category,
                    onValueChange = {
                        category = it.take(MAX_CATEGORY_LENGTH)
                    },
                    label = {
                        Text(text = "分类")
                    },
                    singleLine = true
                )

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = note,
                    onValueChange = {
                        note = it.take(MAX_NOTE_LENGTH)
                    },
                    label = {
                        Text(text = "备注（可选）")
                    },
                    maxLines = 3
                )

                TextButton(
                    onClick = {
                        showDatePicker = true
                    }
                ) {
                    Text(text = "日期：${formatDate(dateEpochDay)}")
                }

                if (errorMessage.isNotBlank()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amountCents = parseAmountToCents(amountText)

                    when {
                        amountCents == null || amountCents <= 0L -> {
                            errorMessage = "请输入正确金额，最多保留两位小数"
                        }

                        category.isBlank() -> {
                            errorMessage = "请输入账目分类"
                        }

                        else -> {
                            val success = onSave(
                                LedgerEntry(
                                    id = entry?.id ?: 0L,
                                    type = selectedType,
                                    amountCents = amountCents,
                                    category = category.trim(),
                                    note = note.trim(),
                                    dateEpochDay = dateEpochDay,
                                    createdAtMillis = entry?.createdAtMillis
                                        ?: System.currentTimeMillis(),
                                    source = entry?.source ?: LedgerSource.MANUAL,
                                    counterparty = entry?.counterparty.orEmpty(),
                                    rawText = entry?.rawText.orEmpty(),
                                    externalKey = entry?.externalKey.orEmpty()
                                )
                            )

                            if (success) {
                                onDismiss()
                            } else {
                                errorMessage = "保存失败，请重试"
                            }
                        }
                    }
                }
            ) {
                Text(text = "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )

    if (showDatePicker) {
        val datePickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = epochDayToUtcMillis(dateEpochDay)
        )

        DatePickerDialog(
            onDismissRequest = {
                showDatePicker = false
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedMillis ->
                            dateEpochDay = utcMillisToEpochDay(selectedMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(text = "确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDatePicker = false
                    }
                ) {
                    Text(text = "取消")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/**
 * 解析用户输入的元金额为整数分。
 *
 * @param text 用户输入文本，只接受正数且最多两位小数。
 *
 * @return 合法时返回分单位金额，不合法或溢出时返回null。
 */
private fun parseAmountToCents(text: String): Long? {
    val normalized = text.trim()
    if (!normalized.matches(Regex("^\\d{1,9}(\\.\\d{1,2})?$"))) {
        return null
    }

    return runCatching {
        BigDecimal(normalized).movePointRight(2).longValueExact()
    }.getOrNull()
}

/**
 * 把分单位金额转换为编辑框使用的普通数字。
 *
 * @param amountCents 分单位金额。
 *
 * @return 不包含货币符号且移除无意义末尾0的文本。
 */
private fun formatAmountInput(amountCents: Long): String {
    return BigDecimal.valueOf(amountCents, 2)
        .stripTrailingZeros()
        .toPlainString()
}

/**
 * 把分单位金额格式化为人民币文本。
 *
 * @param amountCents 分单位金额，按绝对值显示。
 *
 * @return 类似“¥1,234.56”的文本。
 */
private fun formatMoney(amountCents: Long): String {
    val formatter = NumberFormat.getNumberInstance(Locale.CHINA).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }
    return "¥${formatter.format(BigDecimal.valueOf(kotlin.math.abs(amountCents), 2))}"
}

/**
 * 格式化可能为负数的结余金额。
 *
 * @param amountCents 分单位结余。
 *
 * @return 正数不额外加号，负数显示负号的人民币文本。
 */
private fun formatSignedMoney(amountCents: Long): String {
    return if (amountCents < 0L) {
        "−${formatMoney(amountCents)}"
    } else {
        formatMoney(amountCents)
    }
}

/**
 * 把Epoch Day格式化为中文日期。
 *
 * @param epochDay 从1970-01-01开始的天数。
 *
 * @return “yyyy年M月d日”格式日期。
 */
private fun formatDate(epochDay: Long): String {
    return LocalDate.ofEpochDay(epochDay).format(
        DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    )
}

/**
 * 把Epoch Day转换为Material DatePicker使用的UTC毫秒值。
 *
 * @param epochDay 从1970-01-01开始的天数。
 *
 * @return 对应日期UTC零点的时间戳。
 */
private fun epochDayToUtcMillis(epochDay: Long): Long {
    return LocalDate.ofEpochDay(epochDay)
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
}

/**
 * 把Material DatePicker返回的UTC毫秒值转换为Epoch Day。
 *
 * @param utcMillis UTC时间戳。
 *
 * @return 对应日期从1970-01-01开始的天数。
 */
private fun utcMillisToEpochDay(utcMillis: Long): Long {
    return Instant.ofEpochMilli(utcMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .toEpochDay()
}

/**
 * 将账目来源转换为简短中文标签。
 *
 * @param source 手动、微信通知或微信账单导入来源。
 *
 * @return 可显示在账目卡片中的来源文本。
 */
private fun ledgerSourceLabel(source: LedgerSource): String {
    return when (source) {
        LedgerSource.MANUAL -> "手动记录"
        LedgerSource.WECHAT_NOTIFICATION -> "微信通知"
        LedgerSource.WECHAT_IMPORT -> "微信账单"
    }
}

private const val MAX_AMOUNT_INPUT_LENGTH = 12
private const val MAX_CATEGORY_LENGTH = 12
private const val MAX_NOTE_LENGTH = 100
