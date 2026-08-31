package com.example.harleyapp.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.ui.components.bouncyClickable
import com.example.harleyapp.ui.theme.LocalAppVisualTheme
import androidx.compose.runtime.remember

/**
 * 显示一个只负责进入独立功能页的通用首页卡片。
 *
 * 使用方法：
 * 首页新增功能时优先复用本组件，只传入功能名称、简短说明、字符图标和导航回调；查询结果、
 * 表单、设置项和长列表必须放在点击后进入的独立页面，避免首页随着功能增加不断变长和变乱。
 * 卡片本身不持有业务状态，也不直接调用仓库，因此可以安全复用于后续任何功能入口。
 *
 * @param title 功能名称，例如“每日热点”。
 * @param description 一行或两行用途说明，不在首页展开具体数据。
 * @param symbol 简洁字符图标，避免为了单个入口增加额外图标依赖。
 * @param onClick 点击卡片后进入对应独立功能页的回调。
 * @param modifier 外部布局修饰器。
 * @param statusLabel 可选的右上角短标签，例如“实时”或“本地”。
 *
 * @return 无返回值，直接输出可点击功能入口卡片。
 */
@Composable
fun FeatureEntryCard(
    title: String,
    description: String,
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusLabel: String? = null
) {
    val context = LocalContext.current
    val visualTheme = LocalAppVisualTheme.current
    val themeArtResourceId = remember(visualTheme.artResourceName) {
        context.resources.getIdentifier(
            visualTheme.artResourceName,
            "drawable",
            context.packageName
        )
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(
                role = Role.Button,
                onClick = onClick
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                if (themeArtResourceId != 0) {
                    Image(
                        painter = painterResource(themeArtResourceId),
                        contentDescription = "${visualTheme.displayName}人物",
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        modifier = Modifier.padding(12.dp),
                        text = symbol,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    statusLabel?.let { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "进入 ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
