package com.example.harleyapp

import com.example.harleyapp.data.APP_LOCK_RECOVERY_WAIT_MILLIS
import com.example.harleyapp.data.AppLockState
import com.example.harleyapp.model.AppVisualTheme
import com.example.harleyapp.model.EbookBook
import com.example.harleyapp.model.EbookFormat
import com.example.harleyapp.system.detectEbookLanguageCode
import com.example.harleyapp.system.splitEbookTranslationText
import com.example.harleyapp.ui.screens.buildEbookShelfPages
import com.example.harleyapp.ui.screens.buildEbookTableOfContents
import com.example.harleyapp.ui.screens.createOpaqueArgb
import com.example.harleyapp.ui.screens.moveEbookShelfBook
import com.example.harleyapp.ui.screens.normalizeEbookNoteSelection
import com.example.harleyapp.ui.screens.paginateEbookText
import com.example.harleyapp.ui.screens.resolveEbookReaderPageCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证电子书格式、正文分页、密码恢复倒计时和人物主题资源的纯逻辑边界。 */
class EbookModelsTest {

    /**
     * 验证文件扩展名识别忽略大小写，并包含无DRM Kindle容器使用的常见后缀。
     *
     * @return 无返回值；映射错误时由JUnit报告失败。
     */
    @Test
    fun ebookFormatRecognitionSupportsDeclaredFormats() {
        assertEquals(EbookFormat.PDF, EbookFormat.fromFileName("手册.PDF"))
        assertEquals(EbookFormat.EPUB, EbookFormat.fromFileName("novel.epub"))
        assertEquals(EbookFormat.DOCX, EbookFormat.fromFileName("notes.docx"))
        assertEquals(EbookFormat.MARKDOWN, EbookFormat.fromFileName("readme.markdown"))
        assertEquals(EbookFormat.MOBI, EbookFormat.fromFileName("novel.MOBI"))
        assertEquals(EbookFormat.MOBI, EbookFormat.fromFileName("kindle.azw"))
        assertEquals(EbookFormat.MOBI, EbookFormat.fromFileName("modern.azw3"))
        assertEquals(null, EbookFormat.fromFileName("archive.zip"))
    }

    /**
     * 验证超长正文会拆成多页，且拼回后不丢失任何正文字符。
     *
     * @return 无返回值；分页为空、超限或丢字时由JUnit报告失败。
     */
    @Test
    fun longEbookTextPaginatesWithoutLosingContent() {
        val source = (1..220).joinToString("\n\n") { index ->
            "第${index}段：这是用于验证电子书分页的正文内容。".repeat(4)
        }
        val pages = paginateEbookText(source)

        assertTrue(pages.size > 2)
        assertTrue(pages.all { page -> page.length <= 1_050 })
        assertEquals(
            source.filterNot(Char::isWhitespace),
            pages.joinToString("").filterNot(Char::isWhitespace)
        )
    }

    /**
     * 验证文本书籍加载正文期间沿用仓库页数，不能把上次停留页误压回第一页。
     *
     * @return 无返回值；加载前页数或加载后实际页数选择错误时由JUnit报告失败。
     */
    @Test
    fun ebookReaderKeepsSavedPageCountUntilTextLoaded() {
        assertEquals(
            7_434,
            resolveEbookReaderPageCount(
                format = EbookFormat.MOBI,
                savedPageCount = 7_434,
                textLoaded = false,
                loadedTextPageCount = 0
            )
        )
        assertEquals(
            7_510,
            resolveEbookReaderPageCount(
                format = EbookFormat.MOBI,
                savedPageCount = 7_434,
                textLoaded = true,
                loadedTextPageCount = 7_510
            )
        )
        assertEquals(
            32,
            resolveEbookReaderPageCount(
                format = EbookFormat.PDF,
                savedPageCount = 32,
                textLoaded = true,
                loadedTextPageCount = 1
            )
        )
    }

    /**
     * 验证长按产生的反向选区和暂时越界位置都能安全转换成笔记摘录。
     *
     * @return 无返回值；选区排序、边界限制或首尾空白处理错误时由JUnit报告失败。
     */
    @Test
    fun ebookNoteSelectionNormalizesNativeSelectionRange() {
        val text = "  第一段正文，用于创建阅读笔记。  "

        assertEquals(
            "第一段正文，用于创建阅读笔记。",
            normalizeEbookNoteSelection(text, text.length + 20, -5)
        )
        assertEquals("第一段", normalizeEbookNoteSelection(text, 2, 5))
        assertEquals("", normalizeEbookNoteSelection(text, 4, 4))
    }

    /**
     * 验证离线翻译切段不会超过SDK友好长度，也不会丢失正文字符。
     *
     * @return 无返回值；切段超长或正文丢失时由JUnit报告失败。
     */
    @Test
    fun offlineTranslationChunksPreservePageText() {
        val source = (1..80).joinToString("\n") { index ->
            "Sentence $index explains local translation. 第${index}句用于检查中文断句。"
        }
        val chunks = splitEbookTranslationText(source)

        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { chunk -> chunk.length <= 480 })
        assertEquals(
            source.filterNot(Char::isWhitespace),
            chunks.joinToString("").filterNot(Char::isWhitespace)
        )
    }

    /**
     * 验证中文正文和英文正文会选择各自的Android TTS语音语言。
     *
     * @return 无返回值；语言推断错误时由JUnit报告失败。
     */
    @Test
    fun readAloudLanguageDetectionSupportsChineseAndEnglish() {
        assertEquals("zh", detectEbookLanguageCode("这是一本中文小说，正在验证自动朗读语言。"))
        assertEquals("en", detectEbookLanguageCode("This is an English book for read aloud testing."))
    }

    /**
     * 验证真实竖排书架每页最多三十本，超过容量时按原有顺序生成新书架。
     *
     * @return 无返回值；分页数量、容量或顺序错误时由JUnit报告失败。
     */
    @Test
    fun bookshelfCreatesAnotherShelfAfterThirtyBooks() {
        val books = (1..61).map(::createTestBook)
        val pages = buildEbookShelfPages(books)

        assertEquals(listOf(30, 30, 1), pages.map { page -> page.size })
        assertEquals("book_1", pages.first().first().id)
        assertEquals("book_61", pages.last().last().id)
    }

    /**
     * 验证中文章回、Markdown标题和英文Chapter标题都能生成可点击目录，并保留来源页码。
     *
     * @return 无返回值；章节遗漏、顺序或页码错误时由JUnit报告失败。
     */
    @Test
    fun tableOfContentsRecognizesCommonChapterStyles() {
        val chapters = buildEbookTableOfContents(
            listOf(
                "序\n\n这是序言正文。",
                "第一回 风雪夜归人\n\n第一回正文。",
                "## 第二章 新的开始\n\n第二章正文。",
                "Chapter III The Journey\n\nEnglish chapter text."
            )
        )

        assertEquals(
            listOf("序", "第一回 风雪夜归人", "第二章 新的开始", "Chapter III The Journey"),
            chapters.map { chapter -> chapter.title }
        )
        assertEquals(listOf(0, 1, 2, 3), chapters.map { chapter -> chapter.pageIndex })
        assertEquals(2, chapters[2].level)
    }

    /**
     * 验证章节编号与下一行短标题会组成完整目录名，而普通正文不会被误接到章节编号后。
     *
     * @return 无返回值；副标题遗漏或正文被错误合并时由JUnit报告失败。
     */
    @Test
    fun tableOfContentsKeepsDetailedChineseChapterTitles() {
        val chapters = buildEbookTableOfContents(
            listOf(
                "第一章\n山边小村\n这是第一章正文。",
                "第二章\n韩立睁开眼睛，看见窗外已经天亮了。",
                "第三章 墨大夫"
            )
        )

        assertEquals(
            listOf("第一章 山边小村", "第二章", "第三章 墨大夫"),
            chapters.map { chapter -> chapter.title }
        )
        assertEquals(listOf(0, 1, 2), chapters.map { chapter -> chapter.pageIndex })
    }

    /**
     * 验证自定义书脊RGB通道会限制到合法范围，并固定使用完全不透明Alpha。
     *
     * @return 无返回值；ARGB位组合或边界限制错误时由JUnit报告失败。
     */
    @Test
    fun customSpineColorCreatesOpaqueArgb() {
        assertEquals(0xFF0C2238.toInt(), createOpaqueArgb(12, 34, 56))
        assertEquals(0xFFFF00FF.toInt(), createOpaqueArgb(999, -5, 255))
    }

    /**
     * 验证长按拖动可跨越单页三十本边界，并保持除目标书以外的相对顺序。
     *
     * @return 无返回值；移动位置或剩余顺序错误时由JUnit报告失败。
     */
    @Test
    fun shelfDragCanMoveBookAcrossShelfPages() {
        val books = (1..35).map(::createTestBook)
        val moved = moveEbookShelfBook(books, bookId = "book_2", targetIndex = 31)

        assertEquals("book_2", moved[31].id)
        assertEquals("book_1", moved.first().id)
        assertEquals("book_3", moved[1].id)
        assertEquals(books.map(EbookBook::id).toSet(), moved.map(EbookBook::id).toSet())
    }

    /**
     * 验证忘记密码申请严格等待24小时，并在截止时刻变为可完成恢复。
     *
     * @return 无返回值；倒计时计算错误时由JUnit报告失败。
     */
    @Test
    fun appLockRecoveryWaitsForOneFullDay() {
        val requestedAt = 10_000L
        val state = AppLockState(
            enabled = true,
            recoveryRequestedAtMillis = requestedAt
        )

        assertEquals(
            APP_LOCK_RECOVERY_WAIT_MILLIS,
            state.recoveryRemainingMillis(requestedAt)
        )
        assertEquals(
            1L,
            state.recoveryRemainingMillis(requestedAt + APP_LOCK_RECOVERY_WAIT_MILLIS - 1L)
        )
        assertEquals(
            0L,
            state.recoveryRemainingMillis(requestedAt + APP_LOCK_RECOVERY_WAIT_MILLIS)
        )
    }

    /**
     * 验证经典蓝不绑定任何角色图片，其余角色主题各自绑定独立的离线资源名。
     *
     * @return 无返回值；纯色主题误用图片、人物资源缺失或重复时由JUnit报告失败。
     */
    @Test
    fun visualThemesUseUniqueOfflineArtwork() {
        val artworkNames = AppVisualTheme.entries.map(AppVisualTheme::artResourceName)
        val characterArtworkNames = artworkNames.drop(1)

        assertEquals(11, artworkNames.size)
        assertTrue(artworkNames.first().isBlank())
        assertEquals(characterArtworkNames.size, characterArtworkNames.distinct().size)
        assertTrue(characterArtworkNames.all(String::isNotBlank))
    }

    /** @return 书架分页测试使用的最小完整书籍模型。 */
    private fun createTestBook(index: Int): EbookBook {
        return EbookBook(
            id = "book_$index",
            title = "测试书籍$index",
            author = "作者$index",
            format = EbookFormat.TEXT,
            originalFileName = "book_$index.txt",
            storedFileName = "book_$index.txt",
            extractedTextFileName = "book_$index.txt",
            fileSizeBytes = index.toLong(),
            createdAtMillis = index.toLong(),
            updatedAtMillis = index.toLong()
        )
    }
}
