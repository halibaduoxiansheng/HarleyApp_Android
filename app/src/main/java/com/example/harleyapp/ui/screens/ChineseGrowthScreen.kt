package com.example.harleyapp.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.harleyapp.data.ChineseGrowthCatalog
import com.example.harleyapp.data.ChineseGrowthRepository
import com.example.harleyapp.model.ChineseGrowthSection
import com.example.harleyapp.model.ChineseReadingLoadResult
import com.example.harleyapp.model.ChineseReadingTopic
import com.example.harleyapp.model.ChineseWritingMission
import com.example.harleyapp.model.PrimarySchoolGrade

/**
 * 显示按年级组织的小学写作训练和精选在线阅读。
 *
 * 使用方法：
 * 功能中心传入长期复用的[ChineseGrowthRepository]。用户选择年级后，写作区提供方法、提纲和
 * 自查清单；阅读区只展示内置白名单主题，点击后在App内加载公开摘要。网络失败自动显示缓存或
 * 原创离线导读，不打开浏览器，也不接受任意网址。
 *
 * @param repository 年级选择和在线文章缓存仓库。
 * @param onBack 返回功能中心概览的回调。
 * @param modifier 外层传入的安全边距修饰器。
 * @return 无返回值，直接输出语文成长完整页面。
 */
@Composable
fun ChineseGrowthScreen(
    repository: ChineseGrowthRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedGrade by remember(repository) {
        mutableStateOf(repository.getSelectedGrade())
    }
    var selectedSectionName by rememberSaveable {
        mutableStateOf(ChineseGrowthSection.WRITING.name)
    }
    var selectedMissionId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var selectedTopicId by rememberSaveable {
        mutableStateOf<String?>(null)
    }
    var readingResult by remember {
        mutableStateOf<ChineseReadingLoadResult?>(null)
    }
    var isReadingLoading by remember {
        mutableStateOf(false)
    }
    var refreshRequest by rememberSaveable {
        mutableIntStateOf(0)
    }
    var operationMessage by rememberSaveable {
        mutableStateOf<String?>(null)
    }

    val selectedSection = ChineseGrowthSection.entries.firstOrNull { section ->
        section.name == selectedSectionName
    } ?: ChineseGrowthSection.WRITING
    val writingMissions = ChineseGrowthCatalog.writingMissionsFor(selectedGrade)
    val readingTopics = ChineseGrowthCatalog.readingTopicsFor(selectedGrade)
    val selectedMission = writingMissions.firstOrNull { mission ->
        mission.id == selectedMissionId
    }
    val selectedTopic = ChineseGrowthCatalog.allReadingTopics().firstOrNull { topic ->
        topic.id == selectedTopicId
    }

    LaunchedEffect(selectedTopic?.id, refreshRequest) {
        val topic = selectedTopic ?: return@LaunchedEffect
        isReadingLoading = true
        readingResult = repository.loadArticle(
            topic = topic,
            forceRefresh = refreshRequest > 0
        )
        isReadingLoading = false
    }

    val handleBack: () -> Unit = {
        when {
            selectedMissionId != null -> selectedMissionId = null
            selectedTopicId != null -> {
                selectedTopicId = null
                readingResult = null
                refreshRequest = 0
            }
            else -> onBack()
        }
    }
    BackHandler(onBack = handleBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ChineseGrowthHeader(
            title = when {
                selectedMission != null -> selectedMission.title
                selectedTopic != null -> selectedTopic.title
                else -> "语文成长"
            },
            subtitle = when {
                selectedMission != null -> "${selectedGrade.displayName} · 写作训练"
                selectedTopic != null -> "${selectedTopic.category} · App内阅读"
                else -> "提升写作能力，也看见更大的世界"
            },
            onBack = handleBack
        )

        if (selectedMission != null) {
            WritingMissionDetail(
                mission = selectedMission,
                modifier = Modifier.weight(1f)
            )
            return@Column
        }
        if (selectedTopic != null) {
            ReadingArticleDetail(
                topic = selectedTopic,
                result = readingResult,
                isLoading = isReadingLoading,
                onRefresh = { refreshRequest += 1 },
                modifier = Modifier.weight(1f)
            )
            return@Column
        }

        Text(
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp),
            text = "选择年级",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrimarySchoolGrade.entries.forEach { grade ->
                FilterChip(
                    selected = selectedGrade == grade,
                    onClick = {
                        if (repository.saveSelectedGrade(grade)) {
                            selectedGrade = grade
                            operationMessage = null
                        } else {
                            operationMessage = "年级保存失败，请稍后重试"
                        }
                    },
                    label = { Text(text = grade.displayName) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChineseGrowthSection.entries.forEach { section ->
                FilterChip(
                    selected = selectedSection == section,
                    onClick = { selectedSectionName = section.name },
                    label = { Text(text = section.displayName) }
                )
            }
        }
        operationMessage?.let { message ->
            Text(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        when (selectedSection) {
            ChineseGrowthSection.WRITING -> WritingMissionList(
                missions = writingMissions,
                onOpen = { mission -> selectedMissionId = mission.id },
                modifier = Modifier.weight(1f)
            )
            ChineseGrowthSection.READING -> ReadingTopicList(
                topics = readingTopics,
                onOpen = { topic ->
                    readingResult = null
                    refreshRequest = 0
                    selectedTopicId = topic.id
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 显示语文页面统一返回按钮、标题和说明。 */
@Composable
private fun ChineseGrowthHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = "← 返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 显示当前年级三个写作训练入口。 */
@Composable
private fun WritingMissionList(
    missions: List<ChineseWritingMission>,
    onOpen: (ChineseWritingMission) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "writing_intro") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "每次只练一个能力：先看方法和提纲，再自己动笔，最后按清单修改。不提供整篇套用范文。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        items(missions, key = ChineseWritingMission::id) { mission ->
            Card(
                onClick = { onOpen(mission) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = mission.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = "训练重点：${mission.focus}", style = MaterialTheme.typography.bodyMedium)
                    Text(text = mission.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 显示写作方法、可填写提纲和自查清单。 */
@Composable
private fun WritingMissionDetail(
    mission: ChineseWritingMission,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            DetailTextCard("本次重点", mission.focus)
        }
        item {
            DetailTextCard("立即练笔", mission.prompt)
        }
        item {
            DetailListCard("写作方法", mission.methodSteps)
        }
        item {
            DetailListCard("我的提纲", mission.outline.map { item -> "□ $item" })
        }
        item {
            DetailListCard("写完自查", mission.checklist.map { item -> "□ $item" })
        }
    }
}

/** 显示当前年级经过白名单筛选的阅读主题。 */
@Composable
private fun ReadingTopicList(
    topics: List<ChineseReadingTopic>,
    onOpen: (ChineseReadingTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "reading_intro") {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = "只联网读取精选百科主题，并直接在App内显示；成功内容缓存24小时，断网仍可读导读。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        items(topics, key = ChineseReadingTopic::id) { topic ->
            Card(
                onClick = { onOpen(topic) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = topic.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(text = topic.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(text = topic.offlineGuide, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 显示在线文章、缓存或离线导读，并附带阅读问题和小练笔。 */
@Composable
private fun ReadingArticleDetail(
    topic: ChineseReadingTopic,
    result: ChineseReadingLoadResult?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (result == null && isLoading) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
            Text(modifier = Modifier.padding(top = 12.dp), text = "正在获取精选内容……")
        }
        return
    }
    val article = result?.article
    if (article == null) return

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        result.notice?.let { notice ->
            item {
                Text(text = notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(text = article.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (article.description.isNotBlank()) {
                Text(text = article.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(text = article.body, style = MaterialTheme.typography.bodyLarge)
        }
        item {
            HorizontalDivider()
        }
        item {
            DetailTextCard("阅读思考", topic.observationQuestion)
        }
        item {
            DetailTextCard("读后小练笔", topic.writingChallenge)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "内容来源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = article.sourceName, style = MaterialTheme.typography.bodySmall)
                    Text(text = article.licenseLabel, style = MaterialTheme.typography.bodySmall)
                    if (article.sourceUrl.isNotBlank()) {
                        SelectionContainer {
                            Text(text = article.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        text = if (article.isFromCache) "当前显示本机缓存" else if (article.isOnlineContent) "当前显示联网内容" else "当前显示离线内容",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh, enabled = !isLoading) {
                Text(text = if (isLoading) "正在刷新" else "重新联网获取")
            }
        }
    }
}

/** 显示一个标题加正文的详情信息卡。 */
@Composable
private fun DetailTextCard(title: String, body: String) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 显示一个标题和多行步骤列表的详情信息卡。 */
@Composable
private fun DetailListCard(title: String, lines: List<String>) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
