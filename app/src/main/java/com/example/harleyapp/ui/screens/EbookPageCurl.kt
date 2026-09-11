package com.example.harleyapp.ui.screens

import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sin

/** 仿真翻页开始响应手指方向时使用的最小偏移，避免静止状态因浮点抖动误判方向。 */
private const val EBOOK_PAGE_CURL_DIRECTION_EPSILON = 0.001f

/** 曲面纸张使用的固定纵向条带数；兼顾弯曲连续性和每帧重放开销。 */
internal const val EBOOK_PAGE_CURL_SEGMENT_COUNT = 12

/** 横向曲面隆起系数；该值保证映射导数始终大于零，不会让相邻条带交叉或镜像。 */
private const val EBOOK_PAGE_CURL_HORIZONTAL_BEND = 0.14f

/**
 * 一张Pager页面在轻量仿真翻页中的绘制职责。
 *
 * [CURRENT]用于静止状态下唯一可见的当前页；[BASE]是翻页过程中保持平放的目标底页；
 * [TURNING]是唯一执行分段曲面重绘的纸张；[HIDDEN]用于隐藏预加载但不属于本次翻页的页面。
 */
internal enum class EbookPageCurlLayer {
    CURRENT,
    BASE,
    TURNING,
    HIDDEN
}

/**
 * 单张页面在某一翻页帧中的纯视觉参数。
 *
 * 使用方法：
 * UI先通过[resolveEbookPageCurlTargetPage]确定本次手势的目标页，再把每个Pager页面的索引、
 * 已稳定页、目标页和该页面的带符号偏移传给[resolveEbookPageCurlVisualState]。返回值只描述
 * 绘制参数，不读取Compose状态，因此单元测试可以直接验证正向、反向和回弹边界。
 *
 * @param layer 当前页面的绘制职责。
 * @param turnProgress 本次翻页完成度，范围为0到1。
 * @param pagerTranslationCorrectionFraction 用于抵消Pager原始横移的页面宽度比例。
 * @param freeEdgeFraction 纸张自由边在视口宽度中的位置比例，用于绘制跟随纸边移动的底页投影。
 * @param curlStrength 当前纸张弯折和阴影强度，起止为0、中段为1。
 * @param zIndex 当前页面在同一视口内的绘制层级。
 * @param alpha 当前页面透明度；可见纸张始终为1，避免出现纸张整体透明的卡片感。
 * @param isForwardTurn 是否正在从当前页翻到后一页。
 */
internal data class EbookPageCurlVisualState(
    val layer: EbookPageCurlLayer,
    val turnProgress: Float,
    val pagerTranslationCorrectionFraction: Float,
    val freeEdgeFraction: Float,
    val curlStrength: Float,
    val zIndex: Float,
    val alpha: Float,
    val isForwardTurn: Boolean
)

/**
 * 根据Pager当前信息确定轻量仿真翻页要显示的目标页。
 *
 * 使用方法：
 * 每次组合PAGE_CURL分支时传入`settledPage`、`currentPage`、`targetPage`、当前偏移和总页数。
 * 函数优先采用Pager已经给出的目标，其次采用Pager已切换的当前页；手指刚开始移动、Pager尚未
 * 产生目标页时，才使用偏移正负推断相邻页。首尾页会自动钳制，不会生成越界页码。
 *
 * @param settledPage 最近一次完整停稳的零基页码。
 * @param currentPage Pager当前认为最接近吸附位置的零基页码。
 * @param targetPage Pager根据手势或动画预测的目标零基页码。
 * @param currentPageOffsetFraction 当前页相对吸附位置的带符号偏移。
 * @param pageCount 当前书籍总页数。
 * @return 本次仿真翻页目标页；没有有效移动时返回安全的已稳定页，页数非法时返回0。
 */
internal fun resolveEbookPageCurlTargetPage(
    settledPage: Int,
    currentPage: Int,
    targetPage: Int,
    currentPageOffsetFraction: Float,
    pageCount: Int
): Int {
    if (pageCount <= 0) return 0

    val lastPage = pageCount - 1
    val safeSettledPage = settledPage.coerceIn(0, lastPage)
    val safeCurrentPage = currentPage.coerceIn(0, lastPage)
    val safeTargetPage = targetPage.coerceIn(0, lastPage)
    val proposedPage = when {
        safeTargetPage != safeSettledPage -> safeTargetPage
        safeCurrentPage != safeSettledPage -> safeCurrentPage
        currentPageOffsetFraction > EBOOK_PAGE_CURL_DIRECTION_EPSILON -> safeSettledPage + 1
        currentPageOffsetFraction < -EBOOK_PAGE_CURL_DIRECTION_EPSILON -> safeSettledPage - 1
        else -> safeSettledPage
    }

    return proposedPage.coerceIn(0, lastPage)
}

/**
 * 计算Pager页面相对当前吸附位置的带符号偏移。
 *
 * 使用方法：
 * `HorizontalPager`组合每个页面时传入Pager当前页、待绘制页和`currentPageOffsetFraction`。
 * Pager在翻页超过一半后会切换`currentPage`并反转偏移符号，本公式会把这两项合并为连续结果，
 * 因而翻动纸张不会在百分之五十位置跳角度。
 *
 * @param currentPage Pager当前认为最接近吸附位置的零基页码。
 * @param pageIndex 当前正在绘制的零基页面索引。
 * @param currentPageOffsetFraction 当前页相对吸附位置的带符号偏移。
 * @return 钳制在-1到1之间的页面带符号偏移。
 */
internal fun calculateEbookPagerSignedPageOffset(
    currentPage: Int,
    pageIndex: Int,
    currentPageOffsetFraction: Float
): Float {
    return (currentPage - pageIndex + currentPageOffsetFraction).coerceIn(-1f, 1f)
}

/**
 * 把纸张原始横向位置映射到当前曲面投影位置。
 *
 * 使用方法：
 * 绘制层把页面宽度分为[EBOOK_PAGE_CURL_SEGMENT_COUNT]段，并依次传入每个条带左右边界的
 * 原始比例。函数首先按自由纸边位置压缩整页，再加入中部隆起，使靠近书脊和靠近自由边的
 * 局部压缩率不同，从而让正文与PDF内容真正随纸面弯曲，而不是整页等比缩窄。
 *
 * 映射的首点始终为0、末点始终为`freeEdgeFraction`。在当前固定系数下导数始终大于零，
 * 所以条带顺序不会反转，也不会产生镜像文字。
 *
 * @param sourceFraction 纸张原始横向位置比例，0是左侧书脊、1是自由纸边。
 * @param freeEdgeFraction 当前自由纸边在视口宽度中的位置比例。
 * @param curlStrength 当前弯曲强度，0表示纸张完全平放、1表示翻到中段。
 * @return 钳制在0到自由纸边之间的目标横向位置比例。
 */
internal fun mapEbookPageCurlHorizontalFraction(
    sourceFraction: Float,
    freeEdgeFraction: Float,
    curlStrength: Float
): Float {
    val safeSource = sourceFraction.coerceIn(0f, 1f)
    val safeFreeEdge = freeEdgeFraction.coerceIn(0f, 1f)
    val safeCurlStrength = curlStrength.coerceIn(0f, 1f)
    val curvedSource = safeSource +
        EBOOK_PAGE_CURL_HORIZONTAL_BEND * safeCurlStrength *
        sin(PI * safeSource).toFloat()

    return (safeFreeEdge * curvedSource).coerceIn(0f, safeFreeEdge)
}

/**
 * 计算某一横向位置的纸张上下边内收比例。
 *
 * 使用方法：
 * 绘制层将返回值乘以最大内收像素，作为同一条带顶部向下、底部向上的偏移。书脊位置固定为0，
 * 越靠自由纸边越接近最大内收；弯曲开始和结束时[curlStrength]为0，页面会恢复完整矩形。
 *
 * @param sourceFraction 纸张原始横向位置比例，0是书脊、1是自由纸边。
 * @param curlStrength 当前弯曲强度，范围为0到1。
 * @return 范围为0到1的内收权重。
 */
internal fun calculateEbookPageCurlVerticalInsetFactor(
    sourceFraction: Float,
    curlStrength: Float
): Float {
    val safeSource = sourceFraction.coerceIn(0f, 1f)
    val safeCurlStrength = curlStrength.coerceIn(0f, 1f)
    return (
        safeCurlStrength * sin(PI * safeSource / 2.0).toFloat()
    ).coerceIn(0f, 1f)
}

/**
 * 计算单张页面在轻量曲面翻页中的层级、纸边位置和阴影进度。
 *
 * 使用方法：
 * `HorizontalPager`为每个已组合页面计算带符号偏移后调用本函数，并把返回的位移修正、
 * 层级和透明度应用到该页面。向后翻时当前页逐渐弯向左侧书脊，目标页固定在下方；向前翻回时
 * 上一页从书脊处展开，当前页固定在下方。两种方向都只重绘一张曲面纸张。
 *
 * 弯折强度使用`sin(PI * progress)`，因此曲面和阴影不会在起始帧突然出现，也不会残留到
 * 落页后的静止画面。具体条带几何由[mapEbookPageCurlHorizontalFraction]负责。
 *
 * @param pageIndex 当前正在计算的零基页面索引。
 * @param settledPage 最近一次完整停稳的零基页码。
 * @param targetPage [resolveEbookPageCurlTargetPage]返回的本次目标页。
 * @param signedPageOffset 当前页面相对Pager吸附位置的带符号偏移，通常位于-1到1。
 * @return 当前页面完整的纯视觉状态。
 */
internal fun resolveEbookPageCurlVisualState(
    pageIndex: Int,
    settledPage: Int,
    targetPage: Int,
    signedPageOffset: Float
): EbookPageCurlVisualState {
    val safeOffset = signedPageOffset.coerceIn(-1f, 1f)
    val direction = (targetPage - settledPage).coerceIn(-1, 1)
    val isForwardTurn = direction > 0

    if (direction == 0) {
        val layer = if (pageIndex == settledPage) {
            EbookPageCurlLayer.CURRENT
        } else {
            EbookPageCurlLayer.HIDDEN
        }
        return staticEbookPageCurlVisualState(
            layer = layer,
            pagerTranslationCorrectionFraction = safeOffset,
            isForwardTurn = false
        )
    }

    val turningPage = if (isForwardTurn) settledPage else targetPage
    val basePage = if (isForwardTurn) targetPage else settledPage
    val layer = when (pageIndex) {
        turningPage -> EbookPageCurlLayer.TURNING
        basePage -> EbookPageCurlLayer.BASE
        else -> EbookPageCurlLayer.HIDDEN
    }

    if (layer == EbookPageCurlLayer.HIDDEN) {
        return staticEbookPageCurlVisualState(
            layer = layer,
            pagerTranslationCorrectionFraction = safeOffset,
            isForwardTurn = isForwardTurn
        )
    }

    // 翻动页和固定底页具有相反的Pager偏移，因此分别换算后会得到相同的整体翻页进度。
    val turnProgress = when {
        isForwardTurn && layer == EbookPageCurlLayer.TURNING -> safeOffset.absoluteValue
        isForwardTurn -> 1f - safeOffset.absoluteValue
        layer == EbookPageCurlLayer.TURNING -> 1f - safeOffset.absoluteValue
        else -> safeOffset.absoluteValue
    }.coerceIn(0f, 1f)
    val freeEdgeFraction = if (isForwardTurn) {
        1f - turnProgress
    } else {
        turnProgress
    }
    val curlStrength = sin(PI * turnProgress).toFloat().coerceIn(0f, 1f)
    return EbookPageCurlVisualState(
        layer = layer,
        turnProgress = turnProgress,
        pagerTranslationCorrectionFraction = safeOffset,
        freeEdgeFraction = freeEdgeFraction,
        curlStrength = curlStrength,
        zIndex = if (layer == EbookPageCurlLayer.TURNING) 3f else 1f,
        alpha = 1f,
        isForwardTurn = isForwardTurn
    )
}

/**
 * 创建不参与翻动的页面状态。
 *
 * @param layer 静止当前页或隐藏预加载页。
 * @param pagerTranslationCorrectionFraction 用于抵消Pager横移的页面宽度比例。
 * @param isForwardTurn 当前活动手势是否朝后一页；静止状态固定为false。
 * @return 不旋转、无弯折强度的页面视觉状态。
 */
private fun staticEbookPageCurlVisualState(
    layer: EbookPageCurlLayer,
    pagerTranslationCorrectionFraction: Float,
    isForwardTurn: Boolean
): EbookPageCurlVisualState {
    return EbookPageCurlVisualState(
        layer = layer,
        turnProgress = 0f,
        pagerTranslationCorrectionFraction = pagerTranslationCorrectionFraction,
        freeEdgeFraction = 1f,
        curlStrength = 0f,
        zIndex = if (layer == EbookPageCurlLayer.CURRENT) 2f else 0f,
        alpha = if (layer == EbookPageCurlLayer.CURRENT) 1f else 0f,
        isForwardTurn = isForwardTurn
    )
}
