package com.example.harleyapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.GlobalSearchRepository
import com.example.harleyapp.model.EnglishWord
import com.example.harleyapp.model.LaunchableApp
import com.example.harleyapp.model.LocalSearchResult
import com.example.harleyapp.model.LocalSearchType

/**
 * 在全部支持模块中执行不联网、不保存关键词的全局本地搜索。
 *
 * 使用方法：
 * 从功能卡片进入页面，输入关键词后会立即查询本机仓库；点击结果通过[onOpenResult]
 * 返回宿主导航到原账目、提醒、运动或网站页面。
 *
 * @param repository 全局本地搜索仓库。
 * @param englishWords 已加载的5000词离线词库和学习进度。
 * @param launchableApps 手机中已读取的可启动应用列表。
 * @param initialQuery 首页快捷搜索传入的一次性初始关键词；空文本表示不覆盖当前输入。
 * @param onInitialQueryConsumed 初始关键词写入本页状态后的消费回调，防止重组时重复覆盖用户修改。
 * @param onOpenResult 点击结果后的导航回调。
 * @param onBack 返回功能中心的回调。
 * @param modifier 外部安全边距和布局修饰器。
 *
 * @return 无返回值，直接输出搜索页。
 */
@Composable
fun GlobalSearchScreen(
    repository: GlobalSearchRepository,
    englishWords: List<EnglishWord>,
    launchableApps: List<LaunchableApp>,
    initialQuery: String,
    onInitialQueryConsumed: () -> Unit,
    onOpenResult: (LocalSearchResult) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTypeName by rememberSaveable { mutableStateOf("") }

    // 首页提交的关键词只接收一次，用户进入搜索页后仍可自由增删内容。
    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) {
            query = initialQuery.take(MAX_QUERY_LENGTH)
            selectedTypeName = ""
            onInitialQueryConsumed()
        }
    }
    val allResults = remember(query, englishWords, launchableApps) {
        repository.search(
            rawQuery = query,
            englishWords = englishWords,
            launchableApps = launchableApps
        )
    }
    val selectedType = LocalSearchType.entries.firstOrNull { type ->
        type.name == selectedTypeName
    }
    val visibleResults = remember(allResults, selectedType) {
        selectedType?.let { type ->
            allResults.filter { result -> result.type == type }
        } ?: allResults
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text("‹ 返回功能中心")
            }
            Text(
                text = "全局本地搜索",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "覆盖账目、提醒、运动、网站、英语、记事本、电子书、手机应用和功能入口，不保存搜索词。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { value -> query = value.take(MAX_QUERY_LENGTH) },
                label = { Text("输入名称、备注、单词、应用或功能") },
                singleLine = true
            )
        }

        if (query.isNotBlank()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "筛选类型",
                        style = MaterialTheme.typography.labelLarge
                    )
                    LocalSearchType.entries.chunked(TYPE_COLUMNS).forEach { rowTypes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowTypes.forEach { type ->
                                FilterChip(
                                    selected = selectedType == type,
                                    onClick = {
                                        selectedTypeName = if (selectedType == type) "" else type.name
                                    },
                                    label = { Text(type.title) }
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            query.isBlank() -> item {
                SearchEmptyCard(
                    title = "输入关键词开始搜索",
                    subtitle = "例如商户、提醒、运动、单词、文章、书名、应用或功能名称"
                )
            }
            visibleResults.isEmpty() -> item {
                SearchEmptyCard(
                    title = "没有找到本地内容",
                    subtitle = "可以缩短关键词或取消类型筛选后重试"
                )
            }
            else -> items(
                items = visibleResults,
                key = { result -> result.stableId }
            ) { result ->
                SearchResultCard(
                    result = result,
                    onClick = { onOpenResult(result) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** @return 无搜索结果时的说明卡片。 */
@Composable
private fun SearchEmptyCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** @return 单条可点击搜索结果卡片。 */
@Composable
private fun SearchResultCard(result: LocalSearchResult, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = result.type.symbol,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = result.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = result.type.title,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

private const val MAX_QUERY_LENGTH = 100
private const val TYPE_COLUMNS = 3
