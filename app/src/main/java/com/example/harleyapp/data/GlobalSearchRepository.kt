package com.example.harleyapp.data

import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.LocalSearchResult
import com.example.harleyapp.model.LocalSearchType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 汇总账目、提醒、运动、网站和待确认通知的纯本地搜索。
 *
 * 使用方法：
 * 使用已经在HarleyApp中复用的仓库创建实例，页面在输入至少一个字符后调用[search]。
 * 本类只读取本机仓库并在内存中匹配，不访问网络，也不持久化用户搜索词。
 *
 * @param ledgerRepository 账目和待确认通知仓库。
 * @param reminderRepository 通用提醒仓库。
 * @param fitnessRepository 运动仓库。
 * @param websiteRepository 网站仓库。
 */
class GlobalSearchRepository(
    private val ledgerRepository: LedgerRepository,
    private val reminderRepository: ReminderRepository,
    private val fitnessRepository: FitnessRepository,
    private val websiteRepository: WebsiteRepository
) {

    /**
     * 在全部支持模块中搜索用户关键词。
     *
     * @param rawQuery 用户输入内容；会去除首尾空格并忽略大小写。
     * @param limit 最大返回数量，防止大量历史记录影响页面流畅度。
     * @return 按相关内容时间倒序排列的本地结果；空关键词返回空列表。
     */
    fun search(rawQuery: String, limit: Int = DEFAULT_RESULT_LIMIT): List<LocalSearchResult> {
        val query = rawQuery.trim().lowercase(Locale.CHINA)
        if (query.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_RESULT_LIMIT)
        val results = mutableListOf<LocalSearchResult>()

        ledgerRepository.getEntries().forEach { entry ->
            val searchable = listOf(
                entry.category,
                entry.note,
                entry.counterparty,
                entry.rawText,
                ledgerTypeTitle(entry.type),
                formatAmount(entry.amountCents)
            ).joinToString(" ").lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "ledger:${entry.id}",
                    type = LocalSearchType.LEDGER,
                    title = entry.counterparty.ifBlank { entry.category },
                    subtitle = buildLedgerSubtitle(entry),
                    sortTimeMillis = entry.createdAtMillis,
                    targetValue = entry.id.toString()
                )
            }
        }

        reminderRepository.getReminders().forEach { reminder ->
            if (query in reminder.content.lowercase(Locale.CHINA)) {
                results += LocalSearchResult(
                    stableId = "reminder:${reminder.id}",
                    type = LocalSearchType.REMINDER,
                    title = reminder.content,
                    subtitle = "下次提醒 ${formatDateTime(reminder.nextTriggerAtMillis)}",
                    sortTimeMillis = reminder.nextTriggerAtMillis,
                    targetValue = reminder.id.toString()
                )
            }
        }

        val today = LocalDate.now().toEpochDay()
        fitnessRepository.getRecordsInRange(
            startEpochDay = today - FITNESS_HISTORY_DAYS + 1,
            endEpochDay = today
        ).forEach { record ->
            record.items.filter { item -> item.count > 0 }.forEach { item ->
                val searchable = "${item.name} ${item.count} ${item.unit}"
                    .lowercase(Locale.CHINA)
                if (query in searchable) {
                    results += LocalSearchResult(
                        stableId = "fitness:${record.dateEpochDay}:${item.exerciseId}",
                        type = LocalSearchType.FITNESS,
                        title = item.name,
                        subtitle = "${LocalDate.ofEpochDay(record.dateEpochDay)} · ${item.count}${item.unit}",
                        sortTimeMillis = record.dateEpochDay * MILLIS_PER_DAY,
                        targetValue = record.dateEpochDay.toString()
                    )
                }
            }
        }

        websiteRepository.getWebsites().forEach { website ->
            val searchable = "${website.title} ${website.url}".lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "website:${website.id}",
                    type = LocalSearchType.WEBSITE,
                    title = website.title,
                    subtitle = website.url,
                    sortTimeMillis = 0L,
                    targetValue = website.id
                )
            }
        }

        ledgerRepository.getPendingCaptures().forEach { capture ->
            val searchable = "${capture.title} ${capture.content} ${capture.suggestedCategory}"
                .lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "wechat:${capture.externalKey}",
                    type = LocalSearchType.WECHAT_CAPTURE,
                    title = capture.title,
                    subtitle = capture.content.take(MAX_SUBTITLE_LENGTH),
                    sortTimeMillis = capture.receivedAtMillis,
                    targetValue = capture.externalKey
                )
            }
        }

        return results.sortedWith(
            compareByDescending<LocalSearchResult> { result -> result.sortTimeMillis }
                .thenBy { result -> result.type.ordinal }
                .thenBy { result -> result.title }
        ).take(safeLimit)
    }

    /** @return 账目收支方向的中文名称。 */
    private fun ledgerTypeTitle(type: LedgerType): String {
        return when (type) {
            LedgerType.INCOME -> "收入"
            LedgerType.EXPENSE -> "支出"
            LedgerType.TRANSFER -> "转账"
        }
    }

    /** @return 搜索结果中的账目日期、方向和金额摘要。 */
    private fun buildLedgerSubtitle(entry: LedgerEntry): String {
        return "${LocalDate.ofEpochDay(entry.dateEpochDay)} · " +
            "${ledgerTypeTitle(entry.type)} ¥${formatAmount(entry.amountCents)}"
    }

    /** @return 分为单位金额转换后的两位小数文本。 */
    private fun formatAmount(amountCents: Long): String {
        return BigDecimal.valueOf(amountCents)
            .divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            .toPlainString()
    }

    /** @return 使用设备时区格式化的提醒日期时间。 */
    private fun formatDateTime(timestamp: Long): String {
        return java.time.Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(DATE_TIME_FORMATTER)
    }

    private companion object {
        const val DEFAULT_RESULT_LIMIT = 100
        const val MAX_RESULT_LIMIT = 300
        const val FITNESS_HISTORY_DAYS = 370L
        const val MILLIS_PER_DAY = 86_400_000L
        const val MAX_SUBTITLE_LENGTH = 100
        val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    }
}
