package com.example.harleyapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.HotTopicRepository
import com.example.harleyapp.model.HotTopic
import com.example.harleyapp.model.HotTopicPlatform
import com.example.harleyapp.model.HotTopicSnapshot
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 显示微博、百度、知乎和抖音公开热榜，并提供手动刷新与原平台跳转入口。
 *
 * 使用方法：
 * HotTopicsScreen传入长期复用的[HotTopicRepository]和点击回调。组件会先同步展示最后一次成功缓存，
 * 缓存不存在或超过10分钟时自动刷新；用户也可以随时点击“刷新”。默认每个平台显示前10条，
 * 点击“展开全部”后显示接口返回的全部有效标题。
 *
 * @param repository 热点网络读取与本机缓存仓库。
 * @param onOpenTopic 用户点击单条热点后的回调，参数只包含排行、标题和已校验的原平台链接。
 * @param modifier 外部布局修饰器。
 *
 * @return 无返回值，直接输出热点卡片。
 */
@Composable
fun HotTopicsCard(
    repository: HotTopicRepository,
    onOpenTopic: (HotTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    var snapshot by remember(repository) {
        mutableStateOf(repository.getCachedSnapshot())
    }
    var selectedPlatformName by rememberSaveable {
        mutableStateOf(HotTopicPlatform.WEIBO.name)
    }
    var isRefreshing by remember {
        mutableStateOf(false)
    }
    var statusMessage by remember {
        mutableStateOf<String?>(null)
    }
    var showAllTopics by rememberSaveable {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    val selectedPlatform = HotTopicPlatform.entries.firstOrNull { platform ->
        platform.name == selectedPlatformName
    } ?: HotTopicPlatform.WEIBO
    val topics = snapshot?.topicsByPlatform?.get(selectedPlatform).orEmpty()
    val displayedTopics = if (showAllTopics) {
        topics
    } else {
        topics.take(COLLAPSED_TOPIC_COUNT)
    }
    val refreshTopics: suspend () -> Unit = {
        if (!isRefreshing) {
            isRefreshing = true
            statusMessage = null

            repository.refresh().fold(
                onSuccess = { refreshedSnapshot ->
                    val previousFetchedAtMillis = snapshot?.fetchedAtMillis
                    snapshot = refreshedSnapshot
                    statusMessage = if (
                        previousFetchedAtMillis == refreshedSnapshot.fetchedAtMillis
                    ) {
                        "当前已是最新"
                    } else {
                        "已刷新"
                    }
                },
                onFailure = {
                    statusMessage = if (snapshot == null) {
                        "暂时无法获取热点，请检查网络后重试"
                    } else {
                        "刷新失败，当前显示上次成功结果"
                    }
                }
            )

            isRefreshing = false
        }
    }

    // 组件进入热点详情页时优先显示缓存，仅在缓存过期或不存在时自动发起一次网络请求。
    LaunchedEffect(repository) {
        if (repository.isAutomaticRefreshDue(snapshot)) {
            refreshTopics()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "每日热点",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = snapshotTimeText(snapshot),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    enabled = !isRefreshing,
                    onClick = {
                        coroutineScope.launch {
                            refreshTopics()
                        }
                    }
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(text = if (isRefreshing) "刷新中" else "刷新")
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = HotTopicPlatform.entries,
                    key = { platform -> platform.sourceId }
                ) { platform ->
                    FilterChip(
                        selected = platform == selectedPlatform,
                        onClick = {
                            selectedPlatformName = platform.name
                            showAllTopics = false
                        },
                        label = {
                            Text(text = platform.displayName)
                        }
                    )
                }
            }

            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message == "已刷新" || message == "当前已是最新") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            if (displayedTopics.isEmpty()) {
                HotTopicEmptyState(isRefreshing = isRefreshing)
            } else {
                displayedTopics.forEachIndexed { index, topic ->
                    HotTopicRow(
                        topic = topic,
                        onClick = {
                            onOpenTopic(topic)
                        }
                    )

                    if (index < displayedTopics.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                if (topics.size > COLLAPSED_TOPIC_COUNT) {
                    TextButton(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        onClick = {
                            showAllTopics = !showAllTopics
                        }
                    ) {
                        Text(
                            text = if (showAllTopics) {
                                "收起"
                            } else {
                                "展开全部 ${topics.size} 条"
                            }
                        )
                    }
                }
            }

            Text(
                text = "仅展示公开排行标题 · 点击前往原平台 · 数据索引：抖音火热榜",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 显示一条可点击热点，包括原始排行、标题和跳转提示。
 *
 * @param topic 已通过安全校验的热点。
 * @param onClick 点击整行后的回调。
 *
 * @return 无返回值。
 */
@Composable
private fun HotTopicRow(
    topic: HotTopic,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = if (topic.rank <= 3) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = topic.rank.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (topic.rank <= 3) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        Text(
            modifier = Modifier.weight(1f),
            text = topic.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = "打开 ›",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * 在首次加载或当前平台没有有效条目时显示明确状态。
 *
 * @param isRefreshing 当前是否正在联网刷新。
 *
 * @return 无返回值。
 */
@Composable
private fun HotTopicEmptyState(isRefreshing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
            text = if (isRefreshing) "正在获取热点" else "当前平台暂无可显示热点",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 生成人类可读的数据更新时间说明。
 *
 * @param snapshot 当前成功快照，首次加载时允许为null。
 *
 * @return 有服务端时间时优先显示该时间；否则显示本机取得快照的月日和时分。
 */
private fun snapshotTimeText(snapshot: HotTopicSnapshot?): String {
    if (snapshot == null) {
        return "微博、百度、知乎、抖音热榜"
    }
    if (snapshot.updatedAt.isNotBlank()) {
        return "榜单更新：${snapshot.updatedAt}"
    }

    val formattedTime = Instant.ofEpochMilli(snapshot.fetchedAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(SNAPSHOT_TIME_FORMATTER)
    return "获取时间：$formattedTime"
}

private const val COLLAPSED_TOPIC_COUNT = 10

private val SNAPSHOT_TIME_FORMATTER = DateTimeFormatter.ofPattern(
    "MM-dd HH:mm",
    Locale.CHINA
)
