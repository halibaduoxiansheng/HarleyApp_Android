package com.example.harleyapp.ui.screens

import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.model.BillImportResult
import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerSource
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.WechatCapture
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示微信通知自动记账、官方账单导入、待确认记录和汇总分析。
 *
 * 使用方法：
 * LedgerScreen传入账目、待确认通知、系统授权状态和数据回调。通知监听只处理微信支付语义；
 * 用户选择CSV或未加密ZIP后，账单导入在协程中执行，不会阻塞Compose主线程。
 *
 * @param entries 当前全部账目。
 * @param pendingCaptures 金额或方向不完整、需要用户确认的微信通知。
 * @param notificationAccessGranted 是否已经授予Android通知使用权。
 * @param onOpenNotificationAccess 打开系统通知使用权页面回调。
 * @param onImportBill 读取并导入用户所选微信账单文件的挂起回调。
 * @param onReviewCapture 把一条待确认通知载入账目编辑器的回调。
 * @param onIgnoreCapture 忽略并删除待确认通知摘要的回调，成功返回true。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出微信记账工具卡片。
 */
@Composable
fun WechatLedgerToolsCard(
    entries: List<LedgerEntry>,
    pendingCaptures: List<WechatCapture>,
    notificationAccessGranted: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onImportBill: suspend (Uri) -> BillImportResult,
    onReviewCapture: (WechatCapture) -> Unit,
    onIgnoreCapture: (WechatCapture) -> Boolean,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var importFeedback by remember {
        mutableStateOf("")
    }
    var isImporting by remember {
        mutableStateOf(false)
    }
    val billPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }

        coroutineScope.launch {
            isImporting = true
            importFeedback = "正在读取微信账单…"
            val result = onImportBill(uri)
            importFeedback = formatImportResult(result)
            isImporting = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "微信自动记账",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (notificationAccessGranted) {
                        "通知使用权已授权。支付、红包、转账、提现等通知会在本机识别；普通聊天不会保存到账本。"
                    } else {
                        "需要授予通知使用权，才能识别后续出现的微信支付通知。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
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
                            text = if (notificationAccessGranted) "管理通知使用权" else "授予通知使用权"
                        )
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !isImporting,
                        onClick = {
                            billPicker.launch(
                                arrayOf(
                                    "text/csv",
                                    "text/*",
                                    "application/zip",
                                    "application/octet-stream"
                                )
                            )
                        }
                    ) {
                        Text(text = if (isImporting) "导入中…" else "导入微信账单")
                    }
                }
                Text(
                    text = "账单支持微信官方CSV或未加密ZIP。提现、充值等内部转账单独统计，不计入收支。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (importFeedback.isNotBlank()) {
                    Text(
                        text = importFeedback,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        LedgerAnalysisCard(entries = entries)

        if (pendingCaptures.isNotEmpty()) {
            PendingWechatCapturesCard(
                captures = pendingCaptures,
                onReviewCapture = onReviewCapture,
                onIgnoreCapture = onIgnoreCapture
            )
        }
    }
}

/**
 * 显示本月收支、内部转账、近六个月趋势和支出分类排行。
 *
 * @param entries 当前全部账目。
 *
 * @return 无返回值，直接输出汇总分析卡片。
 */
@Composable
private fun LedgerAnalysisCard(entries: List<LedgerEntry>) {
    val currentMonth = YearMonth.now()
    val monthEntries = remember(entries, currentMonth) {
        entries.filter { entry ->
            YearMonth.from(LocalDate.ofEpochDay(entry.dateEpochDay)) == currentMonth
        }
    }
    val income = monthEntries.filter { it.type == LedgerType.INCOME }.sumOf { it.amountCents }
    val expense = monthEntries.filter { it.type == LedgerType.EXPENSE }.sumOf { it.amountCents }
    val transfer = monthEntries.filter { it.type == LedgerType.TRANSFER }.sumOf { it.amountCents }
    val categoryExpenses = remember(monthEntries) {
        monthEntries
            .filter { it.type == LedgerType.EXPENSE }
            .groupBy(LedgerEntry::category)
            .mapValues { (_, categoryEntries) -> categoryEntries.sumOf { it.amountCents } }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_CATEGORY_ROWS)
    }
    val monthTrends = remember(entries, currentMonth) {
        (MONTH_TREND_COUNT - 1 downTo 0).map { offset ->
            val month = currentMonth.minusMonths(offset.toLong())
            val matchingEntries = entries.filter { entry ->
                YearMonth.from(LocalDate.ofEpochDay(entry.dateEpochDay)) == month
            }
            MonthTrend(
                month = month,
                incomeCents = matchingEntries
                    .filter { it.type == LedgerType.INCOME }
                    .sumOf { it.amountCents },
                expenseCents = matchingEntries
                    .filter { it.type == LedgerType.EXPENSE }
                    .sumOf { it.amountCents }
            )
        }
    }
    val trendMaximum = monthTrends.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) }
        ?.coerceAtLeast(1L)
        ?: 1L
    val automaticCount = entries.count { it.source != LedgerSource.MANUAL }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = "汇总与分析",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "微信来源 $automaticCount 条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnalysisValue(
                    modifier = Modifier.weight(1f),
                    title = "本月收入",
                    value = formatLedgerMoney(income)
                )
                AnalysisValue(
                    modifier = Modifier.weight(1f),
                    title = "本月支出",
                    value = formatLedgerMoney(expense)
                )
                AnalysisValue(
                    modifier = Modifier.weight(1f),
                    title = "内部转账",
                    value = formatLedgerMoney(transfer)
                )
            }

            HorizontalDivider()

            Text(
                text = "近6个月趋势",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            monthTrends.forEach { trend ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "${trend.month.monthValue}月  收 ${formatLedgerMoney(trend.incomeCents)}  ·  支 ${formatLedgerMoney(trend.expenseCents)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { trend.incomeCents.toFloat() / trendMaximum.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { trend.expenseCents.toFloat() / trendMaximum.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = "本月支出分类",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (categoryExpenses.isEmpty()) {
                Text(
                    text = "本月暂无支出数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                categoryExpenses.forEach { (category, amountCents) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            modifier = Modifier.weight(1f),
                            text = category,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = formatLedgerMoney(amountCents),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 显示汇总分析中的单个金额值。
 *
 * @param title 指标名称。
 * @param value 已格式化金额。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值。
 */
@Composable
private fun AnalysisValue(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 显示微信通知中无法安全自动确认的记录。
 *
 * @param captures 待确认通知列表。
 * @param onReviewCapture 进入账目编辑器回调。
 * @param onIgnoreCapture 忽略通知回调。
 *
 * @return 无返回值。
 */
@Composable
private fun PendingWechatCapturesCard(
    captures: List<WechatCapture>,
    onReviewCapture: (WechatCapture) -> Unit,
    onIgnoreCapture: (WechatCapture) -> Boolean
) {
    var feedbackText by remember {
        mutableStateOf("")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "待确认微信记录 ${captures.size} 条",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "金额或收支方向不完整，不会在确认前计入汇总。",
                style = MaterialTheme.typography.bodySmall
            )
            captures.take(MAX_PENDING_ROWS).forEach { capture ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = capture.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = capture.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatCaptureTime(capture.receivedAtMillis),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onReviewCapture(capture) }) {
                            Text(text = "补充并记账")
                        }
                        TextButton(
                            onClick = {
                                feedbackText = if (onIgnoreCapture(capture)) {
                                    "已忽略该通知"
                                } else {
                                    "忽略失败，请重试"
                                }
                            }
                        ) {
                            Text(text = "忽略")
                        }
                    }
                }
                HorizontalDivider()
            }
            if (captures.size > MAX_PENDING_ROWS) {
                Text(
                    text = "当前仅展示前${MAX_PENDING_ROWS}条，处理后会继续显示。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (feedbackText.isNotBlank()) {
                Text(
                    text = feedbackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 把账单导入结果转换为简洁中文反馈。
 *
 * @param result 导入扫描、增加、更新、跳过和错误统计。
 *
 * @return 可直接显示在界面的结果文本。
 */
private fun formatImportResult(result: BillImportResult): String {
    if (result.errorMessage.isNotBlank()) {
        return result.errorMessage
    }
    return "扫描${result.scannedRows}条，新增${result.insertedRows}条，更新${result.updatedRows}条，跳过${result.skippedRows}条。"
}

/**
 * 格式化人民币分单位金额。
 *
 * @param amountCents 金额，单位为分。
 *
 * @return 当前中国区域格式的人民币文本。
 */
private fun formatLedgerMoney(amountCents: Long): String {
    return NumberFormat.getCurrencyInstance(Locale.CHINA).format(amountCents / 100.0)
}

/**
 * 格式化待确认通知接收时间。
 *
 * @param timestampMillis Unix毫秒时间戳。
 *
 * @return MM-dd HH:mm格式本地时间；无效时间返回“时间未知”。
 */
private fun formatCaptureTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) {
        return "时间未知"
    }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(CAPTURE_TIME_FORMATTER)
}

/**
 * 单月收入和支出趋势数据。
 *
 * @param month 统计月份。
 * @param incomeCents 当月收入分单位金额。
 * @param expenseCents 当月支出分单位金额。
 */
private data class MonthTrend(
    val month: YearMonth,
    val incomeCents: Long,
    val expenseCents: Long
)

private val CAPTURE_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private const val MONTH_TREND_COUNT = 6
private const val MAX_CATEGORY_ROWS = 5
private const val MAX_PENDING_ROWS = 5
