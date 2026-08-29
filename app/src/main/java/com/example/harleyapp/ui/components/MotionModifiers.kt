package com.example.harleyapp.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * 给可点击内容增加轻量按压缩放、弹簧回弹和Material涟漪反馈。
 *
 * 使用方法：
 * 在卡片或图标原有Modifier链中用`bouncyClickable(onClick = ...)`替换普通clickable。
 * 函数只在手指按住时缩小约3%，释放后立即弹回，不创建无限动画，也不会影响列表滚动。
 *
 * @param enabled 是否允许点击；false时保留布局但不触发动画和回调。
 * @param role 无障碍角色，按钮入口建议使用Role.Button，普通列表项可以不传。
 * @param onClick 用户完成点击时执行的回调。
 *
 * @return 追加了按压动画、涟漪和点击语义的Modifier。
 */
@Composable
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember {
        MutableInteractionSource()
    }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = 520f
        ),
        label = "press_scale"
    )

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        role = role,
        onClick = onClick
    )
}

private const val PRESSED_SCALE = 0.97f
