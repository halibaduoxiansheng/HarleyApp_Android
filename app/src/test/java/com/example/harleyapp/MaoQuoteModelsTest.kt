package com.example.harleyapp

import com.example.harleyapp.model.DEFAULT_MAO_QUOTE_CHAPTER_TITLE
import com.example.harleyapp.model.MAX_MAO_QUOTE_FILE_BYTES
import com.example.harleyapp.model.MaoQuoteFailureReason
import com.example.harleyapp.model.MaoQuoteFilter
import com.example.harleyapp.model.MaoQuoteParseResult
import com.example.harleyapp.model.MaoQuoteReadingPosition
import com.example.harleyapp.model.filterMaoQuotes
import com.example.harleyapp.model.normalizeMaoQuoteFavoriteIds
import com.example.harleyapp.model.normalizeMaoQuoteReadingPosition
import com.example.harleyapp.model.parseMaoQuoteText
import com.example.harleyapp.model.parseMaoQuoteUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证毛主席语录TXT解析、稳定标识和本地筛选的纯Kotlin规则。
 *
 * 使用方法：
 * 在项目根目录执行`gradlew :app:testDebugUnitTest --tests *MaoQuoteModelsTest`。测试文本全部是虚构占位
 * 内容，不包含待授权作品正文，也不依赖Android Context、文件选择器或实机。
 */
class MaoQuoteModelsTest {

    /**
     * 验证四项头部元数据、Markdown式章节和空行段落均按约定解析。
     *
     * @return 无返回值；字段、章节顺序或段落换行不正确时由JUnit报告失败。
     */
    @Test
    fun parsesMetadataChaptersAndBlankLineParagraphs() {
        val document = parseSuccess(
            """
                @title: 测试文集
                @source: 用户合法持有的测试文件
                @license: 仅用于单元测试
                @redistribution-authorized: true

                # 第一章
                测试段落甲第一行
                测试段落甲第二行

                测试段落乙

                # 第二章
                测试段落丙
            """.trimIndent()
        )

        assertEquals("测试文集", document.metadata.title)
        assertEquals("用户合法持有的测试文件", document.metadata.source)
        assertEquals("仅用于单元测试", document.metadata.license)
        assertTrue(document.metadata.redistributionAuthorized)
        assertEquals(listOf("第一章", "第二章"), document.chapters.map { it.title })
        assertEquals(3, document.quotes.size)
        assertEquals("测试段落甲第一行\n测试段落甲第二行", document.quotes.first().text)
        assertEquals(listOf(0, 1, 2), document.quotes.map { it.globalIndex })
        assertEquals(listOf(0, 1), document.chapters.first().quotes.map { it.indexInChapter })
    }

    /**
     * 验证没有任何`# `标题时，所有有效段落统一归入“全文”。
     *
     * @return 无返回值；隐式章节数量、名称或段落切分错误时由JUnit报告失败。
     */
    @Test
    fun contentWithoutHeadingUsesFullTextChapter() {
        val document = parseSuccess("测试段落甲\n\n测试段落乙")

        assertEquals(1, document.chapters.size)
        assertEquals(DEFAULT_MAO_QUOTE_CHAPTER_TITLE, document.chapters.single().title)
        assertEquals(2, document.chapters.single().quotes.size)
    }

    /**
     * 验证BOM和不同换行符不会改变同一内容生成的稳定id。
     *
     * @return 无返回值；文档、章节或语录id随等价文本格式漂移时由JUnit报告失败。
     */
    @Test
    fun stableIdsIgnoreBomLineEndingsAndMetadataChanges() {
        val first = parseSuccess(
            "@title: 标题甲\r\n# 章节\r\n测试段落甲\r\n\r\n测试段落乙"
        )
        val second = parseSuccess(
            "\uFEFF@title: 标题乙\n@source: 新来源\n# 章节\n测试段落甲\n\n测试段落乙"
        )

        assertEquals(first.id, second.id)
        assertEquals(first.chapters.single().id, second.chapters.single().id)
        assertEquals(first.quotes.map { it.id }, second.quotes.map { it.id })
    }

    /**
     * 验证完全相同的重复段落仍获得不同、可重复生成的id。
     *
     * @return 无返回值；重复段落id冲突或二次解析不稳定时由JUnit报告失败。
     */
    @Test
    fun duplicateParagraphsReceiveUniqueStableIds() {
        val source = "测试重复段落\n\n测试重复段落"
        val first = parseSuccess(source)
        val second = parseSuccess(source)

        assertNotEquals(first.quotes[0].id, first.quotes[1].id)
        assertEquals(first.quotes.map { it.id }, second.quotes.map { it.id })
    }

    /**
     * 验证损坏字节不会被UTF-8替换字符静默接受。
     *
     * @return 无返回值；无效编码未返回INVALID_UTF8时由JUnit报告失败。
     */
    @Test
    fun invalidUtf8ReturnsExplicitFailure() {
        val result = parseMaoQuoteUtf8(byteArrayOf(0xC3.toByte(), 0x28))

        assertTrue(result is MaoQuoteParseResult.Failure)
        assertEquals(
            MaoQuoteFailureReason.INVALID_UTF8,
            (result as MaoQuoteParseResult.Failure).reason
        )
    }

    /**
     * 验证超过2MB的来源会在解析前返回稳定大小错误。
     *
     * @return 无返回值；超限内容被接受或错误类型变化时由JUnit报告失败。
     */
    @Test
    fun oversizedFileReturnsExplicitFailure() {
        val result = parseMaoQuoteUtf8(ByteArray(MAX_MAO_QUOTE_FILE_BYTES + 1) { 'a'.code.toByte() })

        assertTrue(result is MaoQuoteParseResult.Failure)
        assertEquals(
            MaoQuoteFailureReason.FILE_TOO_LARGE,
            (result as MaoQuoteParseResult.Failure).reason
        )
    }

    /**
     * 验证只有元数据而没有正文的文件不会生成空阅读页。
     *
     * @return 无返回值；元数据被误当正文或错误类型不明确时由JUnit报告失败。
     */
    @Test
    fun metadataOnlyFileReturnsNoContent() {
        val result = parseMaoQuoteText(
            "@title: 空测试文件\n@redistribution-authorized: false"
        )

        assertTrue(result is MaoQuoteParseResult.Failure)
        assertEquals(
            MaoQuoteFailureReason.NO_CONTENT,
            (result as MaoQuoteParseResult.Failure).reason
        )
    }

    /**
     * 验证超长文档标题、来源说明和章节标题都会在进入界面前被拒绝。
     *
     * @return 无返回值；任一异常元数据被解析器接受时由JUnit报告失败。
     */
    @Test
    fun oversizedMetadataAndChapterTitlesAreRejected() {
        val inputs = listOf(
            "@title: ${"题".repeat(121)}\n\n测试段落",
            "@source: ${"源".repeat(501)}\n\n测试段落",
            "# ${"章".repeat(121)}\n测试段落"
        )

        inputs.forEach { input ->
            val result = parseMaoQuoteText(input)
            assertTrue(result is MaoQuoteParseResult.Failure)
            assertEquals(
                MaoQuoteFailureReason.CONTENT_LIMIT_EXCEEDED,
                (result as MaoQuoteParseResult.Failure).reason
            )
        }
    }

    /**
     * 验证再分发授权只有值为true时才会进入元数据，不会把其他非空文本误判为授权。
     *
     * @return 无返回值；授权布尔值解析过宽时由JUnit报告失败。
     */
    @Test
    fun redistributionAuthorizationRequiresTrueValue() {
        val denied = parseSuccess(
            "@redistribution-authorized: yes\n\n测试段落"
        )
        val allowed = parseSuccess(
            "@redistribution-authorized: TRUE\n\n测试段落"
        )

        assertFalse(denied.metadata.redistributionAuthorized)
        assertTrue(allowed.metadata.redistributionAuthorized)
    }

    /**
     * 验证章节、收藏与关键词条件采用交集，并保持原文顺序。
     *
     * @return 无返回值；任一过滤条件被忽略或结果重新排序时由JUnit报告失败。
     */
    @Test
    fun searchCombinesChapterFavoriteAndKeywordFilters() {
        val document = parseSuccess(
            """
                # 春季
                山川测试内容

                星光测试内容

                # 秋季
                山川另一内容
            """.trimIndent()
        )
        val springChapter = document.chapters.first()
        val favoriteId = springChapter.quotes.first().id

        val result = filterMaoQuotes(
            document = document,
            filter = MaoQuoteFilter(
                query = " 山川 ",
                chapterId = springChapter.id,
                favoritesOnly = true
            ),
            favoriteQuoteIds = setOf(favoriteId, document.quotes.last().id)
        )

        assertEquals(listOf(favoriteId), result.map { it.id })
    }

    /**
     * 验证关键词也可命中章节标题，英文比较忽略大小写。
     *
     * @return 无返回值；章节标题未纳入搜索或大小写处理错误时由JUnit报告失败。
     */
    @Test
    fun searchMatchesChapterTitleIgnoringCase() {
        val document = parseSuccess("# TEST Chapter\n占位段落")

        val result = filterMaoQuotes(
            document = document,
            filter = MaoQuoteFilter(query = "test chapter")
        )

        assertEquals(document.quotes, result)
    }

    /**
     * 验证收藏清理只保留当前文档真实存在的稳定id。
     *
     * @return 无返回值；失效id未删除或有效id丢失时由JUnit报告失败。
     */
    @Test
    fun favoriteNormalizationDropsUnknownIds() {
        val document = parseSuccess("测试段落")
        val validId = document.quotes.single().id

        val result = normalizeMaoQuoteFavoriteIds(
            document,
            setOf(validId, "quote-000000000000000000000000")
        )

        assertEquals(setOf(validId), result)
    }

    /**
     * 验证阅读位置会限制到段落长度，且旧文档失效id不会错误跳到其他段落。
     *
     * @return 无返回值；偏移未修正或失效位置未清除时由JUnit报告失败。
     */
    @Test
    fun readingPositionIsClampedOrDropped() {
        val document = parseSuccess("五字内容测试")
        val quote = document.quotes.single()

        val clamped = normalizeMaoQuoteReadingPosition(
            document,
            MaoQuoteReadingPosition(quote.id, characterOffset = 999)
        )
        val missing = normalizeMaoQuoteReadingPosition(
            document,
            MaoQuoteReadingPosition("quote-000000000000000000000000")
        )

        assertEquals(quote.text.length, clamped?.characterOffset)
        assertNull(missing)
    }

    /**
     * 把测试文本解析为成功文档，集中保留失败诊断。
     *
     * @param text 不含受保护作品正文的虚构测试TXT。
     * @return 解析成功的文档；失败时抛出带原因的AssertionError。
     */
    private fun parseSuccess(text: String) = when (val result = parseMaoQuoteText(text)) {
        is MaoQuoteParseResult.Success -> result.document
        is MaoQuoteParseResult.Failure -> {
            throw AssertionError("Unexpected parse failure: ${result.reason}, ${result.message}")
        }
    }
}
