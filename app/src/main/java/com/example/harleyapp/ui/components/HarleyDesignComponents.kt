package com.example.harleyapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Harley界面的统一间距令牌。
 *
 * 使用方法：
 * 页面和通用组件优先从这里选择间距，避免继续增加18dp、19dp等难以维护的零散数值。
 * 页面水平留白使用[page]，普通卡片内部使用[card]，章节之间使用[section]。
 */
object HarleySpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val regular = 16.dp
    val card = 20.dp
    val page = 20.dp
    val section = 24.dp
    val large = 32.dp
}

/**
 * 输出带轻微主题色渐变的统一页面背景。
 *
 * 使用方法：
 * 把页面的LazyColumn、网格或自定义内容放入[content]。组件只绘制背景，不额外添加系统栏或
 * Scaffold边距，因此调用方可继续安全复用外层传入的modifier，不会产生双重留白。
 *
 * @param modifier 页面外部修饰器，通常包含Scaffold已经计算好的安全边距。
 * @param content 需要显示在背景上方的页面内容。
 *
 * @return 无返回值，直接输出占满可用空间的背景和内容。
 */
@Composable
fun HarleyPageBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to colors.primaryContainer.copy(alpha = 0.22f),
                        0.28f to colors.background,
                        1f to colors.background
                    )
                )
            ),
        content = content
    )
}

/**
 * 输出一级页面统一标题区。
 *
 * 使用方法：
 * 首页、功能中心和“我的”等一级页面在内容顶部调用。需要放置主题人物、计数或按钮时，
 * 通过[trailing]加入右侧区域；不传时标题会自然占满宽度。
 *
 * @param title 页面主标题。
 * @param subtitle 一句简短说明，建议控制在一到两行。
 * @param modifier 外部布局修饰器。
 * @param eyebrow 可选的小型栏目名或状态文字，用于在主标题上方建立品牌层级。
 * @param trailing 标题右侧的可选内容。
 *
 * @return 无返回值，直接输出页面标题行。
 */
@Composable
fun HarleyPageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HarleySpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(HarleySpacing.xSmall)
        ) {
            eyebrow?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        trailing()
    }
}

/**
 * 输出页面内部统一章节标题。
 *
 * 使用方法：
 * 当列表内出现新的内容组时调用；[action]可放置“管理”“查看全部”等低优先级操作。
 *
 * @param title 章节名称。
 * @param subtitle 章节用途或当前状态。
 * @param modifier 外部布局修饰器。
 * @param action 章节右侧的可选操作内容。
 *
 * @return 无返回值，直接输出章节标题和操作区。
 */
@Composable
fun HarleySectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HarleySpacing.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        action()
    }
}

/**
 * 输出功能入口使用的单字徽记。
 *
 * 使用方法：
 * 传入功能自身的稳定短字符，例如“书”“光”“记”。徽记只承担视觉扫描作用，父卡片已经提供
 * 完整标题和说明，所以本组件会清除独立无障碍语义，避免读屏重复朗读。
 *
 * @param symbol 功能短字符，建议只使用一个汉字或一个已有业务符号。
 * @param modifier 外部尺寸或位置修饰器。
 * @param containerColor 徽记底色，默认使用主色容器。
 * @param contentColor 徽记文字色，默认使用与主色容器配对的前景色。
 *
 * @return 无返回值，直接输出统一尺寸的功能徽记。
 */
@Composable
fun HarleySymbolBadge(
    symbol: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(
        modifier = modifier
            .size(44.dp)
            .clearAndSetSemantics { },
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 输出紧凑的状态胶囊。
 *
 * 使用方法：
 * 用于卡片右上角的“离线”“设备”“20项”等短状态，长句应继续使用普通正文。
 *
 * @param label 状态短文案。
 * @param modifier 外部布局修饰器。
 * @param containerColor 胶囊背景色。
 * @param contentColor 胶囊文字色。
 *
 * @return 无返回值，直接输出单行状态胶囊。
 */
@Composable
fun HarleyStatusPill(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/**
 * 创建普通卡片使用的细描边。
 *
 * 使用方法：
 * 传给Card或Surface的border参数。描边取自当前主题的outlineVariant并降低透明度，既能在深色
 * 背景中分隔层级，又不会像高阴影那样让长列表显得厚重。
 *
 * @param alpha 描边透明度，范围0到1，默认适合普通信息卡。
 *
 * @return 可直接传给Material组件的1dp描边。
 */
@Composable
fun harleyCardBorder(alpha: Float = 0.62f): BorderStroke {
    return BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha.coerceIn(0f, 1f))
    )
}
