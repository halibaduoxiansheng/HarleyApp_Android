package com.example.harleyapp

import androidx.compose.ui.unit.IntSize
import com.example.harleyapp.data.APP_LOCK_RECOVERY_WAIT_MILLIS
import com.example.harleyapp.data.AppLockState
import com.example.harleyapp.data.normalizeEbookShelfSlots
import com.example.harleyapp.data.validateEbookPaginationBoundaries
import com.example.harleyapp.model.AppVisualTheme
import com.example.harleyapp.model.EbookBook
import com.example.harleyapp.model.EbookFormat
import com.example.harleyapp.model.EbookShelfSkin
import com.example.harleyapp.system.detectEbookLanguageCode
import com.example.harleyapp.system.splitEbookTranslationText
import com.example.harleyapp.ui.screens.EbookMeasuredTextPage
import com.example.harleyapp.ui.screens.buildEbookShelfPages
import com.example.harleyapp.ui.screens.buildEbookTableOfContents
import com.example.harleyapp.ui.screens.calculateEbookScrollbarDragFraction
import com.example.harleyapp.ui.screens.calculateEbookScrollbarScrollFraction
import com.example.harleyapp.ui.screens.calculateEbookScrollbarScrollTarget
import com.example.harleyapp.ui.screens.calculateEbookScrollbarThumbHeight
import com.example.harleyapp.ui.screens.createOpaqueArgb
import com.example.harleyapp.ui.screens.findEbookPageIndexForExcerpt
import com.example.harleyapp.ui.screens.findEbookPageIndexForOffset
import com.example.harleyapp.ui.screens.measuredEbookPagesToBoundaries
import com.example.harleyapp.ui.screens.moveEbookShelfBookToSlot
import com.example.harleyapp.ui.screens.normalizeEbookNoteSelection
import com.example.harleyapp.ui.screens.paginateEbookText
import com.example.harleyapp.ui.screens.resolveEbookReaderPageCount
import com.example.harleyapp.ui.screens.resolveEbookPaginationViewportSize
import com.example.harleyapp.ui.screens.restoreMeasuredEbookPagesFromBoundaries
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
     * 验证沉浸模式仅增加可用高度时沿用当前分页尺寸，而旋转屏幕改变宽度后仍采用新尺寸。
     *
     * @return 无返回值；沉浸切换触发无谓分页或横竖屏变化未更新尺寸时由JUnit报告失败。
     */
    @Test
    fun ebookImmersiveModeRetainsPaginationUntilWidthChanges() {
        val normalSize = IntSize(width = 1080, height = 2010)
        val immersiveSize = IntSize(width = 1080, height = 2190)
        val landscapeSize = IntSize(width = 2190, height = 950)

        assertEquals(
            normalSize,
            resolveEbookPaginationViewportSize(
                previousSize = IntSize.Zero,
                measuredSize = normalSize,
                retainHeightOnlyChange = false
            )
        )
        assertEquals(
            normalSize,
            resolveEbookPaginationViewportSize(
                previousSize = normalSize,
                measuredSize = immersiveSize,
                retainHeightOnlyChange = true
            )
        )
        assertEquals(
            landscapeSize,
            resolveEbookPaginationViewportSize(
                previousSize = normalSize,
                measuredSize = landscapeSize,
                retainHeightOnlyChange = true
            )
        )
    }

    /**
     * 验证分页边界缓存可以完整压缩并恢复页面，同时拒绝断裂或未覆盖全文的损坏数组。
     *
     * @return 无返回值；缓存往返丢字或损坏边界被接受时由JUnit报告失败。
     */
    @Test
    fun ebookPaginationBoundariesRoundTripAndRejectCorruption() {
        val text = "第一页正文。第二页正文更长。第三页结束。"
        val pages = listOf(
            EbookMeasuredTextPage(text.substring(0, 6), 0, 6),
            EbookMeasuredTextPage(text.substring(6, 14), 6, 14),
            EbookMeasuredTextPage(text.substring(14), 14, text.length)
        )
        val boundaries = measuredEbookPagesToBoundaries(pages)
        val restored = restoreMeasuredEbookPagesFromBoundaries(text, boundaries)

        assertTrue(validateEbookPaginationBoundaries(boundaries, text.length))
        assertEquals(pages, restored)
        assertTrue(!validateEbookPaginationBoundaries(intArrayOf(0, 6, 7, text.length), text.length))
        assertTrue(!validateEbookPaginationBoundaries(intArrayOf(0, 6), text.length))
    }

    /**
     * 验证没有槽位字段的旧书按原shelfOrder迁移，并保留已有合法空槽。
     *
     * @return 无返回值；旧顺序丢失、合法槽位被挤紧或下架书取得槽位时由JUnit报告失败。
     */
    @Test
    fun legacyShelfOrderMigratesWithoutCompactingValidSlots() {
        val legacyLater = createTestBook(1).copy(shelfSlot = -1, shelfOrder = 200L)
        val legacyEarlier = createTestBook(2).copy(shelfSlot = -1, shelfOrder = 100L)
        val positioned = createTestBook(3).copy(shelfSlot = 8, shelfOrder = 8L)
        val removed = createTestBook(4).copy(isOnShelf = false, shelfSlot = 5)
        val normalized = normalizeEbookShelfSlots(
            listOf(legacyLater, legacyEarlier, positioned, removed)
        )

        assertEquals(1, normalized.first { it.id == legacyLater.id }.shelfSlot)
        assertEquals(0, normalized.first { it.id == legacyEarlier.id }.shelfSlot)
        assertEquals(8, normalized.first { it.id == positioned.id }.shelfSlot)
        assertEquals(-1, normalized.first { it.id == removed.id }.shelfSlot)
    }

    /**
     * 验证字号、字体或屏幕尺寸改变后，可以用完整正文字符位置找回包含该位置的新页面。
     *
     * @return 无返回值；页首、页尾或越界位置映射错误时由JUnit报告失败。
     */
    @Test
    fun dynamicPaginationRestoresPageFromTextOffset() {
        val pages = listOf(
            EbookMeasuredTextPage("第一页", startOffset = 0, endOffset = 100),
            EbookMeasuredTextPage("第二页", startOffset = 100, endOffset = 245),
            EbookMeasuredTextPage("第三页", startOffset = 245, endOffset = 400)
        )

        assertEquals(0, findEbookPageIndexForOffset(pages, -20))
        assertEquals(0, findEbookPageIndexForOffset(pages, 99))
        assertEquals(1, findEbookPageIndexForOffset(pages, 100))
        assertEquals(1, findEbookPageIndexForOffset(pages, 244))
        assertEquals(2, findEbookPageIndexForOffset(pages, 245))
        assertEquals(2, findEbookPageIndexForOffset(pages, 999))
    }

    /**
     * 验证旧笔记在真实屏幕重新分页后，会按摘录起点跳到新页，并在重复摘录中选择靠近原页的一处。
     *
     * @return 无返回值；摘录定位、重复内容选择或找不到时的回退页错误时由JUnit报告失败。
     */
    @Test
    fun ebookNoteExcerptRemapsToDynamicPage() {
        val repeatedExcerpt = "这是需要保留的摘录"
        val firstPageText = "开头内容。$repeatedExcerpt。"
        val secondPageText = "中间内容。"
        val thirdPageText = "靠后的章节。$repeatedExcerpt。结束。"
        val fullText = firstPageText + secondPageText + thirdPageText
        val secondStart = firstPageText.length
        val thirdStart = secondStart + secondPageText.length
        val pages = listOf(
            EbookMeasuredTextPage(firstPageText, 0, secondStart),
            EbookMeasuredTextPage(secondPageText, secondStart, thirdStart),
            EbookMeasuredTextPage(thirdPageText, thirdStart, fullText.length)
        )

        assertEquals(
            2,
            findEbookPageIndexForExcerpt(
                pages = pages,
                fullText = fullText,
                excerpt = repeatedExcerpt,
                fallbackPageIndex = 2
            )
        )
        assertEquals(
            1,
            findEbookPageIndexForExcerpt(
                pages = pages,
                fullText = fullText,
                excerpt = "不存在的摘录",
                fallbackPageIndex = 1
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

        assertEquals(listOf(30, 30, 30), pages.map { page -> page.size })
        assertEquals(listOf(30, 30, 1), pages.map { page -> page.count { it != null } })
        assertEquals("book_1", pages.first().first()?.id)
        assertEquals("book_61", pages.last().first()?.id)
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
     * 验证目录扫描可以跨阅读页取得章节副标题，并在达到上限后停止访问后续超大正文。
     *
     * @return 无返回值；跨页副标题丢失或达到上限后仍继续扫描时由JUnit报告失败。
     */
    @Test
    fun tableOfContentsStreamsLinesAndStopsAtLimit() {
        val pages = object : AbstractList<String>() {
            override val size: Int = 2_002

            override fun get(index: Int): String {
                return when {
                    index == 0 -> "第一章"
                    index == 1 -> "山边小村"
                    index in 2..2_000 -> "第${index}章 标题$index"
                    else -> error("目录达到上限后不应继续访问正文")
                }
            }
        }

        val chapters = buildEbookTableOfContents(pages)

        assertEquals(2_000, chapters.size)
        assertEquals("第一章 山边小村", chapters.first().title)
        assertEquals(0, chapters.first().pageIndex)
        assertEquals("第2000章 标题2000", chapters.last().title)
    }

    /**
     * 验证目录滑轮会按可见比例设置滑块高度，并保证超长目录仍保留足够大的触摸区域。
     *
     * @return 无返回值；比例高度、最小触摸高度或非法输入处理错误时由JUnit报告失败。
     */
    @Test
    fun tableOfContentsScrollbarKeepsVisibleAndTouchableThumb() {
        assertEquals(
            200f,
            calculateEbookScrollbarThumbHeight(
                trackHeightPx = 400f,
                minimumThumbHeightPx = 48f,
                contentSize = 800,
                viewportSize = 400
            ),
            0.001f
        )
        assertEquals(
            48f,
            calculateEbookScrollbarThumbHeight(
                trackHeightPx = 400f,
                minimumThumbHeightPx = 48f,
                contentSize = 80_000,
                viewportSize = 400
            ),
            0.001f
        )
        assertEquals(
            0f,
            calculateEbookScrollbarThumbHeight(
                trackHeightPx = 0f,
                minimumThumbHeightPx = 48f,
                contentSize = 8_000,
                viewportSize = 400
            ),
            0.001f
        )
    }

    /**
     * 验证目录普通滚动时，滑轮进度可以准确覆盖开头、中间和末尾位置。
     *
     * @return 无返回值；首尾固定值或中间项内偏移换算错误时由JUnit报告失败。
     */
    @Test
    fun tableOfContentsScrollbarTracksListPosition() {
        assertEquals(
            0f,
            calculateEbookScrollbarScrollFraction(
                scrollOffset = 0,
                contentSize = 10_000,
                viewportSize = 1_000
            ),
            0.001f
        )
        assertEquals(
            0.5f,
            calculateEbookScrollbarScrollFraction(
                scrollOffset = 4_500,
                contentSize = 10_000,
                viewportSize = 1_000
            ),
            0.001f
        )
        assertEquals(
            1f,
            calculateEbookScrollbarScrollFraction(
                scrollOffset = 9_000,
                contentSize = 10_000,
                viewportSize = 1_000
            ),
            0.001f
        )
    }

    /**
     * 验证手动拖拽会保留滑块内抓取点、限制轨道边界，并直接定位到目标目录项附近。
     *
     * @return 无返回值；拖拽比例、边界限制或目标目录项位置错误时由JUnit报告失败。
     */
    @Test
    fun tableOfContentsScrollbarDragMapsToFullCatalog() {
        assertEquals(
            0.5f,
            calculateEbookScrollbarDragFraction(
                pointerYPx = 190f,
                dragGrabOffsetPx = 10f,
                thumbTravelPx = 360f
            ),
            0.001f
        )
        assertEquals(
            0f,
            calculateEbookScrollbarDragFraction(
                pointerYPx = -20f,
                dragGrabOffsetPx = 10f,
                thumbTravelPx = 360f
            ),
            0.001f
        )
        assertEquals(
            1f,
            calculateEbookScrollbarDragFraction(
                pointerYPx = 500f,
                dragGrabOffsetPx = 10f,
                thumbTravelPx = 360f
            ),
            0.001f
        )
        assertEquals(
            0f,
            calculateEbookScrollbarDragFraction(
                pointerYPx = Float.NaN,
                dragGrabOffsetPx = 10f,
                thumbTravelPx = 360f
            ),
            0.001f
        )
        assertEquals(
            0,
            calculateEbookScrollbarScrollTarget(
                scrollFraction = -1f,
                contentSize = 2_000,
                viewportSize = 800,
                itemCount = 20
            ).itemIndex
        )
        assertEquals(
            6,
            calculateEbookScrollbarScrollTarget(
                scrollFraction = 0.5f,
                contentSize = 2_000,
                viewportSize = 800,
                itemCount = 20
            ).itemIndex
        )
        assertEquals(
            60,
            calculateEbookScrollbarScrollTarget(
                scrollFraction = 0.55f,
                contentSize = 2_000,
                viewportSize = 800,
                itemCount = 20
            ).itemScrollOffset
        )
        assertEquals(
            1_999,
            calculateEbookScrollbarScrollTarget(
                scrollFraction = 1f,
                contentSize = 200_000,
                viewportSize = 800,
                itemCount = 2_000
            ).itemIndex
        )
        assertEquals(
            0,
            calculateEbookScrollbarScrollTarget(
                scrollFraction = Float.NaN,
                contentSize = 2_000,
                viewportSize = 800,
                itemCount = 20
            ).itemIndex
        )
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
     * 验证拖到空槽后书籍停在指定位置，源槽保持为空且其他书不会自动挤紧。
     *
     * @return 无返回值；空槽移动或其他书籍位置被意外改变时由JUnit报告失败。
     */
    @Test
    fun shelfDragPlacesBookIntoAnyEmptySlot() {
        val books = (1..5).map(::createTestBook)
        val moved = moveEbookShelfBookToSlot(books, bookId = "book_2", targetSlot = 24)
        val pages = buildEbookShelfPages(moved)

        assertEquals(24, moved.first { book -> book.id == "book_2" }.shelfSlot)
        assertEquals(null, pages.first()[1])
        assertEquals("book_2", pages.first()[24]?.id)
        assertEquals(2, moved.first { book -> book.id == "book_3" }.shelfSlot)
    }

    /**
     * 验证目标槽已有书籍时只交换两本书的位置，不改变其余书和已有空槽。
     *
     * @return 无返回值；换位影响第三本书时由JUnit报告失败。
     */
    @Test
    fun shelfDragSwapsOnlyOccupiedTargetSlot() {
        val books = (1..5).map(::createTestBook)
        val moved = moveEbookShelfBookToSlot(books, bookId = "book_2", targetSlot = 3)

        assertEquals(3, moved.first { book -> book.id == "book_2" }.shelfSlot)
        assertEquals(1, moved.first { book -> book.id == "book_4" }.shelfSlot)
        assertEquals(2, moved.first { book -> book.id == "book_3" }.shelfSlot)
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

    /**
     * 验证书架提供多种名称唯一的可选皮肤，避免选择弹窗出现重复或空白项目。
     *
     * @return 无返回值；皮肤数量不足或名称重复时由JUnit报告失败。
     */
    @Test
    fun ebookShelfOffersMultipleNamedSkins() {
        val names = EbookShelfSkin.entries.map(EbookShelfSkin::displayName)

        assertTrue(names.size >= 8)
        assertEquals(names.size, names.distinct().size)
        assertTrue(names.all(String::isNotBlank))
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
            updatedAtMillis = index.toLong(),
            shelfOrder = (index - 1).toLong(),
            shelfSlot = index - 1
        )
    }
}
