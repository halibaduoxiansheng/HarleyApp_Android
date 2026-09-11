package com.example.harleyapp.data

import com.example.harleyapp.model.LedgerEntry
import com.example.harleyapp.model.LedgerType
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.LocalSearchResult
import com.example.harleyapp.model.LocalSearchType
import com.example.harleyapp.model.searchEnglishWords
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 汇总账目、提醒、运动、网站、英语、记事本、电子书、手机应用和功能入口的纯本地搜索。
 *
 * 使用方法：
 * 使用已经在HarleyApp中复用的仓库创建实例，页面在输入至少一个字符后调用[search]。
 * 本类只读取本机仓库并在内存中匹配，不访问网络，也不持久化用户搜索词。
 *
 * @param ledgerRepository 账目和待确认通知仓库。
 * @param reminderRepository 通用提醒仓库。
 * @param fitnessRepository 运动仓库。
 * @param websiteRepository 网站仓库。
 * @param notebookRepository 本地富内容记事本仓库。
 * @param ebookRepository 本地电子书目录仓库。
 */
class GlobalSearchRepository(
    private val ledgerRepository: LedgerRepository,
    private val reminderRepository: ReminderRepository,
    private val fitnessRepository: FitnessRepository,
    private val websiteRepository: WebsiteRepository,
    private val notebookRepository: NotebookRepository,
    private val ebookRepository: EbookRepository
) {

    /**
     * 在全部支持模块中搜索用户关键词。
     *
     * @param rawQuery 用户输入内容；会去除首尾空格并忽略大小写。
     * @param englishWords 已在宿主读取并合并学习进度的完整离线单词，避免每次输入重新解析词库。
     * @param launchableApps 宿主已经读取的手机可启动应用列表。
     * @param limit 最大返回数量，防止大量历史记录影响页面流畅度。
     * @return 先按关键词相关程度、再按相关内容时间倒序排列的本地结果；空关键词返回空列表。
     */
    fun search(
        rawQuery: String,
        englishWords: List<EnglishWord> = emptyList(),
        launchableApps: List<LaunchableApp> = emptyList(),
        limit: Int = DEFAULT_RESULT_LIMIT
    ): List<LocalSearchResult> {
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

        // 当前运动项目即使还没有历史记录也可以被搜索到，方便直接进入运动管理。
        fitnessRepository.getExerciseDefinitions().forEach { definition ->
            val searchable = listOf(
                definition.name,
                definition.unit,
                definition.dailyGoal.toString(),
                "运动项目",
                "目标"
            ).joinToString(" ").lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "fitness-definition:${definition.id}",
                    type = LocalSearchType.FITNESS,
                    title = definition.name,
                    subtitle = "运动项目 · 每日目标 ${definition.dailyGoal}${definition.unit}",
                    sortTimeMillis = 0L,
                    targetValue = definition.id
                )
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

        // 英语词库最多取最相关的前40项，避免输入单个字母时挤占其他模块结果。
        searchEnglishWords(
            words = englishWords,
            query = rawQuery
        ).take(MAX_ENGLISH_RESULTS).forEach { word ->
            results += LocalSearchResult(
                stableId = "english:${word.id}",
                type = LocalSearchType.ENGLISH_WORD,
                title = word.word,
                subtitle = listOf(word.meaningZh, word.exampleEn)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                    .take(MAX_SUBTITLE_LENGTH),
                sortTimeMillis = 0L,
                targetValue = word.id
            )
        }

        notebookRepository.getArticles().forEach { article ->
            val searchable = buildString {
                append(article.title)
                append(' ')
                append(article.tags.joinToString(" "))
                append(' ')
                append(article.plainText())
                append(if (article.isDraft) " 草稿" else " 已发布")
                append(if (article.isFavorite) " 收藏" else "")
                append(if (article.isPinned) " 置顶" else "")
            }.lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "notebook:${article.id}",
                    type = LocalSearchType.NOTEBOOK,
                    title = article.title.ifBlank { "未命名文章" },
                    subtitle = article.plainText()
                        .replace('\n', ' ')
                        .take(MAX_SUBTITLE_LENGTH)
                        .ifBlank { "本地记事本文章" },
                    sortTimeMillis = article.updatedAtMillis,
                    targetValue = article.id
                )
            }
        }

        ebookRepository.getBooks().forEach { book ->
            val searchable = listOf(
                book.title,
                book.author,
                book.originalFileName,
                book.format.displayName,
                "电子书 阅读 书籍"
            ).joinToString(" ").lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "ebook:${book.id}",
                    type = LocalSearchType.EBOOK,
                    title = book.title,
                    subtitle = listOf(
                        book.author.ifBlank { "作者未填写" },
                        book.format.displayName,
                        "第${book.currentPage + 1}/${book.pageCount}页"
                    ).joinToString(" · "),
                    sortTimeMillis = maxOf(book.lastReadAtMillis, book.updatedAtMillis),
                    targetValue = book.id
                )
            }
        }

        launchableApps.asSequence()
            .filter { app ->
                query in "${app.label} ${app.packageName}".lowercase(Locale.CHINA)
            }
            .take(MAX_APP_RESULTS)
            .forEach { app ->
                results += LocalSearchResult(
                    stableId = "app:${app.packageName}",
                    type = LocalSearchType.APP,
                    title = app.label,
                    subtitle = "打开手机应用 · ${app.packageName}",
                    sortTimeMillis = 0L,
                    targetValue = app.packageName
                )
            }

        SEARCHABLE_FEATURES.forEach { feature ->
            val searchable = listOf(
                feature.title,
                feature.subtitle,
                feature.keywords
            ).joinToString(" ").lowercase(Locale.CHINA)
            if (query in searchable) {
                results += LocalSearchResult(
                    stableId = "feature:${feature.id}",
                    type = LocalSearchType.FEATURE,
                    title = feature.title,
                    subtitle = feature.subtitle,
                    sortTimeMillis = 0L,
                    targetValue = feature.targetValue
                )
            }
        }

        return results.sortedWith(
            compareBy<LocalSearchResult> { result -> searchRelevanceRank(result, query) }
                .thenByDescending { result -> result.sortTimeMillis }
                .thenBy { result -> result.type.ordinal }
                .thenBy { result -> result.title }
        ).take(safeLimit)
    }

    /**
     * 计算全局结果与搜索词的显示优先级。
     *
     * 使用方法：
     * [search]在汇总全部模块后为每项调用本函数。标题完全相同、标题前缀、标题包含和
     * 摘要包含依次降低优先级，避免搜索“good”时，含有good例句的其他单词排在good前面。
     *
     * @param result 已确认命中搜索词的本地结果。
     * @param normalizedQuery 已去除首尾空格并转为小写的搜索词。
     * @return 数值越小表示与搜索词越相关；兜底值用于仓库自行匹配但标题摘要未直接展示关键词的结果。
     */
    private fun searchRelevanceRank(
        result: LocalSearchResult,
        normalizedQuery: String
    ): Int {
        val normalizedTitle = result.title.lowercase(Locale.CHINA)
        val normalizedSubtitle = result.subtitle.lowercase(Locale.CHINA)
        return when {
            normalizedTitle == normalizedQuery -> 0
            normalizedTitle.startsWith(normalizedQuery) -> 1
            normalizedQuery in normalizedTitle -> 2
            normalizedQuery in normalizedSubtitle -> 3
            else -> 4
        }
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
        const val MAX_ENGLISH_RESULTS = 40
        const val MAX_APP_RESULTS = 40
        val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        val SEARCHABLE_FEATURES = listOf(
            SearchableFeature("today", "今日总览", "天气、收支、提醒和运动汇总", "今天 首页 汇总", "TODAY"),
            SearchableFeature("ledger", "记账", "账目、微信账单导入与分析", "收入 支出 转账 财务", "LEDGER"),
            SearchableFeature("fitness", "运动", "目标、记录和区间分析", "步数 锻炼 健身", "FITNESS"),
            SearchableFeature("website", "网站", "打开默认网站和网页工具", "浏览器 网页 播放", "WEBSITE"),
            SearchableFeature("bookmarks", "网站收藏", "文件夹、排序和网站管理", "书签 收藏夹", "BOOKMARKS"),
            SearchableFeature("reminder", "通知提醒", "一次性或重复本机提醒", "闹钟 定时", "GENERAL_REMINDER"),
            SearchableFeature("wechat", "微信消息提醒", "微信未查看消息等待提醒", "微信 通知", "WECHAT_REMINDER"),
            SearchableFeature("cleanup", "手机清理", "清理本App缓存和打开存储管理", "缓存 内存 存储", "LOCAL_CLEANUP"),
            SearchableFeature("english", "英语单词", "5000词、例句和离线朗读", "英语 学习 词库", "ENGLISH_WORDS"),
            SearchableFeature("notebook", "记事本", "富内容文章、查询和往期推荐", "文章 笔记", "NOTEBOOK"),
            SearchableFeature("ebooks", "电子书", "导入书籍、多种翻页和阅读进度", "书架 阅读 PDF EPUB DOCX", "EBOOKS"),
            SearchableFeature("backup", "本地备份", "导出、校验和换机恢复", "导入 导出 恢复", "BACKUP"),
            SearchableFeature("hot", "每日热点", "查看当天热点内容", "新闻 热搜", "HOT_TOPICS"),
            SearchableFeature("mobile", "手机流量", "移动网络用量统计", "数据 网络 流量", "MOBILE_DATA"),
            SearchableFeature("flashlight", "手电筒", "亮度、频率和明灭时长控制", "闪光灯 爆闪 频闪", "FLASHLIGHT"),
            SearchableFeature("mao-quotes", "毛主席语录", "章节阅读、搜索收藏与本地导入", "毛泽东 毛主席 小红书 语录 章节 收藏 导入", "MAO_QUOTES"),
            SearchableFeature("profile", "我的", "个人资料、主题、版本和快捷应用", "外观 设置 头像", "PROFILE")
        )
    }
}

/**
 * 一条不依赖仓库数据的App内部功能搜索索引。
 *
 * @param id 稳定标识。
 * @param title 功能名称。
 * @param subtitle 功能说明。
 * @param keywords 用户可能输入的同义关键词。
 * @param targetValue HarleyApp用于导航的稳定目标值。
 */
private data class SearchableFeature(
    val id: String,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val targetValue: String
)
