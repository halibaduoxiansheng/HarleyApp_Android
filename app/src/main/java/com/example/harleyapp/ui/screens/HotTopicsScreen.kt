package com.example.harleyapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.HotTopicRepository
import com.example.harleyapp.model.HotTopic

/**
 * 承载热点平台切换、刷新、排行展开和原平台跳转的独立详情页。
 *
 * 使用方法：
 * 用户点击首页“每日热点”功能卡片后，由HarleyApp切换到本页面。页面不出现在底部导航中，
 * 顶部返回按钮和系统返回键都应回到首页。后续热点功能扩展只在本页面内部进行，不把详细
 * 榜单重新放回首页。
 *
 * @param repository 热点网络读取和当前进程内临时缓存仓库。
 * @param onOpenTopic 用户点击单条热点后打开原平台的回调。
 * @param onBack 返回首页的回调。
 * @param modifier 外部传入的页面安全边距。
 *
 * @return 无返回值，直接输出完整热点详情页。
 */
@Composable
fun HotTopicsScreen(
    repository: HotTopicRepository,
    onOpenTopic: (HotTopic) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            top = 12.dp,
            end = 20.dp,
            bottom = 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(text = "‹ 返回")
                }

                Text(
                    modifier = Modifier.padding(start = 4.dp),
                    text = "热点详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            HotTopicsCard(
                repository = repository,
                onOpenTopic = onOpenTopic
            )
        }
    }
}
