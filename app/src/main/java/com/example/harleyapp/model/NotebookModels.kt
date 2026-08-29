package com.example.harleyapp.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * 文章内容块类型。
 *
 * 使用方法：
 * 编辑器根据类型显示对应输入控件，详情页按列表顺序渲染。使用稳定枚举名称写入JSON，后续新增
 * 类型时不会破坏已经保存的文字、图片、GIF、链接或分隔线。
 */
enum class NotebookBlockType {
    TEXT,
    IMAGE,
    LINK,
    DIVIDER
}

/**
 * 一个文字块的段落样式。
 *
 * @param displayName 编辑器显示名称。
 */
enum class NotebookTextStyle(val displayName: String) {
    PARAGRAPH("正文"),
    HEADING("小标题"),
    QUOTE("引用"),
    BULLET("项目符号"),
    NUMBERED("编号列表")
}

/**
 * 文章卡片的预设颜色主题。
 *
 * @param displayName 外观选择器显示名称。
 */
enum class NotebookCardTheme(val displayName: String) {
    PAPER("纸张"),
    OCEAN("海洋"),
    FOREST("森林"),
    SUNSET("日落"),
    LAVENDER("薰衣草"),
    CANDY("糖果"),
    NIGHT("夜色")
}

/**
 * 文章卡片版式。
 *
 * @param displayName 外观选择器显示名称。
 */
enum class NotebookCardLayout(val displayName: String) {
    STANDARD("标准"),
    COVER("封面大图"),
    MINIMAL("极简")
}

/**
 * 查询结果排序方式。
 *
 * @param displayName 查询页显示名称。
 */
enum class NotebookSortOrder(val displayName: String) {
    UPDATED_DESC("最近修改"),
    CREATED_DESC("最近创建"),
    TITLE_ASC("标题排序")
}

/**
 * 一篇文章中的单个可排序内容块。
 *
 * 使用方法：
 * 文字块使用[text]和文字样式字段；图片或GIF使用[mediaFileName]和[mediaCaption]；链接使用
 * [linkTitle]和[linkUrl]；分隔线只需要id与类型。未使用字段保持空字符串，便于向后兼容。
 *
 * @param id 内容块稳定id，用于编辑、排序和设置文章封面。
 * @param type 内容类型。
 * @param text 文字块正文。
 * @param textStyle 文字块段落样式。
 * @param bold 是否整块加粗。
 * @param italic 是否整块斜体。
 * @param underline 是否整块加下划线。
 * @param mediaFileName App私有目录中的受控图片文件名。
 * @param mediaMimeType 图片或GIF MIME类型。
 * @param mediaCaption 图片说明。
 * @param linkTitle 链接显示标题。
 * @param linkUrl http或https链接。
 */
data class NotebookContentBlock(
    val id: String,
    val type: NotebookBlockType,
    val text: String = "",
    val textStyle: NotebookTextStyle = NotebookTextStyle.PARAGRAPH,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val mediaFileName: String = "",
    val mediaMimeType: String = "",
    val mediaCaption: String = "",
    val linkTitle: String = "",
    val linkUrl: String = ""
) {

    companion object {
        /**
         * 创建一个空正文块。
         *
         * @return 带随机稳定id、可直接放入编辑器的正文块。
         */
        fun emptyText(): NotebookContentBlock {
            return NotebookContentBlock(
                id = newNotebookStableId("block"),
                type = NotebookBlockType.TEXT
            )
        }
    }
}

/**
 * 本地记事本文章。
 *
 * 使用方法：
 * 新建时调用[newNotebookArticle]；保存由NotebookRepository统一维护创建和修改时间。详情页打开后
 * 只更新[lastViewedAtMillis]和[viewCount]，不会错误改变文章最后修改时间。
 *
 * @param id 文章稳定id。
 * @param title 标题；草稿允许显示“未命名文章”。
 * @param blocks 按正文顺序保存的内容块。
 * @param tags 用户标签，保存时去空、去重。
 * @param isFavorite 是否收藏。
 * @param isPinned 是否置顶。
 * @param isDraft 是否仍为自动保存草稿。
 * @param cardTheme 卡片颜色主题。
 * @param cardLayout 卡片版式。
 * @param coverBlockId 指定封面图片块；为空时使用第一张图片。
 * @param createdAtMillis 创建时间。
 * @param updatedAtMillis 最近修改时间。
 * @param lastViewedAtMillis 最近阅读时间；从未阅读为0。
 * @param viewCount 累计打开详情次数。
 */
data class NotebookArticle(
    val id: String,
    val title: String,
    val blocks: List<NotebookContentBlock>,
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val isDraft: Boolean = true,
    val cardTheme: NotebookCardTheme = NotebookCardTheme.PAPER,
    val cardLayout: NotebookCardLayout = NotebookCardLayout.STANDARD,
    val coverBlockId: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastViewedAtMillis: Long = 0L,
    val viewCount: Int = 0
) {

    /**
     * 把富内容转换为可搜索、复制和系统分享的纯文本。
     *
     * @return 保留段落、图片说明和链接地址的纯文本。
     */
    fun plainText(): String {
        return buildString {
            blocks.forEach { block ->
                val value = when (block.type) {
                    NotebookBlockType.TEXT -> block.text
                    NotebookBlockType.IMAGE -> block.mediaCaption.takeIf(String::isNotBlank)
                        ?.let { caption -> "[图片] $caption" }
                        ?: "[图片]"
                    NotebookBlockType.LINK -> listOf(block.linkTitle, block.linkUrl)
                        .filter(String::isNotBlank)
                        .joinToString(" ")
                    NotebookBlockType.DIVIDER -> "——"
                }
                if (value.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(value.trim())
                }
            }
        }
    }

    /**
     * 生成文章卡片摘要。
     *
     * @param maxLength 最大字符数。
     * @return 去除多余空白后的摘要；没有文字时返回内容类型提示。
     */
    fun summary(maxLength: Int = 100): String {
        val normalized = plainText().replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) {
            return when {
                hasImage() -> "这篇文章包含图片或GIF"
                hasLink() -> "这篇文章包含链接"
                else -> "还没有正文内容"
            }
        }
        return if (normalized.length <= maxLength) {
            normalized
        } else {
            normalized.take(maxLength.coerceAtLeast(1)).trimEnd() + "…"
        }
    }

    /** @return 文章包含至少一个有效图片块时返回true。 */
    fun hasImage(): Boolean {
        return blocks.any { block ->
            block.type == NotebookBlockType.IMAGE && block.mediaFileName.isNotBlank()
        }
    }

    /** @return 文章包含至少一个有效链接块时返回true。 */
    fun hasLink(): Boolean {
        return blocks.any { block ->
            block.type == NotebookBlockType.LINK && block.linkUrl.isNotBlank()
        }
    }

    /**
     * 读取文章卡片封面文件名。
     *
     * @return 指定封面仍存在时返回其文件名，否则返回第一张图片文件名；没有图片时返回null。
     */
    fun coverMediaFileName(): String? {
        val imageBlocks = blocks.filter { block ->
            block.type == NotebookBlockType.IMAGE && block.mediaFileName.isNotBlank()
        }
        return imageBlocks.firstOrNull { block -> block.id == coverBlockId }?.mediaFileName
            ?: imageBlocks.firstOrNull()?.mediaFileName
    }
}

/**
 * 查询页面的完整筛选条件。
 *
 * @param query 标题、正文、标签和链接的关键词。
 * @param startEpochDay 创建日期下限，null表示不限。
 * @param endEpochDay 创建日期上限，null表示不限。
 * @param favoriteOnly 是否只看收藏。
 * @param imageOnly 是否只看含图片或GIF的文章。
 * @param linkOnly 是否只看含链接的文章。
 * @param includeDrafts 是否包含草稿。
 * @param sortOrder 排序方式。
 */
data class NotebookQueryFilter(
    val query: String = "",
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
    val favoriteOnly: Boolean = false,
    val imageOnly: Boolean = false,
    val linkOnly: Boolean = false,
    val includeDrafts: Boolean = true,
    val sortOrder: NotebookSortOrder = NotebookSortOrder.UPDATED_DESC
)

/**
 * 一条可解释的往期文章推荐。
 *
 * @param article 推荐文章。
 * @param reason 页面显示的本地推荐原因。
 * @param score 内部排序分数，数值越高优先级越高。
 */
data class NotebookRecommendation(
    val article: NotebookArticle,
    val reason: String,
    val score: Int
)

/**
 * 创建一篇尚未持久化的新草稿。
 *
 * @param nowMillis 当前时间，测试可传固定值。
 * @return 默认包含一个空正文块、纸张主题和标准卡片的新文章。
 */
fun newNotebookArticle(nowMillis: Long = System.currentTimeMillis()): NotebookArticle {
    return NotebookArticle(
        id = newNotebookStableId("article"),
        title = "",
        blocks = listOf(NotebookContentBlock.emptyText()),
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis
    )
}

/**
 * 按关键词、创建日期和内容属性查询本机文章。
 *
 * 使用方法：
 * 查询页每次输入或切换筛选条件后调用。本函数只读取传入列表，不保存搜索词、不访问网络。
 *
 * @param articles 当前全部文章。
 * @param filter 查询条件。
 * @param zoneId 日期筛选使用的时区，默认手机当前时区。
 * @return 过滤并排序后的文章列表。
 */
fun queryNotebookArticles(
    articles: List<NotebookArticle>,
    filter: NotebookQueryFilter,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<NotebookArticle> {
    val normalizedQuery = filter.query.trim().lowercase(Locale.ROOT)
    val safeStart = when {
        filter.startEpochDay == null -> null
        filter.endEpochDay == null -> filter.startEpochDay
        else -> minOf(filter.startEpochDay, filter.endEpochDay)
    }
    val safeEnd = when {
        filter.endEpochDay == null -> null
        filter.startEpochDay == null -> filter.endEpochDay
        else -> maxOf(filter.startEpochDay, filter.endEpochDay)
    }
    val filtered = articles.filter { article ->
        val createdEpochDay = Instant.ofEpochMilli(article.createdAtMillis)
            .atZone(zoneId)
            .toLocalDate()
            .toEpochDay()
        val searchableText = buildString {
            append(article.title)
            append('\n')
            append(article.tags.joinToString(" "))
            append('\n')
            append(article.plainText())
        }.lowercase(Locale.ROOT)
        (filter.includeDrafts || !article.isDraft) &&
            (normalizedQuery.isBlank() || normalizedQuery in searchableText) &&
            (safeStart == null || createdEpochDay >= safeStart) &&
            (safeEnd == null || createdEpochDay <= safeEnd) &&
            (!filter.favoriteOnly || article.isFavorite) &&
            (!filter.imageOnly || article.hasImage()) &&
            (!filter.linkOnly || article.hasLink())
    }
    return when (filter.sortOrder) {
        NotebookSortOrder.UPDATED_DESC -> filtered.sortedWith(
            compareByDescending<NotebookArticle> { article -> article.isPinned }
                .thenByDescending { article -> article.updatedAtMillis }
        )
        NotebookSortOrder.CREATED_DESC -> filtered.sortedWith(
            compareByDescending<NotebookArticle> { article -> article.isPinned }
                .thenByDescending { article -> article.createdAtMillis }
        )
        NotebookSortOrder.TITLE_ASC -> filtered.sortedWith(
            compareByDescending<NotebookArticle> { article -> article.isPinned }
                .thenBy { article -> article.title.lowercase(Locale.CHINA) }
        )
    }
}

/**
 * 从用户以往文章中生成不联网、可解释的回顾推荐。
 *
 * 使用方法：
 * 推荐页打开或文章发生变化后调用。规则依次考虑同日回忆、未完成草稿、长期未读、收藏、最近修改、
 * 与最近阅读文章的共同标签以及每日稳定随机回顾；不会把文章内容发送给任何服务。
 *
 * @param articles 当前全部文章。
 * @param nowMillis 当前时间，测试可传固定值。
 * @param limit 最多返回条数，限制为1到30。
 * @param zoneId 日期计算时区。
 * @return 按分数从高到低排列且每篇文章只出现一次的推荐。
 */
fun buildNotebookRecommendations(
    articles: List<NotebookArticle>,
    nowMillis: Long = System.currentTimeMillis(),
    limit: Int = 12,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<NotebookRecommendation> {
    if (articles.isEmpty()) return emptyList()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val recentlyViewed = articles
        .filter { article -> article.lastViewedAtMillis > 0L }
        .maxByOrNull { article -> article.lastViewedAtMillis }
    val referenceTags = recentlyViewed?.tags.orEmpty().map { tag ->
        tag.lowercase(Locale.ROOT)
    }.toSet()
    val recommendations = articles.map { article ->
        val createdDate = Instant.ofEpochMilli(article.createdAtMillis)
            .atZone(zoneId)
            .toLocalDate()
        val daysSinceViewed = if (article.lastViewedAtMillis <= 0L) {
            Long.MAX_VALUE
        } else {
            ((nowMillis - article.lastViewedAtMillis).coerceAtLeast(0L) / MILLIS_PER_DAY)
        }
        val daysSinceUpdated = ((nowMillis - article.updatedAtMillis).coerceAtLeast(0L) /
            MILLIS_PER_DAY)
        val matchingTags = article.tags.filter { tag ->
            tag.lowercase(Locale.ROOT) in referenceTags
        }
        val reasonAndScore = when {
            createdDate.year < today.year &&
                createdDate.month == today.month &&
                createdDate.dayOfMonth == today.dayOfMonth -> "同日回忆 · ${createdDate.year}年写下" to 120
            article.isDraft -> "继续完成这篇草稿" to 100
            daysSinceViewed == Long.MAX_VALUE -> "写完后还没有回顾过" to 85
            daysSinceViewed >= 30L -> "已经${daysSinceViewed}天没有打开" to 75
            matchingTags.isNotEmpty() && article.id != recentlyViewed?.id -> {
                "与你最近阅读的“${matchingTags.first()}”标签相关" to 65
            }
            article.isFavorite -> "来自你的收藏" to 55
            daysSinceUpdated <= 7L -> "最近修改过，继续回顾" to 45
            article.isPinned -> "来自你的置顶文章" to 35
            else -> "今日随机回顾" to 10
        }
        NotebookRecommendation(
            article = article,
            reason = reasonAndScore.first,
            score = reasonAndScore.second
        )
    }
    val daySeed = today.toEpochDay().toInt()
    return recommendations
        .sortedWith(
            compareByDescending<NotebookRecommendation> { recommendation -> recommendation.score }
                .thenBy { recommendation -> recommendation.article.id.hashCode() xor daySeed }
        )
        .take(limit.coerceIn(1, 30))
}

/**
 * 生成只由字母、数字和下划线组成的稳定随机id。
 *
 * @param prefix id类型前缀。
 * @return 可安全写入JSON和作为Compose列表键的id。
 */
fun newNotebookStableId(prefix: String): String {
    return "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
