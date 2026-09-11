package com.example.harleyapp.ui.screens

import android.graphics.Matrix as AndroidMatrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix as ComposeMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.setFrom
import androidx.compose.ui.unit.dp

/**
 * 把单页正文或PDF绘制为可连续弯曲的轻量纸面。
 *
 * 使用方法：
 * 仅在仿真翻页Pager的页面内部调用，把原有单页内容放入[content]。静止页和底页会直接绘制；
 * 翻动页会先录制一份完整页面，再按[EBOOK_PAGE_CURL_SEGMENT_COUNT]个竖向条带重映射。
 * 相邻条带共享边界，顶部和底部逐步内收，自由纸边再由曲线路径裁剪，因此正文、图片和PDF
 * 会跟随同一张纸面弯曲，而不是整页保持平直。
 *
 * @param modifier 曲面纸张占用的布局范围，通常传入`Modifier.fillMaxSize()`。
 * @param visualState 当前Pager页面的翻页职责、进度、自由边位置和弯曲强度。
 * @param backgroundColor 阅读页底色；与正文一起录入纸面，避免弯曲条带之间露出透明底。
 * @param textColor 当前阅读配色的正文色，用于夜间模式的细小高光和自由纸边。
 * @param usesDarkLighting 是否采用深色页面的低亮度光照参数。
 * @param content 当前页正文或PDF内容。
 * @return 无返回值，直接在传入范围内绘制曲面纸张。
 */
@Composable
internal fun EbookCurvedPageSurface(
    modifier: Modifier,
    visualState: EbookPageCurlVisualState,
    backgroundColor: Color,
    textColor: Color,
    usesDarkLighting: Boolean,
    content: @Composable () -> Unit
) {
    val recordedPageLayer = rememberGraphicsLayer()
    val androidMatrix = remember { AndroidMatrix() }
    val composeMatrix = remember { ComposeMatrix() }
    val sourcePoints = remember { FloatArray(8) }
    val targetPoints = remember { FloatArray(8) }
    val pageOutline = remember { Path() }
    val segmentClipPath = remember { Path() }
    val freeEdgePath = remember { Path() }

    Box(
        modifier = modifier.drawWithContent {
            when (visualState.layer) {
                EbookPageCurlLayer.HIDDEN -> return@drawWithContent
                EbookPageCurlLayer.CURRENT,
                EbookPageCurlLayer.BASE -> {
                    drawContent()
                    return@drawWithContent
                }

                EbookPageCurlLayer.TURNING -> Unit
            }

            val pageWidth = size.width
            val pageHeight = size.height
            if (pageWidth <= 0f || pageHeight <= 0f) return@drawWithContent

            val freeEdgeX = pageWidth * visualState.freeEdgeFraction.coerceIn(0f, 1f)
            if (freeEdgeX <= 1f) return@drawWithContent

            // 起页和完整落页时直接绘制，既保持静止文字清晰，也避免无意义的多条带重放。
            if (
                visualState.curlStrength <= 0.001f &&
                visualState.freeEdgeFraction >= 0.999f
            ) {
                drawContent()
                return@drawWithContent
            }

            recordedPageLayer.record {
                this@drawWithContent.drawContent()
            }

            val maximumVerticalInset = 10.dp.toPx()
            val freeEdgeVerticalInset = maximumVerticalInset *
                calculateEbookPageCurlVerticalInsetFactor(
                    sourceFraction = 1f,
                    curlStrength = visualState.curlStrength
                )
            val freeEdgeBow = (8.dp.toPx() * visualState.curlStrength)
                .coerceAtMost(freeEdgeX * 0.35f)

            // 外轮廓复用各条带的上下边界，最右侧再裁成轻微弧线，形成柔软的自由纸边。
            pageOutline.reset()
            pageOutline.moveTo(0f, 0f)
            for (boundary in 1..EBOOK_PAGE_CURL_SEGMENT_COUNT) {
                val sourceFraction = boundary.toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT
                val destinationX = pageWidth * mapEbookPageCurlHorizontalFraction(
                    sourceFraction = sourceFraction,
                    freeEdgeFraction = visualState.freeEdgeFraction,
                    curlStrength = visualState.curlStrength
                )
                val topInset = maximumVerticalInset * calculateEbookPageCurlVerticalInsetFactor(
                    sourceFraction = sourceFraction,
                    curlStrength = visualState.curlStrength
                )
                pageOutline.lineTo(destinationX, topInset)
            }
            pageOutline.cubicTo(
                freeEdgeX - freeEdgeBow,
                pageHeight * 0.28f,
                freeEdgeX - freeEdgeBow,
                pageHeight * 0.72f,
                freeEdgeX,
                pageHeight - freeEdgeVerticalInset
            )
            for (boundary in EBOOK_PAGE_CURL_SEGMENT_COUNT - 1 downTo 0) {
                val sourceFraction = boundary.toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT
                val destinationX = pageWidth * mapEbookPageCurlHorizontalFraction(
                    sourceFraction = sourceFraction,
                    freeEdgeFraction = visualState.freeEdgeFraction,
                    curlStrength = visualState.curlStrength
                )
                val bottomInset = maximumVerticalInset * calculateEbookPageCurlVerticalInsetFactor(
                    sourceFraction = sourceFraction,
                    curlStrength = visualState.curlStrength
                )
                pageOutline.lineTo(destinationX, pageHeight - bottomInset)
            }
            pageOutline.close()

            clipPath(pageOutline) {
                repeat(EBOOK_PAGE_CURL_SEGMENT_COUNT) { segmentIndex ->
                    val sourceFractionLeft =
                        segmentIndex.toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT
                    val sourceFractionRight =
                        (segmentIndex + 1).toFloat() / EBOOK_PAGE_CURL_SEGMENT_COUNT
                    val sourceLeft = pageWidth * sourceFractionLeft
                    val sourceRight = pageWidth * sourceFractionRight
                    val destinationLeft = pageWidth * mapEbookPageCurlHorizontalFraction(
                        sourceFraction = sourceFractionLeft,
                        freeEdgeFraction = visualState.freeEdgeFraction,
                        curlStrength = visualState.curlStrength
                    )
                    val destinationRight = pageWidth * mapEbookPageCurlHorizontalFraction(
                        sourceFraction = sourceFractionRight,
                        freeEdgeFraction = visualState.freeEdgeFraction,
                        curlStrength = visualState.curlStrength
                    )
                    val topLeft = maximumVerticalInset * calculateEbookPageCurlVerticalInsetFactor(
                        sourceFraction = sourceFractionLeft,
                        curlStrength = visualState.curlStrength
                    )
                    val topRight = maximumVerticalInset * calculateEbookPageCurlVerticalInsetFactor(
                        sourceFraction = sourceFractionRight,
                        curlStrength = visualState.curlStrength
                    )
                    val bottomLeft = pageHeight - topLeft
                    val bottomRight = pageHeight - topRight

                    sourcePoints[0] = sourceLeft
                    sourcePoints[1] = 0f
                    sourcePoints[2] = sourceRight
                    sourcePoints[3] = 0f
                    sourcePoints[4] = sourceRight
                    sourcePoints[5] = pageHeight
                    sourcePoints[6] = sourceLeft
                    sourcePoints[7] = pageHeight

                    targetPoints[0] = destinationLeft
                    targetPoints[1] = topLeft
                    targetPoints[2] = destinationRight
                    targetPoints[3] = topRight
                    targetPoints[4] = destinationRight
                    targetPoints[5] = bottomRight
                    targetPoints[6] = destinationLeft
                    targetPoints[7] = bottomLeft

                    // 半像素重叠只服务于抗锯齿裁剪，不改变矩阵边界，避免条带之间出现亮色细缝。
                    val overlap = 0.75f
                    segmentClipPath.reset()
                    segmentClipPath.moveTo(destinationLeft - overlap, topLeft)
                    segmentClipPath.lineTo(destinationRight + overlap, topRight)
                    segmentClipPath.lineTo(destinationRight + overlap, bottomRight)
                    segmentClipPath.lineTo(destinationLeft - overlap, bottomLeft)
                    segmentClipPath.close()

                    androidMatrix.reset()
                    val mapped = androidMatrix.setPolyToPoly(
                        sourcePoints,
                        0,
                        targetPoints,
                        0,
                        4
                    )
                    clipPath(segmentClipPath) {
                        if (mapped) {
                            composeMatrix.setFrom(androidMatrix)
                            withTransform({ transform(composeMatrix) }) {
                                drawLayer(recordedPageLayer)
                            }
                        } else {
                            // 极端尺寸下矩阵若无法建立，仍绘制原页填补该条带，避免闪出背景裂缝。
                            drawLayer(recordedPageLayer)
                        }
                    }
                }

                if (visualState.curlStrength > 0.001f) {
                    drawEbookPageCurlFoldLighting(
                        visualState = visualState,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                        freeEdgeX = freeEdgeX,
                        textColor = textColor,
                        usesDarkLighting = usesDarkLighting
                    )
                }
            }

            freeEdgePath.reset()
            freeEdgePath.moveTo(freeEdgeX, freeEdgeVerticalInset)
            freeEdgePath.cubicTo(
                freeEdgeX - freeEdgeBow,
                pageHeight * 0.28f,
                freeEdgeX - freeEdgeBow,
                pageHeight * 0.72f,
                freeEdgeX,
                pageHeight - freeEdgeVerticalInset
            )
            drawPath(
                path = freeEdgePath,
                color = textColor.copy(alpha = 0.42f * visualState.curlStrength),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            content()
        }
    }
}

/**
 * 在曲面纸张内部绘制随折痕移动的明暗变化。
 *
 * 使用方法：
 * 由[EbookCurvedPageSurface]在页面条带完成后调用，不应在普通阅读模式单独调用。阴影中心由翻页
 * 方向和进度共同决定，宽度随弯曲强度变化，所以不会固定黏在书脊或屏幕边缘。
 *
 * @param visualState 当前翻页状态。
 * @param pageWidth 完整阅读页宽度，单位为像素。
 * @param pageHeight 完整阅读页高度，单位为像素。
 * @param freeEdgeX 当前自由纸边横坐标，单位为像素。
 * @param textColor 当前正文色，用作深色页面的低亮度反光。
 * @param usesDarkLighting 是否采用深色页面光照参数。
 * @return 无返回值，直接叠加折痕光照。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEbookPageCurlFoldLighting(
    visualState: EbookPageCurlVisualState,
    pageWidth: Float,
    pageHeight: Float,
    freeEdgeX: Float,
    textColor: Color,
    usesDarkLighting: Boolean
) {
    val foldSourceFraction = if (visualState.isForwardTurn) {
        1f - visualState.turnProgress
    } else {
        visualState.turnProgress
    }
    val foldCenterX = pageWidth * mapEbookPageCurlHorizontalFraction(
        sourceFraction = foldSourceFraction,
        freeEdgeFraction = visualState.freeEdgeFraction,
        curlStrength = visualState.curlStrength
    )
    val foldHalfWidth = (18f + 24f * visualState.curlStrength).dp.toPx()
    val foldLeft = (foldCenterX - foldHalfWidth).coerceAtLeast(0f)
    val foldRight = (foldCenterX + foldHalfWidth).coerceAtMost(freeEdgeX)
    if (foldRight <= foldLeft) return

    val creaseShadow = if (usesDarkLighting) {
        textColor.copy(alpha = 0.055f * visualState.curlStrength)
    } else {
        Color.Black.copy(alpha = 0.15f * visualState.curlStrength)
    }
    val ridgeHighlight = if (usesDarkLighting) {
        textColor.copy(alpha = 0.11f * visualState.curlStrength)
    } else {
        Color.White.copy(alpha = 0.13f * visualState.curlStrength)
    }
    drawRect(
        brush = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.36f to creaseShadow,
                0.58f to Color.Black.copy(alpha = 0.045f * visualState.curlStrength),
                0.76f to ridgeHighlight,
                1f to Color.Transparent
            ),
            startX = foldLeft,
            endX = foldRight
        ),
        topLeft = Offset(foldLeft, 0f),
        size = Size(foldRight - foldLeft, pageHeight)
    )
}

/**
 * 在固定底页上绘制跟随曲面自由边移动的柔和投影。
 *
 * 使用方法：
 * 仅当当前Pager页面职责为[EbookPageCurlLayer.BASE]且弯曲强度大于零时调用。投影使用两层
 * 不同宽度的曲线路径：宽层表达纸张离开底页后的环境阴影，窄层表达纸边附近的接触阴影。
 * 翻动页位于更高的`zIndex`，因此阴影落在已露出的底页上，不会覆盖纸面正文。
 *
 * @param modifier 投影画布范围，应与整页阅读视口一致。
 * @param visualState 底页对应的翻页状态，用于取得自由边位置和弯曲强度。
 * @param shadowColor 当前配色可见的阴影基色；浅色页通常使用黑色，深色页使用正文色。
 * @return 无返回值，直接绘制底页投影。
 */
@Composable
internal fun EbookPageCurlBaseShadow(
    modifier: Modifier,
    visualState: EbookPageCurlVisualState,
    shadowColor: Color
) {
    val shadowPath = remember { Path() }

    Canvas(modifier = modifier) {
        val strength = visualState.curlStrength.coerceIn(0f, 1f)
        if (strength <= 0.001f) return@Canvas

        val freeEdgeX = size.width * visualState.freeEdgeFraction.coerceIn(0f, 1f)
        val verticalInset = 10.dp.toPx() * strength
        val freeEdgeBow = (8.dp.toPx() * strength).coerceAtMost(freeEdgeX * 0.35f)

        shadowPath.reset()
        shadowPath.moveTo(freeEdgeX, verticalInset)
        shadowPath.cubicTo(
            freeEdgeX - freeEdgeBow,
            size.height * 0.28f,
            freeEdgeX - freeEdgeBow,
            size.height * 0.72f,
            freeEdgeX,
            size.height - verticalInset
        )
        drawPath(
            path = shadowPath,
            color = shadowColor.copy(alpha = 0.035f * strength),
            style = Stroke(width = (54f + 18f * strength).dp.toPx())
        )
        drawPath(
            path = shadowPath,
            color = shadowColor.copy(alpha = 0.075f * strength),
            style = Stroke(width = (18f + 10f * strength).dp.toPx())
        )
    }
}
