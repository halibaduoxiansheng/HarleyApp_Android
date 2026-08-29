package com.example.harleyapp.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.example.harleyapp.model.CompanionCategory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 使用Compose原生矢量图形绘制会呼吸、眨眼并带有特色动作的伙伴头像。
 *
 * 使用方法：
 * 在首页、伙伴选择页或成长图鉴中传入伙伴分类和等级即可。绘制不依赖网络、GIF或外部图片，
 * 会根据等级自动叠加光点、星章、皇冠和高阶光环；组件离开界面后动画会随Compose一起停止。
 *
 * @param category 需要绘制的伙伴分类。
 * @param level 当前伙伴等级，用于选择形态阶段和进化装饰。
 * @param modifier 外部尺寸、内边距和布局修饰器；调用方必须提供可用尺寸。
 *
 * @return 无返回值，直接在Canvas中持续绘制动态伙伴。
 */
@Composable
fun AnimatedCompanion(
    category: CompanionCategory,
    level: Int,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "companion_${category.name}")
    val bob by transition.animateFloat(
        initialValue = -1.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "companion_bob"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.018f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "companion_breathe"
    )
    val action by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "companion_action"
    )
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3_600
                1f at 0
                1f at 2_750
                0.08f at 2_840
                1f at 2_960
                1f at 3_600
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "companion_blink"
    )
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "companion_glow"
    )
    val formIndex = category.formIndexFor(level)
    val description = "${category.companionName}，${category.formNameFor(level)}，动态形象"

    Canvas(
        modifier = modifier.semantics {
            contentDescription = description
        }
    ) {
        val unit = size.minDimension / 100f
        val center = Offset(size.width / 2f, size.height / 2f)

        withTransform({
            translate(top = bob * unit)
            scale(scaleX = breathe, scaleY = breathe, pivot = center)
        }) {
            drawEvolutionAura(
                category = category,
                formIndex = formIndex,
                action = action,
                glow = glow
            )

            when (category) {
                CompanionCategory.FOREST -> drawFox(
                    blink = blink,
                    action = action,
                    formIndex = formIndex
                )
                CompanionCategory.OCEAN -> drawOtter(
                    blink = blink,
                    action = action,
                    formIndex = formIndex
                )
                CompanionCategory.TECHNOLOGY -> drawRobot(
                    blink = blink,
                    action = action,
                    formIndex = formIndex
                )
                CompanionCategory.SKY -> drawOwl(
                    blink = blink,
                    action = action,
                    formIndex = formIndex
                )
                CompanionCategory.DESERT -> drawLizard(
                    blink = blink,
                    action = action,
                    formIndex = formIndex
                )
                CompanionCategory.COSMOS -> drawRabbit(
                    blink = blink,
                    action = action,
                    formIndex = formIndex
                )
            }

            drawEvolutionDetails(
                category = category,
                formIndex = formIndex,
                action = action,
                glow = glow
            )
        }
    }
}

/**
 * 绘制伙伴进化后位于身体背后的光环和环绕光点。
 *
 * @param category 当前伙伴分类，用于读取统一配色。
 * @param formIndex 从0开始的形态序号。
 * @param action -1到1之间循环变化的特色动作进度。
 * @param glow 0到1之间循环变化的发光透明度。
 *
 * @return 无返回值，图形直接绘制到当前DrawScope。
 */
private fun DrawScope.drawEvolutionAura(
    category: CompanionCategory,
    formIndex: Int,
    action: Float,
    glow: Float
) {
    val palette = companionPalette(category)
    val unit = size.minDimension / 100f
    val center = point(50f, 51f)

    if (formIndex >= 3) {
        drawCircle(
            color = palette.accent.copy(alpha = 0.18f + glow * 0.12f),
            radius = 39f * unit,
            center = center,
            style = Stroke(width = 2.2f * unit)
        )
    }
    if (formIndex >= 4) {
        val angle = (action + 1f) * PI.toFloat()
        repeat(3) { index ->
            val currentAngle = angle + index * (2f * PI.toFloat() / 3f)
            val orbitCenter = Offset(
                x = center.x + cos(currentAngle) * 42f * unit,
                y = center.y + sin(currentAngle) * 25f * unit
            )
            drawCircle(
                color = palette.glow.copy(alpha = 0.55f + glow * 0.35f),
                radius = (1.8f + index * 0.35f) * unit,
                center = orbitCenter
            )
        }
    }
    if (formIndex >= 5) {
        drawCircle(
            color = palette.glow.copy(alpha = 0.16f + glow * 0.14f),
            radius = 45f * unit,
            center = center,
            style = Stroke(width = 1.4f * unit)
        )
    }
}

/**
 * 绘制星尾狐，尾巴会轻微摆动，并随成长增加胸前星纹。
 *
 * @param blink 眼睛张开比例。
 * @param action 尾巴摆动进度。
 * @param formIndex 当前形态序号。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawFox(blink: Float, action: Float, formIndex: Int) {
    val palette = companionPalette(CompanionCategory.FOREST)
    val unit = size.minDimension / 100f

    rotate(degrees = action * 7f, pivot = point(66f, 66f)) {
        drawOval(
            color = palette.primary,
            topLeft = point(60f, 54f),
            size = Size(31f * unit, 22f * unit)
        )
        drawOval(
            color = palette.cream,
            topLeft = point(78f, 56f),
            size = Size(12f * unit, 17f * unit)
        )
    }
    drawOval(palette.primary, point(33f, 50f), Size(34f * unit, 36f * unit))
    drawCircle(palette.primary, 22f * unit, point(50f, 39f))
    drawTriangle(palette.primary, point(31f, 31f), point(37f, 7f), point(48f, 26f))
    drawTriangle(palette.primary, point(52f, 26f), point(64f, 7f), point(69f, 31f))
    drawTriangle(palette.ear, point(35f, 27f), point(38f, 14f), point(44f, 27f))
    drawTriangle(palette.ear, point(56f, 27f), point(63f, 14f), point(65f, 28f))
    drawOval(palette.cream, point(38f, 38f), Size(24f * unit, 18f * unit))
    drawEyes(blink, leftX = 43f, rightX = 57f, y = 38f, color = palette.line)
    drawCircle(palette.line, 1.8f * unit, point(50f, 47f))
    drawLine(
        color = palette.cream,
        start = point(43f, 60f),
        end = point(55f, 79f),
        strokeWidth = 5f * unit,
        cap = StrokeCap.Round
    )
    if (formIndex >= 2) {
        drawStar(palette.glow, point(50f, 64f), 5.5f * unit, 2.5f * unit)
    }
}

/**
 * 绘制泡泡獭，双臂抱在腹部，身旁泡泡会随动画上下漂浮。
 *
 * @param blink 眼睛张开比例。
 * @param action 泡泡漂浮进度。
 * @param formIndex 当前形态序号。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawOtter(blink: Float, action: Float, formIndex: Int) {
    val palette = companionPalette(CompanionCategory.OCEAN)
    val unit = size.minDimension / 100f

    drawOval(palette.primary, point(32f, 43f), Size(36f * unit, 44f * unit))
    drawCircle(palette.primary, 22f * unit, point(50f, 36f))
    drawCircle(palette.primary, 7f * unit, point(32f, 24f))
    drawCircle(palette.primary, 7f * unit, point(68f, 24f))
    drawCircle(palette.ear, 3.5f * unit, point(32f, 24f))
    drawCircle(palette.ear, 3.5f * unit, point(68f, 24f))
    drawOval(palette.cream, point(39f, 35f), Size(22f * unit, 17f * unit))
    drawOval(palette.cream, point(39f, 56f), Size(22f * unit, 25f * unit))
    drawEyes(blink, leftX = 42f, rightX = 58f, y = 34f, color = palette.line)
    drawCircle(palette.line, 1.8f * unit, point(50f, 43f))
    drawLine(palette.ear, point(35f, 60f), point(47f, 68f), 5f * unit, StrokeCap.Round)
    drawLine(palette.ear, point(65f, 60f), point(53f, 68f), 5f * unit, StrokeCap.Round)

    val bubbleOffset = action * 4f
    drawCircle(
        color = palette.glow.copy(alpha = 0.72f),
        radius = 6f * unit,
        center = point(76f, 35f - bubbleOffset),
        style = Stroke(width = 1.6f * unit)
    )
    if (formIndex >= 1) {
        drawCircle(
            color = palette.glow.copy(alpha = 0.58f),
            radius = 3.6f * unit,
            center = point(25f, 49f + bubbleOffset),
            style = Stroke(width = 1.3f * unit)
        )
    }
}

/**
 * 绘制像素机器人，天线会摆动，眼部光条会完成眨眼。
 *
 * @param blink 眼睛张开比例。
 * @param action 天线摆动进度。
 * @param formIndex 当前形态序号。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawRobot(blink: Float, action: Float, formIndex: Int) {
    val palette = companionPalette(CompanionCategory.TECHNOLOGY)
    val unit = size.minDimension / 100f

    drawRoundRect(
        color = palette.primary,
        topLeft = point(31f, 48f),
        size = Size(38f * unit, 37f * unit),
        cornerRadius = CornerRadius(8f * unit)
    )
    drawRoundRect(
        color = palette.cream,
        topLeft = point(25f, 20f),
        size = Size(50f * unit, 37f * unit),
        cornerRadius = CornerRadius(9f * unit)
    )
    drawRoundRect(
        color = palette.line,
        topLeft = point(30f, 26f),
        size = Size(40f * unit, 23f * unit),
        cornerRadius = CornerRadius(6f * unit)
    )
    drawLine(
        color = palette.accent,
        start = point(50f, 20f),
        end = point(50f + action * 5f, 9f),
        strokeWidth = 2.4f * unit,
        cap = StrokeCap.Round
    )
    drawCircle(palette.glow, 4f * unit, point(50f + action * 5f, 8f))
    drawEyes(blink, leftX = 39f, rightX = 61f, y = 37f, color = palette.glow, radius = 3.2f)
    drawRoundRect(
        color = palette.line.copy(alpha = 0.72f),
        topLeft = point(39f, 60f),
        size = Size(22f * unit, 13f * unit),
        cornerRadius = CornerRadius(3f * unit)
    )
    drawCircle(palette.glow, 2.7f * unit, point(50f, 66.5f))
    drawLine(palette.primary, point(30f, 59f), point(19f, 70f), 6f * unit, StrokeCap.Round)
    drawLine(palette.primary, point(70f, 59f), point(81f, 70f), 6f * unit, StrokeCap.Round)
    if (formIndex >= 2) {
        drawLine(palette.glow, point(38f, 78f), point(62f, 78f), 2f * unit, StrokeCap.Round)
    }
}

/**
 * 绘制云朵鸮，两侧翅膀会以较小幅度轻轻扇动。
 *
 * @param blink 眼睛张开比例。
 * @param action 翅膀扇动进度。
 * @param formIndex 当前形态序号。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawOwl(blink: Float, action: Float, formIndex: Int) {
    val palette = companionPalette(CompanionCategory.SKY)
    val unit = size.minDimension / 100f

    rotate(-10f - action * 8f, pivot = point(34f, 56f)) {
        drawOval(palette.accent, point(20f, 46f), Size(22f * unit, 34f * unit))
    }
    rotate(10f + action * 8f, pivot = point(66f, 56f)) {
        drawOval(palette.accent, point(58f, 46f), Size(22f * unit, 34f * unit))
    }
    drawOval(palette.primary, point(30f, 36f), Size(40f * unit, 50f * unit))
    drawCircle(palette.primary, 24f * unit, point(50f, 35f))
    drawTriangle(palette.primary, point(29f, 25f), point(31f, 8f), point(43f, 20f))
    drawTriangle(palette.primary, point(57f, 20f), point(69f, 8f), point(71f, 25f))
    drawCircle(palette.cream, 10f * unit, point(40f, 34f))
    drawCircle(palette.cream, 10f * unit, point(60f, 34f))
    drawEyes(blink, leftX = 40f, rightX = 60f, y = 34f, color = palette.line, radius = 3f)
    drawTriangle(palette.ear, point(45f, 42f), point(55f, 42f), point(50f, 49f))
    if (formIndex >= 2) {
        drawLine(palette.glow, point(39f, 64f), point(61f, 64f), 3f * unit, StrokeCap.Round)
    }
}

/**
 * 绘制暖阳蜥，长尾巴会缓慢摆动，背部晶片随成长出现。
 *
 * @param blink 眼睛张开比例。
 * @param action 尾巴摆动进度。
 * @param formIndex 当前形态序号。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawLizard(blink: Float, action: Float, formIndex: Int) {
    val palette = companionPalette(CompanionCategory.DESERT)
    val unit = size.minDimension / 100f
    val tailPath = Path().apply {
        moveTo(point(66f, 65f).x, point(66f, 65f).y)
        cubicTo(
            point(86f, 62f + action * 3f).x,
            point(86f, 62f + action * 3f).y,
            point(88f, 82f - action * 4f).x,
            point(88f, 82f - action * 4f).y,
            point(73f, 83f).x,
            point(73f, 83f).y
        )
    }

    drawPath(
        path = tailPath,
        color = palette.primary,
        style = Stroke(width = 9f * unit, cap = StrokeCap.Round)
    )
    drawOval(palette.primary, point(28f, 49f), Size(43f * unit, 31f * unit))
    drawCircle(palette.primary, 18f * unit, point(37f, 43f))
    drawOval(palette.cream, point(25f, 45f), Size(25f * unit, 12f * unit))
    drawEyes(blink, leftX = 34f, rightX = 46f, y = 38f, color = palette.line, radius = 2.5f)
    drawLine(palette.accent, point(34f, 72f), point(23f, 84f), 4.5f * unit, StrokeCap.Round)
    drawLine(palette.accent, point(61f, 72f), point(69f, 84f), 4.5f * unit, StrokeCap.Round)
    if (formIndex >= 1) {
        repeat((formIndex + 1).coerceAtMost(5)) { index ->
            val x = 43f + index * 6f
            drawTriangle(
                color = palette.glow,
                first = point(x, 50f),
                second = point(x + 3f, 41f - index % 2),
                third = point(x + 6f, 51f)
            )
        }
    }
}

/**
 * 绘制月光兔，长耳会左右轻摆，尾部和胸前带有柔和星光。
 *
 * @param blink 眼睛张开比例。
 * @param action 耳朵摆动进度。
 * @param formIndex 当前形态序号。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawRabbit(blink: Float, action: Float, formIndex: Int) {
    val palette = companionPalette(CompanionCategory.COSMOS)
    val unit = size.minDimension / 100f

    rotate(-action * 5f, pivot = point(39f, 28f)) {
        drawOval(palette.primary, point(31f, 3f), Size(14f * unit, 37f * unit))
        drawOval(palette.ear, point(35f, 8f), Size(6f * unit, 25f * unit))
    }
    rotate(action * 5f, pivot = point(61f, 28f)) {
        drawOval(palette.primary, point(55f, 3f), Size(14f * unit, 37f * unit))
        drawOval(palette.ear, point(59f, 8f), Size(6f * unit, 25f * unit))
    }
    drawOval(palette.primary, point(33f, 48f), Size(34f * unit, 39f * unit))
    drawCircle(palette.primary, 22f * unit, point(50f, 39f))
    drawCircle(palette.cream, 7f * unit, point(75f, 70f))
    drawOval(palette.cream, point(39f, 41f), Size(22f * unit, 15f * unit))
    drawEyes(blink, leftX = 42f, rightX = 58f, y = 38f, color = palette.line)
    drawCircle(palette.ear, 1.8f * unit, point(50f, 48f))
    if (formIndex >= 2) {
        drawStar(palette.glow, point(50f, 67f), 5.5f * unit, 2.4f * unit)
    }
}

/**
 * 在所有伙伴上叠加统一的成长标识，保证六段形态有清楚可见的差别。
 *
 * @param category 当前伙伴分类。
 * @param formIndex 当前形态序号。
 * @param action 循环动作进度。
 * @param glow 循环发光透明度。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawEvolutionDetails(
    category: CompanionCategory,
    formIndex: Int,
    action: Float,
    glow: Float
) {
    val palette = companionPalette(category)
    val unit = size.minDimension / 100f

    if (formIndex >= 1) {
        drawCircle(
            color = palette.glow.copy(alpha = 0.55f + glow * 0.35f),
            radius = 2.2f * unit,
            center = point(75f + action * 2f, 25f)
        )
    }
    if (formIndex >= 2) {
        drawStar(
            color = palette.glow.copy(alpha = 0.65f + glow * 0.3f),
            center = point(24f - action * 2f, 32f),
            outerRadius = 4.5f * unit,
            innerRadius = 1.8f * unit
        )
    }
    if (formIndex >= 3) {
        val crown = Path().apply {
            moveTo(point(38f, 18f).x, point(38f, 18f).y)
            lineTo(point(42f, 8f).x, point(42f, 8f).y)
            lineTo(point(50f, 16f).x, point(50f, 16f).y)
            lineTo(point(58f, 8f).x, point(58f, 8f).y)
            lineTo(point(62f, 18f).x, point(62f, 18f).y)
            close()
        }
        drawPath(
            path = crown,
            color = palette.glow.copy(alpha = 0.82f)
        )
    }
    if (formIndex >= 5) {
        drawStar(
            color = Color.White.copy(alpha = 0.5f + glow * 0.45f),
            center = point(82f, 53f - action * 2f),
            outerRadius = 4.2f * unit,
            innerRadius = 1.7f * unit
        )
    }
}

/**
 * 绘制一对可眨眼的圆点眼睛。
 *
 * @param blink 眼睛竖向缩放比例，1为完全睁开，接近0时为闭眼。
 * @param leftX 左眼横坐标，使用0到100的归一化坐标。
 * @param rightX 右眼横坐标，使用0到100的归一化坐标。
 * @param y 双眼纵坐标，使用0到100的归一化坐标。
 * @param color 眼睛颜色。
 * @param radius 完全睁开时的眼睛半径，使用归一化单位。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawEyes(
    blink: Float,
    leftX: Float,
    rightX: Float,
    y: Float,
    color: Color,
    radius: Float = 2.7f
) {
    val unit = size.minDimension / 100f
    val eyeHeight = (radius * 2f * blink).coerceAtLeast(0.5f) * unit
    val eyeSize = Size(radius * 2f * unit, eyeHeight)

    drawOval(
        color = color,
        topLeft = Offset(point(leftX, y).x - radius * unit, point(leftX, y).y - eyeHeight / 2f),
        size = eyeSize
    )
    drawOval(
        color = color,
        topLeft = Offset(point(rightX, y).x - radius * unit, point(rightX, y).y - eyeHeight / 2f),
        size = eyeSize
    )
}

/**
 * 按三个顶点绘制实心三角形，主要用于耳朵、鸟喙和背部晶片。
 *
 * @param color 填充颜色。
 * @param first 第一个顶点。
 * @param second 第二个顶点。
 * @param third 第三个顶点。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawTriangle(
    color: Color,
    first: Offset,
    second: Offset,
    third: Offset
) {
    val path = Path().apply {
        moveTo(first.x, first.y)
        lineTo(second.x, second.y)
        lineTo(third.x, third.y)
        close()
    }
    drawPath(path = path, color = color)
}

/**
 * 绘制五角星，用作高阶形态的动态成长装饰。
 *
 * @param color 填充颜色。
 * @param center 星形中心点。
 * @param outerRadius 外侧顶点半径。
 * @param innerRadius 内侧凹点半径。
 *
 * @return 无返回值。
 */
private fun DrawScope.drawStar(
    color: Color,
    center: Offset,
    outerRadius: Float,
    innerRadius: Float
) {
    val path = Path()
    repeat(10) { index ->
        val angle = -PI / 2 + index * PI / 5
        val radius = if (index % 2 == 0) outerRadius else innerRadius
        val target = Offset(
            x = center.x + cos(angle).toFloat() * radius,
            y = center.y + sin(angle).toFloat() * radius
        )
        if (index == 0) {
            path.moveTo(target.x, target.y)
        } else {
            path.lineTo(target.x, target.y)
        }
    }
    path.close()
    drawPath(path = path, color = color)
}

/**
 * 把0到100的归一化坐标转换为当前Canvas中的真实坐标，并保持图形水平居中。
 *
 * @param x 归一化横坐标。
 * @param y 归一化纵坐标。
 *
 * @return 当前Canvas内对应的像素坐标。
 */
private fun DrawScope.point(x: Float, y: Float): Offset {
    val unit = size.minDimension / 100f
    val horizontalOffset = (size.width - size.minDimension) / 2f
    val verticalOffset = (size.height - size.minDimension) / 2f
    return Offset(
        x = horizontalOffset + x * unit,
        y = verticalOffset + y * unit
    )
}

/**
 * 动态伙伴绘制所使用的一组固定颜色。
 *
 * @param primary 身体主色。
 * @param accent 肢体或装甲强调色。
 * @param cream 面部、腹部等浅色区域。
 * @param ear 耳内、鼻尖等暖色细节。
 * @param line 眼睛和屏幕等深色线条。
 * @param glow 进化光点和能量颜色。
 */
private data class CompanionPalette(
    val primary: Color,
    val accent: Color,
    val cream: Color,
    val ear: Color,
    val line: Color,
    val glow: Color
)

/**
 * 获取每个伙伴稳定且彼此易于区分的动态插画配色。
 *
 * @param category 伙伴分类。
 *
 * @return 该分类专用的六色绘制色板。
 */
private fun companionPalette(category: CompanionCategory): CompanionPalette {
    return when (category) {
        CompanionCategory.FOREST -> CompanionPalette(
            primary = Color(0xFFF28A3C),
            accent = Color(0xFFC85B2A),
            cream = Color(0xFFFFE6C7),
            ear = Color(0xFFDE6C75),
            line = Color(0xFF49332E),
            glow = Color(0xFFFFD85C)
        )
        CompanionCategory.OCEAN -> CompanionPalette(
            primary = Color(0xFF8B654F),
            accent = Color(0xFF66493D),
            cream = Color(0xFFFFE0B5),
            ear = Color(0xFFD58B7E),
            line = Color(0xFF3B2B2A),
            glow = Color(0xFF72E4F2)
        )
        CompanionCategory.TECHNOLOGY -> CompanionPalette(
            primary = Color(0xFF7385D8),
            accent = Color(0xFF5662AE),
            cream = Color(0xFFDDE6FF),
            ear = Color(0xFFFF8FC7),
            line = Color(0xFF29305D),
            glow = Color(0xFF66F4E2)
        )
        CompanionCategory.SKY -> CompanionPalette(
            primary = Color(0xFF8DBAE8),
            accent = Color(0xFF618EC2),
            cream = Color(0xFFFFF6DA),
            ear = Color(0xFFF6B95F),
            line = Color(0xFF33485D),
            glow = Color(0xFFBFF6FF)
        )
        CompanionCategory.DESERT -> CompanionPalette(
            primary = Color(0xFFE1A14A),
            accent = Color(0xFFBB7137),
            cream = Color(0xFFFFE9A6),
            ear = Color(0xFFE56D4B),
            line = Color(0xFF5B3C29),
            glow = Color(0xFFFFD35A)
        )
        CompanionCategory.COSMOS -> CompanionPalette(
            primary = Color(0xFFB5A5E8),
            accent = Color(0xFF806FC1),
            cream = Color(0xFFF8EFFF),
            ear = Color(0xFFF2A8C8),
            line = Color(0xFF453B68),
            glow = Color(0xFFFFD7F5)
        )
    }
}
