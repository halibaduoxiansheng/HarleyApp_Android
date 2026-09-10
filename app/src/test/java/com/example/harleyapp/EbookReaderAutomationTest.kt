package com.example.harleyapp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.harleyapp.system.buildEbookSpeechChunks
import com.example.harleyapp.system.mapEbookSpeechChunkRange
import com.example.harleyapp.system.resolveNextEbookAutomaticPage
import com.example.harleyapp.system.shouldScheduleEbookTimedPageTurn
import com.example.harleyapp.ui.screens.resolveEbookShelfEdgePagingDirection
import com.example.harleyapp.ui.screens.resolveEbookShelfTargetSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证电子书定时自动翻页、连续朗读互斥和朗读高亮范围的纯逻辑边界。
 *
 * 使用方法：
 * 本测试类只调用不依赖Android设备和系统TTS实例的纯函数，可通过Gradle的`testDebugUnitTest`
 * 任务直接运行。涉及真实TTS音色、系统回调时机和最终背景色观感的内容仍需另做实机验证。
 *
 * @return 无返回值；任一自动翻页或朗读范围规则回归时由JUnit报告失败。
 */
class EbookReaderAutomationTest {

    /**
     * 验证一次自动翻页事件只把当前页向后推进一页，不会跨页或重复推进。
     *
     * 使用方法：
     * 模拟定时倒计时或一页连续朗读完成后，把当前零基页码和总页数传给
     * [resolveNextEbookAutomaticPage]，并检查结果严格等于当前页加一。
     *
     * @return 无返回值；一次事件前进超过一页或没有前进时由JUnit报告失败。
     */
    @Test
    fun automaticPageTurnAdvancesExactlyOnePage() {
        assertEquals(1, resolveNextEbookAutomaticPage(currentPage = 0, pageCount = 8))
        assertEquals(5, resolveNextEbookAutomaticPage(currentPage = 4, pageCount = 8))
    }

    /**
     * 验证书末、负页码、越界页码和无有效页面时不会生成下一页。
     *
     * 使用方法：
     * 分别传入末页以及阅读状态恢复期间可能出现的非法边界值；调用方收到`null`后应保持
     * 当前页不变，并停止本轮自动翻页或连续朗读推进。
     *
     * @return 无返回值；非法状态仍返回可跳转页码时由JUnit报告失败。
     */
    @Test
    fun automaticPageTurnReturnsNullAtEndAndForInvalidState() {
        assertNull(resolveNextEbookAutomaticPage(currentPage = 7, pageCount = 8))
        assertNull(resolveNextEbookAutomaticPage(currentPage = 0, pageCount = 1))
        assertNull(resolveNextEbookAutomaticPage(currentPage = -1, pageCount = 8))
        assertNull(resolveNextEbookAutomaticPage(currentPage = 8, pageCount = 8))
        assertNull(resolveNextEbookAutomaticPage(currentPage = 0, pageCount = 0))
    }

    /**
     * 验证连续朗读开启后定时自动翻页暂停，防止TTS完成回调与倒计时同时推进两页。
     *
     * 使用方法：
     * 保持阅读器前台、正文就绪且没有弹层或动画，只切换[shouldScheduleEbookTimedPageTurn]
     * 的连续朗读参数；普通自动翻页应允许调度，连续朗读状态必须拒绝调度。
     *
     * @return 无返回值；两套翻页来源可能同时运行时由JUnit报告失败。
     */
    @Test
    fun timedPageTurnIsMutuallyExclusiveWithContinuousReading() {
        assertTrue(shouldScheduleTimedPageTurn())
        assertFalse(shouldScheduleTimedPageTurn(continuousReading = true))
    }

    /**
     * 验证阅读器进入后台、显示弹层、正在滚动或正文尚未准备好时都会暂停倒计时。
     *
     * 使用方法：
     * 从一个可正常调度的基准状态开始，每次只改变一个暂停条件，确保各条件都能独立阻止
     * 自动翻页；同时验证用户关闭功能或已经到达末页时不会残留定时任务。
     *
     * @return 无返回值；任一不可阅读状态仍启动倒计时时由JUnit报告失败。
     */
    @Test
    fun timedPageTurnPausesForBackgroundOverlayScrollAndUnreadyContent() {
        assertFalse(shouldScheduleTimedPageTurn(readerForeground = false))
        assertFalse(shouldScheduleTimedPageTurn(overlayVisible = true))
        assertFalse(shouldScheduleTimedPageTurn(pageScrollInProgress = true))
        assertFalse(shouldScheduleTimedPageTurn(contentReady = false))
        assertFalse(shouldScheduleTimedPageTurn(enabled = false))
        assertFalse(shouldScheduleTimedPageTurn(currentPage = 5, pageCount = 6))
    }

    /**
     * 验证定时自动翻页只依赖页面是否就绪，不会因为内容来自PDF而被纯逻辑层额外禁止。
     *
     * 使用方法：
     * PDF渲染完成后由调用方传入`contentReady = true`；本函数没有格式参数，因此与普通文本页
     * 共用相同调度规则。这里使用完整可读状态确认其可以启动倒计时。
     *
     * @return 无返回值；纯逻辑层错误加入格式限制导致可读PDF不能翻页时由JUnit报告失败。
     */
    @Test
    fun timedPageTurnAllowsReadyPdfContentWithoutFormatRestriction() {
        assertTrue(shouldScheduleTimedPageTurn(contentReady = true))
    }

    /**
     * 验证自然朗读会保留中文句末标点，并按句号、问号、感叹号和分号生成独立朗读块。
     *
     * 使用方法：
     * 传入长度远小于单块上限的中文正文，避免长度切分干扰标点规则；随后检查每个块仍包含
     * 原标点，且全部块重新拼接后与页面原文逐字符一致。
     *
     * @return 无返回值；中文标点丢失、句子未分开或原文顺序改变时由JUnit报告失败。
     */
    @Test
    fun naturalSpeechChunksSplitAtChinesePunctuation() {
        val source = "第一句。第二句？第三句！第四句；最后一句。"
        val chunks = buildEbookSpeechChunks(
            text = source,
            maxChunkLength = 128,
            naturalReadingEnabled = true
        )

        assertEquals(
            listOf("第一句。", "第二句？", "第三句！", "第四句；", "最后一句。"),
            chunks.map { chunk -> chunk.text }
        )
        assertEquals(source, chunks.joinToString(separator = "") { chunk -> chunk.text })
        assertTrue(chunks.all { chunk -> chunk.pauseAfterMillis > 0L })
    }

    /**
     * 验证自然朗读会识别英文半角句末标点，同时把句间空格保留在原文范围内。
     *
     * 使用方法：
     * 使用包含英文句号、问号、感叹号和分号的页面文本进行分块；断言空格跟随上一句保留，
     * 使各块起止偏移可以直接映射回页面，而不需要对显示正文做二次搜索。
     *
     * @return 无返回值；英文标点边界、空格归属或原文拼接发生回归时由JUnit报告失败。
     */
    @Test
    fun naturalSpeechChunksSplitAtEnglishPunctuation() {
        val source = "First sentence. Second question? Third answer! Fourth clause; Final sentence."
        val chunks = buildEbookSpeechChunks(
            text = source,
            maxChunkLength = 128,
            naturalReadingEnabled = true
        )

        assertEquals(
            listOf(
                "First sentence. ",
                "Second question? ",
                "Third answer! ",
                "Fourth clause; ",
                "Final sentence."
            ),
            chunks.map { chunk -> chunk.text }
        )
        assertEquals(source, chunks.joinToString(separator = "") { chunk -> chunk.text })
    }

    /**
     * 验证超长中英文混合正文会被限制在TTS单块上限内，并且不会丢失UTF-16字符或表情符号。
     *
     * 使用方法：
     * 构造明显超过单块上限、包含中文、英文、逗号和代理项表情的长句；检查每块长度、连续
     * 起止偏移、原文子串以及完整拼接结果，确保分块只改变提交批次而不改变页面正文。
     *
     * @return 无返回值；超长分块越界、偏移断裂、代理项损坏或任一字符丢失时由JUnit报告失败。
     */
    @Test
    fun longSpeechChunksPreserveEveryCharacterAndContinuousOffsets() {
        val source = (1..80).joinToString(separator = "，", postfix = "。") { index ->
            "第${index}段English🙂内容"
        }
        val maxChunkLength = 47
        val chunks = buildEbookSpeechChunks(
            text = source,
            maxChunkLength = maxChunkLength,
            naturalReadingEnabled = true
        )

        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { chunk -> chunk.text.length <= maxChunkLength })
        assertEquals(source, chunks.joinToString(separator = "") { chunk -> chunk.text })
        chunks.forEachIndexed { index, chunk ->
            val expectedStart = if (index == 0) 0 else chunks[index - 1].endOffsetExclusive
            assertEquals(expectedStart, chunk.startOffset)
            assertEquals(source.substring(chunk.startOffset, chunk.endOffsetExclusive), chunk.text)
            assertFalse(Character.isHighSurrogate(chunk.text.last()))
            assertFalse(Character.isLowSurrogate(chunk.text.first()))
        }
        assertEquals(source.length, chunks.last().endOffsetExclusive)
    }

    /**
     * 验证普通朗读按固定长度生成的每个块，都携带可直接定位到当前页面的起止偏移。
     *
     * 使用方法：
     * 对一段长度可预测的正文设置五个UTF-16代码单元的上限；检查每块的零基起点、不包含式
     * 终点以及对应页面子串，保证TTS块内回调可通过块起点换算为页内范围。
     *
     * @return 无返回值；页内偏移不连续、终点包含规则错误或子串不匹配时由JUnit报告失败。
     */
    @Test
    fun speechChunksExposePageRelativeStartAndEndOffsets() {
        val source = "0123456789中文ABC"
        val chunks = buildEbookSpeechChunks(
            text = source,
            maxChunkLength = 5,
            naturalReadingEnabled = false
        )

        assertEquals(listOf(0, 5, 10), chunks.map { chunk -> chunk.startOffset })
        assertEquals(listOf(5, 10, source.length), chunks.map { chunk -> chunk.endOffsetExclusive })
        assertEquals(
            chunks.map { chunk -> source.substring(chunk.startOffset, chunk.endOffsetExclusive) },
            chunks.map { chunk -> chunk.text }
        )
    }

    /**
     * 验证TTS回传的块内范围会叠加当前块起点，得到准确的整页正文高亮范围。
     *
     * 使用方法：
     * 先按固定长度生成多个朗读块，再选择起点不为零的第二块模拟`onRangeStart`；块内一到四
     * 的范围应映射到整页六到九，且该整页子串必须与块内正在朗读的子串相同。
     *
     * @return 无返回值；块内范围未叠加页内偏移或不包含式终点计算错误时由JUnit报告失败。
     */
    @Test
    fun speechChunkRangeMapsToWholePageOffsets() {
        val source = "0123456789ABCDE"
        val chunk = buildEbookSpeechChunks(source, 5, naturalReadingEnabled = false)[1]
        val mappedRange = mapEbookSpeechChunkRange(
            chunk = chunk,
            chunkStart = 1,
            chunkEndExclusive = 4
        )

        requireNotNull(mappedRange)
        assertEquals(6, mappedRange.startOffset)
        assertEquals(9, mappedRange.endOffsetExclusive)
        assertEquals(
            chunk.text.substring(1, 4),
            source.substring(mappedRange.startOffset, mappedRange.endOffsetExclusive)
        )
    }

    /**
     * 验证系统TTS偶发回传负数、超出块长度、空范围或反向范围时不会产生越界高亮。
     *
     * 使用方法：
     * 对第二个朗读块传入两端越界值，合法结果应被夹紧到该块完整页内范围；随后传入相等、
     * 反向和完全落在块尾之外的范围，均应返回`null`供页面清除或忽略高亮。
     *
     * @return 无返回值；高亮越过当前块或无效范围仍被页面接受时由JUnit报告失败。
     */
    @Test
    fun speechChunkRangeClampsOutOfBoundsAndRejectsEmptyRanges() {
        val source = "0123456789ABCDE"
        val chunk = buildEbookSpeechChunks(source, 5, naturalReadingEnabled = false)[1]
        val clampedRange = mapEbookSpeechChunkRange(
            chunk = chunk,
            chunkStart = -20,
            chunkEndExclusive = 200
        )

        requireNotNull(clampedRange)
        assertEquals(chunk.startOffset, clampedRange.startOffset)
        assertEquals(chunk.endOffsetExclusive, clampedRange.endOffsetExclusive)
        assertNull(mapEbookSpeechChunkRange(chunk, chunkStart = 2, chunkEndExclusive = 2))
        assertNull(mapEbookSpeechChunkRange(chunk, chunkStart = 4, chunkEndExclusive = 1))
        assertNull(mapEbookSpeechChunkRange(chunk, chunkStart = 20, chunkEndExclusive = 30))
    }

    /**
     * 验证自然朗读按语句切块并附加韵律，而普通朗读在长度允许时保持单块和中性参数。
     *
     * 使用方法：
     * 对同一页短正文分别开启和关闭自然朗读；两种模式都必须完整保留原文，但自然模式应按
     * 句末拆分并产生停顿，普通模式不应额外改变速度、音高或增加停顿。
     *
     * @return 无返回值；两种模式行为混淆或任一模式改变原文时由JUnit报告失败。
     */
    @Test
    fun naturalAndRegularSpeechModesUseDifferentChunkCadence() {
        val source = "第一句很短。第二句也很短。"
        val naturalChunks = buildEbookSpeechChunks(source, 128, naturalReadingEnabled = true)
        val regularChunks = buildEbookSpeechChunks(source, 128, naturalReadingEnabled = false)

        assertEquals(2, naturalChunks.size)
        assertTrue(naturalChunks.all { chunk -> chunk.pauseAfterMillis > 0L })
        assertEquals(1, regularChunks.size)
        assertEquals(0L, regularChunks.single().pauseAfterMillis)
        assertEquals(1f, regularChunks.single().speechRateMultiplier)
        assertEquals(1f, regularChunks.single().pitch)
        assertEquals(source, naturalChunks.joinToString(separator = "") { chunk -> chunk.text })
        assertEquals(source, regularChunks.single().text)
    }

    /**
     * 验证手指位于完整槽位矩形内部时，会直接命中该槽位而不受其他槽位中心距离影响。
     *
     * 使用方法：
     * 为当前第一页提供两个真实根坐标矩形，并把手指放入第七号绝对槽位内部；解析函数应直接
     * 返回七，作为拖动结束后移动书籍的唯一目标槽位。
     *
     * @return 无返回值；格内坐标被误判为相邻槽位时由JUnit报告失败。
     */
    @Test
    fun shelfTargetSlotPreciselyHitsContainingBounds() {
        val slotBounds = mapOf(
            7 to Rect(left = 84f, top = 40f, right = 94f, bottom = 70f),
            8 to Rect(left = 96f, top = 40f, right = 106f, bottom = 70f)
        )

        assertEquals(
            7,
            resolveEbookShelfTargetSlot(
                pointerInRoot = Offset(x = 92f, y = 66f),
                currentPage = 0,
                slotBoundsBySlot = slotBounds,
                nearbyTolerancePx = 2f
            )
        )
    }

    /**
     * 验证一次快速大幅拖动会按最终绝对坐标直接落到最终格，不依赖中间移动事件数量。
     *
     * 使用方法：
     * 构造第一页完整三行三十槽的真实坐标，先确认按下位置位于零号槽，再把下一帧手指坐标
     * 直接移动到二十九号槽；最终结果必须立即为二十九，不能只前进一步或沿途累计漂移。
     *
     * @return 无返回值；快速拖动仍按旧增量步进或落在中间格时由JUnit报告失败。
     */
    @Test
    fun shelfTargetSlotUsesFinalCoordinateAfterFastLargeMove() {
        val slotBounds = buildShelfPageSlotBounds(firstSlot = 0)

        assertEquals(
            0,
            resolveEbookShelfTargetSlot(
                pointerInRoot = Offset(x = 5f, y = 15f),
                currentPage = 0,
                slotBoundsBySlot = slotBounds,
                nearbyTolerancePx = 2f
            )
        )
        assertEquals(
            29,
            resolveEbookShelfTargetSlot(
                pointerInRoot = Offset(x = 113f, y = 79f),
                currentPage = 0,
                slotBoundsBySlot = slotBounds,
                nearbyTolerancePx = 2f
            )
        )
    }

    /**
     * 验证手指落在相邻书脊之间两像素空隙时，会选择中心点距离更近的一侧。
     *
     * 使用方法：
     * 两个槽位宽十像素，中间保留从十到十二的两像素间隙；分别把手指放在间隙靠左和靠右
     * 的位置，解析结果应稳定指向最近中心，避免沿书脊边缘拖动时目标来回飘动。
     *
     * @return 无返回值；间隙坐标未选择最近槽位或左右结果颠倒时由JUnit报告失败。
     */
    @Test
    fun shelfTargetSlotUsesNearestCenterInsideTwoPixelGap() {
        val slotBounds = mapOf(
            0 to Rect(left = 0f, top = 0f, right = 10f, bottom = 30f),
            1 to Rect(left = 12f, top = 0f, right = 22f, bottom = 30f)
        )

        assertEquals(
            0,
            resolveEbookShelfTargetSlot(Offset(10.25f, 15f), 0, slotBounds, 2f)
        )
        assertEquals(
            1,
            resolveEbookShelfTargetSlot(Offset(11.75f, 15f), 0, slotBounds, 2f)
        )
    }

    /**
     * 验证坐标命中只在当前书架页的三十个绝对槽位中解析，不会选中相同位置的相邻页槽位。
     *
     * 使用方法：
     * 为第一页零至二十九槽和第二页三十至五十九槽提供一组重叠根坐标，模拟分页器预加载相邻
     * 页面；同一手指位置在第一页应返回十七，在第二页应返回四十七。
     *
     * @return 无返回值；预加载页面的重叠边界串入当前页候选集合时由JUnit报告失败。
     */
    @Test
    fun shelfTargetSlotOnlyConsidersThirtySlotsOnCurrentPage() {
        val firstPageBounds = buildShelfPageSlotBounds(firstSlot = 0)
        val secondPageBounds = buildShelfPageSlotBounds(firstSlot = 30)
        val allBounds = firstPageBounds + secondPageBounds
        val pointer = Offset(x = 89f, y = 47f)

        assertEquals(17, resolveEbookShelfTargetSlot(pointer, 0, allBounds, 2f))
        assertEquals(47, resolveEbookShelfTargetSlot(pointer, 1, allBounds, 2f))
    }

    /**
     * 验证拖到书架槽位区域之外时不会强制吸附到某个“最近槽”。
     *
     * 使用方法：
     * 构造一页真实槽位后，分别把手指放到标题上方、书架下方和右侧远处。三个位置到最近槽边缘
     * 都超过两像素容差，解析结果必须为null，拖动结束逻辑据此保留书籍原槽。
     *
     * @return 无返回值；越界坐标仍返回槽号时由JUnit报告失败。
     */
    @Test
    fun shelfTargetSlotRejectsPointerOutsideNearbyTolerance() {
        val slotBounds = buildShelfPageSlotBounds(firstSlot = 0)

        assertNull(resolveEbookShelfTargetSlot(Offset(40f, -8f), 0, slotBounds, 2f))
        assertNull(resolveEbookShelfTargetSlot(Offset(40f, 110f), 0, slotBounds, 2f))
        assertNull(resolveEbookShelfTargetSlot(Offset(145f, 40f), 0, slotBounds, 2f))
    }

    /**
     * 验证边缘翻架必须同时满足横向边缘、纵向仍在书架内以及存在相邻页。
     *
     * 使用方法：
     * 在两页书架上把手指放入第一页右边缘应返回1；保持相同横坐标但移到书架上方或下方必须
     * 返回0。末页右缘和第一页左缘也必须返回0，避免越界或上下方误触自动翻架。
     *
     * @return 无返回值；任一边缘或纵向约束失效时由JUnit报告失败。
     */
    @Test
    fun shelfEdgePagingRequiresPointerInsideVerticalShelfBounds() {
        val shelfBounds = Rect(left = 10f, top = 20f, right = 130f, bottom = 120f)

        assertEquals(
            1,
            resolveEbookShelfEdgePagingDirection(Offset(128f, 70f), shelfBounds, 0, 2, 8f)
        )
        assertEquals(
            0,
            resolveEbookShelfEdgePagingDirection(Offset(128f, 12f), shelfBounds, 0, 2, 8f)
        )
        assertEquals(
            0,
            resolveEbookShelfEdgePagingDirection(Offset(128f, 128f), shelfBounds, 0, 2, 8f)
        )
        assertEquals(
            0,
            resolveEbookShelfEdgePagingDirection(Offset(128f, 70f), shelfBounds, 1, 2, 8f)
        )
        assertEquals(
            0,
            resolveEbookShelfEdgePagingDirection(Offset(12f, 70f), shelfBounds, 0, 2, 8f)
        )
    }

    /**
     * 生成一页十列三行书架槽位的稳定根坐标，供真实坐标命中测试复用。
     *
     * 使用方法：
     * 传入当前页第一个绝对槽位；每格宽十像素、高二十像素，横向和纵向均保留两像素间隙，
     * 返回的三十个矩形可直接交给[resolveEbookShelfTargetSlot]。
     *
     * @param firstSlot 当前书架页第一个绝对槽位，第一页为0、第二页为30。
     * @return 包含当前页三十个绝对槽位及其根坐标矩形的映射。
     */
    private fun buildShelfPageSlotBounds(firstSlot: Int): Map<Int, Rect> {
        return (0 until 30).associate { localSlot ->
            val column = localSlot % 10
            val row = localSlot / 10
            val left = column * 12f
            val top = row * 32f
            firstSlot + localSlot to Rect(
                left = left,
                top = top,
                right = left + 10f,
                bottom = top + 30f
            )
        }
    }

    /**
     * 生成自动翻页调度测试使用的完整基准状态，并允许单独覆盖一个边界条件。
     *
     * 使用方法：
     * 各测试只传入需要改变的命名参数，其余参数保持为前台、无弹层、无滚动、正文就绪且尚未
     * 到达末页的状态，避免大量重复参数掩盖测试意图。
     *
     * @param enabled 用户是否开启定时自动翻页。
     * @param continuousReading 是否正在连续朗读。
     * @param readerForeground 阅读器是否位于前台RESUMED状态。
     * @param overlayVisible 是否存在遮挡正文的设置或目录弹层。
     * @param pageScrollInProgress 当前是否仍在执行手势或程序翻页动画。
     * @param contentReady 当前文本页或PDF页面是否已经准备完成。
     * @param currentPage 当前零基页码。
     * @param pageCount 当前书籍总页数。
     * @return 当前组合状态是否允许启动一次定时翻页倒计时。
     */
    private fun shouldScheduleTimedPageTurn(
        enabled: Boolean = true,
        continuousReading: Boolean = false,
        readerForeground: Boolean = true,
        overlayVisible: Boolean = false,
        pageScrollInProgress: Boolean = false,
        contentReady: Boolean = true,
        currentPage: Int = 2,
        pageCount: Int = 6
    ): Boolean {
        return shouldScheduleEbookTimedPageTurn(
            enabled = enabled,
            continuousReading = continuousReading,
            readerForeground = readerForeground,
            overlayVisible = overlayVisible,
            pageScrollInProgress = pageScrollInProgress,
            contentReady = contentReady,
            currentPage = currentPage,
            pageCount = pageCount
        )
    }
}
