package com.example.harleyapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 应用统一圆角体系。
 *
 * 使用方法：
 * 由[HarleyAppTheme]传入MaterialTheme。普通输入控件使用small或medium，功能卡片使用large，
 * 页面主卡片和对话框使用extraLarge；圆形头像或胶囊标签继续显式使用CircleShape。
 *
 * @return 一组按组件层级递增的Material 3圆角定义。
 */
val HarleyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)
