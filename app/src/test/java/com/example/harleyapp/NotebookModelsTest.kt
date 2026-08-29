package com.example.harleyapp

import com.example.harleyapp.model.NotebookArticle
import com.example.harleyapp.model.NotebookBlockType
import com.example.harleyapp.model.NotebookCardLayout
import com.example.harleyapp.model.NotebookCardTheme
import com.example.harleyapp.model.NotebookContentBlock
import com.example.harleyapp.model.NotebookQueryFilter
import com.example.harleyapp.model.NotebookSortOrder
import com.example.harleyapp.model.buildNotebookRecommendations
import com.example.harleyapp.model.queryNotebookArticles
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证记事本的纯数据查询、富内容摘要和本地往期推荐规则。
 *
 * 使用方法：
 * 在项目根目录执行gradlew testDebugUnitTest。本测试不依赖Android设备，也不会读写真实文章。
 */
class NotebookModelsTest {

    /**
     * 验证查询词会覆盖标题、正文、标签和链接，同时能够组合收藏与内容类型筛选。
     *
     * @return 无返回值；遗漏可搜索字段或筛选条件失效时由JUnit报告失败。
     */
    @Test
    fun queryMatchesAllRichContentAndCombinedFilters() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val matching = article(
            id = "article_matching",
            title = "周末摄影记录",
            createdAtMillis = timestamp(2026, 8, 20, zoneId),
            updatedAtMillis = timestamp(2026, 8, 21, zoneId),
            favorite = true,
            blocks = listOf(
                textBlock("在江边等待日落"),
                imageBlock("media_photo.jpg", "晚霞"),
                linkBlock("作品集", "https://example.com/gallery")
            ),
            tags = listOf("生活", "摄影")
        )
        val unrelated = article(
            id = "article_unrelated",
            title = "采购清单",
            createdAtMillis = timestamp(2026, 8, 22, zoneId),
            updatedAtMillis = timestamp(2026, 8, 23, zoneId),
            blocks = listOf(textBlock("牛奶和水果"))
        )

        val result = queryNotebookArticles(
            articles = listOf(unrelated, matching),
            filter = NotebookQueryFilter(
                query = "example.com",
                favoriteOnly = true,
                imageOnly = true,
                linkOnly = true,
                includeDrafts = false
            ),
            zoneId = zoneId
        )

        assertEquals(listOf(matching), result)
    }

    /**
     * 验证创建日期筛选会自动纠正倒置区间，并在标题排序时继续优先显示置顶文章。
     *
     * @return 无返回值；日期边界或置顶排序发生回归时由JUnit报告失败。
     */
    @Test
    fun queryNormalizesDateRangeAndKeepsPinnedFirst() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val firstDay = java.time.LocalDate.of(2026, 8, 10).toEpochDay()
        val lastDay = java.time.LocalDate.of(2026, 8, 20).toEpochDay()
        val ordinary = article(
            id = "article_a",
            title = "A文章",
            createdAtMillis = timestamp(2026, 8, 12, zoneId),
            updatedAtMillis = timestamp(2026, 8, 12, zoneId)
        )
        val pinned = article(
            id = "article_z",
            title = "Z文章",
            createdAtMillis = timestamp(2026, 8, 18, zoneId),
            updatedAtMillis = timestamp(2026, 8, 18, zoneId),
            pinned = true
        )
        val outside = article(
            id = "article_outside",
            title = "区间外",
            createdAtMillis = timestamp(2026, 8, 25, zoneId),
            updatedAtMillis = timestamp(2026, 8, 25, zoneId)
        )

        val result = queryNotebookArticles(
            articles = listOf(ordinary, outside, pinned),
            filter = NotebookQueryFilter(
                startEpochDay = lastDay,
                endEpochDay = firstDay,
                sortOrder = NotebookSortOrder.TITLE_ASC
            ),
            zoneId = zoneId
        )

        assertEquals(listOf(pinned, ordinary), result)
    }

    /**
     * 验证同日回忆、未完成草稿和长期未读文章采用可解释分值顺序且不会重复出现。
     *
     * @return 无返回值；推荐理由、排序或去重逻辑变化时由JUnit报告失败。
     */
    @Test
    fun recommendationsPreferMemoryDraftAndLongUnread() {
        val zoneId = ZoneId.of("Asia/Shanghai")
        val now = timestamp(2026, 8, 29, zoneId)
        val memory = article(
            id = "article_memory",
            title = "去年的今天",
            createdAtMillis = timestamp(2025, 8, 29, zoneId),
            updatedAtMillis = timestamp(2025, 8, 29, zoneId),
            lastViewedAtMillis = timestamp(2026, 8, 28, zoneId)
        )
        val draft = article(
            id = "article_draft",
            title = "待完成",
            createdAtMillis = timestamp(2026, 8, 28, zoneId),
            updatedAtMillis = timestamp(2026, 8, 28, zoneId),
            draft = true
        )
        val longUnread = article(
            id = "article_unread",
            title = "很久没看",
            createdAtMillis = timestamp(2026, 1, 1, zoneId),
            updatedAtMillis = timestamp(2026, 1, 1, zoneId),
            lastViewedAtMillis = timestamp(2026, 7, 1, zoneId)
        )

        val result = buildNotebookRecommendations(
            articles = listOf(longUnread, draft, memory),
            nowMillis = now,
            zoneId = zoneId
        )

        assertEquals(listOf(memory.id, draft.id, longUnread.id), result.map { it.article.id })
        assertTrue(result[0].reason.startsWith("同日回忆"))
        assertEquals("继续完成这篇草稿", result[1].reason)
        assertTrue(result[2].reason.contains("天没有打开"))
    }

    /**
     * 验证纯文本和摘要保留图片说明、链接标题及地址，同时文章外观设置不会参与内容转换。
     *
     * @return 无返回值；富内容转换遗漏关键字段时由JUnit报告失败。
     */
    @Test
    fun plainTextKeepsMediaCaptionAndLinkAddress() {
        val article = article(
            id = "article_rich",
            title = "富内容",
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            blocks = listOf(
                textBlock("第一段"),
                imageBlock("media_demo.gif", "开心表情"),
                linkBlock("参考链接", "https://example.com")
            ),
            theme = NotebookCardTheme.NIGHT,
            layout = NotebookCardLayout.COVER
        )

        assertEquals(
            "第一段\n[图片] 开心表情\n参考链接 https://example.com",
            article.plainText()
        )
        assertTrue(article.summary().contains("开心表情"))
        assertEquals("media_demo.gif", article.coverMediaFileName())
    }

    /**
     * 创建测试文章并集中提供稳定默认值。
     *
     * @param id 文章稳定id。
     * @param title 标题。
     * @param createdAtMillis 创建时间。
     * @param updatedAtMillis 修改时间。
     * @param favorite 是否收藏。
     * @param pinned 是否置顶。
     * @param draft 是否为草稿。
     * @param lastViewedAtMillis 最近阅读时间。
     * @param blocks 富内容块。
     * @param tags 标签。
     * @param theme 卡片主题。
     * @param layout 卡片版式。
     * @return 可直接参与查询和推荐测试的文章。
     */
    private fun article(
        id: String,
        title: String,
        createdAtMillis: Long,
        updatedAtMillis: Long,
        favorite: Boolean = false,
        pinned: Boolean = false,
        draft: Boolean = false,
        lastViewedAtMillis: Long = 0L,
        blocks: List<NotebookContentBlock> = listOf(textBlock("正文")),
        tags: List<String> = emptyList(),
        theme: NotebookCardTheme = NotebookCardTheme.PAPER,
        layout: NotebookCardLayout = NotebookCardLayout.STANDARD
    ): NotebookArticle {
        return NotebookArticle(
            id = id,
            title = title,
            blocks = blocks,
            tags = tags,
            isFavorite = favorite,
            isPinned = pinned,
            isDraft = draft,
            cardTheme = theme,
            cardLayout = layout,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            lastViewedAtMillis = lastViewedAtMillis
        )
    }

    /**
     * @param text 正文内容。
     * @return 测试使用的正文块。
     */
    private fun textBlock(text: String): NotebookContentBlock {
        return NotebookContentBlock(
            id = "block_text_${text.hashCode()}",
            type = NotebookBlockType.TEXT,
            text = text
        )
    }

    /**
     * @param fileName 受控媒体文件名。
     * @param caption 图片说明。
     * @return 测试使用的图片块。
     */
    private fun imageBlock(fileName: String, caption: String): NotebookContentBlock {
        return NotebookContentBlock(
            id = "block_image_${fileName.hashCode()}",
            type = NotebookBlockType.IMAGE,
            mediaFileName = fileName,
            mediaMimeType = if (fileName.endsWith(".gif")) "image/gif" else "image/jpeg",
            mediaCaption = caption
        )
    }

    /**
     * @param title 链接标题。
     * @param url 链接地址。
     * @return 测试使用的链接块。
     */
    private fun linkBlock(title: String, url: String): NotebookContentBlock {
        return NotebookContentBlock(
            id = "block_link_${url.hashCode()}",
            type = NotebookBlockType.LINK,
            linkTitle = title,
            linkUrl = url
        )
    }

    /**
     * 把固定的中国时区本地日期转换为毫秒时间戳。
     *
     * @param year 年。
     * @param month 月。
     * @param day 月内日期。
     * @param zoneId 固定时区。
     * @return 当天中午的Unix毫秒时间戳，避免日期边界歧义。
     */
    private fun timestamp(
        year: Int,
        month: Int,
        day: Int,
        zoneId: ZoneId
    ): Long {
        return LocalDateTime.of(year, month, day, 12, 0)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}
