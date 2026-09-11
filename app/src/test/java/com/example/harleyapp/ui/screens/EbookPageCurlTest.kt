package com.example.harleyapp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证电子书轻量曲面翻页的方向、层级、连续进度和条带几何。
 *
 * 使用方法：
 * 本测试不依赖Android设备或Compose渲染器，可直接运行Gradle的`testDebugUnitTest`任务。
 * 测试通过表示翻页数学状态不会越界、镜像或在半程跳变；最终纸张观感和跟手程度仍需实机观察。
 *
 * @return 无返回值；任一翻页状态或曲面几何回归时由JUnit报告失败。
 */
class EbookPageCurlTest {

    /**
     * 验证Pager还未报告目标页时，可从偏移方向推断相邻页，并且首尾页不会越界。
     *
     * @return 无返回值；目标优先级或页码钳制错误时测试失败。
     */
    @Test
    fun targetPageUsesPagerPredictionThenOffsetFallbackWithinBounds() {
        assertEquals(4, resolveEbookPageCurlTargetPage(3, 3, 4, -0.2f, 6))
        assertEquals(4, resolveEbookPageCurlTargetPage(3, 3, 3, 0.2f, 6))
        assertEquals(2, resolveEbookPageCurlTargetPage(3, 3, 3, -0.2f, 6))
        assertEquals(0, resolveEbookPageCurlTargetPage(0, 0, 0, -0.2f, 6))
        assertEquals(5, resolveEbookPageCurlTargetPage(5, 5, 5, 0.2f, 6))
        assertEquals(0, resolveEbookPageCurlTargetPage(0, 0, 0, 0f, 0))
    }

    /**
     * 验证静止时只有已稳定页面可见，预加载页面不参与绘制或阴影。
     *
     * @return 无返回值；静止页职责、透明度或曲面强度异常时测试失败。
     */
    @Test
    fun restingStateShowsOnlySettledPage() {
        val current = resolveEbookPageCurlVisualState(2, 2, 2, 0f)
        val adjacent = resolveEbookPageCurlVisualState(3, 2, 2, -1f)

        assertEquals(EbookPageCurlLayer.CURRENT, current.layer)
        assertEquals(1f, current.alpha, FLOAT_TOLERANCE)
        assertEquals(1f, current.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(0f, current.curlStrength, FLOAT_TOLERANCE)
        assertEquals(EbookPageCurlLayer.HIDDEN, adjacent.layer)
        assertEquals(0f, adjacent.alpha, FLOAT_TOLERANCE)
    }

    /**
     * 验证翻到后一页时仅当前纸张弯曲，目标页保持为固定底页，且两层共享同一自由边位置。
     *
     * @return 无返回值；正向翻页层级、进度、自由边或弯曲强度错误时测试失败。
     */
    @Test
    fun forwardTurnKeepsTargetFlatAndCurvesOnlyCurrentSheet() {
        val turning = resolveEbookPageCurlVisualState(2, 2, 3, 0.5f)
        val base = resolveEbookPageCurlVisualState(3, 2, 3, -0.5f)

        assertEquals(EbookPageCurlLayer.TURNING, turning.layer)
        assertEquals(EbookPageCurlLayer.BASE, base.layer)
        assertEquals(0.5f, turning.turnProgress, FLOAT_TOLERANCE)
        assertEquals(0.5f, turning.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(turning.freeEdgeFraction, base.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(1f, turning.curlStrength, FLOAT_TOLERANCE)
        assertEquals(1f, turning.alpha, FLOAT_TOLERANCE)
        assertTrue(turning.zIndex > base.zIndex)
    }

    /**
     * 验证返回上一页时上一张纸从左侧书脊展开，当前页只作为固定底页。
     *
     * @return 无返回值；反向曲面进度、层级、自由边或透明度错误时测试失败。
     */
    @Test
    fun backwardTurnUnfoldsPreviousSheetFromLeftSpine() {
        val turning = resolveEbookPageCurlVisualState(1, 2, 1, 0.5f)
        val base = resolveEbookPageCurlVisualState(2, 2, 1, -0.5f)

        assertEquals(EbookPageCurlLayer.TURNING, turning.layer)
        assertEquals(EbookPageCurlLayer.BASE, base.layer)
        assertEquals(0.5f, turning.turnProgress, FLOAT_TOLERANCE)
        assertEquals(0.5f, turning.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(turning.freeEdgeFraction, base.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(1f, turning.curlStrength, FLOAT_TOLERANCE)
        assertEquals(1f, turning.alpha, FLOAT_TOLERANCE)
        assertTrue(turning.zIndex > base.zIndex)
    }

    /**
     * 验证Pager在翻过半程后切换当前页并反转偏移符号时，翻动纸张和底页的进度仍连续。
     *
     * 使用方法：
     * 分别模拟百分之四十九与百分之五十一两帧；后一帧把Pager当前页切到目标页并改变偏移符号，
     * 再确认曲面进度只前进百分之二，而不是跳回起点。
     *
     * @return 无返回值；偏移换算或翻页进度在半程发生跳变时测试失败。
     */
    @Test
    fun progressRemainsContinuousWhenPagerSwitchesCurrentPageHalfway() {
        val turningBeforeOffset = calculateEbookPagerSignedPageOffset(2, 2, 0.49f)
        val baseBeforeOffset = calculateEbookPagerSignedPageOffset(2, 3, 0.49f)
        val turningAfterOffset = calculateEbookPagerSignedPageOffset(3, 2, -0.49f)
        val baseAfterOffset = calculateEbookPagerSignedPageOffset(3, 3, -0.49f)

        val turningBefore = resolveEbookPageCurlVisualState(2, 2, 3, turningBeforeOffset)
        val baseBefore = resolveEbookPageCurlVisualState(3, 2, 3, baseBeforeOffset)
        val turningAfter = resolveEbookPageCurlVisualState(2, 2, 3, turningAfterOffset)
        val baseAfter = resolveEbookPageCurlVisualState(3, 2, 3, baseAfterOffset)

        assertEquals(0.49f, turningBefore.turnProgress, FLOAT_TOLERANCE)
        assertEquals(0.49f, baseBefore.turnProgress, FLOAT_TOLERANCE)
        assertEquals(0.51f, turningAfter.turnProgress, FLOAT_TOLERANCE)
        assertEquals(0.51f, baseAfter.turnProgress, FLOAT_TOLERANCE)
        assertEquals(
            0.02f,
            turningAfter.turnProgress - turningBefore.turnProgress,
            FLOAT_TOLERANCE
        )
    }

    /**
     * 验证弯曲强度在翻页两端归零、半程达到峰值，同时正向和反向自由边落在正确位置。
     *
     * @return 无返回值；强度曲线或自由纸边起止位置不符合边界时测试失败。
     */
    @Test
    fun curlStrengthPeaksHalfwayAndReturnsToZeroAtBothEnds() {
        val forwardStart = resolveEbookPageCurlVisualState(2, 2, 3, 0f)
        val forwardMiddle = resolveEbookPageCurlVisualState(2, 2, 3, 0.5f)
        val forwardEnd = resolveEbookPageCurlVisualState(2, 2, 3, 1f)
        val backwardStart = resolveEbookPageCurlVisualState(1, 2, 1, 1f)
        val backwardEnd = resolveEbookPageCurlVisualState(1, 2, 1, 0f)

        assertEquals(0f, forwardStart.curlStrength, FLOAT_TOLERANCE)
        assertEquals(1f, forwardStart.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(1f, forwardMiddle.curlStrength, FLOAT_TOLERANCE)
        assertEquals(0f, forwardEnd.curlStrength, FLOAT_TOLERANCE)
        assertEquals(0f, forwardEnd.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(0f, backwardStart.curlStrength, FLOAT_TOLERANCE)
        assertEquals(0f, backwardStart.freeEdgeFraction, FLOAT_TOLERANCE)
        assertEquals(0f, backwardEnd.curlStrength, FLOAT_TOLERANCE)
        assertEquals(1f, backwardEnd.freeEdgeFraction, FLOAT_TOLERANCE)
    }

    /**
     * 验证曲面关闭时映射保持单位变换，静止正文不会被压缩或重排。
     *
     * @return 无返回值；任一条带边界在静止状态偏离原始位置时测试失败。
     */
    @Test
    fun flatSurfaceMappingKeepsEverySegmentBoundaryUnchanged() {
        for (boundary in 0..EBOOK_PAGE_CURL_SEGMENT_COUNT) {
            val source = boundary.toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT
            assertEquals(
                source,
                mapEbookPageCurlHorizontalFraction(source, 1f, 0f),
                FLOAT_TOLERANCE
            )
        }
    }

    /**
     * 验证半程曲面的首尾点固定、所有条带严格递增，不会出现文字镜像或条带交叉。
     *
     * @return 无返回值；端点漂移、相邻条带重排或自由边越界时测试失败。
     */
    @Test
    fun curvedSurfaceMappingStaysMonotonicBetweenSpineAndFreeEdge() {
        val boundaries = (0..EBOOK_PAGE_CURL_SEGMENT_COUNT).map { boundary ->
            mapEbookPageCurlHorizontalFraction(
                sourceFraction = boundary.toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT,
                freeEdgeFraction = 0.5f,
                curlStrength = 1f
            )
        }

        assertEquals(0f, boundaries.first(), FLOAT_TOLERANCE)
        assertEquals(0.5f, boundaries.last(), FLOAT_TOLERANCE)
        boundaries.zipWithNext().forEach { (left, right) ->
            assertTrue("曲面条带必须保持从书脊到自由边的原始顺序", right > left)
        }
    }

    /**
     * 验证曲面条带宽度不是统一缩放，书页中部确实形成空间隆起。
     *
     * @return 无返回值；首尾条带宽度相同或弯曲方向反转时测试失败。
     */
    @Test
    fun curvedSurfaceUsesNonUniformSegmentWidthsInsteadOfFlatScaling() {
        val boundaries = (0..EBOOK_PAGE_CURL_SEGMENT_COUNT).map { boundary ->
            mapEbookPageCurlHorizontalFraction(
                sourceFraction = boundary.toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT,
                freeEdgeFraction = 0.5f,
                curlStrength = 1f
            )
        }
        val firstSegmentWidth = boundaries[1] - boundaries[0]
        val lastIndex = boundaries.lastIndex
        val lastSegmentWidth = boundaries[lastIndex] - boundaries[lastIndex - 1]

        assertTrue(firstSegmentWidth > lastSegmentWidth)
        assertTrue(firstSegmentWidth - lastSegmentWidth > 0.02f)
    }

    /**
     * 验证上下边缘从书脊到自由边平滑内收，并在曲面关闭时完全归零。
     *
     * @return 无返回值；内收方向、峰值或静止边界错误时测试失败。
     */
    @Test
    fun verticalInsetGrowsTowardFreeEdgeAndDisappearsWhenFlat() {
        assertEquals(0f, calculateEbookPageCurlVerticalInsetFactor(0f, 1f), FLOAT_TOLERANCE)
        assertEquals(1f, calculateEbookPageCurlVerticalInsetFactor(1f, 1f), FLOAT_TOLERANCE)
        assertEquals(0f, calculateEbookPageCurlVerticalInsetFactor(1f, 0f), FLOAT_TOLERANCE)
        assertTrue(
            calculateEbookPageCurlVerticalInsetFactor(0.75f, 1f) >
                calculateEbookPageCurlVerticalInsetFactor(0.25f, 1f)
        )
    }

    private companion object {
        /** 浮点映射和三角函数断言允许的最大误差。 */
        const val FLOAT_TOLERANCE = 0.001f
    }
}
